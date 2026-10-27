package com.learntrack.ui.forms;

import com.learntrack.model.Exam;
import com.learntrack.model.SubjectInfo;
import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.SyllabusUnit;
import com.learntrack.model.Topic;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DateUtils;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Represents a single exam row for MST/EST/Other evaluations.
 * Reusable UI component for the Exams tab.
 */
public class ExamRowView {

    private final SubjectInfo subjectInfo;
    private final String examType;
    private final Consumer<ExamRowView> onSave;
    private final Consumer<ExamRowView> onDelete;
    private final double defaultPyqHours;
    private final boolean isNormalMode; // true for NORMAL exams with evalName

    private VBox root;
    private TextField evalNameField; // Only for NORMAL exams
    private DatePicker examDatePicker;
    private TextField startTimeField;
    private TextField endTimeField;
    private Label tentativeLabel;
    private Label dateErrorLabel;
    private Spinner<Double> creditsSpinner;
    private TextField evalMarksField;
    private Spinner<Integer> travelTimeSpinner;
    private Spinner<Double> pyqHoursSpinner;
    private Button saveButton;
    private Button deleteButton;
    private Button syllabusButton;
    private HBox scopeWarningBadge;
    private HBox dateTbdBadge;
    private Label statusBadge;
    private TitledPane moreDetailsPane;
    private ExamScopeView examScopeView;

    private Exam existingExam = null;
    private BooleanProperty hasExamProperty = new SimpleBooleanProperty(false);
    private boolean datesheetMode = false; // Set externally based on type-level checkbox

    public ExamRowView(SubjectInfo subjectInfo, String examType, Consumer<ExamRowView> onSave,
                       Consumer<ExamRowView> onDelete, double defaultPyqHours) {
        this.subjectInfo = subjectInfo;
        this.examType = examType;
        this.onSave = onSave;
        this.onDelete = onDelete;
        this.defaultPyqHours = defaultPyqHours;
        this.isNormalMode = "NORMAL".equalsIgnoreCase(examType);
        buildUI();
    }

