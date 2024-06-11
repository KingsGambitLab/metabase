(ns metabase.search.impl
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [honey.sql :as sql]
   [honey.sql.helpers :as sql.helpers]
   [medley.core :as m]
   [metabase.audit :as audit]
   [metabase.db :as mdb]
   [metabase.db.query :as mdb.query]
   [metabase.driver :as driver]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.legacy-mbql.normalize :as mbql.normalize]
   [metabase.lib.core :as lib]
   [metabase.models.collection :as collection]
   [metabase.models.data-permissions :as data-perms]
   [metabase.models.database :as database]
   [metabase.models.interface :as mi]
   [metabase.models.permissions :as perms]
   [metabase.permissions.util :as perms.u]
   [metabase.public-settings.premium-features :as premium-features]
   [metabase.search.config
    :as search.config
    :refer [SearchableModel SearchContext]]
   [metabase.search.filter :as search.filter]
   [metabase.search.scoring :as scoring]
   [metabase.search.util :as search.util]
   [metabase.util :as u]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.i18n :refer [deferred-tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.registry :as mr]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]
   [toucan2.instance :as t2.instance]
   [toucan2.jdbc.options :as t2.jdbc.options]
   [toucan2.realize :as t2.realize]))

(set! *warn-on-reflection* true)

(def ^:private HoneySQLColumn
  [:or
   :keyword
   [:tuple :any :keyword]])

(mu/defn ^:private ->column-alias :- keyword?
  "Returns the column name. If the column is aliased, i.e. [`:original_name` `:aliased_name`], return the aliased
  column name"
  [column-or-aliased :- HoneySQLColumn]
  (if (sequential? column-or-aliased)
    (second column-or-aliased)
    column-or-aliased))

(mu/defn ^:private canonical-columns :- [:sequential HoneySQLColumn]
  "Returns a seq of canonicalized list of columns for the search query with the given `model` Will return column names
  prefixed with the `model` name so that it can be used in criteria. Projects a `nil` for columns the `model` doesn't
  have and doesn't modify aliases."
  [model :- SearchableModel, col-alias->honeysql-clause :- [:map-of :keyword HoneySQLColumn]]
  (for [[search-col col-type] search.config/all-search-columns
        :let                  [maybe-aliased-col (get col-alias->honeysql-clause search-col)]]
    (cond
      (= search-col :model)
      [(h2x/literal model) :model]

      ;; This is an aliased column, no need to include the table alias
      (sequential? maybe-aliased-col)
      maybe-aliased-col

      ;; This is a column reference, need to add the table alias to the column
      maybe-aliased-col
      (search.config/column-with-alias model maybe-aliased-col)

      ;; This entity is missing the column, project a null for that column value. For Postgres and H2, cast it to the
      ;; correct type, e.g.
      ;;
      ;;    SELECT cast(NULL AS integer)
      ;;
      ;; For MySQL, this is not needed.
      :else
      [(when-not (= (mdb/db-type) :mysql)
         [:cast nil col-type])
       search-col])))

(mu/defn ^:private select-clause-for-model :- [:sequential HoneySQLColumn]
  "The search query uses a `union-all` which requires that there be the same number of columns in each of the segments
  of the query. This function will take the columns for `model` and will inject constant `nil` values for any column
  missing from `entity-columns` but found in `search.config/all-search-columns`."
  [model :- SearchableModel]
  (let [entity-columns                (search.config/columns-for-model model)
        column-alias->honeysql-clause (m/index-by ->column-alias entity-columns)
        cols-or-nils                  (canonical-columns model column-alias->honeysql-clause)]
    cols-or-nils))

(mu/defn ^:private from-clause-for-model :- [:tuple [:tuple :keyword :keyword]]
  [model :- SearchableModel]
  (let [{:keys [db-model alias]} (get search.config/model-to-db-model model)]
    [[(t2/table-name db-model) alias]]))

(defn- wildcarded-tokens [s]
  (->> s
       search.util/normalize
       search.util/tokenize
       (map search.util/wildcard-match)))

(mu/defn ^:private search-string-clause
  [model
   search-ctx :- SearchContext]
  (when (not (str/blank? (:search-string search-ctx)))
    (into
     [:or]
     (for [wildcarded-token (wildcarded-tokens (:search-string search-ctx))
           column (cond-> (search.config/searchable-columns-for-model model)
                    (:search-native-query search-ctx)
                    (concat (search.config/native-query-columns model)))]
       [:like
        [:lower column]
        wildcarded-token]))))

(mu/defn ^:private base-query-for-model :- [:map {:closed true}
                                            [:select :any]
                                            [:from :any]
                                            [:where {:optional true} :any]
                                            [:join {:optional true} :any]
                                            [:left-join {:optional true} :any]]
  "Create a HoneySQL query map with `:select`, `:from`, and `:where` clauses for `model`, suitable for the `UNION ALL`
  used in search."
  [model :- SearchableModel context :- SearchContext]
  (-> {:select (select-clause-for-model model)
       :from   (from-clause-for-model model)}
      (sql.helpers/where (search-string-clause model context))
      (search.filter/build-filters model context)))

