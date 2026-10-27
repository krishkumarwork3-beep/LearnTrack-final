package com.learntrack.ui.forms;

import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.SyllabusUnit;
import com.learntrack.model.Topic;
import com.learntrack.ui.UiKit;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Reusable component for displaying exam syllabus scope with units, subtopics, and study progress.
 */
public class ExamScopeView extends VBox {

    private final ExamScopePresenter presenter;
    private final Consumer<Void> onEditRequest;
    private final boolean examSaved;

    private List<SyllabusSubtopic> selectedSubtopics;
    private Map<Integer, String> unitNames;
    private Map<Integer, Integer> unitTotalCounts;
    private Map<Integer, Topic> subtopicToTopic;

    private ExamScopePresenter.StatusCategory currentFilter = ExamScopePresenter.StatusCategory.ALL;
    private ExamScopePresenter.SortMode currentSort = ExamScopePresenter.SortMode.SYLLABUS_ORDER;

    private VBox contentArea;

    public ExamScopeView(Consumer<Void> onEditRequest, boolean examSaved) {
        this.presenter = new ExamScopePresenter();
        this.onEditRequest = onEditRequest;
        this.examSaved = examSaved;
        
        this.selectedSubtopics = new ArrayList<>();
        this.unitNames = new HashMap<>();
        this.unitTotalCounts = new HashMap<>();
        this.subtopicToTopic = new HashMap<>();

        buildUI();
    }

    private void buildUI() {
        setSpacing(8);
        setPadding(new Insets(8, 0, 0, 0));

        contentArea = new VBox();
        getChildren().add(contentArea);
        
        refresh();
    }

    /**
     * Update the scope data and refresh the view.
     * @param selectedSubtopics List of selected subtopics for this exam
     * @param allUnits List of all units (for names)
     * @param allSubtopicsInUnits Map of unitId -> total subtopic count in that unit
     * @param subtopicToTopic Map of subtopicId -> linked Topic
     */
    public void setScope(List<SyllabusSubtopic> selectedSubtopics,
                         List<SyllabusUnit> allUnits,
                         Map<Integer, Integer> allSubtopicsInUnits,
                         Map<Integer, Topic> subtopicToTopic) {
        this.selectedSubtopics = new ArrayList<>(selectedSubtopics);
        this.subtopicToTopic = new HashMap<>(subtopicToTopic);
        
        // Build unit names
        this.unitNames.clear();
        for (SyllabusUnit unit : allUnits) {
            unitNames.put(unit.getUnitId(), unit.getName());
        }
        
        // Store total counts
        this.unitTotalCounts.clear();
        this.unitTotalCounts.putAll(allSubtopicsInUnits);
        
        refresh();
    }

    private void refresh() {
        contentArea.getChildren().clear();

        if (selectedSubtopics.isEmpty()) {
            contentArea.getChildren().add(buildEmptyState());
        } else {
            contentArea.getChildren().add(buildHeader());
            contentArea.getChildren().add(buildToolbar());
            contentArea.getChildren().add(buildUnitCards());
        }
    }

    private VBox buildEmptyState() {
        VBox emptyState = UiKit.emptyState("No syllabus selected yet",
                examSaved ? "Choose the subtopics that this exam covers."
                        : "Save this exam first, then add its syllabus scope.");

        Button addButton = UiKit.primaryButton("Add from syllabus");
        addButton.setDisable(!examSaved);
        addButton.setOnAction(e -> onEditRequest.accept(null));
        if (!examSaved) {
            addButton.setTooltip(new Tooltip("Save this exam first"));
        }
        emptyState.getChildren().add(addButton);
        return emptyState;
    }

    private HBox buildHeader() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        double totalHours = presenter.calculateTotalHours(selectedSubtopics, subtopicToTopic);
        int unitCount = (int) selectedSubtopics.stream().map(SyllabusSubtopic::getUnitId).distinct().count();

        Label summaryLabel = new Label(String.format("Syllabus scope: %d of %d subtopics · %d units · ~%.0f h",
                selectedSubtopics.size(),
                unitTotalCounts.values().stream().mapToInt(Integer::intValue).sum(),
                unitCount,
                totalHours));
        summaryLabel.getStyleClass().add("section-title");

        Button editButton = UiKit.secondaryButton("Edit syllabus");
        editButton.setOnAction(e -> onEditRequest.accept(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(summaryLabel, spacer, editButton);
        return header;
    }

    private HBox buildToolbar() {
        HBox toolbar = new HBox(12);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 0, 4, 0));

        // Filter
        Label filterLabel = new Label("Filter:");
        filterLabel.getStyleClass().add("subtle");

        ToggleGroup filterGroup = new ToggleGroup();
        RadioButton allRadio = new RadioButton("All");
        allRadio.setToggleGroup(filterGroup);
        allRadio.setSelected(true);

        RadioButton notStartedRadio = new RadioButton("Not started");
        notStartedRadio.setToggleGroup(filterGroup);

        RadioButton inProgressRadio = new RadioButton("In progress");
        inProgressRadio.setToggleGroup(filterGroup);

        RadioButton doneRadio = new RadioButton("Done");
        doneRadio.setToggleGroup(filterGroup);

        filterGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
            if (newVal == allRadio) currentFilter = ExamScopePresenter.StatusCategory.ALL;
            else if (newVal == notStartedRadio) currentFilter = ExamScopePresenter.StatusCategory.NOT_STARTED;
            else if (newVal == inProgressRadio) currentFilter = ExamScopePresenter.StatusCategory.IN_PROGRESS;
            else if (newVal == doneRadio) currentFilter = ExamScopePresenter.StatusCategory.DONE;
            refresh();
        });

        // Sort
        Label sortLabel = new Label("Sort:");
        sortLabel.getStyleClass().add("subtle");

        ToggleGroup sortGroup = new ToggleGroup();
        RadioButton syllabusOrderRadio = new RadioButton("Syllabus order");
        syllabusOrderRadio.setToggleGroup(sortGroup);
        syllabusOrderRadio.setSelected(true);

        RadioButton mostHoursRadio = new RadioButton("Most hours first");
        mostHoursRadio.setToggleGroup(sortGroup);

        sortGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
            if (newVal == syllabusOrderRadio) currentSort = ExamScopePresenter.SortMode.SYLLABUS_ORDER;
            else if (newVal == mostHoursRadio) currentSort = ExamScopePresenter.SortMode.MOST_HOURS_FIRST;
            refresh();
        });

        toolbar.getChildren().addAll(
                filterLabel, allRadio, notStartedRadio, inProgressRadio, doneRadio,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                sortLabel, syllabusOrderRadio, mostHoursRadio
        );

        return toolbar;
    }

    private VBox buildUnitCards() {
        VBox cardsContainer = new VBox(8);

        List<ExamScopePresenter.UnitScope> units = presenter.groupByUnit(
                selectedSubtopics, unitNames, unitTotalCounts, subtopicToTopic);

        for (ExamScopePresenter.UnitScope unit : units) {
            VBox unitCard = buildUnitCard(unit);
            if (unitCard != null) {
                cardsContainer.getChildren().add(unitCard);
            }
        }

        return cardsContainer;
    }

    private VBox buildUnitCard(ExamScopePresenter.UnitScope unit) {
        // Filter subtopics
        List<ExamScopePresenter.SubtopicScope> filteredSubtopics = presenter.filter(unit.getSelectedSubtopics(), currentFilter);
        if (filteredSubtopics.isEmpty()) {
            return null; // Don't show empty units
        }

        // Sort subtopics
        filteredSubtopics = presenter.sort(filteredSubtopics, currentSort);

        VBox card = new VBox(6);
        card.getStyleClass().add("scope-card");

        // Unit header
        HBox unitHeader = new HBox(8);
        unitHeader.setAlignment(Pos.CENTER_LEFT);

        Label unitTitle = new Label(unit.getUnitName());
        unitTitle.getStyleClass().add("scope-unit-title");

        Label countChip = new Label(unit.getSelectedCount() + " of " + unit.getTotalSubtopics());
        countChip.getStyleClass().addAll("chip", "chip-info");

        // Progress bar
        ProgressBar progressBar = new ProgressBar(unit.getProgress());
        progressBar.setPrefWidth(100);
        progressBar.getStyleClass().add("progress-bar-success");

        // Collapse toggle
        Button collapseToggle = UiKit.iconButton(
                unit.getSelectedCount() > 3 ? UiKit.Icon.CHEVRON_RIGHT : UiKit.Icon.CHEVRON_DOWN,
                "Show or hide this unit's subtopics");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        unitHeader.getChildren().addAll(unitTitle, countChip, progressBar, spacer, collapseToggle);

        // Subtopics list (collapsible)
        VBox subtopicsList = new VBox(4);
        subtopicsList.setPadding(new Insets(4, 0, 0, 16));
        subtopicsList.setManaged(unit.getSelectedCount() <= 3);
        subtopicsList.setVisible(unit.getSelectedCount() <= 3);

        for (ExamScopePresenter.SubtopicScope subtopic : filteredSubtopics) {
            subtopicsList.getChildren().add(buildSubtopicRow(subtopic));
        }

        collapseToggle.setOnAction(e -> {
            boolean isVisible = subtopicsList.isVisible();
            subtopicsList.setVisible(!isVisible);
            subtopicsList.setManaged(!isVisible);
            collapseToggle.setGraphic(UiKit.icon(isVisible
                    ? UiKit.Icon.CHEVRON_RIGHT : UiKit.Icon.CHEVRON_DOWN));
        });

        card.getChildren().addAll(unitHeader, subtopicsList);
        return card;
    }

    private HBox buildSubtopicRow(ExamScopePresenter.SubtopicScope subtopic) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 0, 2, 0));

        Label nameLabel = new Label(subtopic.getName());

        // Hours badge
        Label hoursBadge = new Label(String.format("%.1fh / %.1fh rev", subtopic.getHoursToCover(), subtopic.getHoursToRevise()));
        hoursBadge.getStyleClass().addAll("chip", "chip-info");

        // Status chip
        String statusText = subtopic.getStatusLabel();
        Label statusChip = new Label(statusText);
        statusChip.getStyleClass().add("chip");
        if ("Done".equals(statusText)) {
            statusChip.getStyleClass().add("chip-ok");
        } else if ("Not started".equals(statusText)) {
            statusChip.getStyleClass().add("chip-info");
        } else {
            statusChip.getStyleClass().add("chip-warn");
        }

        row.getChildren().addAll(nameLabel, hoursBadge, statusChip);
        return row;
    }

    /**
     * Refresh the view with current data.
     */
    public void refreshView() {
        refresh();
    }
}