    private void buildUI() {
        root = new VBox(6);
        root.getStyleClass().add("card-flat");

        dateTbdBadge = UiKit.status(UiKit.Kind.WARN, "Date TBD");
        dateTbdBadge.setVisible(false);
        dateTbdBadge.setManaged(false);

        // Header row: subject name, code, badge (only for MST/EST, not for NORMAL sub-rows)
        if (!isNormalMode) {
            HBox headerRow = new HBox(8);
            headerRow.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(subjectInfo.getDisplayName());
            nameLabel.getStyleClass().add("section-title");

            Label codeLabel = new Label("[" + subjectInfo.getSubjectCode() + "]");
            codeLabel.getStyleClass().add("subtle");

            // Credits / marks are plain data, not a status: keep them as text.
            Label badgeLabel = new Label(subjectInfo.getBadge());
            badgeLabel.getStyleClass().add("subtle");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            headerRow.getChildren().addAll(nameLabel, codeLabel, badgeLabel, dateTbdBadge, spacer);
            root.getChildren().add(headerRow);
        }

        // Main fields row
        GridPane fieldsGrid = new GridPane();
        fieldsGrid.setHgap(8);
        fieldsGrid.setVgap(6);
        fieldsGrid.setPadding(new Insets(6, 0, 0, 0));

        int rowIndex = 0;

        // For NORMAL exams, add eval name field first
        if (isNormalMode) {
            evalNameField = new TextField();
            evalNameField.setPromptText("Evaluation name (required)");
            evalNameField.setPrefWidth(200);
            fieldsGrid.add(new Label("Evaluation Name:"), 0, rowIndex);
            fieldsGrid.add(evalNameField, 1, rowIndex, 3, 1);
            fieldsGrid.add(dateTbdBadge, 4, rowIndex);
            rowIndex++;
        }

        // Date/time fields (MST/EST/NORMAL)
        examDatePicker = new DatePicker();
        examDatePicker.setPrefWidth(140);

        startTimeField = new TextField("10:00");
        startTimeField.setPrefWidth(70);
        startTimeField.setPromptText("HH:mm");

        endTimeField = new TextField("12:00");
        endTimeField.setPrefWidth(70);
        endTimeField.setPromptText("HH:mm");

        // Tentative label for MST/EST when datesheet not announced
        if (!isNormalMode) {
            tentativeLabel = new Label();
            tentativeLabel.getStyleClass().add("hint");
            tentativeLabel.setWrapText(true);
            tentativeLabel.setMaxWidth(400);

            dateErrorLabel = new Label();
            dateErrorLabel.getStyleClass().add("text-danger");
            dateErrorLabel.setVisible(false);
            dateErrorLabel.setManaged(false);
        }

        fieldsGrid.add(new Label("Exam Date:"), 0, rowIndex);
        fieldsGrid.add(examDatePicker, 1, rowIndex);
        fieldsGrid.add(new Label("Start:"), 2, rowIndex);
        fieldsGrid.add(startTimeField, 3, rowIndex);
        fieldsGrid.add(new Label("End:"), 4, rowIndex);
        fieldsGrid.add(endTimeField, 5, rowIndex);
        rowIndex++;

        // Add tentative label row for MST/EST
        if (!isNormalMode && tentativeLabel != null) {
            fieldsGrid.add(tentativeLabel, 0, rowIndex, 6, 1);
            rowIndex++;
            fieldsGrid.add(dateErrorLabel, 0, rowIndex, 6, 1);
        }

        // More details (collapsed)
        GridPane detailsGrid = new GridPane();
        detailsGrid.setHgap(8);
        detailsGrid.setVgap(6);
        detailsGrid.setPadding(new Insets(6));

        double initialCredits = (subjectInfo != null && subjectInfo.getCredits() > 0) ? subjectInfo.getCredits() : 3.0;
        creditsSpinner = new Spinner<>(0.5, 10.0, initialCredits, 0.5);
        creditsSpinner.setEditable(true);
        creditsSpinner.setPrefWidth(80);

        evalMarksField = new TextField("20.0");
        evalMarksField.setPrefWidth(80);

        travelTimeSpinner = new Spinner<>(0, 180, 30, 15);
        travelTimeSpinner.setPrefWidth(80);

        pyqHoursSpinner = new Spinner<>(0.0, 20.0, defaultPyqHours, 0.5);
        pyqHoursSpinner.setEditable(true);
        pyqHoursSpinner.setPrefWidth(80);

        detailsGrid.add(new Label("Credits:"), 0, 0);
        detailsGrid.add(creditsSpinner, 1, 0);
        detailsGrid.add(new Label("Eval Marks:"), 2, 0);
        detailsGrid.add(evalMarksField, 3, 0);
        detailsGrid.add(new Label("Travel (min):"), 0, 1);
        detailsGrid.add(travelTimeSpinner, 1, 1);
        detailsGrid.add(new Label("PYQ Hours:"), 2, 1);
        detailsGrid.add(pyqHoursSpinner, 3, 1);

        moreDetailsPane = new TitledPane("More details", detailsGrid);
        moreDetailsPane.setExpanded(false);

        // Buttons
        HBox buttonBar = new HBox(8);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(6, 0, 0, 0));

        statusBadge = new Label();
        statusBadge.getStyleClass().add("badge");

        saveButton = UiKit.primaryButton("Save");
        saveButton.setOnAction(e -> onSave.accept(this));

        deleteButton = UiKit.dangerTextButton("Delete");
        deleteButton.setOnAction(e -> onDelete.accept(this));
        deleteButton.visibleProperty().bind(hasExamProperty);
        deleteButton.managedProperty().bind(hasExamProperty);

        syllabusButton = UiKit.secondaryButton("Syllabus (0 of 0)");
        syllabusButton.disableProperty().bind(hasExamProperty.not());
        syllabusButton.setTooltip(new Tooltip("Save the exam first"));

        scopeWarningBadge = UiKit.status(UiKit.Kind.WARN, "No scope");
        scopeWarningBadge.setVisible(false);
        scopeWarningBadge.setManaged(false);