(mu/defn add-collection-join-and-where-clauses
  "Add a `WHERE` clause to the query to only return Collections the Current User has access to; join against Collection
  so we can return its `:name`.

  A brief note here on `collection-join-id` and `collection-permission-id`. What the heck do these represent?

  Permissions on Trashed items work differently than normal permissions. If something is in the trash, you can only
  see it if you have the relevant permissions on the *original* collection the item was trashed from. This is set as
  `trashed_from_collection_id`.

  However, the item is actually *in* the Trash, and we want to show that to the frontend. Therefore, we need two
  different collection IDs. One, the ID we should be checking permissions on, and two, the ID we should be joining to
  Collections on."
  [honeysql-query                                :- ms/Map
   model                                         :- :string
   {:keys [current-user-perms
           filter-items-in-personal-collection]} :- SearchContext]
  (let [visible-collections      (collection/permissions-set->visible-collection-ids current-user-perms)
        collection-join-id       (if (= model "collection")
                                   :collection.id
                                   :collection_id)
        collection-permission-id (if (= model "collection")
                                   :collection.id
                                   (mi/parent-collection-id-column-for-perms (:db-model (search.config/model-to-db-model model))))
        collection-filter-clause (collection/visible-collection-ids->honeysql-filter-clause
                                  collection-permission-id
                                  visible-collections)]
    (cond-> honeysql-query
      true
      (sql.helpers/where collection-filter-clause (perms/audit-namespace-clause :collection.namespace nil))
      ;; add a JOIN against Collection *unless* the source table is already Collection
      (not= model "collection")
      (sql.helpers/left-join [:collection :collection]
                             [:= collection-join-id :collection.id])

      (some? filter-items-in-personal-collection)
      (sql.helpers/where
       (case filter-items-in-personal-collection
         "only"
         (concat [:or]
                 ;; sub personal collections
                 (for [id (t2/select-pks-set :model/Collection :personal_owner_id [:not= nil])]
                   [:like :collection.location (format "/%d/%%" id)])
                 ;; top level personal collections
                 [[:and
                   [:= :collection.location "/"]
                   [:not= :collection.personal_owner_id nil]]])

         "exclude"
         (conj [:or]
               (into
                [:and [:= :collection.personal_owner_id nil]]
                (for [id (t2/select-pks-set :model/Collection :personal_owner_id [:not= nil])]
                  [:not-like :collection.location (format "/%d/%%" id)]))
               [:= collection-join-id nil]))))))

(mu/defn ^:private add-table-db-id-clause
  "Add a WHERE clause to only return tables with the given DB id.
  Used in data picker for joins because we can't join across DB's."
  [query :- ms/Map id :- [:maybe ms/PositiveInt]]
  (if (some? id)
    (sql.helpers/where query [:= id :db_id])
    query))

(mu/defn ^:private add-card-db-id-clause
  "Add a WHERE clause to only return cards with the given DB id.
  Used in data picker for joins because we can't join across DB's."
  [query :- ms/Map id :- [:maybe ms/PositiveInt]]
  (if (some? id)
    (sql.helpers/where query [:= id :database_id])
    query))

(mu/defn ^:private replace-select :- :map
  "Replace a select from query that has alias is `target-alias` with [`with` `target-alias`] column.
   If the target-alias cannot be found, add the select.

  This works with the assumption that `query` contains a list of select from [[select-clause-for-model]],
  and some of them are dummy column casted to the correct type.

  This function then will replace the dummy column with alias is `target-alias` with the `with` column."
  [query        :- :map
   target-alias :- :keyword
   with         :- :keyword]
  (let [selects     (:select query)
        idx         (first (keep-indexed (fn [index item]
                                           (when (and (coll? item)
                                                      (= (last item) target-alias))
                                             index))
                                         selects))
        with-select [with target-alias]]
    (if (some? idx)
      (assoc query :select (m/replace-nth idx with-select selects))
      (sql.helpers/select query [with target-alias]))))

(mu/defn ^:private with-last-editing-info :- :map
  [query :- :map
   model :- [:enum "card" "dashboard"]]
  (-> query
      (replace-select :last_editor_id :r.user_id)
      (replace-select :last_edited_at :r.timestamp)
      (sql.helpers/left-join [:revision :r]
                             [:and [:= :r.model_id (search.config/column-with-alias model :id)]
                              [:= :r.most_recent true]
                              [:= :r.model [:inline (search.config/search-model->revision-model model)]]])))

(mu/defn ^:private with-moderated-status :- :map
  [query :- :map
   model :- [:enum "card" "dataset"]]
  (-> query
      (replace-select :moderated_status :mr.status)
      (sql.helpers/left-join [:moderation_review :mr]
                             [:and
                              [:= :mr.moderated_item_type [:inline "card"]]
                              [:= :mr.moderated_item_id (search.config/column-with-alias model :id)]
                              [:= :mr.most_recent true]])))

;; ----------------- NEW IMPLEMENTATION BEGINS -------------------

;; -------------- Model-specific code -----------------

