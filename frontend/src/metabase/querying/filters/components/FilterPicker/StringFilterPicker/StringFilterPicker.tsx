import type { FormEvent } from "react";
import { useMemo } from "react";
import { t } from "ttag";

import {
  type OperatorType,
  useStringFilter,
} from "metabase/querying/filters/hooks/use-string-filter";
import { Box, Checkbox, Flex, MultiAutocomplete } from "metabase/ui";
import * as Lib from "metabase-lib";

import { StringFilterValuePicker } from "../../FilterValuePicker";
import { FilterOperatorPicker } from "../FilterOperatorPicker";
import { FilterPickerFooter } from "../FilterPickerFooter";
import { FilterPickerForm } from "../FilterPickerForm";
import { FilterPickerHeader } from "../FilterPickerHeader";
import type { FilterPickerWidgetProps } from "../types";

export function StringFilterPicker({
  query,
  stageIndex,
  column,
  filter,
  isNew,
  onChange,
  onBack,
}: FilterPickerWidgetProps) {
  const columnInfo = useMemo(
    () => Lib.displayInfo(query, stageIndex, column),
    [query, stageIndex, column],
  );

  const {
    type,
    operator,
    availableOptions,
    values,
    options,
    isValid,
    getDefaultValues,
    getFilterClause,
    setOperator,
    setValues,
    setOptions,
  } = useStringFilter({
    query,
    stageIndex,
    column,
    filter,
  });

  const handleOperatorChange = (newOperator: Lib.StringFilterOperatorName) => {
    setOperator(newOperator);
    setValues(getDefaultValues(newOperator, values));
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();

    const filter = getFilterClause(operator, values, options);
    if (filter) {
      onChange(filter);
    }
  };

  return (
    <FilterPickerForm
      data-testid="string-filter-picker"
      onSubmit={handleSubmit}
    >
      <FilterPickerHeader
        columnName={columnInfo.longDisplayName}
        onBack={onBack}
      >
        <FilterOperatorPicker
          value={operator}
          options={availableOptions}
          onChange={handleOperatorChange}
        />
      </FilterPickerHeader>
      <Flex direction="column" mih={0}>
        <StringValueInput
          query={query}
          stageIndex={stageIndex}
          column={column}
          values={values}
          type={type}
          onChange={setValues}
        />
        <FilterPickerFooter isNew={isNew} canSubmit={isValid}>
          {type === "partial" && (
            <CaseSensitiveOption
              value={options["case-sensitive"] ?? false}
              onChange={newValue => setOptions({ "case-sensitive": newValue })}
            />
          )}
        </FilterPickerFooter>
      </Flex>
    </FilterPickerForm>
  );
}

interface StringValueInputProps {
  query: Lib.Query;
  stageIndex: number;
  column: Lib.ColumnMetadata;
  values: string[];
  type: OperatorType;
  onChange: (values: string[]) => void;
}

function StringValueInput({
  query,
  stageIndex,
  column,
  values,
  type,
  onChange,
}: StringValueInputProps) {
  if (type === "exact") {
    return (
      <Box p="md" style={{ overflow: "auto" }}>
        <StringFilterValuePicker
          query={query}
          stageIndex={stageIndex}
          column={column}
          values={values}
          autoFocus
          onChange={onChange}
        />
      </Box>
    );
  }

  if (type === "partial") {
    return (
      <Box p="md">
        <MultiAutocomplete
          value={values}
          data={[]}
          placeholder={t`Enter some text`}
          autoFocus
          w="100%"
          aria-label={t`Filter value`}
          onChange={onChange}
        />
      </Box>
    );
  }

  return null;
}

interface CaseSensitiveOptionProps {
  value: boolean;
  onChange: (value: boolean) => void;
}

function CaseSensitiveOption({ value, onChange }: CaseSensitiveOptionProps) {
  return (
    <Flex align="center" px="sm">
      <Checkbox
        size="xs"
        label={t`Case sensitive`}
        checked={value}
        onChange={e => onChange(e.target.checked)}
      />
    </Flex>
  );
}