        buttonBar.getChildren().addAll(statusBadge, scopeWarningBadge, UiKit.spacer(),
                saveButton, deleteButton, syllabusButton);

        root.getChildren().addAll(fieldsGrid, moreDetailsPane, buttonBar);

        updateFieldVisibility();
    }

    /**
     * Set datesheet mode for this row (controlled by type-level checkbox).
     */
    public void setDatesheetMode(boolean datesheetMode) {
        this.datesheetMode = datesheetMode;
        updateFieldVisibility();
    }

    private void updateFieldVisibility() {
        boolean isUndated = (existingExam != null && existingExam.getExamDate() == null);
        if (dateTbdBadge != null) {
            dateTbdBadge.setVisible(isUndated);
            dateTbdBadge.setManaged(isUndated);
        }

        if (isNormalMode) {
            // NORMAL exams always show date/time fields
            return;
        }

        // MST/EST: show fields based on type-level datesheet mode
        examDatePicker.setVisible(datesheetMode);
        examDatePicker.setManaged(datesheetMode);
        startTimeField.setVisible(datesheetMode);
        startTimeField.setManaged(datesheetMode);
        endTimeField.setVisible(datesheetMode);
        endTimeField.setManaged(datesheetMode);

        // Labels visibility
        if (examDatePicker.getParent() instanceof GridPane) {
            GridPane grid = (GridPane) examDatePicker.getParent();
            grid.getChildren().stream()
                .filter(n -> n instanceof Label && GridPane.getColumnIndex(n) != null)
                .forEach(n -> {
                    int col = GridPane.getColumnIndex(n);
                    if (col == 0 || col == 2 || col == 4) { // "Exam Date:", "Start:", "End:" labels
                        n.setVisible(datesheetMode);
                        n.setManaged(datesheetMode);
                    }
                });
        }

        // Update tentative label
        if (tentativeLabel != null) {
            tentativeLabel.setVisible(!datesheetMode);
            tentativeLabel.setManaged(!datesheetMode);
            
            if (!datesheetMode && existingExam != null) {
                LocalDate tentativeDate = existingExam.getExamDate();
                if (tentativeDate != null && existingExam.isExamDateTentative()) {
                    tentativeLabel.setText(ExamRowHelper.formatTentativeDisplay(tentativeDate));
                } else if (existingExam.getWindowStartDate() != null) {
                    tentativeLabel.setText(ExamRowHelper.formatTentativeDisplay(existingExam.getWindowStartDate()));
                } else {
                    tentativeLabel.setText("Date TBD (planned to +60 days)");
                }
            }
        }        // Hide error label initially
        if (dateErrorLabel != null) {
            dateErrorLabel.setVisible(false);
            dateErrorLabel.setManaged(false);
        }
        updateStatusBadge();
    }

    /** One text status per row, never colour alone. */
    private void updateStatusBadge() {
        if (statusBadge == null) {
            return;
        }
        if (existingExam == null) {
            UiKit.setBadge(statusBadge, "Not saved", UiKit.Kind.INFO);
        } else if (existingExam.getExamDate() == null || existingExam.isExamDateTentative()) {
            UiKit.setBadge(statusBadge, "Tentative date", UiKit.Kind.WARN);
        } else if (datesheetMode) {
            UiKit.setBadge(statusBadge, "Dated", UiKit.Kind.SUCCESS);
        } else {
            UiKit.setBadge(statusBadge, "In timetable", UiKit.Kind.SUCCESS);
        }
    }

    public void setExam(Exam exam) {
        this.existingExam = exam;
        hasExamProperty.set(exam != null);

        if (exam == null) {
            clearFields();
            return;
        }

        // Populate fields
        if (isNormalMode && evalNameField != null) {
            evalNameField.setText(exam.getEvalName() != null ? exam.getEvalName() : "");
        }

        creditsSpinner.getValueFactory().setValue(exam.getCredits());
        evalMarksField.setText(String.valueOf(exam.getEvalMarks()));
        pyqHoursSpinner.getValueFactory().setValue(exam.getPyqRemainingHours());
        travelTimeSpinner.getValueFactory().setValue(exam.getTravelTimeMinutes());

        examDatePicker.setValue(exam.getExamDate());
        startTimeField.setText(exam.getExamStartTime() != null ? DateUtils.formatTime(exam.getExamStartTime()) : "10:00");
        endTimeField.setText(exam.getExamEndTime() != null ? DateUtils.formatTime(exam.getExamEndTime()) : "12:00");

        updateFieldVisibility();
    }

    private void clearFields() {
        if (isNormalMode && evalNameField != null) {
            evalNameField.clear();
        }
        double defCredits = (subjectInfo != null && subjectInfo.getCredits() > 0) ? subjectInfo.getCredits() : 3.0;
        creditsSpinner.getValueFactory().setValue(defCredits);
        evalMarksField.setText("20.0");
        pyqHoursSpinner.getValueFactory().setValue(defaultPyqHours);
        travelTimeSpinner.getValueFactory().setValue(30);
        examDatePicker.setValue(null);
        startTimeField.setText("10:00");
        endTimeField.setText("12:00");
        if (scopeWarningBadge != null) {
            scopeWarningBadge.setVisible(false);
            scopeWarningBadge.setManaged(false);
        }
        if (dateTbdBadge != null) {
            dateTbdBadge.setVisible(false);
            dateTbdBadge.setManaged(false);
        }
        updateFieldVisibility();
    }

    public VBox getView() {
        return root;
    }

    public SubjectInfo getSubjectInfo() {
        return subjectInfo;
    }

    public String getExamType() {
        return examType;
    }

    public Exam getExistingExam() {
        return existingExam;
    }

    public String getEvalName() {
        if (isNormalMode && evalNameField != null) {
            return evalNameField.getText().trim();
        }
        return null;
    }

    public double getCredits() throws Exception {
        try {
            creditsSpinner.commitValue();
            return creditsSpinner.getValue();
        } catch (Exception ex) {
            return Double.parseDouble(creditsSpinner.getEditor().getText().trim());
        }
    }

    public double getEvalMarks() {
        return Double.parseDouble(evalMarksField.getText().trim());
    }

    public double getPyqHours() {
        return pyqHoursSpinner.getValue();
    }

    public int getTravelMinutes() {
        return travelTimeSpinner.getValue();
    }

    public LocalDate getExamDate() {
        return examDatePicker.getValue();
    }

    public LocalTime getStartTime() {
        return DateUtils.parseTime(startTimeField.getText());
    }

    public LocalTime getEndTime() {
        return DateUtils.parseTime(endTimeField.getText());
    }

    /**
     * Validate date fields (for datesheet mode) and show inline error.
     * @return true if valid, false otherwise
     */
    public boolean validateDateFields() {
        if (!datesheetMode || isNormalMode) {
            return true; // No validation in tentative mode
        }

        LocalDate examDate = examDatePicker.getValue();
        LocalTime startTime = DateUtils.parseTime(startTimeField.getText());
        LocalTime endTime = DateUtils.parseTime(endTimeField.getText());

        String error = ExamRowHelper.validateDateFields(examDate, startTime, endTime);
        
        if (dateErrorLabel != null) {
            if (error != null) {
                dateErrorLabel.setText(error);
                dateErrorLabel.setVisible(true);
                dateErrorLabel.setManaged(true);
                return false;
            } else {
                dateErrorLabel.setVisible(false);
                dateErrorLabel.setManaged(false);
                return true;
            }
        }

        return error == null;
    }

    /**
     * Check if this exam is in datesheet mode.
     */
    public boolean isDatesheetMode() {
        return datesheetMode;
    }

    /**
     * @deprecated Window dates are now managed at the exam type level for MST/EST.
     * Returns null for MST/EST rows as windows are set at type level.
     */
    @Deprecated
    public LocalDate getWindowStart() {
        return null;
    }

    /**
     * @deprecated Window dates are now managed at the exam type level for MST/EST.
     * Returns null for MST/EST rows as windows are set at type level.
     */
    @Deprecated
    public LocalDate getWindowEnd() {
        return null;
    }

    public void bindButtonsToReplanRunner(javafx.beans.property.ReadOnlyBooleanProperty busyProperty) {
        saveButton.disableProperty().bind(busyProperty);
        deleteButton.disableProperty().bind(busyProperty);
    }

    public void setSyllabusButtonHandler(Consumer<ExamRowView> handler) {
        syllabusButton.setOnAction(e -> handler.accept(this));
    }

    public void updateSyllabusButtonLabel(int selectedCount, int totalCount) {
        if (selectedCount == 0) {
            syllabusButton.setText("Add from syllabus");
            if (hasExamProperty.get()) {
                syllabusButton.setTooltip(new Tooltip("Select syllabus scope for this exam"));
                if (scopeWarningBadge != null) {
                    scopeWarningBadge.setVisible(true);
                    scopeWarningBadge.setManaged(true);
                }
            } else {
                syllabusButton.setTooltip(new Tooltip("Save this exam first, then add syllabus"));
                if (scopeWarningBadge != null) {
                    scopeWarningBadge.setVisible(false);
                    scopeWarningBadge.setManaged(false);
                }
            }
        } else {
            syllabusButton.setText("Edit syllabus (" + selectedCount + " of " + totalCount + ")");
            syllabusButton.setTooltip(new Tooltip(selectedCount + " subtopics selected"));
            if (scopeWarningBadge != null) {
                scopeWarningBadge.setVisible(false);
                scopeWarningBadge.setManaged(false);
            }
        }
    }

    public void setScopeWarning(boolean showWarning) {
        if (scopeWarningBadge != null) {
            scopeWarningBadge.setVisible(showWarning);
            scopeWarningBadge.setManaged(showWarning);
        }
    }

    public boolean isScopeWarningVisible() {
        return scopeWarningBadge != null && scopeWarningBadge.isVisible();
    }

    public void setDateTbd(boolean isTbd) {
        if (dateTbdBadge != null) {
            dateTbdBadge.setVisible(isTbd);
            dateTbdBadge.setManaged(isTbd);
        }
    }

    public boolean isDateTbd() {
        return dateTbdBadge != null && dateTbdBadge.isVisible();
    }

    /**
     * Update the ExamScopeView with loaded scope data.
     * @param selectedSubtopics List of selected subtopics for this exam
     * @param allUnits All units for the subject
     * @param unitTotals Map of unitId -> total subtopic count
     * @param subtopicToTopic Map of subtopicId -> Topic (null if no topic linked)
     */
    public void setScopeData(List<SyllabusSubtopic> selectedSubtopics, 
                             List<SyllabusUnit> allUnits,
                             Map<Integer, Integer> unitTotals,
                             Map<Integer, Topic> subtopicToTopic) {
        // Remove existing scope view if present
        root.getChildren().removeIf(n -> n instanceof ExamScopeView);

        if (examScopeView == null) {
            examScopeView = new ExamScopeView(
                v -> {
                    if (syllabusButton.getOnAction() != null) {
                        syllabusButton.fire();
                    }
                },
                hasExamProperty.get()
            );
        }

        examScopeView.setScope(selectedSubtopics, allUnits, unitTotals, subtopicToTopic);
        
        // Insert before button bar (last element)
        int insertIndex = root.getChildren().size() - 1;
        if (insertIndex < 0) insertIndex = 0;
        root.getChildren().add(insertIndex, examScopeView);
    }

    /**
     * Refresh the scope view with current data (keeps filters/sort state).
     */
    public void refreshScopeView(List<SyllabusSubtopic> selectedSubtopics, 
                                  List<SyllabusUnit> allUnits,
                                  Map<Integer, Integer> unitTotals,
                                  Map<Integer, Topic> subtopicToTopic) {
        if (examScopeView != null) {
            examScopeView.setScope(selectedSubtopics, allUnits, unitTotals, subtopicToTopic);
            examScopeView.refreshView();
        } else {
            setScopeData(selectedSubtopics, allUnits, unitTotals, subtopicToTopic);
        }
    }
}
