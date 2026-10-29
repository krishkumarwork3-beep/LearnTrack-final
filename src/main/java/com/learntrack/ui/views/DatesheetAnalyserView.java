package com.learntrack.ui.views;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.service.*;
import com.learntrack.ui.ReplanRunner;
import com.learntrack.ui.UiKit;
import com.learntrack.ui.setup.CapacityNegotiationDialogHelper;
import com.learntrack.ui.setup.TopicTrimmingDialog;
import com.learntrack.util.DurationFormat;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * UI View for the Datesheet Analyser.
 * Allows pasting datesheet text, previewing matches and clashes,
 * editing end times, and applying schedule to exams.
 * Part 1 (general parser) and Part 2 (exam window) are both wired here.
 */
public class DatesheetAnalyserView {

    private static final DateTimeFormatter TIME_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter DATE_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter SLOT_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SLOT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final SemesterDAO semesterDAO;
    private final ExamDAO examDAO;
    private final TopicDAO topicDAO;
    private final DatesheetAnalyserService analyserService;
    private final ExamPeriodCapacityService capacityService;
    private final SemesterReplanService replanService;
    private final ReplanRunner replanRunner;
    private final Runnable refreshAll;

    private TextArea pasteArea;
    private Button btnAnalyse;
    private Button btnClear;
    private Button btnApply;
    private Label summaryLabel;
    private Label statusLabel;
    private ComboBox<String> examTypeCombo;
    private Label examTypeNote;
    private boolean syncingExamTypeCombo;
    private String lastAnalysedText;
    private VBox ambiguousPanel;
    private TableView<DatesheetMatchRow> matchTable;
    private final ObservableList<DatesheetMatchRow> matchItems = FXCollections.observableArrayList();
    private VBox unmatchedBox;
    private VBox warningsBox;
    private VBox warningsCard;
    private Label noticesLabel;
    private final SubjectListService subjectListService;
    private Button btnAddExam;
    private Button btnEditExam;
    private Button btnRemoveExam;
    private BooleanBinding cannotApply;

    private String lastExamType;
    private Map<String, List<DatesheetEntry>> lastAmbiguous;
    /** Subjects of the active semester this sheet never mentioned (straight from the analysis). */
    private final List<SubjectInfo> analysedUnmatched = new ArrayList<>();
    /** The part of {@link #analysedUnmatched} with no row in the table: a hand-added exam drops out of it at once. */
    private final List<SubjectInfo> lastUnmatched = new ArrayList<>();
    private String lastSummaryPrefix;
    /** How many rows the analysis produced, so clearing the table by hand reads differently. */
    private int analysedMatchCount;
    private final Map<String, ComboBox<String>> ambiguousResolutionCombos = new HashMap<>();

    public DatesheetAnalyserView(SemesterDAO semesterDAO, ExamDAO examDAO, TopicDAO topicDAO,
                                 TimetableDAO timetableDAO, SettingsDAO settingsDAO,
                                 ExamTimelineService timelineService,
                                 ExamPeriodCapacityService capacityService,
                                 SemesterReplanService replanService,
                                 ReplanRunner replanRunner,
                                 Runnable refreshAll) {
        this.semesterDAO = semesterDAO;
        this.examDAO = examDAO;
        this.topicDAO = topicDAO;
        this.capacityService = capacityService;
        this.replanService = replanService;
        this.replanRunner = replanRunner;
        this.refreshAll = refreshAll;

        this.subjectListService = new SubjectListService(timetableDAO, examDAO);
        this.analyserService = new DatesheetAnalyserService(
                examDAO, subjectListService, settingsDAO, timelineService, null
        );
    }

    public Node getView() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("page");

        HBox header = UiKit.pageHeader("Datesheet Analyser",
                "Paste datesheet text and review the matches before applying them to your exams.",
                null);

        HBox stepper = new HBox(10,
                step(1, "Paste"),
                stepArrow(),
                step(2, "Review"),
                stepArrow(),
                step(3, "Apply"));
        stepper.setAlignment(Pos.CENTER_LEFT);

        // ------------------------------------------------------------ exam type
        Label examTypeLabel = new Label("Exam type");
        examTypeLabel.getStyleClass().add("subtle");

        examTypeCombo = new ComboBox<>();
        examTypeCombo.getItems().addAll("MST", "EST", "Other");
        // No preselection: a sheet that names neither MST nor EST must be classified by the user.
        examTypeCombo.setTooltip(new Tooltip(
                "Select the exam type for this datesheet. The detected type is set automatically but you can override it here."));
        examTypeCombo.setPrefWidth(140);

        examTypeNote = UiKit.hint("Choose which exam type these dates belong to.");
        examTypeNote.setVisible(false);
        examTypeNote.setManaged(false);

        examTypeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (syncingExamTypeCombo || newValue == null || newValue.isBlank()) return;
            if (lastAnalysedText != null && !lastAnalysedText.isBlank()) {
                // Re-read the sheet with the chosen type so durations and row labels follow it.
                runAnalyse(lastAnalysedText, newValue);
            }
        });

        HBox examTypeBox = new HBox(10, examTypeLabel, examTypeCombo, examTypeNote);
        examTypeBox.setAlignment(Pos.CENTER_LEFT);

        // ------------------------------------------------------------ paste
        pasteArea = new TextArea();
        pasteArea.setPromptText("Paste raw datesheet text here…");
        pasteArea.setPrefRowCount(7);
        pasteArea.setWrapText(false);
        pasteArea.getStyleClass().add("paste-area");

        btnAnalyse = UiKit.primaryButton("Analyse Datesheet");
        btnAnalyse.setOnAction(e -> handleAnalyse());

        btnClear = UiKit.secondaryButton("Clear");
        btnClear.setOnAction(e -> clearAll());

        HBox buttonBar = new HBox(10, btnAnalyse, btnClear);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        VBox pasteCard = UiKit.card(
                UiKit.sectionTitle("Paste datesheet text"),
                examTypeBox,
                pasteArea,
                buttonBar);

        // ------------------------------------------------------------ exam dates summary
        summaryLabel = new Label("No datesheet analysed yet.");
        summaryLabel.getStyleClass().add("page-description");

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("subtle");
        statusLabel.setWrapText(true);

        // ------------------------------------------------------------ decisions
        ambiguousPanel = new VBox(10);
        ambiguousPanel.getStyleClass().addAll("card", "banner-warn");
        ambiguousPanel.setVisible(false);
        ambiguousPanel.setManaged(false);
        Label ambiguousTitle = new Label("Needs Your Decision");
        ambiguousTitle.getStyleClass().add("banner-title");
        Label ambiguousSubtitle = new Label(
                "The following courses have multiple possible date/time slots. Choose the correct slot for each, or skip.");
        ambiguousSubtitle.getStyleClass().add("banner-text");
        ambiguousSubtitle.setWrapText(true);
        ambiguousPanel.getChildren().addAll(ambiguousTitle, ambiguousSubtitle);

        noticesLabel = new Label();
        noticesLabel.getStyleClass().add("hint");
        noticesLabel.setWrapText(true);
        noticesLabel.setVisible(false);
        noticesLabel.setManaged(false);

        VBox reviewCard = UiKit.card(
                UiKit.sectionTitle("Exam dates"),
                summaryLabel,
                statusLabel,
                ambiguousPanel,
                noticesLabel);

        // ------------------------------------------------------------ results table
        matchTable = new TableView<>(matchItems);
        matchTable.setEditable(true);
        matchTable.setPrefHeight(240);
        matchTable.setMinHeight(180);
        matchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        matchTable.setPlaceholder(UiKit.emptyState("No matched subjects yet",
                "Paste a datesheet above and press Type Analyse to see how your subjects map to exam slots."));

        TableColumn<DatesheetMatchRow, String> codeCol = new TableColumn<>("Course code");
        codeCol.setCellValueFactory(new PropertyValueFactory<>("courseCode"));
        codeCol.setPrefWidth(100);
        codeCol.setMinWidth(90);

        // Only the user's own subject name is shown; the sheet prints the same course once per
        // batch, so its wording adds no information here.
        TableColumn<DatesheetMatchRow, String> nameCol = new TableColumn<>("Subject");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("userSubjectName"));
        nameCol.setPrefWidth(320);
        nameCol.setMinWidth(180);

        TableColumn<DatesheetMatchRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("examType"));
        typeCol.setPrefWidth(60);
        typeCol.setMinWidth(55);

        TableColumn<DatesheetMatchRow, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(cellData -> {
            LocalDate d = cellData.getValue().getExamDate();
            return new SimpleStringProperty(d != null ? d.toString() : "");
        });
        dateCol.setPrefWidth(100);
        dateCol.setMinWidth(95);

        TableColumn<DatesheetMatchRow, String> dayCol = new TableColumn<>("Day");
        dayCol.setCellValueFactory(new PropertyValueFactory<>("weekday"));
        dayCol.setPrefWidth(90);
        dayCol.setMinWidth(85);

        TableColumn<DatesheetMatchRow, String> startCol = new TableColumn<>("Start");
        startCol.setCellValueFactory(cellData -> {
            LocalTime t = cellData.getValue().getStartTime();
            return new SimpleStringProperty(t != null ? t.format(TIME_DISPLAY_FORMAT) : "");
        });
        startCol.setPrefWidth(90);
        startCol.setMinWidth(85);

        TableColumn<DatesheetMatchRow, String> endCol = new TableColumn<>("End (editable)");
        endCol.setCellValueFactory(cellData -> {
            LocalTime t = cellData.getValue().getEndTime();
            return new SimpleStringProperty(t != null ? t.format(TIME_DISPLAY_FORMAT) : "");
        });
        endCol.setCellFactory(TextFieldTableCell.forTableColumn());
        endCol.setOnEditCommit(event -> {
            String val = event.getNewValue();
            if (val != null && !val.isBlank()) {
                try {
                    String cleaned = val.trim().replaceAll("\\s+", " ");
                    DateTimeFormatter f = DateTimeFormatter.ofPattern(
                            "[hh:mm a][h:mm a][hh:mma][h:mma][HH:mm][H:mm]", Locale.ENGLISH);
                    LocalTime parsed = LocalTime.parse(cleaned.toUpperCase(), f);
                    event.getRowValue().setEndTime(parsed);
                } catch (Exception ex) {
                    UiKit.toast("Invalid time. Use a format such as 11:00 AM or 14:00.", UiKit.Kind.WARN);
                }
            }
            matchTable.refresh();
        });
        endCol.setPrefWidth(115);
        endCol.setMinWidth(105);

        TableColumn<DatesheetMatchRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(110);
        statusCol.setMinWidth(100);

        matchTable.getColumns().addAll(codeCol, nameCol, typeCol, dateCol, dayCol, startCol, endCol, statusCol);
        matchTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // ------------------------------------------------------------ add / edit / remove rows
        btnAddExam = UiKit.secondaryButton("Add exam");
        btnAddExam.setTooltip(new Tooltip("Add an exam that is not on this datesheet, e.g. one missing from it."));
        btnAddExam.setOnAction(e -> handleExamRowDialog(null));

        btnEditExam = UiKit.secondaryButton("Edit selected exam");
        btnEditExam.setTooltip(new Tooltip("Change the course, date, day and times of the selected exam."));
        btnEditExam.setOnAction(e -> {
            DatesheetMatchRow selected = matchTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                UiKit.toast("Select a row in Matched subjects to edit it.", UiKit.Kind.WARN);
                return;
            }
            handleExamRowDialog(selected);
        });

        btnRemoveExam = UiKit.dangerTextButton("Remove selected");
        btnRemoveExam.setTooltip(new Tooltip("Take the selected exam(s) out of this datesheet review."));
        btnRemoveExam.setOnAction(e -> handleRemoveSelected());

        btnEditExam.disableProperty().bind(matchTable.getSelectionModel().selectedItemProperty().isNull());
        btnRemoveExam.disableProperty().bind(matchTable.getSelectionModel().selectedItemProperty().isNull());

        HBox tableActions = new HBox(10, btnAddExam, btnEditExam, btnRemoveExam, UiKit.spacer(),
                UiKit.hint("The day always follows the date you choose."));
        tableActions.setAlignment(Pos.CENTER_LEFT);

        VBox tableCard = UiKit.card(UiKit.sectionTitle("Matched subjects"), tableActions, matchTable);

        // ------------------------------------------------------------ unmatched / warnings
        // Both boxes are pinned to the same fixed height so the page never reflows when one
        // list grows: the content scrolls inside its own box instead.
        unmatchedBox = new VBox(6);
        ScrollPane unmatchedScroll = new ScrollPane(unmatchedBox);
        unmatchedScroll.setFitToWidth(true);
        fixBoxHeight(unmatchedScroll);
        isolateScroll(unmatchedScroll);

        warningsBox = new VBox(6);
        ScrollPane warningsScroll = new ScrollPane(warningsBox);
        warningsScroll.setFitToWidth(true);
        fixBoxHeight(warningsScroll);
        isolateScroll(warningsScroll);

        VBox unmatchedCard = UiKit.card(
                UiKit.sectionTitle("Subjects not found in datesheet"), unmatchedScroll);
        warningsCard = UiKit.card(
                UiKit.sectionTitle("Clashes"), warningsScroll);
        HBox.setHgrow(unmatchedCard, Priority.ALWAYS);
        HBox.setHgrow(warningsCard, Priority.ALWAYS);
        HBox sideBySide = new HBox(12, unmatchedCard, warningsCard);

        setUnmatched(Collections.emptyList());
        setClashes(Collections.emptyList());

        // ------------------------------------------------------------ apply
        btnApply = UiKit.primaryButton("Apply to Exams");
        btnApply.setDisable(true);

        cannotApply = new BooleanBinding() {
            {
                super.bind(matchItems, examTypeCombo.valueProperty());
            }

            @Override
            protected boolean computeValue() {
                if (matchItems.isEmpty()) return true;
                String chosenType = examTypeCombo.getValue();
                if (chosenType == null || chosenType.isBlank()) return true; // exam type not chosen yet
                // Check if any ambiguous course needs resolution
                if (lastAmbiguous != null && !lastAmbiguous.isEmpty()) {
                    for (String code : lastAmbiguous.keySet()) {
                        ComboBox<String> combo = ambiguousResolutionCombos.get(code);
                        if (combo == null || combo.getValue() == null || combo.getValue().isEmpty()) {
                            return true; // Disable if not resolved
                        }
                    }
                }
                return false;
            }
        };
        btnApply.disableProperty().bind(replanRunner != null
                ? cannotApply.or(replanRunner.busyProperty())
                : cannotApply.or(new SimpleBooleanProperty(false)));
        btnApply.setOnAction(e -> handleApply());

        Label applyHint = UiKit.hint("Applying writes the exam dates and replans the study sessions around them.");
        HBox applyBox = new HBox(12, btnApply, applyHint);
        applyBox.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(16,
                header, stepper,
                pasteCard, reviewCard, tableCard, sideBySide, applyBox);
        content.setPadding(new Insets(24));
        content.getStyleClass().add("page");
        ScrollPane page = new ScrollPane(content);
        page.setFitToWidth(true);
        page.setPadding(new Insets(0));
        page.getStyleClass().add("page-scroll");
        return page;
    }

    // ------------------------------------------------------------------ small builders

    private HBox step(int number, String label) {
        Label numberLabel = new Label(String.valueOf(number));
        numberLabel.getStyleClass().add("step-number");
        Label text = new Label(label);
        text.getStyleClass().add("subtle");
        HBox box = new HBox(6, numberLabel, text);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node stepArrow() {
        Region rule = new Region();
        rule.getStyleClass().add("step-rule");
        return rule;
    }

    private Node warningRow(String text) {
        Label message = new Label(text);
        message.setWrapText(true);
        message.getStyleClass().add("banner-text");
        HBox.setHgrow(message, Priority.ALWAYS);
        HBox row = new HBox(8, message);
        row.getStyleClass().addAll("banner", "banner-warn");
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /** Only real slot collisions belong here; when there are none the card disappears. */
    private void setClashes(List<String> clashes) {
        warningsBox.getChildren().clear();
        boolean hasClashes = clashes != null && !clashes.isEmpty();
        warningsCard.setVisible(hasClashes);
        warningsCard.setManaged(hasClashes);
        if (!hasClashes) {
            return;
        }
        for (String clash : clashes) {
            warningsBox.getChildren().add(warningRow(clash));
        }
    }

    /** Informational notices live with the exam dates, never in the Clashes card. */
    private void setNotices(List<String> warnings, List<String> clashes) {
        if (noticesLabel == null) {
            return;
        }
        List<String> others = new ArrayList<>();
        if (warnings != null) {
            for (String warning : warnings) {
                if (clashes == null || !clashes.contains(warning)) {
                    others.add(warning);
                }
            }
        }
        boolean visible = !others.isEmpty();
        noticesLabel.setText(visible ? String.join(" ", others) : "");
        noticesLabel.setVisible(visible);
        noticesLabel.setManaged(visible);
    }

    private static void fixBoxHeight(ScrollPane pane) {
        pane.setPrefHeight(150);
        pane.setMinHeight(150);
        pane.setMaxHeight(150);
    }

    /**
     * Wheel events over a box scroll the box, never the page behind it. The vvalue is adjusted
     * here so the inner pane and the outer page cannot both react to the same event.
     */
    private static void isolateScroll(ScrollPane pane) {
        pane.addEventFilter(ScrollEvent.SCROLL, event -> {
            double contentHeight = pane.getContent() != null
                    ? pane.getContent().getBoundsInLocal().getHeight() : 0;
            double viewportHeight = pane.getViewportBounds().getHeight();
            double scrollable = contentHeight - viewportHeight;
            if (scrollable > 1 && Math.abs(event.getDeltaY()) > 0) {
                double next = pane.getVvalue() - event.getDeltaY() / scrollable;
                pane.setVvalue(Math.max(0, Math.min(1, next)));
            }
            event.consume();
        });
    }

    /**
     * The one line under "Sheet …": how many of my subjects were found, how many are still
     * missing from this sheet, and whether a decision is pending. Recomputed from the current
     * table and missing list so hand-added exams keep it truthful.
     */
    private void refreshStatusLine() {
        if (statusLabel == null) {
            return;
        }
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        boolean analysed = lastAnalysedText != null && !lastAnalysedText.isBlank();
        if (matchItems.isEmpty()) {
            if (!analysed) {
                statusLabel.setText("");
                return;
            }
            statusLabel.setText(analysedMatchCount > 0
                    ? "No exams left in this review. Add one back with Add exam."
                    : "No subject codes from your active semester matched this datesheet.");
            statusLabel.getStyleClass().add(analysedMatchCount > 0
                    ? "text-success" : "text-danger");
            return;
        }

        StringBuilder text = new StringBuilder();
        text.append(matchItems.size()).append(" subject(s) matched and ready to apply.");
        if (!lastUnmatched.isEmpty()) {
            text.append(" ").append(lastUnmatched.size())
                    .append(" subject(s) not found in this datesheet.");
        }
        if (lastAmbiguous != null && !lastAmbiguous.isEmpty()) {
            text.append(" Decide the ambiguous course(s) below before applying.");
        }
        statusLabel.setText(text.toString());
        statusLabel.getStyleClass().add("text-success");
    }

    private void setUnmatched(List<SubjectInfo> subjects) {
        unmatchedBox.getChildren().clear();
        if (subjects == null || subjects.isEmpty()) {
            unmatchedBox.getChildren().add(UiKit.hint(
                    "Every subject in your active semester appears on this datesheet."));
            return;
        }
        for (SubjectInfo subject : subjects) {
            Label label = new Label(subject.getSubjectCode() + " · " + subject.getDisplayName());
            label.getStyleClass().add("subtle");
            label.setWrapText(true);
            unmatchedBox.getChildren().add(label);
        }
    }

    private Window window() {
        return btnApply != null && btnApply.getScene() != null ? btnApply.getScene().getWindow() : null;
    }

    // ------------------------------------------------------------------ add / edit / remove rows

    /**
     * The "not found in datesheet" list is what the sheet never mentioned, minus whatever the
     * table now carries: adding an exam by hand takes its subject out of the list at once.
     */
    private void recomputeUnmatched() {
        lastUnmatched.clear();
        for (SubjectInfo subject : analysedUnmatched) {
            String code = subject.getSubjectCode();
            boolean inTable = code != null && matchItems.stream().anyMatch(row ->
                    row.getCourseCode() != null
                            && row.getCourseCode().equalsIgnoreCase(code.trim()));
            if (!inTable) {
                lastUnmatched.add(subject);
            }
        }
        setUnmatched(lastUnmatched);
        refreshStatusLine();
    }

    /** The date range in the summary line always follows the rows currently in the table. */
    private void refreshSummaryRange() {
        if (summaryLabel == null || lastSummaryPrefix == null) {
            return;
        }
        StringBuilder text = new StringBuilder(lastSummaryPrefix);
        List<LocalDate> dates = new ArrayList<>();
        for (DatesheetMatchRow row : matchItems) {
            if (row.getExamDate() != null) {
                dates.add(row.getExamDate());
            }
        }
        if (!dates.isEmpty()) {
            LocalDate first = Collections.min(dates);
            LocalDate last = Collections.max(dates);
            text.append(" · ").append(first.format(DATE_DISPLAY_FORMAT));
            if (!last.equals(first)) {
                text.append(" to ").append(last.format(DATE_DISPLAY_FORMAT));
            }
        }
        summaryLabel.setText(text.toString());
    }

    /** Accepts "14:30", "2:30 PM", "9:00 AM"; null when the text is not a time. */
    private static LocalTime parseTimeInput(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String cleaned = text.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ENGLISH);
        DateTimeFormatter format = DateTimeFormatter.ofPattern(
                "[hh:mm a][h:mm a][hh:mma][h:mma][HH:mm][H:mm]", Locale.ENGLISH);
        try {
            return LocalTime.parse(cleaned, format);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String formatTimeText(LocalTime time) {
        return time != null ? time.format(TIME_DISPLAY_FORMAT) : "";
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtle");
        return label;
    }

    /** Removes the selected rows from the review; they simply get no date when the sheet is applied. */
    private void handleRemoveSelected() {
        List<DatesheetMatchRow> selected = new ArrayList<>(matchTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            UiKit.toast("Select one or more rows in Matched subjects first.", UiKit.Kind.WARN);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (DatesheetMatchRow row : selected) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(row.getCourseCode());
        }
        boolean sure = UiKit.confirm(window(), "Remove Exams",
                "Remove " + selected.size() + " exam(s) from this datesheet?",
                names + "\nThey will not get a date when you apply. You can add them back with Add exam.",
                "Remove");
        if (!sure) {
            return;
        }
        matchItems.removeAll(selected);
        matchTable.getSelectionModel().clearSelection();
        matchTable.refresh();
        recomputeUnmatched();
        refreshSummaryRange();
        if (cannotApply != null) {
            cannotApply.invalidate();
        }
        UiKit.toast(selected.size() + " exam(s) removed from the review.");
    }

    /**
     * Add (row == null) or edit one row of Matched subjects. The day is derived from the date,
     * so changing the date rewrites the day by itself; nothing is typed by hand.
     */
    private void handleExamRowDialog(DatesheetMatchRow row) {
        List<SubjectInfo> subjects;
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                UiKit.toast("Create or activate a semester first.", UiKit.Kind.WARN);
                return;
            }
            subjects = subjectListService.buildSubjectList(active.getSemesterId());
        } catch (SQLException ex) {
            UiKit.error(window(), "Your subjects could not be read.", ex);
            return;
        }

        final boolean adding = row == null;
        Dialog<DatesheetMatchRow> dialog = new Dialog<>();
        dialog.setTitle(adding ? "Add Exam" : "Edit Exam");
        dialog.setHeaderText(adding ? "Add an exam missing from this datesheet"
                : "Edit " + row.getCourseCode());
        UiKit.styleDialog(dialog);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        ComboBox<String> codeCombo = new ComboBox<>();
        codeCombo.setEditable(true);
        codeCombo.setPromptText("Course code");
        codeCombo.setPrefWidth(240);
        Map<String, String> namesByCode = new LinkedHashMap<>();
        for (SubjectInfo subject : subjects) {
            codeCombo.getItems().add(subject.getSubjectCode());
            namesByCode.put(subject.getSubjectCode().trim().toUpperCase(Locale.ENGLISH),
                    subject.getDisplayName());
        }
        String initialCode = adding ? null : row.getCourseCode();
        codeCombo.getEditor().setText(initialCode != null ? initialCode : "");
        codeCombo.setValue(initialCode);

        TextField nameField = new TextField(!adding && row.getUserSubjectName() != null
                ? row.getUserSubjectName() : "");
        nameField.setPromptText("Subject name");
        nameField.setPrefWidth(240);
        codeCombo.getEditor().textProperty().addListener((obs, oldV, newV) -> {
            if (newV == null || newV.trim().isEmpty()) {
                return;
            }
            String known = namesByCode.get(newV.trim().toUpperCase(Locale.ENGLISH));
            if (known != null && nameField.getText().isBlank()) {
                nameField.setText(known);
            }
        });

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("MST", "EST", "Other");
        typeCombo.setPrefWidth(130);
        String initialType = !adding && row.getExamType() != null && !row.getExamType().isBlank()
                ? row.getExamType()
                : (examTypeCombo.getValue() != null && !examTypeCombo.getValue().isBlank()
                        ? examTypeCombo.getValue() : "MST");
        if (!typeCombo.getItems().contains(initialType)) {
            typeCombo.getItems().add(initialType);
        }
        typeCombo.setValue(initialType);

        DatePicker datePicker = new DatePicker(adding ? null : row.getExamDate());
        datePicker.setPromptText("Exam date");

        Label dayLabel = new Label();
        dayLabel.getStyleClass().add("subtle");
        Runnable showDay = () -> {
            LocalDate date = datePicker.getValue();
            dayLabel.setText(date == null
                    ? "Pick a date"
                    : date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
        };
        datePicker.valueProperty().addListener((obs, oldV, newV) -> showDay.run());
        showDay.run();

        TextField startField = new TextField(adding ? "9:00 AM" : formatTimeText(row.getStartTime()));
        startField.setPromptText("e.g. 9:00 AM or 14:00");
        startField.setPrefWidth(160);
        TextField endField = new TextField(formatTimeText(adding ? null : row.getEndTime()));
        endField.setPromptText("e.g. 12:00 PM (optional)");
        endField.setPrefWidth(160);

        grid.addRow(0, fieldLabel("Course code"), codeCombo);
        grid.addRow(1, fieldLabel("Subject name"), nameField);
        grid.addRow(2, fieldLabel("Exam type"), typeCombo);
        grid.addRow(3, fieldLabel("Date"), datePicker);
        grid.addRow(4, fieldLabel("Day"), dayLabel);
        grid.addRow(5, fieldLabel("Start"), startField);
        grid.addRow(6, fieldLabel("End"), endField);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // OK stays disabled until there is a course code, a date and a readable start time.
        BooleanBinding complete = new BooleanBinding() {
            {
                super.bind(codeCombo.getEditor().textProperty(), datePicker.valueProperty(),
                        startField.textProperty(), endField.textProperty());
            }

            @Override
            protected boolean computeValue() {
                String code = codeCombo.getEditor().getText();
                if (code == null || code.trim().isEmpty()) return false;
                if (datePicker.getValue() == null) return false;
                LocalTime start = parseTimeInput(startField.getText());
                if (start == null) return false;
                String endText = endField.getText();
                if (endText == null || endText.isBlank()) return true;
                LocalTime end = parseTimeInput(endText);
                return end != null && end.isAfter(start);
            }
        };
        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okButton != null) {
            okButton.disableProperty().bind(complete.not());
        }

        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String code = codeCombo.getEditor().getText().trim().toUpperCase(Locale.ENGLISH);
            String name = nameField.getText() != null && !nameField.getText().isBlank()
                    ? nameField.getText().trim() : code;
            String endText = endField.getText();
            LocalTime end = endText == null || endText.isBlank() ? null : parseTimeInput(endText);
            String sheetName = adding ? name
                    : (row.getDatesheetCourseName() != null ? row.getDatesheetCourseName() : name);
            return new DatesheetMatchRow(code, sheetName, name, typeCombo.getValue(),
                    datePicker.getValue(), parseTimeInput(startField.getText()), end,
                    adding ? "Added" : (row.getStatus() != null ? row.getStatus() : "Matched"));
        });

        dialog.showAndWait().ifPresent(edited -> {
            if (adding) {
                matchItems.add(edited);
                matchTable.getSelectionModel().clearAndSelect(matchItems.indexOf(edited));
                UiKit.toast(edited.getCourseCode() + " added to Matched subjects.");
            } else {
                row.setCourseCode(edited.getCourseCode());
                row.setDatesheetCourseName(edited.getDatesheetCourseName());
                row.setUserSubjectName(edited.getUserSubjectName());
                row.setExamType(edited.getExamType());
                row.setExamDate(edited.getExamDate());
                row.setStartTime(edited.getStartTime());
                row.setEndTime(edited.getEndTime());
                matchTable.getSelectionModel().clearAndSelect(matchItems.indexOf(row));
                UiKit.toast(row.getCourseCode() + " updated.");
            }
            matchTable.refresh();
            recomputeUnmatched();
            refreshSummaryRange();
            if (cannotApply != null) {
                cannotApply.invalidate();
            }
        });
    }

    // ------------------------------------------------------------------ analyse

    private void handleAnalyse() {
        runAnalyse(pasteArea.getText(), null);
    }

    private void runAnalyse(String text, String examTypeOverride) {
        if (text == null || text.isBlank()) {
            UiKit.toast("Paste datesheet text into the box before analysing.", UiKit.Kind.WARN);
            return;
        }

        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                summaryLabel.setText("No active semester found.");
                return;
            }

            DatesheetAnalysisResult result = analyserService.analyse(active.getSemesterId(), text, examTypeOverride);
            lastAnalysedText = text;

            matchItems.setAll(result.getMatchedRows());
            analysedMatchCount = result.getMatchedRows().size();
            analysedUnmatched.clear();
            analysedUnmatched.addAll(result.getUnmatchedSubjects());
            recomputeUnmatched();
            setClashes(result.getClashes());
            setNotices(result.getWarnings(), result.getClashes());

            // Store for apply
            lastExamType = result.getExamType();
            lastAmbiguous = result.getAmbiguousUserSubjects();

            // Set exam type combo to the detected (or chosen) type, otherwise ask for one
            syncingExamTypeCombo = true;
            try {
                boolean known = selectExamTypeItem(result.getExamType());
                examTypeNote.setVisible(!known);
                examTypeNote.setManaged(!known);
            } finally {
                syncingExamTypeCombo = false;
            }

            // Build ambiguous resolution panel
            ambiguousResolutionCombos.clear();
            ambiguousPanel.getChildren().removeIf(node -> !(node instanceof Label)); // Keep title and subtitle

            if (lastAmbiguous != null && !lastAmbiguous.isEmpty()) {
                for (Map.Entry<String, List<DatesheetEntry>> entry : lastAmbiguous.entrySet()) {
                    String subjectCode = entry.getKey();
                    List<DatesheetEntry> candidates = entry.getValue();

                    HBox ambigRow = new HBox(10);
                    ambigRow.setAlignment(Pos.CENTER_LEFT);

                    Label codeLabel = new Label(subjectCode);
                    codeLabel.getStyleClass().add("banner-title");
                    codeLabel.setMinWidth(90);

                    ComboBox<String> choiceCombo = new ComboBox<>();
                    choiceCombo.getItems().add("(Skip this course)");
                    for (DatesheetEntry candidate : candidates) {
                        choiceCombo.getItems().add(formatSlot(candidate));
                    }
                    choiceCombo.setValue(choiceCombo.getItems().get(0));
                    choiceCombo.setPrefWidth(320);
                    choiceCombo.valueProperty().addListener((obs, oldV, newV) -> {
                        if (cannotApply != null) {
                            cannotApply.invalidate();
                        }
                    });

                    ambiguousResolutionCombos.put(subjectCode, choiceCombo);
                    ambigRow.getChildren().addAll(codeLabel, choiceCombo);
                    ambiguousPanel.getChildren().add(ambigRow);
                }
                ambiguousPanel.setVisible(true);
                ambiguousPanel.setManaged(true);
            } else {
                ambiguousPanel.setVisible(false);
                ambiguousPanel.setManaged(false);
            }

            StringBuilder summary = new StringBuilder();
            summary.append("Sheet ")
                    .append(result.getSheetCode().isEmpty() ? "code not detected" : result.getSheetCode())
                    .append(" · ")
                    .append(result.getExamType() != null && !result.getExamType().isBlank()
                            ? result.getExamType() : "exam type not chosen");
            lastSummaryPrefix = summary.toString();
            refreshSummaryRange();

            refreshStatusLine();
            if (cannotApply != null) {
                cannotApply.invalidate();
            }

        } catch (SQLException ex) {
            UiKit.error(window(), "The datesheet could not be analysed.", ex);
        }
    }

    private void clearAll() {
        pasteArea.clear();
        matchItems.clear();
        analysedMatchCount = 0;
        analysedUnmatched.clear();
        lastUnmatched.clear();
        setUnmatched(Collections.emptyList());
        setClashes(Collections.emptyList());
        lastSummaryPrefix = null;
        setNotices(Collections.emptyList(), Collections.emptyList());
        summaryLabel.setText("No datesheet analysed yet.");
        statusLabel.setText("");
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        lastExamType = null;
        lastAmbiguous = null;
        lastAnalysedText = null;
        ambiguousResolutionCombos.clear();
        syncingExamTypeCombo = true;
        try {
            examTypeCombo.setValue(null);
        } finally {
            syncingExamTypeCombo = false;
        }
        examTypeNote.setVisible(false);
        examTypeNote.setManaged(false);
        ambiguousPanel.setVisible(false);
        ambiguousPanel.setManaged(false);
        if (cannotApply != null) {
            cannotApply.invalidate();
        }
    }

    // ------------------------------------------------------------------ apply

    private void handleApply() {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) return;
            // A sheet can leave only decisions to make (every subject printed under batch codes).
            if (matchItems.isEmpty() && (lastAmbiguous == null || lastAmbiguous.isEmpty())) return;
            int semId = active.getSemesterId();

            // Use the exam type from the ComboBox (user can override detected type)
            String selectedExamType = examTypeCombo.getValue();
            if (selectedExamType == null || selectedExamType.isBlank()) {
                UiKit.toast("Choose which exam type these dates belong to before applying.", UiKit.Kind.WARN);
                return;
            }
            final String examType = selectedExamType;

            // Build resolutions map and skipped set from ambiguous panel
            Map<String, DatesheetEntry> resolutions = new HashMap<>();
            Set<String> skipped = new HashSet<>();

            if (lastAmbiguous != null && !lastAmbiguous.isEmpty()) {
                for (Map.Entry<String, List<DatesheetEntry>> ambigEntry : lastAmbiguous.entrySet()) {
                    String code = ambigEntry.getKey();
                    List<DatesheetEntry> candidates = ambigEntry.getValue();
                    ComboBox<String> combo = ambiguousResolutionCombos.get(code);

                    if (combo != null && combo.getValue() != null) {
                        int selectedIndex = combo.getSelectionModel().getSelectedIndex();
                        if (selectedIndex == 0) {
                            // First item is "(Skip this course)"
                            skipped.add(code);
                        } else if (selectedIndex > 0 && selectedIndex <= candidates.size()) {
                            // User chose a specific candidate; a slot with no date cannot be applied.
                            DatesheetEntry chosen = candidates.get(selectedIndex - 1);
                            if (chosen.getDate() == null) {
                                skipped.add(code);
                            } else {
                                resolutions.put(code, chosen);
                            }
                        }
                    }
                }
            }

            List<DatesheetMatchRow> rows = new ArrayList<>(matchItems);

            // Update exam type in all rows to match the selected type
            for (DatesheetMatchRow row : rows) {
                row.setExamType(examType);
            }

            // Apply updates to DB; the service derives the window from the exams it dates.
            Set<String> notOnSheet = new HashSet<>();
            for (SubjectInfo s : lastUnmatched) {
                if (s.getSubjectCode() != null) notOnSheet.add(s.getSubjectCode());
            }
            analyserService.apply(semId, rows, null, null, resolutions, skipped, examType, notOnSheet);

            // Show the window the exams will actually span: first to last of MY dated exams
            // (the sheet lists every batch, so its own range is not the user's exam period).
            LocalDate[] userWindow = appliedWindow(rows, resolutions);
            final String windowMsg = buildWindowMessage(examType,
                    userWindow != null ? userWindow[0] : null,
                    userWindow != null ? userWindow[1] : null);

            Runnable afterReplan = () -> {
                if (refreshAll != null) {
                    refreshAll.run();
                }

                // Check for capacity deficit in the newly dated exam period
                try {
                    LocalDate today = LocalDate.now();
                    if (capacityService != null && capacityService.isBatchDated(semId, examType)) {
                        double req = capacityService.getRequiredHours(semId, examType, today);
                        double avail = capacityService.getAvailableHours(semId, examType, today);

                        if (req > avail + 0.05) {
                            Alert deficitAlert = new Alert(Alert.AlertType.CONFIRMATION);
                            UiKit.styleDialog(deficitAlert);
                            deficitAlert.setTitle("Exam Period Capacity Negotiation");
                            deficitAlert.setHeaderText("Datesheet applied, but study demand exceeds available capacity.");
                            deficitAlert.setContentText(String.format(
                                    "Your exams have been updated with exact dates.%n%n" +
                                    "Required study hours (%s) exceed available free study hours (%s) in the %s window.%n%n" +
                                    "Would you like to negotiate capacity and prioritise topics now?%n%n%s",
                                    DurationFormat.format(req), DurationFormat.format(avail), examType, windowMsg
                            ));
                            ButtonType btnNegotiate = new ButtonType("Negotiate Scope", ButtonBar.ButtonData.YES);
                            ButtonType btnLater = new ButtonType("Keep as Deficit", ButtonBar.ButtonData.CANCEL_CLOSE);
                            deficitAlert.getButtonTypes().setAll(btnNegotiate, btnLater);

                            Optional<ButtonType> choice = deficitAlert.showAndWait();
                            if (choice.isPresent() && choice.get() == btnNegotiate) {
                                CapacityNegotiationDialogHelper.runNegotiationLoop(
                                        semId, examType, today, capacityService, replanService,
                                        () -> TopicTrimmingDialog.show(semId, examType, topicDAO, examDAO,
                                                capacityService, replanService, replanRunner, refreshAll),
                                        refreshAll
                                );
                            }
                            return;
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                UiKit.toast("Datesheet applied. " + windowMsg);
            };

            if (replanRunner != null) {
                replanRunner.run(semId, LocalDate.now(), afterReplan, null);
            } else if (replanService != null) {
                replanService.regenerateSemesterPlan(semId, LocalDate.now());
                afterReplan.run();
            } else {
                afterReplan.run();
            }

        } catch (SQLException ex) {
            UiKit.error(window(), "The datesheet could not be applied to your exams.", ex);
        }
    }

    /** Selects the combo item matching the detected type; false when the type is unknown. */
    private boolean selectExamTypeItem(String detected) {
        if (detected == null || detected.isBlank()) {
            examTypeCombo.setValue(null);
            return false;
        }
        for (String item : examTypeCombo.getItems()) {
            if (item.equalsIgnoreCase(detected)) {
                examTypeCombo.setValue(item);
                return true;
            }
        }
        examTypeCombo.setValue(null);
        return false;
    }

    /** Formats a candidate slot for the decision combo, e.g. "Mon 24 Aug 2026, 17:30". */
    private String formatSlot(DatesheetEntry entry) {
        if (entry.getDate() == null) {
            return entry.getStart() != null
                    ? "Date unknown, " + entry.getStart().format(SLOT_TIME_FORMAT) + " - skip"
                    : "Date unknown - skip";
        }
        String time = entry.getStart() != null ? entry.getStart().format(SLOT_TIME_FORMAT) : "time unknown";
        return entry.getDate().format(SLOT_DATE_FORMAT) + ", " + time;
    }

    /** First to last date of the exams this apply writes, or null when nothing gets a date. */
    private LocalDate[] appliedWindow(List<DatesheetMatchRow> rows, Map<String, DatesheetEntry> resolutions) {
        List<LocalDate> dates = new ArrayList<>();
        if (rows != null) {
            for (DatesheetMatchRow row : rows) {
                if (row.getExamDate() != null) {
                    dates.add(row.getExamDate());
                }
            }
        }
        if (resolutions != null) {
            for (DatesheetEntry entry : resolutions.values()) {
                if (entry != null && entry.getDate() != null) {
                    dates.add(entry.getDate());
                }
            }
        }
        if (dates.isEmpty()) {
            return null;
        }
        return new LocalDate[]{Collections.min(dates), Collections.max(dates)};
    }

    private String buildWindowMessage(String examType, LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return "";
        }
        return String.format("Exam window %s: %s to %s. Weekly classes are not followed on these days.",
                examType != null ? examType : "exam",
                start.format(DATE_DISPLAY_FORMAT),
                end.format(DATE_DISPLAY_FORMAT));
    }
}
