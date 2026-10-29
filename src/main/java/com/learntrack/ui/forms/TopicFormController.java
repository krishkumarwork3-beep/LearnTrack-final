package com.learntrack.ui.forms;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.service.ExamScopeService;
import com.learntrack.service.ParsedUnit;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.service.SubjectListService;
import com.learntrack.service.SyllabusDocxReader;
import com.learntrack.service.SyllabusParser;
import com.learntrack.ui.ReplanRunner;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DurationFormat;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Controller/View for managing subjects, syllabus, and topics under exams.
 * Master-detail: a searchable subject list on the left, one subject's detail on the right.
 */
public class TopicFormController {

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    // Subtopic table columns, shared by the header row and the rows themselves.
    private static final double STUDIED_COL = 50;
    private static final double STATUS_COL = 116;
    private static final double HOURS_COL = 62;
    private static final double ACTIONS_COL = 138;
    private static final double ROW_GAP = 8;
    private static final double IMPORTANCE_COL = 106;

    private final ExamDAO examDAO;
    private final SemesterDAO semesterDAO;
    private final TimetableDAO timetableDAO;
    private final SemesterReplanService replanService;
    private final Runnable onDataChanged;
    private final ReplanRunner replanRunner;

    private final SyllabusDAO syllabusDAO;
    private final SubjectListService subjectListService;
    private final SubjectCreditDAO subjectCreditDAO;
    private final ExamScopeService examScopeService;

    private VBox subjectListBox;
    private VBox detailHost;
    private VBox syllabusUnitsContainer;
    private TextField searchField;

    private List<SubjectInfo> allSubjects = new ArrayList<>();
    private final Map<String, int[]> progressCache = new HashMap<>();
    private String selectedSubjectCode;
    private int activeSemesterId = -1;

    // Debouncing for replan after spinner changes
    private boolean isPopulatingUI = false;
    private java.util.Timer debounceTimer = null;
    private final Object debounceTimerLock = new Object();

    public TopicFormController(ExamDAO examDAO, SemesterDAO semesterDAO,
                               SemesterReplanService replanService, Runnable onDataChanged) {
        this(examDAO, semesterDAO, replanService, onDataChanged, null);
    }

    public TopicFormController(ExamDAO examDAO, SemesterDAO semesterDAO,
                               SemesterReplanService replanService, Runnable onDataChanged,
                               ReplanRunner replanRunner) {
        this.examDAO = examDAO;
        this.semesterDAO = semesterDAO;
        this.replanService = replanService;
        this.onDataChanged = onDataChanged;
        this.replanRunner = replanRunner;

        this.timetableDAO = new TimetableDAO();
        this.syllabusDAO = new SyllabusDAO();
        this.subjectCreditDAO = new SubjectCreditDAO();
        this.examScopeService = new ExamScopeService();
        this.subjectListService = new SubjectListService(timetableDAO, examDAO);
    }

    // ------------------------------------------------------------------ layout

    public Node getView() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("page");

        HBox header = UiKit.pageHeader("Subjects",
                "Choose a subject to review its syllabus. Hours edited here are kept when the plan is rebuilt.",
                null);

        searchField = new TextField();
        searchField.setPromptText("Search by code or name…");
        searchField.textProperty().addListener((obs, oldV, newV) -> rebuildSubjectList());

        Button btnAddSubject = UiKit.secondaryButton("Add");
        btnAddSubject.setTooltip(new Tooltip(
                "Add a subject that is not in your timetable but you still study or sit exams for."));
        btnAddSubject.setOnAction(e -> handleAddSubject());

        HBox searchRow = new HBox(8, searchField, btnAddSubject);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        subjectListBox = new VBox(8);
        ScrollPane listScroll = new ScrollPane(subjectListBox);
        listScroll.setFitToWidth(true);
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        VBox leftPanel = new VBox(8, searchRow, listScroll);
        leftPanel.setMinWidth(260);
        leftPanel.setPrefWidth(300);
        leftPanel.setMaxWidth(340);

        detailHost = new VBox(12);
        detailHost.setMinWidth(520);

        SplitPane split = new SplitPane(leftPanel, detailHost);
        split.setDividerPositions(0.27);
        SplitPane.setResizableWithParent(leftPanel, Boolean.FALSE);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.getChildren().addAll(header, split);

