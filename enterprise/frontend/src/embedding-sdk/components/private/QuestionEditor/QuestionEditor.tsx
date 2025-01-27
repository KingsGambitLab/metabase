import { useDisclosure } from "@mantine/hooks";
import { useState } from "react";

import { FlexibleSizeComponent } from "embedding-sdk";
import { InteractiveQuestion } from "embedding-sdk/components/public/InteractiveQuestion";
import { SaveQuestionModal } from "metabase/containers/SaveQuestionModal";
import { Box, Button, Group, Icon, Stack, Tabs } from "metabase/ui";

import type { InteractiveQuestionProps } from "../../public/InteractiveQuestion";
import { useInteractiveQuestionContext } from "../InteractiveQuestion/context";

import QuestionEditorS from "./QuestionEditor.module.css";

const QuestionEditorInner = () => {
  const {
    queryResults,
    runQuestion,
    isSaveEnabled,
    question,
    originalQuestion,
    onSave,
    onCreate,
  } = useInteractiveQuestionContext();

  const [activeTab, setActiveTab] = useState<
    "notebook" | "visualization" | (string & unknown) | null
  >("notebook");
  const [isSaveModalOpen, { open: openSaveModal, close: closeSaveModal }] =
    useDisclosure(false);

  const onOpenVisualizationTab = async () => {
    setActiveTab("visualization");
    await runQuestion();
  };

  const [isVisualizationSelectorOpen, { toggle: toggleVisualizationSelector }] =
    useDisclosure();

  return (
    <FlexibleSizeComponent>
      <Tabs
        value={activeTab}
        onTabChange={setActiveTab}
        defaultValue="notebook"
        h="100%"
        display="flex"
        style={{ flexDirection: "column", overflow: "hidden" }}
      >
        <Group position="apart">
          <Tabs.List>
            <Tabs.Tab value="notebook">Notebook</Tabs.Tab>
            {queryResults && (
              <Tabs.Tab value="visualization" onClick={onOpenVisualizationTab}>
                Visualization
              </Tabs.Tab>
            )}
          </Tabs.List>

          {!isSaveModalOpen && (
            <Group>
              <InteractiveQuestion.ResetButton
                onClick={() => {
                  setActiveTab("notebook");
                  closeSaveModal();
                }}
              />
              {isSaveEnabled && (
                <InteractiveQuestion.SaveButton onClick={openSaveModal} />
              )}
            </Group>
          )}
        </Group>

        <Tabs.Panel value="notebook" h="100%" style={{ overflow: "auto" }}>
          <InteractiveQuestion.Notebook
            onApply={() => setActiveTab("visualization")}
          />
        </Tabs.Panel>
        <Tabs.Panel
          value="visualization"
          h="100%"
          p="md"
          style={{ overflow: "hidden" }}
        >
          <Stack h="100%">
            <Box>
              <Button
                compact
                radius="xl"
                py="sm"
                px="md"
                variant="filled"
                color="brand"
                onClick={toggleVisualizationSelector}
              >
                <Group>
                  <Icon
                    name={
                      isVisualizationSelectorOpen ? "arrow_left" : "arrow_right"
                    }
                  />
                  <Icon name="eye" />
                </Group>
              </Button>
            </Box>

            <Box className={QuestionEditorS.Main} w="100%" h="100%">
              <Box className={QuestionEditorS.ChartTypeSelector}>
                {isVisualizationSelectorOpen && (
                  <InteractiveQuestion.ChartTypeSelector />
                )}
              </Box>
              <Box className={QuestionEditorS.Content}>
                <InteractiveQuestion.QuestionVisualization />
              </Box>
            </Box>
          </Stack>
        </Tabs.Panel>
      </Tabs>

      {/* Refer to the SaveQuestionProvider for context on why we have to do it like this */}
      {isSaveModalOpen && question && (
        <SaveQuestionModal
          question={question}
          originalQuestion={originalQuestion ?? null}
          opened={true}
          closeOnSuccess={true}
          onClose={closeSaveModal}
          onCreate={onCreate}
          onSave={onSave}
        />
      )}
    </FlexibleSizeComponent>
  );
};

export const QuestionEditor = ({
  questionId,
  isSaveEnabled = true,
  onBeforeSave,
  onSave,
  plugins,
  entityTypeFilter,
}: InteractiveQuestionProps) => (
  <InteractiveQuestion
    questionId={questionId}
    plugins={plugins}
    onSave={onSave}
    onBeforeSave={onBeforeSave}
    isSaveEnabled={isSaveEnabled}
    entityTypeFilter={entityTypeFilter}
  >
    <QuestionEditorInner />
  </InteractiveQuestion>
);
