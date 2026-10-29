package com.learntrack.ui.forms;

import com.learntrack.dao.DailyLogDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.dao.SkillDAO;
import com.learntrack.dao.SkillSubtopicDAO;
import com.learntrack.model.Semester;
import com.learntrack.model.Skill;
import com.learntrack.model.SkillSubtopic;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.ui.ReplanRunner;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DateUtils;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * Controller/View for managing permanent, semester-independent skills and their subtopics.
 * Skills have subtopics with dedicated learning, revision intervals, and completion checkboxes.
 */
public class SkillFormController {

    private final SkillDAO skillDAO;
    private final SkillSubtopicDAO skillSubtopicDAO;
    private final DailyLogDAO dailyLogDAO;
    private final SemesterDAO semesterDAO;
    private final SemesterReplanService replanService;
    private final Runnable onDataChanged;
    private final ReplanRunner replanRunner;

    private final VBox skillCardsContainer = new VBox(16);
    private TextField newSkillNameField;
    private TextField newSkillCategoryField;
    private ComboBox<Integer> newSkillPriority;
    private DatePicker newSkillTarget;
    private static final java.time.format.DateTimeFormatter GOAL_DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public SkillFormController(SkillDAO skillDAO, SemesterDAO semesterDAO,
                               SemesterReplanService replanService, Runnable onDataChanged) {
        this(skillDAO, semesterDAO, replanService, onDataChanged, null);
    }

    public SkillFormController(SkillDAO skillDAO, SemesterDAO semesterDAO,
                               SemesterReplanService replanService, Runnable onDataChanged,
                               ReplanRunner replanRunner) {
        this(skillDAO, new SkillSubtopicDAO(), new DailyLogDAO(), semesterDAO, replanService, onDataChanged, replanRunner);
    }

    public SkillFormController(SkillDAO skillDAO, SkillSubtopicDAO skillSubtopicDAO, DailyLogDAO dailyLogDAO,
                               SemesterDAO semesterDAO, SemesterReplanService replanService,
                               Runnable onDataChanged, ReplanRunner replanRunner) {
        this.skillDAO = skillDAO;
        this.skillSubtopicDAO = skillSubtopicDAO != null ? skillSubtopicDAO : new SkillSubtopicDAO();
        this.dailyLogDAO = dailyLogDAO != null ? dailyLogDAO : new DailyLogDAO();
        this.semesterDAO = semesterDAO;
        this.replanService = replanService;
        this.onDataChanged = onDataChanged;
        this.replanRunner = replanRunner;
    }

    public Node getView() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("page");

        HBox header = UiKit.pageHeader("Skills",
                "Skills carry across semesters. Break each one into subtopics with learning hours and a revision interval.",
                null);

        newSkillNameField = new TextField();
        newSkillNameField.setPromptText("e.g. Data Structures in C++");
        newSkillNameField.setPrefWidth(300);
        newSkillNameField.setOnAction(e -> handleAddSkill());

        newSkillCategoryField = new TextField("General");
        newSkillCategoryField.setPromptText("Category (optional)");
        newSkillCategoryField.setPrefWidth(180);
        newSkillCategoryField.setOnAction(e -> handleAddSkill());

        Button btnAddSkill = UiKit.primaryButton("Add skill");
        btnAddSkill.setOnAction(e -> handleAddSkill());
        if (replanRunner != null) {
            btnAddSkill.disableProperty().bind(replanRunner.busyProperty());
        }

        newSkillPriority = UiKit.levelPicker(3, Skill::priorityLabel, null);
        newSkillPriority.setPrefWidth(130);
        newSkillPriority.setPrefHeight(34);
        newSkillTarget = new DatePicker();
        newSkillTarget.setPromptText("Optional");
        newSkillTarget.setPrefWidth(150);

        VBox nameBox = new VBox(4, smallCaption("Skill name"), newSkillNameField);
        VBox categoryBox = new VBox(4, smallCaption("Category"), newSkillCategoryField);
        VBox priorityBox = new VBox(4, smallCaption("Priority"), newSkillPriority);
        VBox targetBox = new VBox(4, smallCaption("Target date"), newSkillTarget);
        HBox addSkillBar = new HBox(12, nameBox, categoryBox, priorityBox, targetBox, btnAddSkill);
        addSkillBar.setAlignment(Pos.BOTTOM_LEFT);

        VBox addCard = UiKit.card(UiKit.sectionTitle("Add a skill"), addSkillBar,
                UiKit.hint("Skills use the time exam work leaves free. Higher priority skills go first; a target date "
                        + "speeds a skill up when its remaining hours get tight."));
        addCard.setSpacing(12);

        ScrollPane scrollPane = new ScrollPane(skillCardsContainer);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root.getChildren().addAll(header, addCard, scrollPane);