;; TODO: make :query and :columns separate multimethods?
(defmulti ^:private searchable-data-query-for-model
  {:arglists '([model])}
  (fn [model] model))

;; TODO: Querying is WIP
;; we need to define a subquery that includes logic specific to the search model
;; and combine them with an OR clause
;; [:or [:and [:= :search_model "card"] ...]]
;; `search-filters-for-model` defines what goes in the AND clause for each model
;; it replaces `search-query-for-model`
(defmulti ^:private search-filters-for-model
  {:arglists '([model search-context])}
  (fn [model _] model))

(defmethod searchable-data-query-for-model "card"
  [model]
  {:query (-> {:select (concat [[[:inline "card"] :search_model]]
                               (map #(search.config/column-with-alias model %)
                                    (conj search.config/default-columns
                                          :type ;; rename this to card_type?
                                          :query_type
                                          :collection_id
                                          :trashed_from_collection_id
                                          :collection_position
                                          :dataset_query
                                          :display
                                          :creator_id)))
               :from   (from-clause-for-model model)}
              ;; TODO: add all columns from filters
              #_(search.filter/build-filters model context)
              ;; TODO: collection perms clause
              #_(add-collection-join-and-where-clauses "card" search-ctx)
              (with-last-editing-info "card")
              (with-moderated-status "card"))
   :columns [:type
             :query_type
             :search_model
             :description
             :archived
             :collection_position
             :trashed_from_collection_id
             :collection_id
             :name
             :creator_id
             :updated_at
             :moderated_status
             :dataset_query
             :last_editor_id
             :id
             :last_edited_at
             :display
             :created_at]})

(defmethod search-filters-for-model "card"
  [model search-ctx]
  (let [card-type "question"]
    (:where
     (-> {}
         ;; TODO: extract search string clause and apply it across all models to allow for full-text search
         (sql.helpers/where (search-string-clause model search-ctx))
         (sql.helpers/where [:= :type card-type])
         ;; TODO: update build-filters to only query from the search table, and not use joins
         (search.filter/build-filters model search-ctx)
         ; TODO: implement bookmarks
         #_(sql.helpers/left-join [:card_bookmark :bookmark]
                                  [:and
                                   [:= :bookmark.card_id :card.id]
                                   [:= :bookmark.user_id (:current-user-id search-ctx)]])
         ; TODO: implement collection filtering
         #_(add-collection-join-and-where-clauses "card" search-ctx)
         ; TODO: implement table-db-id fitler
         #_(add-card-db-id-clause (:table-db-id search-ctx))))))

(defmethod searchable-data-query-for-model "table"
  [model]
  {:query (let [table-column #(search.config/column-with-alias model %)]
            (-> (-> {:select [[[:inline "table"] :search_model]
                              [:table.id :id]
                              [:table.name :name]
                              [:table.created_at :created_at]
                              [:table.display_name :display_name]
                              [:table.description :description]
                              [:table.updated_at :updated_at]
                              [:table.initial_sync_status :initial_sync_status]
                              [:table.id :table_id]
                              [:table.db_id :database_id]
                              [:table.schema :table_schema]
                              [:table.name :table_name]
                              [:table.description :table_description]
                              [:metabase_database.name :database_name]]
                     :from   (from-clause-for-model model)}
              ;; TODO
                    #_(search.filter/build-filters model context))
                (sql.helpers/where [:and
                                    [:not [:= :table.db_id [:inline audit/audit-db-id]]]
                                    [:= :table.active true]
                                    [:= :table.visibility_type nil]])
                (sql.helpers/left-join :metabase_database [:= (table-column :db_id) :metabase_database.id])))
   :columns [:search_model
             :id
             :name
             :created_at
             :display_name
             :description
             :updated_at
             :initial_sync_status
             :table_id
             :database_id
             :table_schema
             :table_name
             :table_description
             :database_name]})

(defn searchable-data-query-for-instance [model id]
  (let [query (:query (searchable-data-query-for-model model))]
    (sql.helpers/where query [:= :id id])))

;; TODO: right not this returns a HoneySQL expression that can be
;; used in a WHERE clause, but this will take a different format
;; in the future to allow more backends to compile it to their
;; own query language
(defn general-search-query
  "Returns a HoneySQL clause that can be used in a WHERE clause to search across all models."
  [search-ctx]
  (into
   [:or]
   (for [model ["card"]] ; just card for now
     (search-filters-for-model model search-ctx))))

;; -------------- Backend-specific code -----------------

;; union all the queries for each model, selecting all columns from each query
(def models ["table" "card"])
(def search-table-name "search")

;; TODO: move this to metabase.driver
(defmulti ^:private search-type->database-type
  "Given a search type, return the database type for that column. Applies to postgres for now"
  {:arglists '([driver])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

;; TODO: implement for MySQL and H2
(defmethod search-type->database-type :postgres
  [_driver search-type]
  (case search-type
    :text      "TEXT"
    :integer   "INTEGER"
    :boolean   "BOOLEAN"
    :timestamp "TIMESTAMP"
    :json      "JSON"
    :decimal   "DECIMAL"
    :float     "DOUBLE"
    :time      "TIME"))

;; TODO: implement this for each search backend
(defn reindex []
  ;; 1. Create the search table with the appropriate columns
  (let [driver  :postgres
        columns (->> models
                     (map #(set (:columns (searchable-data-query-for-model %))))
                     (reduce set/union)
                     sort)
        columns+types (for [column columns
                            :let [type (get (assoc search.config/all-search-columns :search_model :text)
                                            column)]]
                        [column (keyword (search-type->database-type driver type))])]
    (t2/query {:drop-table [:if-exists (keyword search-table-name)]})
    (t2/query {:create-table (keyword search-table-name)
               :with-columns columns+types})
  ;; 2. For each model, insert the data into the search table
  (doseq [model models]
    (let [{:keys [query columns]} (searchable-data-query-for-model model)
          ->sql #(first (mdb.query/compile %))]
      (t2/query (str (->sql {:insert-into (keyword search-table-name)
                             :columns columns})
                     "\n"
                     (->sql {:select columns
                             :from   [[query :alias]]})))))))

(comment
  (reindex)
  ;; TODO: make this a test checking that there are no query parameters in each query
  (assert (every? nil? (for [model ["card" "table"]]
                         (let [{:keys [query]} (searchable-data-query-for-model model)
                               format-sql #(sql/format % :quoted true, :dialect (sql.qp/quote-style driver))]
                           (second (format-sql query)))))
          )
  )

;; TODO: implement this for each search backend
(defn index [& changes]
  (for [{:keys [change-type search-model id] :as change} changes]
    (case change-type
      :insert
      (let [search-data (t2/query (searchable-data-query-for-instance search-model id))]
        (t2/insert! :model/Searchable search-data))
      :delete
      (t2/delete! :model/Searchable :id id :search_model search-model)
      :update
      (let [search-data (t2/query (searchable-data-query-for-instance search-model id))]
        (t2/update! :model/Searchable
                    :id (:id change)
                    :search_model (:search-model change)
                    search-data)))))

;; TODO: make this a multimethod to be implemented by each backend that compiles the query.
;; For now we use HoneySQL
(defn execute-search [filters-subquery]
  ;; `build-query` is very simple right now because `filters-subquery` is in HoneySQL.
  ;; But we will need to make `filters-
  (let [build-query (fn [filters-subquery]
                      {:select :*
                       :from   :search
                       :where  filters-subquery})]
    (map #(into {} %) (t2/query (build-query filters-subquery)))))

(comment
  ;; 1. reindex
  (reindex)
  ;; 2. execute search:
  (let [search-ctx {:search-string      "orders"
                    :models             search.config/all-models
                    :model-ancestors?   false
                    :current-user-id    1
                    :current-user-perms #{"/"}}]
    (execute-search (general-search-query search-ctx)))
  ;; => ({:description nil,
  ;;      :archived false,
  ;;      :collection_position nil,
  ;;      :table_id nil,
  ;;      :trashed_from_collection_id nil,
  ;;      :database_id nil,
  ;;      :collection_id nil,
  ;;      :database_name nil,
  ;;      :query_type "query",
  ;;      :name "Orders q",
  ;;      :type "question",
  ;;      :table_schema nil,
  ;;      :search_model "card",
  ;;      :creator_id 3,
  ;;      :updated_at #t "2024-06-11T00:50:50.820882",
  ;;      :moderated_status nil,
  ;;      :dataset_query "{\"database\":2,\"type\":\"query\",\"query\":{\"source-table\":12}}",
  ;;      :last_editor_id 3,
  ;;      :id 237,
  ;;      :last_edited_at #t "2024-06-11T00:50:50.865469",
  ;;      :table_description nil,
  ;;      :display "table",
  ;;      :initial_sync_status nil,
  ;;      :table_name nil,
  ;;      :display_name nil,
  ;;      :created_at #t "2024-06-11T00:50:50.820882"})

  )

;; ----------------- NEW IMPLEMENTATION ENDS -------------------

(mu/defn ^:private shared-card-impl
  [type       :- :metabase.models.card/type
   search-ctx :- SearchContext]
  (-> (base-query-for-model "card" search-ctx)
      (sql.helpers/where (when (:search-native-query search-ctx)
                           [:= (search.config/column-with-alias "card" :query_type) [:inline "native"]]))
      (sql.helpers/where [:= :card.type (name type)])
      (sql.helpers/left-join [:card_bookmark :bookmark]
                             [:and
                              [:= :bookmark.card_id :card.id]
                              [:= :bookmark.user_id (:current-user-id search-ctx)]])
      (add-collection-join-and-where-clauses "card" search-ctx)
      (add-card-db-id-clause (:table-db-id search-ctx))
      (with-last-editing-info "card")
      (with-moderated-status "card")))

(defmulti ^:private search-query-for-model
  {:arglists '([model search-context])}
  (fn [model _] model))

(defmethod search-query-for-model "action"
  [model search-ctx]
  (-> (base-query-for-model model search-ctx)
      (sql.helpers/left-join [:report_card :model]
                             [:= :model.id :action.model_id])
      (sql.helpers/left-join :query_action
                             [:= :query_action.action_id :action.id])
      (add-collection-join-and-where-clauses model
                                             search-ctx)))

(defmethod search-query-for-model "card"
  [_model search-ctx]
  (shared-card-impl :question search-ctx))

(defmethod search-query-for-model "dataset"
  [_model search-ctx]
  (-> (shared-card-impl :model search-ctx)
      (update :select (fn [columns]
                        (cons [(h2x/literal "dataset") :model] (rest columns))))))

(defmethod search-query-for-model "metric"
  [_model search-ctx]
  (-> (shared-card-impl :metric search-ctx)
      (update :select (fn [columns]
                        (cons [(h2x/literal "metric") :model] (rest columns))))))

(defmethod search-query-for-model "collection"
  [model search-ctx]
  (-> (base-query-for-model "collection" search-ctx)
      (sql.helpers/left-join [:collection_bookmark :bookmark]
                             [:and
                              [:= :bookmark.collection_id :collection.id]
                              [:= :bookmark.user_id (:current-user-id search-ctx)]])
      (add-collection-join-and-where-clauses model search-ctx)))

(defmethod search-query-for-model "database"
  [model search-ctx]
  (base-query-for-model model search-ctx))

(defmethod search-query-for-model "dashboard"
  [model search-ctx]
  (-> (base-query-for-model model search-ctx)
      (sql.helpers/left-join [:dashboard_bookmark :bookmark]
                             [:and
                              [:= :bookmark.dashboard_id :dashboard.id]
                              [:= :bookmark.user_id (:current-user-id search-ctx)]])
      (add-collection-join-and-where-clauses model search-ctx)
      (with-last-editing-info "dashboard")))

(defn- add-model-index-permissions-clause
  [query current-user-perms]
  (let [build-path (fn [x y z] (h2x/concat (h2x/literal x) y (h2x/literal z)))
        has-perm-clause (fn [x y z] [:in (build-path x y z) current-user-perms])]
    (if (contains? current-user-perms "/")
      query
      ;; User has /collection/:id/ or /collection/:id/read/ for the collection the model is in. We will check
      ;; permissions on the database after the query is complete, in `check-permissions-for-model`
      (let [has-root-access?
            (or (contains? current-user-perms "/collection/root/")
                (contains? current-user-perms "/collection/root/read/"))

            collection-id [:coalesce :model.trashed_from_collection_id :collection_id]

            collection-perm-clause
            [:or
             (when has-root-access? [:= collection-id nil])
             [:and
              [:not= collection-id nil]
              [:or
               (has-perm-clause "/collection/" collection-id "/")
               (has-perm-clause "/collection/" collection-id "/read/")]]]]
        (sql.helpers/where
         query
         collection-perm-clause)))))

(defn- sandboxed-or-impersonated-user? []
  ;; TODO FIXME -- search actually currently still requires [[metabase.api.common/*current-user*]] to be bound,
  ;; because [[metabase.public-settings.premium-features/sandboxed-or-impersonated-user?]] requires it to be bound.
  ;; Since it's part of the search context it would be nice if we could run search without having to bind that stuff at
  ;; all.
  (assert @@(requiring-resolve 'metabase.api.common/*current-user*)
          "metabase.api.common/*current-user* must be bound in order to use search for an indexed entity")
  (premium-features/sandboxed-or-impersonated-user?))

(defmethod search-query-for-model "indexed-entity"
  [model {:keys [current-user-perms] :as search-ctx}]
  (-> (base-query-for-model model search-ctx)
      (sql.helpers/left-join [:model_index :model-index]
                             [:= :model-index.id :model-index-value.model_index_id])
      (sql.helpers/left-join [:report_card :model] [:= :model-index.model_id :model.id])
      (sql.helpers/left-join [:collection :collection] [:= :model.collection_id :collection.id])
      (sql.helpers/where (when (sandboxed-or-impersonated-user?) search.filter/false-clause))
      (add-model-index-permissions-clause current-user-perms)))

(defmethod search-query-for-model "segment"
  [model search-ctx]
  (-> (base-query-for-model model search-ctx)
      (sql.helpers/left-join [:metabase_table :table] [:= :segment.table_id :table.id])))

(defn order-clause
  "CASE expression that lets the results be ordered by whether they're an exact (non-fuzzy) match or not"
  [query]
  (let [match             (search.util/wildcard-match (search.util/normalize query))
        columns-to-search (->> search.config/all-search-columns
                               (filter (fn [[_k v]] (= v :text)))
                               (map first)
                               (remove #{:collection_authority_level :moderated_status
                                         :initial_sync_status :pk_ref :location
                                         :collection_location}))
        case-clauses      (as-> columns-to-search <>
                            (map (fn [col] [:like [:lower col] match]) <>)
                            (interleave <> (repeat [:inline 0]))
                            (concat <> [:else [:inline 1]]))]
    [(into [:case] case-clauses)]))

(defmulti ^:private check-permissions-for-model
  {:arglists '([search-ctx search-result])}
  (fn [_search-ctx search-result] ((comp keyword :model) search-result)))

(defn- assert-current-user-perms-set-is-bound
  "TODO FIXME -- search actually currently still requires [[metabase.api.common/*current-user-permissions-set*]] to be
  bound (since [[mi/can-write?]] and [[mi/can-read?]] depend on it) despite search context requiring
  `:current-user-perms` to be passed in. We should fix things so search works independently of API-specific dynamic
  variables. This might require updating `can-read?` and `can-write?` to take explicit perms sets instead of relying
  on dynamic variables."
  []
  (assert (seq @@(requiring-resolve 'metabase.api.common/*current-user-permissions-set*))
          "metabase.api.common/*current-user-permissions-set* must be bound in order to check search permissions"))

(defn- can-write? [instance]
  (assert-current-user-perms-set-is-bound)
  (mi/can-write? instance))

(defn- can-read? [instance]
  (assert-current-user-perms-set-is-bound)
  (mi/can-read? instance))

(defmethod check-permissions-for-model :default
  [search-ctx instance]
  (if (:archived search-ctx)
    (can-write? instance)
    ;; We filter what we can (ie. everything that is in a collection) out already when querying
    true))

(defmethod check-permissions-for-model :table
  [search-ctx instance]
  ;; we've already filtered out tables w/o collection permissions in the query itself.
  (and
   (data-perms/user-has-permission-for-table?
    (:current-user-id search-ctx)
    :perms/view-data
    :unrestricted
    (database/table-id->database-id (:id instance))
    (:id instance))
   (data-perms/user-has-permission-for-table?
    (:current-user-id search-ctx)
    :perms/create-queries
    :query-builder
    (database/table-id->database-id (:id instance))
    (:id instance))))

(defmethod check-permissions-for-model :indexed-entity
  [search-ctx instance]
  (and
   (= :query-builder-and-native
      (data-perms/full-db-permission-for-user (:current-user-id search-ctx) :perms/create-queries (:database_id instance)))
   (= :unrestricted
      (data-perms/full-db-permission-for-user (:current-user-id search-ctx) :perms/view-data (:database_id instance)))))

(defmethod check-permissions-for-model :metric
  [search-ctx instance]
  (if (:archived search-ctx)
    (can-write? instance)
    (can-read? instance)))

(defmethod check-permissions-for-model :segment
  [search-ctx instance]
  (if (:archived search-ctx)
    (can-write? instance)
    (can-read? instance)))

(defmethod check-permissions-for-model :database
  [search-ctx instance]
  (if (:archived search-ctx)
    (can-write? instance)
    (can-read? instance)))

(mu/defn query-model-set :- [:set SearchableModel]
  "Queries all models with respect to query for one result to see if we get a result or not"
  [search-ctx :- SearchContext]
  (let [model-queries (for [model (search.filter/search-context->applicable-models
                                   (assoc search-ctx :models search.config/all-models))]
                        {:nest (sql.helpers/limit (search-query-for-model model search-ctx) 1)})
        query         (when (pos-int? (count model-queries))
                        {:select [:*]
                         :from   [[{:union-all model-queries} :dummy_alias]]})]
    (set (some->> query
                  mdb.query/query
                  (map :model)
                  set))))

(mu/defn ^:private full-search-query
  "Postgres 9 is not happy with the type munging it needs to do to make the union-all degenerate down to trivial case of
  one model without errors. Therefore we degenerate it down for it"
  [search-ctx :- SearchContext]
  (let [models       (:models search-ctx)
        order-clause [((fnil order-clause "") (:search-string search-ctx))]]
    (cond
      (= (count models) 0)
      {:select [nil]}

      (= (count models) 1)
      (search-query-for-model (first models) search-ctx)

      :else
      {:select   [:*]
       :from     [[{:union-all (vec (for [model models
                                          :let  [query (search-query-for-model model search-ctx)]
                                          :when (seq query)]
                                      query))} :alias_is_required_by_sql_but_not_needed_here]]
       :order-by order-clause})))

(defn- hydrate-user-metadata
  "Hydrate common-name for last_edited_by and created_by from result."
  [results]
  (let [user-ids             (set (flatten (for [result results]
                                             (remove nil? ((juxt :last_editor_id :creator_id) result)))))
        user-id->common-name (if (pos? (count user-ids))
                               (t2/select-pk->fn :common_name [:model/User :id :first_name :last_name :email] :id [:in user-ids])
                               {})]
    (mapv (fn [{:keys [creator_id last_editor_id] :as result}]
            (assoc result
                   :creator_common_name (get user-id->common-name creator_id)
                   :last_editor_common_name (get user-id->common-name last_editor_id)))
          results)))

(defn add-dataset-collection-hierarchy
  "Adds `collection_effective_ancestors` to *datasets* in the search results."
  [search-results]
  (let [;; this helper function takes a search result (with `collection_id` and `collection_location`) and returns the
        ;; effective location of the result.
        result->loc  (fn [{:keys [collection_id collection_location]}]
                        (:effective_location
                         (t2/hydrate
                          (if (nil? collection_id)
                            collection/root-collection
                            {:location collection_location})
                          :effective_location)))
        ;; a map of collection-ids to collection info
        col-id->info (into {}
                           (for [item  search-results
                                 :when (= (:model item) "dataset")]
                              [(:collection_id item)
                               {:id                 (:collection_id item)
                                :name               (-> {:name (:collection_name item)
                                                         :id   (:collection_id item)
                                                         :type (:collection_type item)}
                                                        collection/maybe-localize-trash-name
                                                        :name)
                                :type               (:collection_type item)
                                :effective_location (result->loc item)}]))
        ;; the set of all collection IDs where we *don't* know the collection name. For example, if `col-id->info`
        ;; contained `{1 {:effective_location "/2/" :name "Foo"}}`, we need to look up the name of collection `2`.
        to-fetch     (into #{} (comp (keep :effective_location)
                                     (mapcat collection/location-path->ids)
                                      ;; already have these names
                                     (remove col-id->info))
                            (vals col-id->info))
        ;; the now COMPLETE map of collection IDs to info
        col-id->info (merge (if (seq to-fetch)
                              (t2/select-pk->fn #(select-keys % [:name :type :id]) :model/Collection :id [:in to-fetch])
                              {})
                            (update-vals col-id->info #(dissoc % :effective_location)))
        annotate     (fn [x]
                        (cond-> x
                          (= (:model x) "dataset")
                          (assoc :collection_effective_ancestors
                                 (if-let [loc (result->loc x)]
                                   (->> (collection/location-path->ids loc)
                                        (map col-id->info))
                                   []))))]
    (map annotate search-results)))

(defn- add-collection-effective-location
  "Batch-hydrates :effective_location and :effective_parent on collection search results. Keeps search results in
  order."
  [search-results]
  (let [collections    (filter #(mi/instance-of? :model/Collection %) search-results)
        hydrated-colls (t2/hydrate collections :effective_parent)
        idx->coll      (into {} (map (juxt :id identity) hydrated-colls))]
    (map (fn [search-result]
           (if (mi/instance-of? :model/Collection search-result)
             (idx->coll (:id search-result))
             (assoc search-result :effective_location nil)))
         search-results)))

;;; TODO OMG mix of kebab-case and snake_case here going to make me throw up, we should use all kebab-case in Clojure
;;; land and then convert the stuff that actually gets sent over the wire in the REST API to snake_case in the API
;;; endpoint itself, not in the search impl.
(defn serialize
  "Massage the raw result from the DB and match data into something more useful for the client"
  [{:as result :keys [all-scores relevant-scores name display_name collection_id collection_name
                      collection_authority_level collection_type collection_effective_ancestors effective_parent]}]
  (let [matching-columns    (into #{} (remove nil? (map :column relevant-scores)))
        match-context-thunk (first (keep :match-context-thunk relevant-scores))]
    (-> result
        (assoc
         :name           (if (and (contains? matching-columns :display_name) display_name)
                           display_name
                           name)
         :context        (when (and match-context-thunk
                                    (empty?
                                     (remove matching-columns search.config/displayed-columns)))
                           (match-context-thunk))
         :collection     (merge {:id              collection_id
                                 :name            collection_name
                                 :authority_level collection_authority_level
                                 :type            collection_type}
                                (when effective_parent
                                  effective_parent)
                                (when collection_effective_ancestors
                                  {:effective_ancestors collection_effective_ancestors}))
         :scores          all-scores)
        (update :dataset_query (fn [dataset-query]
                                 (when-let [query (some-> dataset-query json/parse-string)]
                                   (if (get query "type")
                                     (mbql.normalize/normalize query)
                                     (not-empty (lib/normalize query))))))
        (dissoc
         :all-scores
         :relevant-scores
         :collection_effective_ancestors
         :trashed_from_collection_id
         :collection_id
         :collection_location
         :collection_name
         :collection_type
         :display_name
         :effective_parent))))

(defn- add-can-write [row]
  (if (some #(mi/instance-of? % row) [:model/Dashboard :model/Card])
    (assoc row :can_write (can-write? row))
    row))

(defn- bit->boolean
  "Coerce a bit returned by some MySQL/MariaDB versions in some situations to Boolean."
  [v]
  (if (number? v)
    (not (zero? v))
    v))

(mu/defn search
  "Builds a search query that includes all the searchable entities and runs it"
  [search-ctx :- SearchContext]
  (let [search-query       (full-search-query search-ctx)
        _                  (log/tracef "Searching with query:\n%s\n%s"
                                       (u/pprint-to-str search-query)
                                       (mdb.query/format-sql (first (mdb.query/compile search-query))))
        to-toucan-instance (fn [row]
                             (let [model (-> row :model search.config/model-to-db-model :db-model)]
                               (t2.instance/instance model row)))
        reducible-results  (reify clojure.lang.IReduceInit
                             (reduce [_this rf init]
                               (binding [t2.jdbc.options/*options* (assoc t2.jdbc.options/*options* :max-rows search.config/*db-max-results*)]
                                 (reduce rf init (t2/reducible-query search-query)))))
        xf                 (comp
                            (map t2.realize/realize)
                            (map to-toucan-instance)
                            (map #(cond-> %
                                    (t2/instance-of? :model/Collection %) (assoc :type (:collection_type %))))
                            (map #(cond-> % (t2/instance-of? :model/Collection %) collection/maybe-localize-trash-name))
                            ;; MySQL returns `:bookmark` and `:archived` as `1` or `0` so convert those to boolean as
                            ;; needed
                            (map #(update % :bookmark bit->boolean))

                            (map #(update % :archived bit->boolean))
                            (filter (partial check-permissions-for-model search-ctx))

                            (map #(update % :pk_ref json/parse-string))
                            (map add-can-write)
                            (map (partial scoring/score-and-result (:search-string search-ctx) (:search-native-query search-ctx)))

                            (filter #(pos? (:score %))))
        total-results       (cond->> (scoring/top-results reducible-results search.config/max-filtered-results xf)
                              true                           hydrate-user-metadata
                              (:model-ancestors? search-ctx) (add-dataset-collection-hierarchy)
                              true                           (add-collection-effective-location)
                              true                           (map serialize))
        add-perms-for-col  (fn [item]
                             (cond-> item
                               (mi/instance-of? :model/Collection item)
                               (assoc :can_write (can-write? item))))]
    ;; We get to do this slicing and dicing with the result data because
    ;; the pagination of search is for UI improvement, not for performance.
    ;; We intend for the cardinality of the search results to be below the default max before this slicing occurs
    {:available_models (query-model-set search-ctx)
     :data             (cond->> total-results
                         (some? (:offset-int search-ctx)) (drop (:offset-int search-ctx))
                         (some? (:limit-int search-ctx)) (take (:limit-int search-ctx))
                         true (map add-perms-for-col))
     :limit            (:limit-int search-ctx)
     :models           (:models search-ctx)
     :offset           (:offset-int search-ctx)
     :table_db_id      (:table-db-id search-ctx)
     :total            (count total-results)}))

(mr/def ::search-context.input
  [:map {:closed true}
   [:search-string                                        [:maybe ms/NonBlankString]]
   [:models                                               [:maybe [:set SearchableModel]]]
   [:current-user-id                                      pos-int?]
   [:current-user-perms                                   [:set perms.u/PathSchema]]
   [:archived                            {:optional true} [:maybe :boolean]]
   [:created-at                          {:optional true} [:maybe ms/NonBlankString]]
   [:created-by                          {:optional true} [:maybe [:set ms/PositiveInt]]]
   [:filter-items-in-personal-collection {:optional true} [:maybe [:enum "only" "exclude"]]]
   [:last-edited-at                      {:optional true} [:maybe ms/NonBlankString]]
   [:last-edited-by                      {:optional true} [:maybe [:set ms/PositiveInt]]]
   [:limit                               {:optional true} [:maybe ms/Int]]
   [:offset                              {:optional true} [:maybe ms/Int]]
   [:table-db-id                         {:optional true} [:maybe ms/PositiveInt]]
   [:search-native-query                 {:optional true} [:maybe boolean?]]
   [:model-ancestors?                    {:optional true} [:maybe boolean?]]
   [:verified                            {:optional true} [:maybe true?]]])

(mu/defn search-context
  "Create a new search context that you can pass to other functions like [[search]]."
  [{:keys [archived
           created-at
           created-by
           current-user-id
           current-user-perms
           last-edited-at
           last-edited-by
           limit
           models
           filter-items-in-personal-collection
           offset
           search-string
           model-ancestors?
           table-db-id
           search-native-query
           verified]}      :- ::search-context.input] :- SearchContext
  ;; for prod where Malli is disabled
  {:pre [(pos-int? current-user-id) (set? current-user-perms)]}
  (when (some? verified)
    (premium-features/assert-has-any-features
     [:content-verification :official-collections]
     (deferred-tru "Content Management or Official Collections")))
  (let [models (if (string? models) [models] models)
        ctx    (cond-> {:current-user-id current-user-id
                        :current-user-perms current-user-perms
                        :model-ancestors?   (boolean model-ancestors?)
                        :models             models
                        :search-string      search-string}
                 (some? archived)                            (assoc :archived archived)
                 (some? created-at)                          (assoc :created-at created-at)
                 (seq created-by)                            (assoc :created-by created-by)
                 (some? filter-items-in-personal-collection) (assoc :filter-items-in-personal-collection filter-items-in-personal-collection)
                 (some? last-edited-at)                      (assoc :last-edited-at last-edited-at)
                 (seq last-edited-by)                        (assoc :last-edited-by last-edited-by)
                 (some? table-db-id)                         (assoc :table-db-id table-db-id)
                 (some? limit)                               (assoc :limit-int limit)
                 (some? offset)                              (assoc :offset-int offset)
                 (some? search-native-query)                 (assoc :search-native-query search-native-query)
                 (some? verified)                            (assoc :verified verified))]
    (assoc ctx :models (search.filter/search-context->applicable-models ctx))))