        loadExams();
        return root;
    }

    public void loadExams() {
        if (subjectListBox == null) {
            return;
        }
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null || active.getSemesterId() == null) {
                activeSemesterId = -1;
                allSubjects = new ArrayList<>();
                subjectListBox.getChildren().setAll(
                        UiKit.emptyState("No active semester", "Create or activate a semester first."));
                detailHost.getChildren().setAll(
                        UiKit.emptyState("Nothing to show", "There is no active semester."));
                return;
            }

            activeSemesterId = active.getSemesterId();
            allSubjects = subjectListService.buildSubjectList(activeSemesterId);

            progressCache.clear();
            for (SubjectInfo subject : allSubjects) {
                progressCache.put(subject.getSubjectCode(), computeProgress(subject.getSubjectCode()));
            }

            rebuildSubjectList();

            if (allSubjects.isEmpty()) {
                detailHost.getChildren().setAll(UiKit.emptyState("No subjects yet",
                        "Add classes to your timetable or create exams in the Exams tab to see subjects here."));
                return;
            }

            SubjectInfo selected = findSubject(selectedSubjectCode);
            if (selected == null) {
                selected = allSubjects.get(0);
            }
            selectedSubjectCode = selected.getSubjectCode();
            rebuildSubjectList();
            showSubjectDetail(selected);

        } catch (SQLException e) {
            showError("Subjects could not be loaded", e.getMessage());
        }
    }

    /**
     * Keeps a subject that has no timetable entry: it is stored as a kept subject, so it joins the
     * subject list like any other and can carry syllabus, study sessions and exams.
     */
    private void handleAddSubject() {
        if (activeSemesterId <= 0) {
            showError("No active semester", "Create or activate a semester first.");
            return;
        }

        Dialog<String[]> dialog = new Dialog<>();
        dialog.setTitle("Add Subject");
        dialog.setHeaderText("Add a subject kept for study and exams");
        UiKit.styleDialog(dialog);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        TextField codeField = new TextField();
        codeField.setPromptText("e.g. UEX101");
        codeField.setPrefWidth(240);
        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Engineering Graphics");
        nameField.setPrefWidth(240);

        Label codeLabel = new Label("Course code");
        codeLabel.getStyleClass().add("subtle");
        Label nameLabel = new Label("Subject name");
        nameLabel.getStyleClass().add("subtle");

        grid.addRow(0, codeLabel, codeField);
        grid.addRow(1, nameLabel, nameField);
        grid.addRow(2, new Label(), UiKit.hint(
                "This subject has no entry in your timetable. It is kept for study and exams only, "
                        + "like a subject you removed from the timetable. Leave the name blank to use the code."));

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okButton != null) {
            okButton.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                    () -> codeField.getText() == null || codeField.getText().trim().isEmpty(), codeField.textProperty()));
        }
        codeField.setOnAction(e -> {
            Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
            if (ok instanceof Button) {
                ((Button) ok).fire();
            }
        });

        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK
                ? new String[]{codeField.getText(), nameField.getText()}
                : null);

        dialog.showAndWait().ifPresent(data -> {
            String code = data[0] == null ? "" : data[0].trim().toUpperCase(Locale.ENGLISH);
            if (code.isEmpty()) {
                return;
            }
            if (findSubject(code) != null) {
                UiKit.toast(code + " is already in your subject list.", UiKit.Kind.WARN);
                return;
            }
            String name = data[1] != null && !data[1].trim().isEmpty() ? data[1].trim() : code;
            try {
                KeptSubjectDAO.upsert(activeSemesterId, code, name);
                selectedSubjectCode = code;
                loadExams();
                UiKit.toast(code + " added: kept for study and exams, not in your timetable.");
            } catch (SQLException e) {
                showError("The subject could not be added", e.getMessage());
            }
        });
    }

    private SubjectInfo findSubject(String code) {
        if (code == null) {
            return null;
        }
        for (SubjectInfo subject : allSubjects) {
            if (code.equalsIgnoreCase(subject.getSubjectCode())) {
                return subject;
            }
        }
        return null;
    }

    private int[] computeProgress(String subjectCode) {
        int total = 0;
        int done = 0;
        try {
            for (SyllabusUnit unit : syllabusDAO.findUnitsBySubject(activeSemesterId, subjectCode)) {
                for (SyllabusSubtopic subtopic : syllabusDAO.findSubtopicsByUnit(unit.getUnitId())) {
                    total++;
                    if (subtopic.isDone()) {
                        done++;
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        return new int[]{done, total};
    }

    private void rebuildSubjectList() {
        if (subjectListBox == null) {
            return;
        }
        subjectListBox.getChildren().clear();

        if (allSubjects.isEmpty()) {
            return;
        }

        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ENGLISH);
        int shown = 0;
        for (SubjectInfo subject : allSubjects) {
            String name = subject.getDisplayName() == null ? "" : subject.getDisplayName();
            if (!query.isEmpty()
                    && !subject.getSubjectCode().toLowerCase(Locale.ENGLISH).contains(query)
                    && !name.toLowerCase(Locale.ENGLISH).contains(query)) {
                continue;
            }
            subjectListBox.getChildren().add(createSubjectListItem(subject));
            shown++;
        }

        if (shown == 0) {
            subjectListBox.getChildren().add(UiKit.hint("No subject matches “" + searchField.getText().trim() + "”."));
        }
    }

    private Node createSubjectListItem(SubjectInfo subject) {
        int[] progress = progressCache.getOrDefault(subject.getSubjectCode(), new int[]{0, 0});

        Label code = new Label(subject.getSubjectCode());
        code.getStyleClass().add("subject-code");
        HBox top = new HBox(8, code, UiKit.spacer(), statusChip(progress));
        top.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(displayName(subject));
        name.getStyleClass().add("subject-name");
        name.setWrapText(true);

        ProgressBar bar = new ProgressBar(progress[1] > 0 ? (double) progress[0] / progress[1] : 0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(6);
        if (progress[1] > 0 && progress[0] == progress[1]) {
            bar.getStyleClass().add("progress-bar-success");
        }

        Label progressLabel = new Label(progress[1] > 0
                ? progress[0] + " of " + progress[1] + " subtopics studied"
                : "No syllabus yet");
        progressLabel.getStyleClass().add("hint");

        VBox item = new VBox(4, top, name, bar, progressLabel);
        item.getStyleClass().add("subject-item");
        if (subject.getSubjectCode().equalsIgnoreCase(selectedSubjectCode)) {
            // Same highlight as the selected category card on the Exams tab.
            item.getStyleClass().addAll("subject-item-selected", "row-selected", "row-picked");
        }
        item.setOnMouseClicked(e -> selectSubject(subject));
        // Keyboard users can reach the list with Tab and pick a subject with Enter or Space.
        item.setFocusTraversable(true);
        item.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                selectSubject(subject);
                e.consume();
            }
        });
        return item;
    }

    private void selectSubject(SubjectInfo subject) {
        selectedSubjectCode = subject.getSubjectCode();
        rebuildSubjectList();
        showSubjectDetail(subject);
    }

    private Node statusChip(int[] progress) {
        if (progress[1] == 0) {
            return UiKit.status(UiKit.Kind.WARN, "No syllabus");
        }
        if (progress[0] == progress[1]) {
            return UiKit.status(UiKit.Kind.SUCCESS, "Done");
        }
        return UiKit.status(UiKit.Kind.INFO, "In progress");
    }

    private String displayName(SubjectInfo subject) {
        String name = subject.getDisplayName();
        if (name == null || name.isBlank() || name.equalsIgnoreCase(subject.getSubjectCode())) {
            return "No display name";
        }
        return name;
    }

    // ------------------------------------------------------------------ detail

    private void showSubjectDetail(SubjectInfo subject) {
        detailHost.getChildren().clear();

        List<Exam> exams = findExams(subject);
        int[] progress = computeProgress(subject.getSubjectCode());
        progressCache.put(subject.getSubjectCode(), progress);

        Label title = new Label(displayName(subject));
        title.getStyleClass().add("page-title");
        Label code = new Label(subject.getSubjectCode());
        code.getStyleClass().add("subject-code");
        HBox titleRow = new HBox(10, title, code);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // Credits editor
        double initialCredits = subject.getCredits() > 0 ? subject.getCredits() : 3.0;
        Spinner<Double> creditsSpinner = new Spinner<>(0.5, 50.0, initialCredits, 0.5);
        creditsSpinner.setEditable(true);
        creditsSpinner.setPrefWidth(100);
        creditsSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal > 0 && Math.abs(newVal - subject.getCredits()) > 0.001) {
                try {
                    subjectCreditDAO.setCredits(activeSemesterId, subject.getSubjectCode(), newVal);
                    subject.setCredits(newVal);
                    if (replanRunner != null) {
                        replanRunner.runReplan(activeSemesterId);
                    } else if (replanService != null) {
                        replanService.replanFromToday(activeSemesterId);
                    }
                    if (onDataChanged != null) {
                        onDataChanged.run();
                    }
                } catch (SQLException ex) {
                    showError("Credits could not be updated", ex.getMessage());
                }
            }
        });
        VBox creditsBox = new VBox(2, smallCaption("Credits"), creditsSpinner);

        // Overall progress
        ProgressBar progressBar = new ProgressBar(progress[1] > 0 ? (double) progress[0] / progress[1] : 0);
        progressBar.setPrefWidth(180);
        progressBar.setPrefHeight(10);
        if (progress[1] > 0 && progress[0] == progress[1]) {
            progressBar.getStyleClass().add("progress-bar-success");
        }
        Label progressLabel = new Label(progress[1] > 0
                ? progress[0] + " of " + progress[1] + " subtopics studied"
                : "No syllabus yet");
        progressLabel.getStyleClass().add("subtle");
        VBox progressBox = new VBox(3, smallCaption("Overall progress"), progressBar, progressLabel);

        // Exam dates are status as well: the dot says dated or tentative, the words say which.
        HBox examChips = new HBox(12);
        examChips.setAlignment(Pos.CENTER_LEFT);
        if (exams.isEmpty()) {
            examChips.getChildren().add(UiKit.status(UiKit.Kind.WARN, "No exam yet"));
        } else {
            for (Exam exam : exams) {
                examChips.getChildren().add(examChip(exam));
            }
        }
        VBox examsBox = new VBox(3, smallCaption("Exams"), examChips);

        HBox metaRow = new HBox(28, creditsBox, progressBox, examsBox);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        // The syllabus toolbar owns the actions, so the header card stays informational.
        VBox headerCard = UiKit.card(titleRow, metaRow);

        Node syllabus = buildSyllabusTab(subject);
        VBox.setVgrow(syllabus, Priority.ALWAYS);

        detailHost.getChildren().addAll(headerCard, syllabus);
    }

    private Label smallCaption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("stat-label");
        return label;
    }

    private Node examChip(Exam exam) {
        boolean dated = exam.getExamDate() != null && !exam.isExamDateTentative();
        String when = dated ? exam.getExamDate().format(SHORT_DATE)
                : (exam.getExamDate() != null ? "tentative" : "date TBD");
        return UiKit.status(dated ? UiKit.Kind.SUCCESS : UiKit.Kind.WARN,
                exam.getExamType() + " · " + when);
    }

    private List<Exam> findExams(SubjectInfo subject) {
        List<Exam> result = new ArrayList<>();
        try {
            for (Exam exam : examDAO.findBySemester(activeSemesterId)) {
                boolean matches;
                if (exam.getSubjectCode() != null && !exam.getSubjectCode().trim().isEmpty()) {
                    matches = exam.getSubjectCode().equalsIgnoreCase(subject.getSubjectCode());
                } else {
                    matches = exam.getSubject().equalsIgnoreCase(subject.getSubjectCode());
                }
                if (matches) {
                    result.add(exam);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    // ------------------------------------------------------------------ syllabus

    private Node buildSyllabusTab(SubjectInfo subject) {
        VBox content = new VBox(10);
        content.setPadding(new Insets(12));

        Button btnAddUnit = UiKit.primaryButton("+ Add Unit");
        btnAddUnit.setOnAction(e -> handleAddUnit());
        Button btnPaste = UiKit.secondaryButton("Paste Syllabus");
        btnPaste.setOnAction(e -> handlePasteSyllabus(subject));
        Button btnClear = UiKit.dangerTextButton("Clear Syllabus");
        btnClear.setOnAction(e -> handleClearSyllabus());
        bindBusy(btnAddUnit, btnPaste, btnClear);
        HBox toolbar = new HBox(10, btnAddUnit, btnPaste, UiKit.spacer(), btnClear);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        syllabusUnitsContainer = new VBox(10);
        ScrollPane scroll = new ScrollPane(syllabusUnitsContainer);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        content.getChildren().addAll(toolbar, scroll);
        refreshSyllabusDisplay();
        return content;
    }

    private Node emptySyllabusState() {
        SubjectInfo subject = findSubject(selectedSubjectCode);
        VBox empty = UiKit.emptyState("No syllabus yet",
                "Add units and subtopics one by one, or paste the whole syllabus and review it before importing.");
        // The toolbar above already carries the primary action.
        Button btnAddUnit = UiKit.secondaryButton("+ Add Unit");
        btnAddUnit.setOnAction(e -> handleAddUnit());
        Button btnPaste = UiKit.secondaryButton("Paste Syllabus");
        if (subject != null) {
            btnPaste.setOnAction(e -> handlePasteSyllabus(subject));
        }
        bindBusy(btnAddUnit, btnPaste);
        HBox actions = new HBox(10, btnAddUnit, btnPaste);
        actions.setAlignment(Pos.CENTER);
        empty.getChildren().add(actions);
        return empty;
    }

    private void refreshSyllabusDisplay() {
        if (syllabusUnitsContainer == null) {
            return;
        }
        isPopulatingUI = true;
        syllabusUnitsContainer.getChildren().clear();

        try {
            String subjectCode = selectedSubjectCode;
            if (subjectCode == null || activeSemesterId < 0) {
                return;
            }

            List<SyllabusUnit> units = syllabusDAO.findUnitsBySubject(activeSemesterId, subjectCode);
            if (units.isEmpty()) {
                syllabusUnitsContainer.getChildren().add(emptySyllabusState());
                return;
            }

            for (int i = 0; i < units.size(); i++) {
                syllabusUnitsContainer.getChildren().add(createUnitDisplay(units.get(i), i, units.size()));
            }

            progressCache.put(subjectCode, computeProgress(subjectCode));
            rebuildSubjectList();

        } catch (SQLException e) {
            showError("The syllabus could not be loaded", e.getMessage());
        } finally {
            isPopulatingUI = false;
        }
    }

    private Node createUnitDisplay(SyllabusUnit unit, int index, int total) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card-flat");

        List<SyllabusSubtopic> subtopics;
        try {
            subtopics = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
        } catch (SQLException e) {
            subtopics = List.of();
        }
        long doneCount = subtopics.stream().filter(SyllabusSubtopic::isDone).count();
        int totalSubs = subtopics.size();

        Label unitName = new Label(unit.getName());
        unitName.getStyleClass().addAll("section-title", "unit-title");
        unitName.setMinWidth(120);
        HBox.setHgrow(unitName, Priority.ALWAYS);
        unitName.setMaxWidth(Double.MAX_VALUE);

        Button btnCollapse = UiKit.iconButton(UiKit.Icon.CHEVRON_DOWN, "Collapse or expand this unit");

        Button btnHours = UiKit.iconButton(UiKit.Icon.CLOCK, "Set learn and revise hours for every subtopic in this unit");
        btnHours.setOnAction(e -> handleSetHoursForUnit(unit));
        Button btnRename = UiKit.iconButton(UiKit.Icon.PENCIL, "Rename unit");
        btnRename.setOnAction(e -> handleRenameUnit(unit));
        Button btnDelete = UiKit.dangerIconButton(UiKit.Icon.TRASH, "Delete unit");
        btnDelete.setOnAction(e -> handleDeleteUnit(unit));
        Button btnUp = UiKit.iconButton(UiKit.Icon.UP, "Move unit up");
        btnUp.setDisable(index == 0);
        btnUp.setOnAction(e -> handleMoveUnit(unit, -1));
        Button btnDown = UiKit.iconButton(UiKit.Icon.DOWN, "Move unit down");
        btnDown.setDisable(index == total - 1);
        btnDown.setOnAction(e -> handleMoveUnit(unit, 1));

        HBox titleRow = new HBox(8, btnCollapse, unitName, unitImportanceMenu(unit), btnHours, btnRename, btnDelete, btnUp, btnDown);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        ProgressBar progressBar = new ProgressBar(totalSubs > 0 ? (double) doneCount / totalSubs : 0);
        progressBar.setPrefWidth(120);
        progressBar.setPrefHeight(6);
        if (totalSubs > 0 && doneCount == totalSubs) {
            progressBar.getStyleClass().add("progress-bar-success");
        }
        Label progress = new Label(doneCount + " of " + totalSubs + " topics studied");
        progress.getStyleClass().add("hint");
        HBox progressRow = new HBox(8, progressBar, progress);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        // Column headers (text, no emoji). The widths match createSubtopicRow exactly so the
        // header and the values line up at every window width.
        Label hStudied = fixedColumn(columnLabel("Studied"), STUDIED_COL);
        Label hTopic = columnLabel("Topic");
        // Labels default to maxWidth = prefWidth, so without this the header's flexible column
        // cannot grow and the headers drift away from the values.
        hTopic.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hTopic, Priority.ALWAYS);
        Label hImportance = fixedColumn(columnLabel("Importance"), IMPORTANCE_COL);
        Label hStatus = fixedColumn(columnLabel("Status"), STATUS_COL);
        Label hLearn = fixedColumn(columnLabel("Learn"), HOURS_COL);
        Label hRevise = fixedColumn(columnLabel("Revise"), HOURS_COL);
        Label hActions = fixedColumn(columnLabel("Actions"), ACTIONS_COL);
        HBox colHeaders = new HBox(ROW_GAP, hStudied, hTopic, hImportance, hStatus, hLearn, hRevise, hActions);
        colHeaders.setAlignment(Pos.CENTER_LEFT);

        VBox subtopicsBox = new VBox(4);
        for (int i = 0; i < subtopics.size(); i++) {
            subtopicsBox.getChildren().add(createSubtopicRow(subtopics.get(i), i, subtopics.size()));
        }

        // Add subtopic controls
        Button btnAddSubtopic = UiKit.ghostButton("+ Subtopic");
        btnAddSubtopic.setOnAction(e -> handleAddSubtopic(unit));
        TextField bulkField = new TextField();
        bulkField.setPromptText("Bulk add: topic1, topic2, …");
        bulkField.setPrefWidth(300);
        Button btnBulkAdd = UiKit.secondaryButton("Add");
        btnBulkAdd.setOnAction(e -> {
            String text = bulkField.getText().trim();
            if (!text.isEmpty()) {
                handleBulkAddSubtopics(unit, text);
                bulkField.clear();
            }
        });
        HBox addRow = new HBox(8, btnAddSubtopic, bulkField, btnBulkAdd);
        addRow.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(6, colHeaders, subtopicsBox, addRow);
        btnCollapse.setOnAction(e -> {
            boolean show = !body.isVisible();
            body.setVisible(show);
            body.setManaged(show);
            btnCollapse.setGraphic(UiKit.icon(show ? UiKit.Icon.CHEVRON_DOWN : UiKit.Icon.CHEVRON_RIGHT));
        });

        card.getChildren().addAll(titleRow, progressRow, body);
        return card;
    }

    private Label columnLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("column-label");
        return label;
    }

    /** Pins a column to one width; used by both the header row and every subtopic row. */
    private <T extends javafx.scene.layout.Region> T fixedColumn(T node, double width) {
        node.setMinWidth(width);
        node.setPrefWidth(width);
        node.setMaxWidth(width);
        return node;
    }

    private Node createSubtopicRow(SyllabusSubtopic subtopic, int index, int total) {
        HBox row = new HBox(ROW_GAP);
        row.getStyleClass().add("subtopic-row");
        row.setAlignment(Pos.CENTER_LEFT);

        CheckBox studiedBox = new CheckBox();
        studiedBox.setSelected(subtopic.isDone());
        fixedColumn(studiedBox, STUDIED_COL);
        studiedBox.setTooltip(new Tooltip("Tick if you have already learned this topic\n"
                + "(e.g. in class, MST or an assignment).\nIt will only be scheduled for revision."));
        studiedBox.setOnAction(e -> {
            try {
                syllabusDAO.updateSubtopicDone(subtopic.getSubtopicId(), studiedBox.isSelected());
                examScopeService.syncTopicPhasesForSubject(activeSemesterId, selectedSubjectCode);
                if (replanRunner != null) {
                    replanRunner.runReplan(activeSemesterId);
                } else if (replanService != null) {
                    replanService.replanFromToday(activeSemesterId);
                }
                refreshSyllabusDisplay();
                if (onDataChanged != null) {
                    onDataChanged.run();
                }
            } catch (SQLException ex) {
                showError("The topic could not be updated", ex.getMessage());
            }
        });

        Label topicLabel = new Label(subtopic.getName());
        topicLabel.getStyleClass().add("topic-name");
        topicLabel.setWrapText(true);
        topicLabel.setMaxWidth(Double.MAX_VALUE);
        topicLabel.setMinWidth(100);
        HBox.setHgrow(topicLabel, Priority.ALWAYS);

        Node status = statusBadge(subtopic);

        Node learnCell = editableHours(subtopic, true);
        Node reviseCell = editableHours(subtopic, false);

        Button btnUp = UiKit.iconButton(UiKit.Icon.UP, "Move up");
        btnUp.setDisable(index == 0);
        btnUp.setOnAction(e -> handleMoveSubtopic(subtopic, -1));
        Button btnDown = UiKit.iconButton(UiKit.Icon.DOWN, "Move down");
        btnDown.setDisable(index == total - 1);
        btnDown.setOnAction(e -> handleMoveSubtopic(subtopic, 1));
        Button btnRename = UiKit.iconButton(UiKit.Icon.PENCIL, "Rename subtopic");
        btnRename.setOnAction(e -> handleRenameSubtopic(subtopic));
        Button btnHours = UiKit.iconButton(UiKit.Icon.CLOCK, "Set learn and revise hours");
        btnHours.setOnAction(e -> handleSetHoursForSubtopic(subtopic));
        Button btnDelete = UiKit.dangerIconButton(UiKit.Icon.TRASH, "Delete subtopic");
        btnDelete.setOnAction(e -> {
            if (UiKit.confirm(window(), "Delete subtopic",
                    "Delete “" + subtopic.getName() + "”?",
                    "The subtopic and its hours are removed from this unit. Exam dates and marks are kept.",
                    "Delete subtopic")) {
                handleDeleteSubtopic(subtopic);
            }
        });

        HBox actions = new HBox(4, btnUp, btnDown, btnRename, btnHours, btnDelete);
        actions.setMinWidth(ACTIONS_COL);
        actions.setPrefWidth(ACTIONS_COL);
        actions.setAlignment(Pos.CENTER_LEFT);

        row.getChildren().addAll(studiedBox, topicLabel, importancePicker(subtopic), status, learnCell, reviseCell, actions);
        return row;
    }

    // ------------------------------------------------------------------ importance

    /**
     * How much a subtopic matters for the exam. The planner uses it to decide which topics to
     * cover first and which to drop when time is short, so every change replans.
     */
    private Node importancePicker(SyllabusSubtopic subtopic) {
        ComboBox<Integer> picker = UiKit.levelPicker(subtopic.getImportance(),
                SyllabusSubtopic::importanceLabel, level -> {
                    try {
                        syllabusDAO.updateSubtopicImportance(subtopic.getSubtopicId(), level);
                        subtopic.setImportance(level);
                        scheduleDebounceReplan(activeSemesterId);
                    } catch (SQLException ex) {
                        showError("Importance could not be updated", ex.getMessage());
                    }
                });
        picker.setTooltip(new Tooltip("How important this topic is for the exam.\n"
                + "Must-know topics are planned first; Optional ones are the first to go when time is short."));
        fixedColumn(picker, IMPORTANCE_COL);
        return picker;
    }

    /** Sets one importance level for every subtopic of a unit. */
    private Node unitImportanceMenu(SyllabusUnit unit) {
        MenuButton menu = new MenuButton("Importance");
        menu.getStyleClass().add("btn-ghost");
        menu.setTooltip(new Tooltip("Set the importance of every subtopic in this unit"));
        for (int level = 5; level >= 1; level--) {
            final int value = level;
            MenuItem item = new MenuItem("All " + SyllabusSubtopic.importanceLabel(value));
            item.setOnAction(e -> {
                try {
                    syllabusDAO.updateUnitImportance(unit.getUnitId(), value);
                    refreshSyllabusDisplay();
                    scheduleDebounceReplan(activeSemesterId);
                    UiKit.toast("Unit marked " + SyllabusSubtopic.importanceLabel(value) + ".");
                } catch (SQLException ex) {
                    showError("Importance could not be updated", ex.getMessage());
                }
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    // ------------------------------------------------------------------ inline hours editing

    /**
     * Click-to-edit learn/revise hours. The label is replaced by a small text field; Enter
     * commits, Esc or losing focus cancels, and values outside 15 minutes … 40 hours are
     * rejected without saving.
     */
    private Node editableHours(SyllabusSubtopic subtopic, boolean learn) {
        StackPane holder = new StackPane();
        holder.getStyleClass().add("hours-cell");
        holder.setAlignment(Pos.CENTER_LEFT);
        fixedColumn(holder, HOURS_COL);
        double current = learn ? subtopic.getHoursToCover() : subtopic.getHoursToRevise();
        holder.getChildren().add(hoursValueLabel(holder, subtopic, learn, current));
        return holder;
    }

    private Label hoursValueLabel(StackPane holder, SyllabusSubtopic subtopic, boolean learn, double hours) {
        Label value = new Label(DurationFormat.formatMinutes((int) Math.round(hours * 60.0)));
        value.getStyleClass().add("hours-value");
        value.setTooltip(new Tooltip("Click to edit " + (learn ? "learn" : "revise") + " hours"));
        value.setOnMouseClicked(e -> beginHoursEdit(holder, subtopic, learn, hours));
        return value;
    }

    private void beginHoursEdit(StackPane holder, SyllabusSubtopic subtopic, boolean learn, double hours) {
        TextField field = new TextField(DurationFormat.formatMinutes((int) Math.round(hours * 60.0)));
        field.getStyleClass().add("hours-editor");
        holder.getChildren().setAll(field);
        field.selectAll();
        field.requestFocus();

        boolean[] settled = {false};
        Runnable cancel = () -> {
            if (settled[0]) {
                return;
            }
            settled[0] = true;
            holder.getChildren().setAll(hoursValueLabel(holder, subtopic, learn, hours));
        };

        field.setOnAction(e -> {
            if (settled[0]) {
                return;
            }
            OptionalDouble parsed = DurationFormat.parseHours(field.getText());
            if (parsed.isEmpty()) {
                if (!field.getStyleClass().contains("text-field-invalid")) {
                    field.getStyleClass().add("text-field-invalid");
                }
                field.setTooltip(new Tooltip("Enter between 15m and 40h, e.g. 1h 30m, 1.5 or 90m"));
                return;
            }
            settled[0] = true;
            holder.getChildren().setAll(hoursValueLabel(holder, subtopic, learn, parsed.getAsDouble()));
            commitHours(subtopic, learn, parsed.getAsDouble());
        });

        field.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                e.consume();
                cancel.run();
            }
        });
        field.focusedProperty().addListener((obs, was, isNow) -> {
            if (!isNow) {
                cancel.run();
            }
        });
    }

    private void commitHours(SyllabusSubtopic subtopic, boolean learn, double hours) {
        try {
            if (learn) {
                syllabusDAO.updateSubtopicHoursToCover(subtopic.getSubtopicId(), hours);
                subtopic.setHoursToCover(hours);
            } else {
                syllabusDAO.updateSubtopicHoursToRevise(subtopic.getSubtopicId(), hours);
                subtopic.setHoursToRevise(hours);
            }
            examScopeService.syncTopicPhasesForSubject(activeSemesterId, selectedSubjectCode);
            if (replanRunner != null) {
                replanRunner.runReplan(activeSemesterId);
            } else if (replanService != null) {
                replanService.replanFromToday(activeSemesterId);
            }
            refreshSyllabusDisplay();
            if (onDataChanged != null) {
                onDataChanged.run();
            }
            UiKit.toast((learn ? "Learn" : "Revise") + " hours updated.");
        } catch (SQLException ex) {
            showError("Hours could not be updated", ex.getMessage());
            refreshSyllabusDisplay();
        }
    }

    private Node statusBadge(SyllabusSubtopic subtopic) {
        ExamScopeService.TopicCoverage coverage = examScopeService.getTopicCoverage(
                activeSemesterId, selectedSubjectCode, subtopic.getSubtopicId(), null, LocalDate.now());

        UiKit.Kind kind = UiKit.Kind.INFO;
        if ("done".equals(coverage.styleHint)) {
            kind = UiKit.Kind.SUCCESS;
        } else if ("shared".equals(coverage.styleHint)) {
            kind = UiKit.Kind.WARN;
        }

        Label badge;
        if (coverage.badgeText != null && !coverage.badgeText.isBlank()) {
            badge = UiKit.badge(coverage.badgeText, kind);
            badge.setWrapText(true);
        } else {
            badge = new Label("-");
            badge.getStyleClass().add("subtle");
        }
        fixedColumn(badge, STATUS_COL);
        badge.setWrapText(true);
        return badge;
    }

    // ------------------------------------------------------------------ syllabus handlers

    private void handleAddUnit() {
        TextInputDialog dialog = new TextInputDialog();
        UiKit.styleDialog(dialog);
        dialog.setTitle("Add Unit");
        dialog.setHeaderText("New unit for " + selectedSubjectCode);
        dialog.setContentText("Unit name:");

        dialog.showAndWait().ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                try {
                    List<SyllabusUnit> existing = syllabusDAO.findUnitsBySubject(activeSemesterId, selectedSubjectCode);
                    syllabusDAO.insertUnit(activeSemesterId, selectedSubjectCode, name.trim(), existing.size());
                    refreshSyllabusDisplay();
                    examScopeService.resyncHoursForSubject(activeSemesterId, selectedSubjectCode);
                    scheduleDebounceReplan(activeSemesterId);
                    UiKit.toast("Unit added: " + name.trim());
                } catch (SQLException e) {
                    showError("The unit could not be added", e.getMessage());
                }
            }
        });
    }

    private void handleRenameUnit(SyllabusUnit unit) {
        TextInputDialog dialog = new TextInputDialog(unit.getName());
        UiKit.styleDialog(dialog);
        dialog.setTitle("Rename Unit");
        dialog.setHeaderText("Rename “" + unit.getName() + "”");
        dialog.setContentText("New name:");

        dialog.showAndWait().ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                try {
                    syllabusDAO.updateUnitName(unit.getUnitId(), name.trim());
                    refreshSyllabusDisplay();
                } catch (SQLException e) {
                    showError("The unit could not be renamed", e.getMessage());
                }
            }
        });
    }

    private void handleDeleteUnit(SyllabusUnit unit) {
        try {
            List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
            String detail = subtopics.isEmpty()
                    ? "The unit is removed from this subject. Exam dates and marks are kept."
                    : "The unit and its " + subtopics.size() + " subtopics are removed. Exam dates and marks are kept.";
            if (!UiKit.confirm(window(), "Delete unit", "Delete “" + unit.getName() + "”?", detail, "Delete unit")) {
                return;
            }
            syllabusDAO.deleteUnit(unit.getUnitId());
            examScopeService.resyncHoursForSubject(activeSemesterId, selectedSubjectCode);
            refreshSyllabusDisplay();
            scheduleDebounceReplan(activeSemesterId);
            UiKit.toast("Unit deleted.");
        } catch (SQLException e) {
            showError("The unit could not be deleted", e.getMessage());
        }
    }

    private void handleMoveUnit(SyllabusUnit unit, int direction) {
        try {
            List<SyllabusUnit> units = syllabusDAO.findUnitsBySubject(activeSemesterId, selectedSubjectCode);
            int currentIndex = -1;
            for (int i = 0; i < units.size(); i++) {
                if (units.get(i).getUnitId() == unit.getUnitId()) {
                    currentIndex = i;
                    break;
                }
            }
            if (currentIndex >= 0) {
                int newIndex = currentIndex + direction;
                if (newIndex >= 0 && newIndex < units.size()) {
                    SyllabusUnit other = units.get(newIndex);
                    syllabusDAO.updateUnitPosition(unit.getUnitId(), newIndex);
                    syllabusDAO.updateUnitPosition(other.getUnitId(), currentIndex);
                    refreshSyllabusDisplay();
                }
            }
        } catch (SQLException e) {
            showError("The unit could not be moved", e.getMessage());
        }
    }

    private void handleClearSyllabus() {
        String subjectCode = selectedSubjectCode;
        if (!UiKit.confirm(window(), "Clear syllabus",
                "Clear the syllabus for " + subjectCode + "?",
                "This permanently deletes every unit and subtopic of " + subjectCode + " and re-syncs its topic hours. "
                        + "Exam dates, marks and study sessions already logged are kept.",
                "Clear syllabus")) {
            return;
        }
        try {
            syllabusDAO.deleteAllForSubject(activeSemesterId, subjectCode);
            examScopeService.resyncHoursForSubject(activeSemesterId, subjectCode);
            refreshSyllabusDisplay();
            scheduleDebounceReplan(activeSemesterId);
            UiKit.toast("Syllabus cleared for " + subjectCode + ".");
        } catch (SQLException e) {
            showError("The syllabus could not be cleared", e.getMessage());
        }
    }

    private void handleAddSubtopic(SyllabusUnit unit) {
        Dialog<java.util.Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Add Subtopic");
        dialog.setHeaderText("New subtopic in " + unit.getName());
        UiKit.styleDialog(dialog);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        TextField nameField = new TextField();
        nameField.setPromptText("Subtopic name");
        Spinner<Double> coverSpinner = new Spinner<>(0.1, 100.0, 1.0, 0.25);
        coverSpinner.setEditable(true);
        coverSpinner.setPrefWidth(120);
        Spinner<Double> reviseSpinner = new Spinner<>(0.1, 100.0, 0.5, 0.25);
        reviseSpinner.setEditable(true);
        reviseSpinner.setPrefWidth(120);

        grid.addRow(0, label("Name"), nameField);
        grid.addRow(1, label("Learn hours (h)"), coverSpinner);
        grid.addRow(2, label("Revise hours (h)"), reviseSpinner);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        nameField.setOnAction(e -> {
            Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
            if (ok instanceof Button) {
                ((Button) ok).fire();
            }
        });

        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                java.util.Map<String, Object> result = new java.util.HashMap<>();
                result.put("name", nameField.getText());
                result.put("hoursToCover", coverSpinner.getValue());
                result.put("hoursToRevise", reviseSpinner.getValue());
                return result;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(data -> {
            String name = (String) data.get("name");
            double cover = (Double) data.get("hoursToCover");
            double revise = (Double) data.get("hoursToRevise");
            if (!name.trim().isEmpty()) {
                try {
                    List<SyllabusSubtopic> existing = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
                    syllabusDAO.insertSubtopic(unit.getUnitId(), name.trim(), existing.size(), 5, cover, revise);
                    examScopeService.resyncHoursForSubject(activeSemesterId, selectedSubjectCode);
                    refreshSyllabusDisplay();
                    scheduleDebounceReplan(activeSemesterId);
                    UiKit.toast("Subtopic added.");
                } catch (SQLException e) {
                    showError("The subtopic could not be added", e.getMessage());
                }
            }
        });
    }

    private void handleBulkAddSubtopics(SyllabusUnit unit, String text) {
        List<String> subtopics = SyllabusParser.splitRespectingParentheses(text, ',');
        try {
            List<SyllabusSubtopic> existing = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
            int position = existing.size();
            int added = 0;
            for (String subtopic : subtopics) {
                String cleaned = subtopic.trim();
                if (!cleaned.isEmpty()) {
                    syllabusDAO.insertSubtopic(unit.getUnitId(), cleaned, position++);
                    added++;
                }
            }
            examScopeService.resyncHoursForSubject(activeSemesterId, selectedSubjectCode);
            refreshSyllabusDisplay();
            scheduleDebounceReplan(activeSemesterId);
            UiKit.toast(added + " subtopic(s) added.");
        } catch (SQLException e) {
            showError("The subtopics could not be added", e.getMessage());
        }
    }

    private void handleRenameSubtopic(SyllabusSubtopic subtopic) {
        TextInputDialog dialog = new TextInputDialog(subtopic.getName());
        UiKit.styleDialog(dialog);
        dialog.setTitle("Rename Subtopic");
        dialog.setHeaderText("Rename “" + subtopic.getName() + "”");
        dialog.setContentText("New name:");

        dialog.showAndWait().ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                try {
                    syllabusDAO.updateSubtopicName(subtopic.getSubtopicId(), name.trim());
                    refreshSyllabusDisplay();
                } catch (SQLException e) {
                    showError("The subtopic could not be renamed", e.getMessage());
                }
            }
        });
    }

    private void handleDeleteSubtopic(SyllabusSubtopic subtopic) {
        try {
            syllabusDAO.deleteSubtopic(subtopic.getSubtopicId());
            examScopeService.resyncHoursForSubject(activeSemesterId, selectedSubjectCode);
            refreshSyllabusDisplay();
            scheduleDebounceReplan(activeSemesterId);
            UiKit.toast("Subtopic deleted.");
        } catch (SQLException e) {
            showError("The subtopic could not be deleted", e.getMessage());
        }
    }

    private void handleMoveSubtopic(SyllabusSubtopic subtopic, int direction) {
        try {
            List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(subtopic.getUnitId());
            int currentIndex = -1;
            for (int i = 0; i < subtopics.size(); i++) {
                if (subtopics.get(i).getSubtopicId() == subtopic.getSubtopicId()) {
                    currentIndex = i;
                    break;
                }
            }
            if (currentIndex >= 0) {
                int newIndex = currentIndex + direction;
                if (newIndex >= 0 && newIndex < subtopics.size()) {
                    SyllabusSubtopic other = subtopics.get(newIndex);
                    syllabusDAO.updateSubtopicPosition(subtopic.getSubtopicId(), newIndex);
                    syllabusDAO.updateSubtopicPosition(other.getSubtopicId(), currentIndex);
                    refreshSyllabusDisplay();
                }
            }
        } catch (SQLException e) {
            showError("The subtopic could not be moved", e.getMessage());
        }
    }

    private void handleSetHoursForUnit(SyllabusUnit unit) {
        try {
            List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
            if (subtopics.isEmpty()) {
                UiKit.toast("This unit has no subtopics.", UiKit.Kind.WARN);
                return;
            }
            applyHoursDialog("Set unit hours", "Set hours for all " + subtopics.size() + " subtopics in " + unit.getName(),
                    1.0, 0.5, hrs -> {
                        try {
                            List<Integer> ids = subtopics.stream().map(SyllabusSubtopic::getSubtopicId).toList();
                            syllabusDAO.bulkUpdateHours(ids, hrs[0], hrs[1]);
                            examScopeService.resyncHoursForSubject(activeSemesterId, selectedSubjectCode);
                            if (replanRunner != null) {
                                replanRunner.runReplan(activeSemesterId);
                            } else if (replanService != null) {
                                replanService.replanFromToday(activeSemesterId);
                            }
                            refreshSyllabusDisplay();
                            if (onDataChanged != null) {
                                onDataChanged.run();
                            }
                            UiKit.toast("Unit hours updated.");
                        } catch (SQLException ex) {
                            showError("Hours could not be updated", ex.getMessage());
                        }
                    });
        } catch (SQLException e) {
            showError("The unit could not be loaded", e.getMessage());
        }
    }

    private void handleSetHoursForSubtopic(SyllabusSubtopic subtopic) {
        applyHoursDialog("Set subtopic hours", subtopic.getName(),
                subtopic.getHoursToCover(), subtopic.getHoursToRevise(), hrs -> {
                    try {
                        syllabusDAO.bulkUpdateHours(List.of(subtopic.getSubtopicId()), hrs[0], hrs[1]);
                        examScopeService.resyncHoursForSubject(activeSemesterId, selectedSubjectCode);
                        if (replanRunner != null) {
                            replanRunner.runReplan(activeSemesterId);
                        } else if (replanService != null) {
                            replanService.replanFromToday(activeSemesterId);
                        }
                        refreshSyllabusDisplay();
                        if (onDataChanged != null) {
                            onDataChanged.run();
                        }
                        UiKit.toast("Hours updated.");
                    } catch (SQLException ex) {
                        showError("Hours could not be updated", ex.getMessage());
                    }
                });
    }

    private void applyHoursDialog(String title, String header, double learnHours, double reviseHours,
                                  java.util.function.Consumer<double[]> onApply) {
        Dialog<double[]> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        UiKit.styleDialog(dialog);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        Spinner<Double> coverSpinner = new Spinner<>(0.1, 200.0, Math.max(0.1, learnHours), 0.25);
        coverSpinner.setEditable(true);
        coverSpinner.setPrefWidth(130);
        Spinner<Double> reviseSpinner = new Spinner<>(0.1, 200.0, Math.max(0.1, reviseHours), 0.25);
        reviseSpinner.setEditable(true);
        reviseSpinner.setPrefWidth(130);
        grid.addRow(0, label("Learn hours (h)"), coverSpinner);
        grid.addRow(1, label("Revise hours (h)"), reviseSpinner);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> btn == ButtonType.OK
                ? new double[]{coverSpinner.getValue(), reviseSpinner.getValue()} : null);

        dialog.showAndWait().ifPresent(onApply);
    }

    private Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtle");
        return label;
    }

    // ------------------------------------------------------------------ paste syllabus

    private void handlePasteSyllabus(SubjectInfo subject) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Paste syllabus for " + subject.getDisplayName());
        dialog.setHeaderText(null);
        dialog.getDialogPane().setPrefWidth(860);
        dialog.getDialogPane().setPrefHeight(680);
        UiKit.styleDialog(dialog);

        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        TextArea textArea = new TextArea();
        textArea.setWrapText(true);
        textArea.setPrefRowCount(8);
        textArea.setPromptText("Example:\nFoundation of Cognitive Computing: Understanding cognition, Cognitive computing\n"
                + "Cognitive System Design Principles: Components, Machine learning");

        Button btnParse = UiKit.secondaryButton("Parse");
        Button btnLoadFile = UiKit.secondaryButton("Load .txt / .docx…");
        btnLoadFile.setOnAction(e -> loadSyllabusFile(textArea, btnParse));
        HBox parseRow = new HBox(10, btnLoadFile, btnParse);
        parseRow.setAlignment(Pos.CENTER_LEFT);

        VBox previewBox = new VBox(8);
        previewBox.getChildren().add(UiKit.hint(
                "Paste your syllabus text, then click Parse to preview the units before importing."));
        ScrollPane previewScroll = new ScrollPane(previewBox);
        previewScroll.setFitToWidth(true);
        previewScroll.setPrefHeight(280);
        VBox.setVgrow(previewScroll, Priority.ALWAYS);

        Label summaryChip = UiKit.chip("Nothing parsed yet", UiKit.Kind.INFO);
        HBox summaryRow = new HBox(summaryChip);

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton rbAdd = new RadioButton("Add to existing syllabus");
        rbAdd.setToggleGroup(modeGroup);
        rbAdd.setSelected(true);
        RadioButton rbReplace = new RadioButton("Replace existing syllabus");
        rbReplace.setToggleGroup(modeGroup);
        HBox modeBox = new HBox(20, rbAdd, rbReplace);

        content.getChildren().addAll(
                UiKit.sectionTitle("Paste syllabus"),
                UiKit.hint("One unit per line. Format: Unit Name: subtopic 1, subtopic 2, …"),
                textArea,
                parseRow,
                UiKit.sectionTitle("Preview (editable before import)"),
                previewScroll,
                summaryRow,
                modeBox);
        dialog.getDialogPane().setContent(content);

        ButtonType importButton = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, ButtonType.CANCEL);
        Button importBtn = (Button) dialog.getDialogPane().lookupButton(importButton);
        importBtn.setDisable(true);

        List<ParsedUnit> parsedUnits = new ArrayList<>();

        btnParse.setOnAction(e -> {
            String text = textArea.getText().trim();
            parsedUnits.clear();
            if (text.isEmpty()) {
                previewBox.getChildren().setAll(UiKit.hint("Please enter syllabus text above."));
                summaryChip.setText("Nothing parsed yet");
                importBtn.setDisable(true);
                return;
            }
            parsedUnits.addAll(SyllabusParser.parseUnitList(text));
            buildPasteSyllabusPreview(previewBox, parsedUnits, summaryChip);
            importBtn.setDisable(parsedUnits.isEmpty());
        });

        dialog.setResultConverter(button -> button == importButton);

        boolean imported = dialog.showAndWait().orElse(false);
        if (imported && !parsedUnits.isEmpty()) {
            boolean replaceMode = rbReplace.isSelected();
            if (replaceMode && !UiKit.confirm(window(), "Replace syllabus",
                    "Replace the existing syllabus for " + subject.getSubjectCode() + "?",
                    "Every existing unit and subtopic of " + subject.getSubjectCode()
                            + " will be deleted, then the pasted units will be imported. Exam dates and marks are kept.",
                    "Replace syllabus")) {
                return;
            }
            importParsedSyllabus(parsedUnits, subject.getSubjectCode(), replaceMode);
        }
    }

    /** Loads a .txt or .docx syllabus into the paste area, then parses it. */
    private void loadSyllabusFile(TextArea textArea, Button btnParse) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Open syllabus file");
        chooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("Syllabus files (*.txt, *.docx)", "*.txt", "*.docx"),
                new javafx.stage.FileChooser.ExtensionFilter("Text files (*.txt)", "*.txt"),
                new javafx.stage.FileChooser.ExtensionFilter("Word documents (*.docx)", "*.docx"));
        java.io.File file = chooser.showOpenDialog(window());
        if (file == null) {
            return;
        }
        try {
            String text;
            if (file.getName().toLowerCase(Locale.ENGLISH).endsWith(".docx")) {
                text = new SyllabusDocxReader().extractText(file);
            } else {
                text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            textArea.setText(text);
            btnParse.fire();
        } catch (Exception ex) {
            showError("That file could not be read", ex.getMessage());
        }
    }

    private void buildPasteSyllabusPreview(VBox previewBox, List<ParsedUnit> units, Label summaryChip) {
        previewBox.getChildren().clear();

        if (units.isEmpty()) {
            previewBox.getChildren().add(UiKit.hint("No units found. Check the format and try again."));
            summaryChip.setText("Nothing parsed yet");
            return;
        }

        int totalSubtopics = 0;

        for (ParsedUnit unit : units) {
            VBox unitCard = UiKit.cardFlat();

            TextField unitNameField = new TextField(unit.getName());
            HBox.setHgrow(unitNameField, Priority.ALWAYS);
            unitNameField.textProperty().addListener((obs, old, val) -> unit.setName(val));
            Label subtopicCount = UiKit.hint(unit.getSubtopics().size() + " subtopics");
            Button btnRemoveUnit = UiKit.dangerIconButton(UiKit.Icon.CLOSE, "Remove this unit from the preview");
            btnRemoveUnit.setOnAction(e -> {
                units.remove(unit);
                buildPasteSyllabusPreview(previewBox, units, summaryChip);
            });
            HBox unitHeader = new HBox(10, unitNameField, subtopicCount, btnRemoveUnit);
            unitHeader.setAlignment(Pos.CENTER_LEFT);

            VBox subtopicsBox = new VBox(4);
            subtopicsBox.setPadding(new Insets(4, 0, 0, 12));
            for (String subtopic : unit.getSubtopics()) {
                Label subtopicLabel = new Label("- " + subtopic);
                subtopicLabel.setWrapText(true);
                HBox.setHgrow(subtopicLabel, Priority.ALWAYS);
                Button btnRemove = UiKit.dangerIconButton(UiKit.Icon.CLOSE, "Remove this subtopic from the preview");
                btnRemove.setOnAction(e -> {
                    unit.getSubtopics().remove(subtopic);
                    buildPasteSyllabusPreview(previewBox, units, summaryChip);
                });
                HBox subtopicRow = new HBox(8, subtopicLabel, btnRemove);
                subtopicRow.setAlignment(Pos.CENTER_LEFT);
                subtopicsBox.getChildren().add(subtopicRow);
                totalSubtopics++;
            }

            Button btnAddSubtopic = UiKit.ghostButton("+ Add subtopic");
            btnAddSubtopic.setOnAction(e -> {
                TextInputDialog inputDialog = new TextInputDialog();
                inputDialog.setTitle("Add Subtopic");
                inputDialog.setHeaderText("Subtopic name");
                UiKit.styleDialog(inputDialog);
                inputDialog.showAndWait().ifPresent(name -> {
                    if (!name.trim().isEmpty()) {
                        unit.getSubtopics().add(name.trim());
                        buildPasteSyllabusPreview(previewBox, units, summaryChip);
                    }
                });
            });

            unitCard.getChildren().addAll(unitHeader, subtopicsBox, btnAddSubtopic);
            previewBox.getChildren().add(unitCard);
        }

        Button btnAddUnit = UiKit.ghostButton("+ Add unit");
        btnAddUnit.setOnAction(e -> {
            TextInputDialog inputDialog = new TextInputDialog();
            inputDialog.setTitle("Add Unit");
            inputDialog.setHeaderText("Unit name");
            UiKit.styleDialog(inputDialog);
            inputDialog.showAndWait().ifPresent(name -> {
                if (!name.trim().isEmpty()) {
                    units.add(new ParsedUnit(name.trim(), new ArrayList<>()));
                    buildPasteSyllabusPreview(previewBox, units, summaryChip);
                }
            });
        });
        previewBox.getChildren().add(btnAddUnit);

        summaryChip.setText(units.size() + " units · " + totalSubtopics + " subtopics");
    }

    private void importParsedSyllabus(List<ParsedUnit> units, String subjectCode, boolean replaceMode) {
        try {
            if (replaceMode) {
                syllabusDAO.deleteAllForSubject(activeSemesterId, subjectCode);
            }

            List<SyllabusUnit> existingUnits = syllabusDAO.findUnitsBySubject(activeSemesterId, subjectCode);
            Map<String, SyllabusUnit> existingUnitMap = new HashMap<>();
            for (SyllabusUnit unit : existingUnits) {
                existingUnitMap.put(unit.getName().toLowerCase(), unit);
            }

            int position = existingUnits.size();

            for (ParsedUnit parsedUnit : units) {
                String unitName = parsedUnit.getName().trim();
                if (unitName.isEmpty()) {
                    continue;
                }

                SyllabusUnit existingUnit = existingUnitMap.get(unitName.toLowerCase());
                int unitId;
                if (existingUnit != null && !replaceMode) {
                    unitId = existingUnit.getUnitId();
                } else {
                    unitId = syllabusDAO.insertUnit(activeSemesterId, subjectCode, unitName, position++);
                }

                List<SyllabusSubtopic> existingSubtopics = syllabusDAO.findSubtopicsByUnit(unitId);
                Set<String> existingSubtopicNames = new HashSet<>();
                for (SyllabusSubtopic st : existingSubtopics) {
                    existingSubtopicNames.add(st.getName().toLowerCase());
                }

                int subtopicPosition = existingSubtopics.size();
                for (String subtopicName : parsedUnit.getSubtopics()) {
                    String cleaned = subtopicName.trim();
                    if (cleaned.isEmpty()) {
                        continue;
                    }
                    if (!existingSubtopicNames.contains(cleaned.toLowerCase())) {
                        syllabusDAO.insertSubtopic(unitId, cleaned, subtopicPosition++);
                        existingSubtopicNames.add(cleaned.toLowerCase());
                    }
                }
            }

            examScopeService.resyncHoursForSubject(activeSemesterId, subjectCode);
            refreshSyllabusDisplay();
            scheduleDebounceReplan(activeSemesterId);
            UiKit.toast("Syllabus imported for " + subjectCode + ".");

        } catch (SQLException e) {
            showError("The syllabus could not be imported", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ shared

    private void scheduleDebounceReplan(int semesterId) {
        if (isPopulatingUI) {
            return;
        }
        synchronized (debounceTimerLock) {
            if (debounceTimer != null) {
                debounceTimer.cancel();
            }
            debounceTimer = new java.util.Timer();
            debounceTimer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    javafx.application.Platform.runLater(() -> {
                        try {
                            if (replanRunner != null) {
                                replanRunner.runReplan(semesterId);
                            } else if (replanService != null) {
                                replanService.replanFromToday(semesterId);
                            }
                            if (onDataChanged != null) {
                                onDataChanged.run();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            }, 500);
        }
    }

    private void showError(String title, String msg) {
        UiKit.error(window(), title, msg);
    }

    private Window window() {
        return detailHost != null && detailHost.getScene() != null
                ? detailHost.getScene().getWindow() : null;
    }

    /** Actions that re-plan the semester are unavailable while a replan is running. */
    private void bindBusy(Button... buttons) {
        if (replanRunner == null) {
            return;
        }
        for (Button button : buttons) {
            button.disableProperty().bind(replanRunner.busyProperty());
        }
    }
}