        loadSkills();
        return root;
    }

    private Label smallCaption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("stat-label");
        return label;
    }

    public void loadSkills() {
        skillCardsContainer.getChildren().clear();
        try {
            List<Skill> skills = new ArrayList<>(skillDAO.getAll());
            // Highest priority first, then the nearest goal date.
            skills.sort(Comparator.comparingInt((Skill s) -> -s.getPriority())
                    .thenComparing(s -> s.getTargetDate() == null ? LocalDate.MAX : s.getTargetDate()));
            if (skills.isEmpty()) {
                skillCardsContainer.getChildren().add(UiKit.emptyState("No skills yet",
                        "Add a skill above, then split it into subtopics you can schedule and revise."));
                return;
            }

            // Fetch upcoming scheduled dates for all subtopics from daily_log
            Map<Integer, List<LocalDate>> upcomingLearn = dailyLogDAO.findUpcomingDatesByItemTypes(
                    List.of("SKILL_LEARN"), LocalDate.now()
            );
            Map<Integer, List<LocalDate>> upcomingRevise = dailyLogDAO.findUpcomingDatesByItemTypes(
                    List.of("SKILL_REVISE"), LocalDate.now()
            );

            for (Skill skill : skills) {
                Node card = createSkillCard(skill, upcomingLearn, upcomingRevise);
                skillCardsContainer.getChildren().add(card);
            }
        } catch (SQLException e) {
            showError("Database Error", "Failed to load skills: " + e.getMessage());
        }
    }

    private Node createSkillCard(Skill skill, Map<Integer, List<LocalDate>> upcomingLearn, Map<Integer, List<LocalDate>> upcomingRevise) throws SQLException {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");

        List<SkillSubtopic> subtopics = skillSubtopicDAO.findBySkill(skill.getSkillId());

        Label nameLabel = new Label(skill.getName());
        nameLabel.getStyleClass().add("section-title");

        Label catLabel = new Label(skill.getCategory() != null ? skill.getCategory() : "General");
        catLabel.getStyleClass().add("subtle");

        // Calculate progress
        int totalCount = subtopics.size();
        int coveredCount = 0;
        double totalHours = 0.0;
        double doneHours = 0.0;
        for (SkillSubtopic st : subtopics) {
            totalHours += (st.getLearnHours() + st.getReviseHours());
            if (st.isCovered()) {
                coveredCount++;
            }
            if (st.isLearned()) {
                doneHours += st.getLearnHours();
            } else {
                doneHours += Math.max(0.0, st.getLearnHours() - st.getLearnRemainingHours());
            }
            if (st.isRevised()) {
                doneHours += st.getReviseHours();
            } else {
                doneHours += Math.max(0.0, st.getReviseHours() - st.getReviseRemainingHours());
            }
        }

        int percentDone = totalHours > 0 ? (int) Math.round((doneHours / totalHours) * 100.0) : (totalCount > 0 ? 0 : 100);

        Label progressLabel = new Label(totalCount > 0
                ? String.format("%d / %d subtopics covered · %d%% of hours done", coveredCount, totalCount, percentDone)
                : "No subtopics yet");
        progressLabel.getStyleClass().add("subtle");

        ProgressBar progressBar = new ProgressBar(totalHours > 0 ? (doneHours / totalHours) : (totalCount > 0 ? 0.0 : 1.0));
        progressBar.setPrefWidth(160);
        progressBar.setPrefHeight(10);
        if (percentDone >= 100) {
            progressBar.getStyleClass().add("progress-bar-success");
        }
        VBox progressBox = new VBox(3, progressBar, progressLabel);

        Button btnAddSubtopic = UiKit.primaryButton("Add subtopic");
        btnAddSubtopic.setOnAction(e -> showAddSubtopicDialog(skill));

        Button btnEditSkill = UiKit.secondaryButton("Edit skill");
        btnEditSkill.setOnAction(e -> showEditSkillDialog(skill));

        Button btnDeleteSkill = UiKit.dangerTextButton("Delete");
        btnDeleteSkill.setOnAction(e -> handleDeleteSkill(skill));

        if (replanRunner != null) {
            btnAddSubtopic.disableProperty().bind(replanRunner.busyProperty());
            btnEditSkill.disableProperty().bind(replanRunner.busyProperty());
            btnDeleteSkill.disableProperty().bind(replanRunner.busyProperty());
        }

        HBox titleRow = new HBox(8, nameLabel, catLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (totalCount > 0 && coveredCount == totalCount) {
            titleRow.getChildren().add(UiKit.status(UiKit.Kind.SUCCESS, "Covered"));
        }

        // Priority can be changed in place; the goal line says whether the plan meets the target.
        ComboBox<Integer> priorityPicker = UiKit.levelPicker(skill.getPriority(), Skill::priorityLabel, level -> {
            try {
                skill.setPriority(level);
                skillDAO.update(skill);
                triggerReplan();
            } catch (SQLException ex) {
                showError("Priority could not be updated", ex.getMessage());
            }
        });
        priorityPicker.setTooltip(new Tooltip("Priority: higher priority skills get free time first"));
        HBox metaRow = new HBox(8, priorityPicker);
        metaRow.getStyleClass().add("skill-meta");
        Label goal = goalStatus(skill, subtopics, upcomingLearn);
        if (goal != null) {
            metaRow.getChildren().add(goal);
        }
        VBox titleBox = new VBox(6, titleRow, metaRow);

        HBox actionBox = new HBox(8, btnAddSubtopic, btnEditSkill, btnDeleteSkill);
        actionBox.setAlignment(Pos.CENTER_RIGHT);

        HBox headerBox = new HBox(12, titleBox, UiKit.spacer(), progressBox, actionBox);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().add(headerBox);

        // Subtopic table
        if (!subtopics.isEmpty()) {
            Label tableError = new Label();
            tableError.getStyleClass().add("field-error");
            tableError.setVisible(false);
            tableError.setManaged(false);
            TableView<SkillSubtopic> table = createSubtopicsTable(skill, subtopics, upcomingLearn, upcomingRevise,
                    tableError);
            card.getChildren().addAll(table, tableError);
        } else {
            card.getChildren().add(UiKit.emptyState("No subtopics yet",
                    "Use Add subtopic to break this skill into pieces you can schedule."));
        }

        return card;
    }

    /**
     * Goal line for a skill: when its last planned learning session falls compared with the
     * target date, or how much is left when there is no target.
     */
    private Label goalStatus(Skill skill, List<SkillSubtopic> subtopics, Map<Integer, List<LocalDate>> upcomingLearn) {
        double remaining = 0.0;
        LocalDate lastPlanned = null;
        boolean unscheduled = false;
        for (SkillSubtopic st : subtopics) {
            if (st.isLearned() || st.getLearnRemainingHours() <= 0.01) continue;
            remaining += st.getLearnRemainingHours();
            List<LocalDate> dates = upcomingLearn.get(st.getSubtopicId());
            if (dates == null || dates.isEmpty()) {
                unscheduled = true;
                continue;
            }
            for (LocalDate d : dates) {
                if (lastPlanned == null || d.isAfter(lastPlanned)) lastPlanned = d;
            }
        }
        LocalDate target = skill.getTargetDate();
        Label label;
        if (subtopics.isEmpty()) {
            return target == null ? null : goalLabel("Target " + target.format(GOAL_DATE), "goal-warn");
        }
        if (remaining <= 0.01) {
            label = goalLabel("All subtopics learned", "goal-ok");
        } else if (target == null) {
            label = goalLabel(com.learntrack.util.DurationFormat.format(remaining) + " left to learn"
                    + (lastPlanned != null && !unscheduled ? " · planned by " + lastPlanned.format(GOAL_DATE) : ""), null);
        } else if (unscheduled || lastPlanned == null) {
            label = goalLabel("Target " + target.format(GOAL_DATE) + " · not all sessions fit yet", "goal-warn");
        } else if (lastPlanned.isAfter(target)) {
            label = goalLabel("Behind: plan finishes " + lastPlanned.format(GOAL_DATE) + ", target "
                    + target.format(GOAL_DATE), "goal-danger");
        } else {
            label = goalLabel("On track for " + target.format(GOAL_DATE) + " · finishes "
                    + lastPlanned.format(GOAL_DATE), "goal-ok");
        }
        return label;
    }

    private Label goalLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        if (styleClass != null) {
            label.getStyleClass().add(styleClass);
        }
        return label;
    }

    private TableView<SkillSubtopic> createSubtopicsTable(Skill skill, List<SkillSubtopic> subtopics,
                                                          Map<Integer, List<LocalDate>> upcomingLearn,
                                                          Map<Integer, List<LocalDate>> upcomingRevise,
                                                          Label tableError) {
        TableView<SkillSubtopic> table = new TableView<>();
        // Every column is constrained to the card width, so the Actions column can never be
        // pushed out of view at the minimum window size.
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(44);
        table.setPrefHeight(46 + (subtopics.size() * 44));
        table.setMinHeight(46 + Math.min(subtopics.size(), 3) * 44);
        table.setPlaceholder(UiKit.hint("No subtopics yet."));
        table.getStyleClass().add("compact-table");

        ObservableList<SkillSubtopic> items = FXCollections.observableArrayList(subtopics);
        table.setItems(items);

        // Double-click anywhere that is not an editor or a button opens the subtopic dialog.
        table.setRowFactory(tv -> {
            TableRow<SkillSubtopic> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && !isInputTarget(event.getTarget())) {
                    showEditSubtopicDialog(row.getItem());
                }
            });
            return row;
        });

        TableColumn<SkillSubtopic, Integer> colPos = new TableColumn<>("#");
        colPos.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().getPosition()));
        colPos.setPrefWidth(36);
        colPos.setMinWidth(30);
        colPos.setMaxWidth(48);
        colPos.getStyleClass().add("cell-center");

        TableColumn<SkillSubtopic, String> colName = new TableColumn<>("Subtopic");
        colName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getName()));
        colName.setPrefWidth(260);
        colName.setMinWidth(130);

        TableColumn<SkillSubtopic, SkillSubtopic> colLearn = new TableColumn<>("Learn");
        colLearn.setPrefWidth(84);
        colLearn.setMinWidth(72);
        colLearn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colLearn.setCellFactory(c -> editableNumberCell("Learn hours", false, 0.25, 100.0,
                SkillSubtopic::getLearnHours,
                st -> String.format("%.2f h", st.getLearnHours()),
                this::saveLearnHours, tableError));

        TableColumn<SkillSubtopic, SkillSubtopic> colRevise = new TableColumn<>("Revise");
        colRevise.setPrefWidth(84);
        colRevise.setMinWidth(72);
        colRevise.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colRevise.setCellFactory(c -> editableNumberCell("Revise hours", false, 0.25, 100.0,
                SkillSubtopic::getReviseHours,
                st -> String.format("%.2f h", st.getReviseHours()),
                this::saveReviseHours, tableError));

        TableColumn<SkillSubtopic, SkillSubtopic> colReviseAfter = new TableColumn<>("Every");
        colReviseAfter.setPrefWidth(66);
        colReviseAfter.setMinWidth(56);
        colReviseAfter.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colReviseAfter.setCellFactory(c -> editableNumberCell("Revise after", true, 1.0, 365.0,
                st -> (double) st.getReviseAfterDays(),
                st -> st.getReviseAfterDays() + " d",
                this::saveReviseAfterDays, tableError));

        TableColumn<SkillSubtopic, String> colSched = new TableColumn<>("Next");
        colSched.setCellValueFactory(cell -> {
            SkillSubtopic st = cell.getValue();
            List<LocalDate> learnDates = upcomingLearn.get(st.getSubtopicId());
            List<LocalDate> reviseDates = upcomingRevise.get(st.getSubtopicId());
            if (learnDates != null && !learnDates.isEmpty()) {
                return new SimpleStringProperty(DateUtils.formatDate(learnDates.get(0)));
            } else if (reviseDates != null && !reviseDates.isEmpty()) {
                return new SimpleStringProperty(DateUtils.formatDate(reviseDates.get(0)));
            }
            return new SimpleStringProperty(st.isCovered() ? "Covered" : "-");
        });
        colSched.setPrefWidth(94);
        colSched.setMinWidth(78);

        TableColumn<SkillSubtopic, Void> colLearned = new TableColumn<>("Done");
        colLearned.setPrefWidth(56);
        colLearned.setMinWidth(50);
        colLearned.getStyleClass().add("cell-center");
        colLearned.setCellFactory(col -> new TableCell<>() {
            private final CheckBox check = new CheckBox();

            {
                check.setTooltip(new Tooltip("Mark this subtopic as learned"));
                check.setOnAction(e -> {
                    SkillSubtopic item = getTableRow() == null ? null : getTableRow().getItem();
                    if (item != null) {
                        handleToggleLearned(item, check.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(Void val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    SkillSubtopic st = getTableRow().getItem();
                    check.setSelected(st.isLearned());
                    if (replanRunner != null) {
                        check.disableProperty().unbind();
                        check.disableProperty().bind(replanRunner.busyProperty());
                    }
                    setGraphic(check);
                }
            }
        });

        TableColumn<SkillSubtopic, Void> colRevised = new TableColumn<>("Revised");
        colRevised.setPrefWidth(70);
        colRevised.setMinWidth(62);
        colRevised.getStyleClass().add("cell-center");
        colRevised.setCellFactory(col -> new TableCell<>() {
            private final CheckBox check = new CheckBox();

            {
                check.setTooltip(new Tooltip("Mark the revision cycle as complete"));
                check.setOnAction(e -> {
                    SkillSubtopic item = getTableRow() == null ? null : getTableRow().getItem();
                    if (item != null) {
                        handleToggleRevised(item, check.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(Void val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    SkillSubtopic st = getTableRow().getItem();
                    check.setSelected(st.isRevised());
                    boolean busy = replanRunner != null && replanRunner.busyProperty().get();
                    check.setDisable(!st.isLearned() || busy);
                    setGraphic(check);
                }
            }
        });

        TableColumn<SkillSubtopic, Void> colActions = new TableColumn<>("Actions");
        colActions.setPrefWidth(140);
        colActions.setMinWidth(132);
        colActions.setMaxWidth(160);
        colActions.setSortable(false);
        colActions.getStyleClass().add("cell-center");
        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnUp = UiKit.iconButton(UiKit.Icon.UP, "Move subtopic up");
            private final Button btnDown = UiKit.iconButton(UiKit.Icon.DOWN, "Move subtopic down");
            private final Button btnEdit = UiKit.iconButton(UiKit.Icon.PENCIL, "Edit subtopic");
            private final Button btnDel = UiKit.dangerIconButton(UiKit.Icon.TRASH, "Delete subtopic");
            private final HBox box = new HBox(2, btnUp, btnDown, btnEdit, btnDel);

            {
                box.setAlignment(Pos.CENTER);

                btnUp.setOnAction(e -> {
                    SkillSubtopic st = getTableRow() == null ? null : getTableRow().getItem();
                    if (st != null) {
                        handleMoveSubtopic(skill, st, -1);
                    }
                });
                btnDown.setOnAction(e -> {
                    SkillSubtopic st = getTableRow() == null ? null : getTableRow().getItem();
                    if (st != null) {
                        handleMoveSubtopic(skill, st, 1);
                    }
                });
                btnEdit.setOnAction(e -> {
                    SkillSubtopic st = getTableRow() == null ? null : getTableRow().getItem();
                    if (st != null) {
                        showEditSubtopicDialog(st);
                    }
                });
                btnDel.setOnAction(e -> {
                    SkillSubtopic st = getTableRow() == null ? null : getTableRow().getItem();
                    if (st != null) {
                        handleDeleteSubtopic(st);
                    }
                });
            }

            @Override
            protected void updateItem(Void val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    int idx = getTableRow().getIndex();
                    btnUp.setDisable(idx == 0);
                    btnDown.setDisable(idx == items.size() - 1);
                    if (replanRunner != null) {
                        btnEdit.disableProperty().unbind();
                        btnEdit.disableProperty().bind(replanRunner.busyProperty());
                        btnDel.disableProperty().unbind();
                        btnDel.disableProperty().bind(replanRunner.busyProperty());
                    }
                    setGraphic(box);
                }
            }
        });

        table.getColumns().addAll(colPos, colName, colLearn, colRevise, colReviseAfter, colSched,
                colLearned, colRevised, colActions);
        return table;
    }

    /**
     * Numeric table cell that switches to a small text field on click. Enter saves, Esc or
     * focus loss cancels, and invalid input keeps the editor open with an inline message.
     */
    private TableCell<SkillSubtopic, SkillSubtopic> editableNumberCell(
            String columnTitle, boolean wholeNumber, double min, double max,
            java.util.function.Function<SkillSubtopic, Double> read,
            java.util.function.Function<SkillSubtopic, String> display,
            java.util.function.BiConsumer<SkillSubtopic, Double> write,
            Label tableError) {
        return new TableCell<>() {
            private final TextField editor = new TextField();
            private boolean editing;

            {
                editor.getStyleClass().add("cell-editor");
                editor.setOnAction(e -> commit());
                editor.setOnKeyPressed(e -> {
                    if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        cancel();
                        e.consume();
                    }
                });
                editor.focusedProperty().addListener((obs, was, is) -> {
                    if (was && !is && editing) {
                        cancel();
                    }
                });
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 1 && getItem() != null && !editing) {
                        begin();
                    }
                });
            }

            private void begin() {
                SkillSubtopic st = getItem();
                if (st == null) {
                    return;
                }
                editing = true;
                editor.getStyleClass().remove("text-field-invalid");
                editor.setText(wholeNumber
                        ? String.format("%.0f", read.apply(st))
                        : String.format("%.2f", read.apply(st)));
                setText(null);
                setGraphic(editor);
                editor.requestFocus();
                editor.selectAll();
                showError(null);
            }

            private void cancel() {
                if (!editing) {
                    return;
                }
                editing = false;
                setGraphic(null);
                updateItem(getItem(), getItem() == null);
                showError(null);
            }

            private void commit() {
                SkillSubtopic st = getItem();
                if (st == null || !editing) {
                    return;
                }
                String raw = editor.getText().trim().replace(',', '.');
                double value;
                try {
                    value = Double.parseDouble(raw);
                } catch (NumberFormatException ex) {
                    reject("Enter a number, for example " + (wholeNumber ? "7" : "1.5"));
                    return;
                }
                if (value < min || value > max) {
                    reject(wholeNumber
                            ? "Use a whole number from 1 to 365"
                            : "Use a value from 0.25 to 100");
                    return;
                }
                editing = false;
                setGraphic(null);
                showError(null);
                write.accept(st, value);
            }

            private void reject(String message) {
                editor.getStyleClass().add("text-field-invalid");
                showError(columnTitle + ": " + message);
                editor.requestFocus();
            }

            private void showError(String message) {
                if (tableError == null) {
                    return;
                }
                boolean hide = message == null;
                tableError.setText(message);
                tableError.setVisible(!hide);
                tableError.setManaged(!hide);
            }

            @Override
            protected void updateItem(SkillSubtopic item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (!editing) {
                    setText(display.apply(item));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        };
    }

    private boolean isInputTarget(Object target) {
        if (!(target instanceof Node)) {
            return false;
        }
        Node node = (Node) target;
        while (node != null) {
            if (node instanceof TextField || node instanceof Button) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void saveLearnHours(SkillSubtopic st, double hours) {
        try {
            double previous = st.getLearnHours();
            st.setLearnHours(hours);
            if (!st.isLearned()) {
                st.setLearnRemainingHours(Math.min(hours,
                        Math.max(0.0, st.getLearnRemainingHours() + (hours - previous))));
            }
            skillSubtopicDAO.update(st);
            triggerReplan();
        } catch (SQLException e) {
            showError("The subtopic could not be saved", e.getMessage());
        }
    }

    private void saveReviseHours(SkillSubtopic st, double hours) {
        try {
            double previous = st.getReviseHours();
            st.setReviseHours(hours);
            if (!st.isRevised()) {
                st.setReviseRemainingHours(Math.min(hours,
                        Math.max(0.0, st.getReviseRemainingHours() + (hours - previous))));
            }
            skillSubtopicDAO.update(st);
            triggerReplan();
        } catch (SQLException e) {
            showError("The subtopic could not be saved", e.getMessage());
        }
    }

    private void saveReviseAfterDays(SkillSubtopic st, double days) {
        try {
            int value = (int) Math.round(days);
            st.setReviseAfterDays(value);
            if (st.isLearned() && st.getLearnedOn() != null) {
                st.setRevisionDueDate(st.getLearnedOn().plusDays(value));
            }
            skillSubtopicDAO.update(st);
            triggerReplan();
        } catch (SQLException e) {
            showError("The subtopic could not be saved", e.getMessage());
        }
    }


    /** Label-above field used by the small skill dialogs. */
    private VBox formField(String label, javafx.scene.Node control) {
        Label caption = smallCaption(label);
        if (control instanceof javafx.scene.layout.Region) {
            ((javafx.scene.layout.Region) control).setMaxWidth(Double.MAX_VALUE);
        }
        VBox box = new VBox(4, caption, control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return box;
    }

    /** Gives the dialog's OK button the name of the action it performs. */
    private void actionLabel(Dialog<?> dialog, String text) {
        javafx.scene.Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (ok instanceof Button) {
            ((Button) ok).setText(text);
        }
    }

    private void handleAddSkill() {
        String name = newSkillNameField.getText().trim();
        if (name.isEmpty()) {
            showError("Validation Error", "Please provide a skill name.");
            return;
        }
        String category = newSkillCategoryField.getText().trim();
        if (category.isEmpty()) {
            category = "General";
        }

        try {
            Skill skill = new Skill(null, name, category, 3, null);
            skill.setPriority(newSkillPriority.getValue() == null ? 3 : newSkillPriority.getValue());
            skill.setTargetDate(newSkillTarget.getValue());
            skillDAO.insert(skill);
            newSkillNameField.clear();
            newSkillCategoryField.setText("General");
            newSkillPriority.setValue(3);
            newSkillTarget.setValue(null);
            UiKit.toast("Skill added: " + name);
            triggerReplan();
        } catch (SQLException e) {
            showError("Database Error", "Failed to add skill: " + e.getMessage());
        }
    }

    private void showEditSkillDialog(Skill skill) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit skill");
        dialog.setHeaderText("Update the skill name, category, priority and goal");
        UiKit.styleDialog(dialog);

        TextField nameField = new TextField(skill.getName());
        TextField catField = new TextField(skill.getCategory());
        ComboBox<Integer> priorityField = UiKit.levelPicker(skill.getPriority(), Skill::priorityLabel, null);
        priorityField.setPrefHeight(34);
        DatePicker targetField = new DatePicker(skill.getTargetDate());
        targetField.setPromptText("No target");
        Button clearTarget = UiKit.ghostButton("Clear");
        clearTarget.setOnAction(e -> targetField.setValue(null));
        HBox targetRow = new HBox(8, targetField, clearTarget);
        targetRow.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(16, formField("Skill name", nameField), formField("Category", catField),
                formField("Priority", priorityField), formField("Target date", targetRow));
        form.setPrefWidth(360);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        actionLabel(dialog, "Save changes");
        nameField.setOnAction(e -> fireOk(dialog));
        catField.setOnAction(e -> fireOk(dialog));

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            String newName = nameField.getText().trim();
            if (newName.isEmpty()) {
                showError("Validation Error", "Skill name cannot be empty.");
                return;
            }
            try {
                skill.setName(newName);
                skill.setCategory(catField.getText().trim());
                skill.setPriority(priorityField.getValue() == null ? skill.getPriority() : priorityField.getValue());
                skill.setTargetDate(targetField.getValue());
                skillDAO.update(skill);
                triggerReplan();
            } catch (SQLException e) {
                showError("Database Error", e.getMessage());
            }
        }
    }

    private void handleDeleteSkill(Skill skill) {
        boolean confirmed = UiKit.confirm(ownerWindow(), "Delete Skill",
                "Delete skill '" + skill.getName() + "'?",
                "The skill and all of its subtopics are removed. Practice history for this skill is deleted as well.",
                "Delete Skill");
        if (confirmed) {
            try {
                skillDAO.delete(skill.getSkillId());
                UiKit.toast("Skill deleted: " + skill.getName());
                triggerReplan();
            } catch (SQLException e) {
                showError("Delete Error", e.getMessage());
            }
        }
    }

    private void showAddSubtopicDialog(Skill skill) {
        Dialog<SkillSubtopic> dialog = new Dialog<>();
        dialog.setTitle("Add subtopic");
        dialog.setHeaderText("Add a subtopic to " + skill.getName());
        UiKit.styleDialog(dialog);

        TextField nameField = new TextField();
        nameField.setPromptText("For example: balanced trees");

        Spinner<Double> learnSpinner = new Spinner<>(0.25, 100.0, 1.0, 0.25);
        learnSpinner.setEditable(true);

        Spinner<Double> reviseSpinner = new Spinner<>(0.25, 100.0, 0.5, 0.25);
        reviseSpinner.setEditable(true);

        Spinner<Integer> daysSpinner = new Spinner<>(1, 365, 7, 1);
        daysSpinner.setEditable(true);

        HBox hoursRow = new HBox(16, formField("Learn hours", learnSpinner),
                formField("Revise hours", reviseSpinner), formField("Revise after (days)", daysSpinner));
        VBox form = new VBox(16, formField("Subtopic name", nameField), hoursRow);
        form.setPrefWidth(420);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        actionLabel(dialog, "Add subtopic");
        nameField.setOnAction(e -> fireOk(dialog));

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                String name = nameField.getText().trim();
                if (name.isEmpty()) {
                    showError("Validation Error", "Subtopic name cannot be empty.");
                    return null;
                }
                double lHrs = learnSpinner.getValue();
                double rHrs = reviseSpinner.getValue();
                int days = daysSpinner.getValue();
                if (lHrs <= 0 || rHrs <= 0 || days < 1) {
                    showError("Validation Error", "Hours must be > 0 and days >= 1.");
                    return null;
                }
                try {
                    List<SkillSubtopic> existing = skillSubtopicDAO.findBySkill(skill.getSkillId());
                    int nextPos = existing.size() + 1;
                    return new SkillSubtopic(
                            null, skill.getSkillId(), name, nextPos,
                            lHrs, lHrs, rHrs, rHrs, days,
                            false, null, false, null, null
                    );
                } catch (SQLException e) {
                    showError("Error", e.getMessage());
                    return null;
                }
            }
            return null;
        });

        Optional<SkillSubtopic> res = dialog.showAndWait();
        res.ifPresent(sub -> {
            try {
                skillSubtopicDAO.insert(sub);
                UiKit.toast("Subtopic added: " + sub.getName());
                triggerReplan();
            } catch (SQLException e) {
                showError("The subtopic could not be saved", e.getMessage());
            }
        });
    }

    private void showEditSubtopicDialog(SkillSubtopic st) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit subtopic");
        dialog.setHeaderText("Edit " + st.getName());
        UiKit.styleDialog(dialog);

        TextField nameField = new TextField(st.getName());

        Spinner<Double> learnSpinner = new Spinner<>(0.25, 100.0, st.getLearnHours(), 0.25);
        learnSpinner.setEditable(true);

        Spinner<Double> reviseSpinner = new Spinner<>(0.25, 100.0, st.getReviseHours(), 0.25);
        reviseSpinner.setEditable(true);

        Spinner<Integer> daysSpinner = new Spinner<>(1, 365, st.getReviseAfterDays(), 1);
        daysSpinner.setEditable(true);

        HBox hoursRow = new HBox(16, formField("Learn hours", learnSpinner),
                formField("Revise hours", reviseSpinner), formField("Revise after (days)", daysSpinner));
        VBox form = new VBox(16, formField("Subtopic name", nameField), hoursRow);
        form.setPrefWidth(420);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        actionLabel(dialog, "Save changes");
        nameField.setOnAction(e -> fireOk(dialog));

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                showError("Validation Error", "Subtopic name cannot be empty.");
                return;
            }
            double lHrs = learnSpinner.getValue();
            double rHrs = reviseSpinner.getValue();
            int days = daysSpinner.getValue();
            if (lHrs <= 0 || rHrs <= 0 || days < 1) {
                showError("Validation Error", "Hours must be > 0 and days >= 1.");
                return;
            }
            try {
                st.setName(name);
                // Adjust remaining proportionally or cap
                double oldLHrs = st.getLearnHours();
                st.setLearnHours(lHrs);
                if (!st.isLearned()) {
                    st.setLearnRemainingHours(Math.min(lHrs, st.getLearnRemainingHours() + (lHrs - oldLHrs)));
                }
                double oldRHrs = st.getReviseHours();
                st.setReviseHours(rHrs);
                if (!st.isRevised()) {
                    st.setReviseRemainingHours(Math.min(rHrs, st.getReviseRemainingHours() + (rHrs - oldRHrs)));
                }
                st.setReviseAfterDays(days);
                if (st.isLearned() && st.getLearnedOn() != null) {
                    st.setRevisionDueDate(st.getLearnedOn().plusDays(days));
                }
                skillSubtopicDAO.update(st);
                triggerReplan();
            } catch (SQLException e) {
                showError("Database Error", e.getMessage());
            }
        }
    }

    private void handleDeleteSubtopic(SkillSubtopic st) {
        boolean confirmed = UiKit.confirm(ownerWindow(), "Delete Subtopic",
                "Delete subtopic '" + st.getName() + "'?",
                "This subtopic is removed from the skill. This cannot be undone.",
                "Delete Subtopic");
        if (confirmed) {
            try {
                skillSubtopicDAO.delete(st.getSubtopicId());
                // Reorder remaining subtopics
                List<SkillSubtopic> subs = skillSubtopicDAO.findBySkill(st.getSkillId());
                for (int i = 0; i < subs.size(); i++) {
                    SkillSubtopic sub = subs.get(i);
                    sub.setPosition(i + 1);
                    skillSubtopicDAO.update(sub);
                }
                triggerReplan();
            } catch (SQLException e) {
                showError("Delete Error", e.getMessage());
            }
        }
    }

    private void handleMoveSubtopic(Skill skill, SkillSubtopic st, int delta) {
        try {
            List<SkillSubtopic> subs = skillSubtopicDAO.findBySkill(skill.getSkillId());
            int idx = -1;
            for (int i = 0; i < subs.size(); i++) {
                if (subs.get(i).getSubtopicId().equals(st.getSubtopicId())) {
                    idx = i;
                    break;
                }
            }
            int targetIdx = idx + delta;
            if (targetIdx >= 0 && targetIdx < subs.size()) {
                SkillSubtopic other = subs.get(targetIdx);
                int p1 = st.getPosition();
                int p2 = other.getPosition();
                st.setPosition(p2);
                other.setPosition(p1);
                skillSubtopicDAO.update(st);
                skillSubtopicDAO.update(other);
                triggerReplan();
            }
        } catch (SQLException e) {
            showError("Reorder Error", e.getMessage());
        }
    }

    private void handleToggleLearned(SkillSubtopic st, boolean isLearned) {
        try {
            if (isLearned) {
                skillSubtopicDAO.markLearned(st.getSubtopicId(), LocalDate.now(), st.getReviseAfterDays());
                dailyLogDAO.markSubtopicLogsDone(st.getSubtopicId(), "SKILL_LEARN");
            } else {
                skillSubtopicDAO.unmarkLearned(st.getSubtopicId());
            }
            triggerReplan();
        } catch (SQLException e) {
            showError("Database Error", e.getMessage());
        }
    }

    private void handleToggleRevised(SkillSubtopic st, boolean isRevised) {
        try {
            if (isRevised) {
                skillSubtopicDAO.markRevised(st.getSubtopicId(), LocalDate.now());
                dailyLogDAO.markSubtopicLogsDone(st.getSubtopicId(), "SKILL_REVISE");
            } else {
                skillSubtopicDAO.unmarkRevised(st.getSubtopicId());
            }
            triggerReplan();
        } catch (SQLException e) {
            showError("Database Error", e.getMessage());
        }
    }

    private void triggerReplan() {
        Runnable after = () -> {
            loadSkills();
            if (onDataChanged != null) {
                onDataChanged.run();
            }
        };

        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null) {
                if (replanRunner != null) {
                    replanRunner.run(active.getSemesterId(), LocalDate.now(), after, null);
                } else if (replanService != null) {
                    replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
                    after.run();
                } else {
                    after.run();
                }
            } else {
                after.run();
            }
        } catch (SQLException ex) {
            showError("Replan Error", ex.getMessage());
        }
    }

    private Window ownerWindow() {
        return skillCardsContainer.getScene() != null ? skillCardsContainer.getScene().getWindow() : null;
    }

    private void showError(String title, String msg) {
        UiKit.error(ownerWindow(), title, msg);
    }

    /** Enter inside a dialog's text field submits its OK action. */
    private void fireOk(Dialog<?> dialog) {
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (ok instanceof Button) {
            ((Button) ok).fire();
        }
    }
}
