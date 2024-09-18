import { useState } from "react";

import CS from "metabase/css/core/index.css";
import { Icon, Popover } from "metabase/ui";
import * as Lib from "metabase-lib";

import { NotebookCellItem } from "../NotebookCell";

import { FilterPopover } from "./FilterPopover";

interface FilterSuggestionProps {
  field: any;
  query: any;
  stageIndex: number;
  handleAddFilter: any;
  handleUpdateFilter: any;
}

export const FilterSuggestion = ({
  field,
  query,
  stageIndex,
  handleAddFilter,
  handleUpdateFilter,
}: FilterSuggestionProps) => {
  const [opened, setOpened] = useState(false);

  const filter = (() => {
    if (Lib.isNumber(field.column)) {
      const argsAttribute = field.attribute + "_most_used_args";
      const opAttribute = field.attribute + "_most_used_op";

      return Lib.numberFilterClause({
        operator: field[opAttribute],
        column: field.column,
        values: field[argsAttribute],
      });
    }

    if (Lib.isString(field.column)) {
      const argsAttribute = field.attribute + "_most_used_args";
      const opAttribute = field.attribute + "_most_used_op";

      return Lib.stringFilterClause({
        operator: field[opAttribute],
        column: field.column,
        values: field[argsAttribute],
        options: {},
      });
    }

    return null;
  })();

  return (
    <Popover opened={opened} onChange={setOpened}>
      <Popover.Target>
        <NotebookCellItem
          color="#8F90EA33"
          containerStyle={{
            color: "#7173AD",
            border: "1px dashed",
            boxSizing: "border-box",
            background: "linear-gradient(to left, #8DC0ED33, #8F90EA33)",
            marginLeft: "0.5rem",
          }}
          onClick={() => setOpened(true)}
        >
          <Icon className={CS.mr1} name="sparkles" />
          {generateFilterLabel(field)}
          <Icon className={CS.ml1} name="add" />
        </NotebookCellItem>
      </Popover.Target>
      <Popover.Dropdown>
        <FilterPopover
          initialFilter={filter as any}
          initialColumn={field.column}
          query={query}
          stageIndex={stageIndex}
          onAddFilter={clause => {
            setOpened(false);
            handleAddFilter(clause);
          }}
          onUpdateFilter={handleUpdateFilter}
        />
      </Popover.Dropdown>
    </Popover>
  );
};

function generateFilterLabel(field) {
  const args = field[field.attribute + "_most_used_args"];
  const operator = field[field.attribute + "_most_used_op"];

  if (args && operator) {
    return `${field.display_name} ${operator === "between" ? `is between` : operator} ${args?.join(" and ")}`;
  } else {
    return field.display_name;
  }
}
