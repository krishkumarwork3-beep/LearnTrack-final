package com.learntrack.ui.forms;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.service.ExamPeriodCapacityService;
import com.learntrack.service.ExamProvisioningService;
import com.learntrack.service.ExamTimelineService;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.service.SubjectListService;
import com.learntrack.ui.UiKit;
import com.learntrack.ui.setup.CapacityNegotiationDialogHelper;
import com.learntrack.ui.setup.TopicTrimmingDialog;
import com.learntrack.util.DateUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Controller/View for managing exams (MST, EST, and Other evaluations).
 * Redesigned with per-subject sections.
 * Plan Section 3, 4, 14.
 */
public class ExamFormController {

    private final ExamDAO examDAO;
    private final TimetableDAO timetableDAO;
    private final SemesterDAO semesterDAO;
    private final ExamPeriodRoutineDAO routineDAO;
    private final SemesterReplanService replanService;
    private final SettingsDAO settingsDAO;
    private final ExamPeriodCapacityService capacityService;
    private final Runnable onDataChanged;
    private final Runnable onTrimRequested;
    private final com.learntrack.ui.ReplanRunner replanRunner;

    private final ExamScopeDAO examScopeDAO;
    private final SyllabusDAO syllabusDAO;
    private final TopicDAO topicDAO;
    private final ExamTimelineService timelineService;
    private final ExamTimelineHelper timelineHelper;
    private final ExamProvisioningService provisioningService;

    private VBox mstSection;
    private VBox estSection;
    private VBox otherSection;
    private TitledPane mstPane;
    private TitledPane estPane;
    private TitledPane otherPane;
    private Label mstTimelineError;
    private Label estTimelineError;

    private double defaultPyqHours = 2.0;

    // Constructors with backward compatibility
    public ExamFormController(ExamDAO examDAO, SemesterDAO semesterDAO,
                              ExamPeriodRoutineDAO routineDAO, SemesterReplanService replanService,
                              SettingsDAO settingsDAO, ExamPeriodCapacityService capacityService,
                              Runnable onDataChanged) {
        this(examDAO, null, semesterDAO, routineDAO, replanService, settingsDAO, capacityService, onDataChanged, null, null);
    }

    public ExamFormController(ExamDAO examDAO, SemesterDAO semesterDAO,
                              ExamPeriodRoutineDAO routineDAO, SemesterReplanService replanService,
                              SettingsDAO settingsDAO, ExamPeriodCapacityService capacityService,
                              Runnable onDataChanged, Runnable onTrimRequested) {
        this(examDAO, null, semesterDAO, routineDAO, replanService, settingsDAO, capacityService, onDataChanged, onTrimRequested, null);
    }

    public ExamFormController(ExamDAO examDAO, SemesterDAO semesterDAO,
                              ExamPeriodRoutineDAO routineDAO, SemesterReplanService replanService,
                              SettingsDAO settingsDAO, ExamPeriodCapacityService capacityService,
                              Runnable onDataChanged, Runnable onTrimRequested,
                              com.learntrack.ui.ReplanRunner replanRunner) {
        this(examDAO, null, semesterDAO, routineDAO, replanService, settingsDAO, capacityService, onDataChanged, onTrimRequested, replanRunner);
    }

    public ExamFormController(ExamDAO examDAO, TimetableDAO timetableDAO, SemesterDAO semesterDAO,
                              ExamPeriodRoutineDAO routineDAO, SemesterReplanService replanService,
                              SettingsDAO settingsDAO, ExamPeriodCapacityService capacityService,
                              Runnable onDataChanged, Runnable onTrimRequested,
                              com.learntrack.ui.ReplanRunner replanRunner) {
        this.examDAO = examDAO;
        this.timetableDAO = timetableDAO;
        this.semesterDAO = semesterDAO;
        this.routineDAO = routineDAO;
        this.replanService = replanService;
        this.settingsDAO = settingsDAO;
        this.capacityService = capacityService;
        this.onDataChanged = onDataChanged;
        this.onTrimRequested = onTrimRequested;
        this.replanRunner = replanRunner;

        this.examScopeDAO = new ExamScopeDAO();
        this.syllabusDAO = new SyllabusDAO();
        this.topicDAO = new TopicDAO();
        this.timelineService = new ExamTimelineService();
        this.timelineHelper = new ExamTimelineHelper(timelineService);
        this.provisioningService = new ExamProvisioningService();

        try {
            if (settingsDAO != null) {
                defaultPyqHours = settingsDAO.getDouble("pyq_practice_hours", 2.0);
            }
        } catch (Exception ignored) {
        }
    }

    public Node getView() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));

        HBox header = UiKit.pageHeader("Exams",
                "Credits and marks are entered upfront for every exam. For MST/EST without a datesheet, "
                        + "give the rough window; exact dates unlock when the sheet is announced.",
                null);

        // Create sections
        mstSection = new VBox(8);
        estSection = new VBox(8);
        otherSection = new VBox(8);

        mstPane = new TitledPane("MST (Mid-Semester Tests)", mstSection);
        mstPane.setExpanded(true);

        estPane = new TitledPane("EST (End-Semester Tests)", estSection);
        estPane.setExpanded(false);

        otherPane = new TitledPane("Other Evaluations (Quizzes, Assignments, etc.)", otherSection);
        otherPane.setExpanded(false);

        VBox sectionsContainer = new VBox(8);
        sectionsContainer.getChildren().addAll(mstPane, estPane, otherPane);

        ScrollPane scrollPane = new ScrollPane(sectionsContainer);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root.getChildren().addAll(header, scrollPane);

        loadExams();
        return root;
    }

    private VBox createTimelineCard(int semesterId, String examType) throws SQLException {
        VBox card = new VBox(10);
        card.getStyleClass().addAll("card", "timeline-card");

        // Check datesheet announced state
        boolean datesheetAnnounced = new com.learntrack.dao.ExamTimelineDAO().isDatesheetAnnounced(semesterId, examType);
        Optional<LocalDate[]> storedWindow = new com.learntrack.dao.ExamTimelineDAO().getWindow(semesterId, examType);

        // Datesheet checkbox
        CheckBox datesheetCheck = new CheckBox("Datesheet announced");
        datesheetCheck.setSelected(datesheetAnnounced);
        datesheetCheck.getStyleClass().add("check-strong");

        // Title and status chip
        String chipText = datesheetAnnounced ? "From datesheet" : "Tentative";
        UiKit.Kind chipKind = datesheetAnnounced ? UiKit.Kind.INFO : UiKit.Kind.WARN;

        // If datesheet announced and we have exact dates, show count
        if (datesheetAnnounced) {
            int datedCount = timelineService.countDatedExams(semesterId, examType);
            int totalCount = timelineService.countTotalExams(semesterId, examType);
            boolean allDated = (datedCount == totalCount && totalCount > 0);
            
            if (allDated) {
                chipText = "Confirmed";
                chipKind = UiKit.Kind.SUCCESS;
            } else if (datedCount > 0) {
                chipText = "Partial: " + datedCount + " of " + totalCount + " dated";
                chipKind = UiKit.Kind.WARN;
            }
        }

        Label statusChip = UiKit.chip(chipText, chipKind);

        HBox titleRow = new HBox(12);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getChildren().addAll(datesheetCheck, statusChip);

        // Helper text (only in tentative mode)
        Label helper = UiKit.hint("Rough idea of when all " + examType
                + " exams happen. Until the datesheet is announced, every subject is assumed to have its exam on the start date, "
                + "and your normal study timetable is paused during this window.");
        helper.setVisible(!datesheetAnnounced);
        helper.setManaged(!datesheetAnnounced);

        // Date row
        HBox dateRow = new HBox(12);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        DatePicker startPicker = new DatePicker();
        startPicker.setPrefWidth(140);
        DatePicker endPicker = new DatePicker();
        endPicker.setPrefWidth(140);

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("text-danger");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // Store error label reference
        if ("MST".equals(examType)) {
            mstTimelineError = errorLabel;
        } else {
            estTimelineError = errorLabel;
        }

        if (datesheetAnnounced) {
            // MODE 2: Datesheet mode - read-only. Prefer the window announced by the datesheet
            // itself; falling back to the span of the entered exam dates keeps the old display.
            LocalDate[] derived = timelineService.getDerivedWindow(semesterId, examType);
            LocalDate[] shown = storedWindow.isPresent() ? storedWindow.get() : derived;
            if (shown != null) {
                startPicker.setValue(shown[0]);
                endPicker.setValue(shown[1]);
            }
            startPicker.setDisable(true);
            endPicker.setDisable(true);

            if (shown == null) {
                // No exact dates yet
                dateRow.getChildren().add(UiKit.hint("Enter each subject's exact date below"));
            } else {
                Label windowCaption = new Label(storedWindow.isPresent() ? "Sheet window:" : "Exams span:");
                windowCaption.getStyleClass().add("stat-label");
                Label dash = new Label("-");
                dash.getStyleClass().add("subtle");
                dateRow.getChildren().addAll(
                    windowCaption, startPicker, dash, endPicker
                );
            }
            
            // Add "Enter all dates" button
            Button enterAllDatesBtn = UiKit.primaryButton("Enter all dates");
            enterAllDatesBtn.setOnAction(e -> showEnterAllDatesDialog(semesterId, examType));
            dateRow.getChildren().add(enterAllDatesBtn);
        } else {
            // MODE 1: Tentative mode - editable with Save button
            if (storedWindow.isPresent()) {
                startPicker.setValue(storedWindow.get()[0]);
                endPicker.setValue(storedWindow.get()[1]);
            }

            Button saveBtn = UiKit.primaryButton("Save");

            saveBtn.setOnAction(e -> {
                LocalDate start = startPicker.getValue();
                LocalDate end = endPicker.getValue();

                errorLabel.setVisible(false);
                errorLabel.setManaged(false);

                if (start == null || end == null) {
                    errorLabel.setText("Both start and end dates are required");
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                    return;
                }

                if (end.isBefore(start)) {
                    errorLabel.setText("End date must be >= start date");
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                    return;
                }

                try {
                    timelineService.saveTentativeWindow(semesterId, examType, start, end);
                    
                    // Replan off FX thread
                    Runnable afterSave = () -> {
                        javafx.application.Platform.runLater(() -> {
                            loadExams();
                            if (onDataChanged != null) {
                                onDataChanged.run();
                            }
                        });
                    };

                    if (replanRunner != null) {
                        replanRunner.run(semesterId, LocalDate.now(), afterSave, null);
                    } else {
                        new Thread(() -> {
                            try {
                                replanService.regenerateSemesterPlan(semesterId, LocalDate.now());
                                javafx.application.Platform.runLater(afterSave);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }).start();
                    }
                } catch (Exception ex) {
                    errorLabel.setText("Error saving: " + ex.getMessage());
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                }
            });

            dateRow.getChildren().addAll(
                new Label("Start:"), startPicker,
                new Label("End:"), endPicker,
                saveBtn
            );
        }

        // Checkbox handler
        datesheetCheck.setOnAction(e -> {
            boolean newState = datesheetCheck.isSelected();
            
            if (!newState) {
                // Switching back to tentative - confirm
                boolean confirmed = UiKit.confirm(window(), "Switch to tentative mode",
                        "Switch back to tentative?",
                        "Subject dates will be reset to the timeline start date. Exact dates you entered will be lost.",
                        "Switch to tentative");
                if (confirmed) {
                    try {
                        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
                        timelineDAO.setDatesheetAnnounced(semesterId, examType, false);
                        timelineDAO.clearWindow(semesterId, examType); // Clear stored sheet window
                        timelineService.resetToTentative(semesterId, examType);
                        
                        // Replan and refresh
                        Runnable afterReset = () -> {
                            javafx.application.Platform.runLater(() -> {
                                loadExams();
                                if (onDataChanged != null) {
                                    onDataChanged.run();
                                }
                            });
                        };

                        if (replanRunner != null) {
                            replanRunner.run(semesterId, LocalDate.now(), afterReset, null);
                        } else {
                            new Thread(() -> {
                                try {
                                    replanService.regenerateSemesterPlan(semesterId, LocalDate.now());
                                    javafx.application.Platform.runLater(afterReset);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }).start();
                        }
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                        datesheetCheck.setSelected(true); // Revert
                    }
                } else {
                    datesheetCheck.setSelected(true); // User cancelled, revert
                }
            } else {
                // Switching to datesheet mode
                try {
                    new com.learntrack.dao.ExamTimelineDAO().setDatesheetAnnounced(semesterId, examType, true);
                    loadExams(); // Refresh to show new mode
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    datesheetCheck.setSelected(false); // Revert
                }
            }
        });

        card.getChildren().addAll(titleRow, helper, dateRow, errorLabel);
        return card;
    }

    public void loadExams() {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                mstSection.getChildren().clear();
                estSection.getChildren().clear();
                otherSection.getChildren().clear();
                return;
            }

            int semesterId = active.getSemesterId();

            // Provision exams for all subjects in timetable (runs async off FX thread)
            provisionExamsAsync(semesterId, () -> {
                // After provisioning completes, continue loading UI
                loadExamsAfterProvisioning(semesterId);
            });

        } catch (Exception e) {
            e.printStackTrace();
            showError("Load Error", "Failed to load exams: " + e.getMessage());
        }
    }

    private void provisionExamsAsync(int semesterId, Runnable onComplete) {
        CompletableFuture.runAsync(() -> {
            try {
                provisioningService.ensureExamsForSemester(semesterId);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }).thenRunAsync(onComplete, Platform::runLater);
    }

    private void loadExamsAfterProvisioning(int semesterId) {
        try {
            // Build subject list
            List<SubjectInfo> subjects = Collections.emptyList();
            if (timetableDAO != null) {
                SubjectListService subjectService = new SubjectListService(timetableDAO, examDAO);
                subjects = subjectService.buildSubjectList(semesterId);
            }

            // Get all exams
            List<Exam> allExams = examDAO.findBySemester(semesterId);
            Map<String, Exam> mstExams = new HashMap<>();
            Map<String, Exam> estExams = new HashMap<>();
            Map<String, List<Exam>> normalExamsBySubject = new HashMap<>();
            List<Exam> unassignedMst = new ArrayList<>();
            List<Exam> unassignedEst = new ArrayList<>();
            List<Exam> unassignedNormal = new ArrayList<>();

            for (Exam exam : allExams) {
                if ("MST".equalsIgnoreCase(exam.getExamType())) {
                    if (exam.getSubjectCode() != null && !exam.getSubjectCode().trim().isEmpty()) {
                        mstExams.put(exam.getSubjectCode().toUpperCase(), exam);
                    } else {
                        unassignedMst.add(exam);
                    }
                } else if ("EST".equalsIgnoreCase(exam.getExamType())) {
                    if (exam.getSubjectCode() != null && !exam.getSubjectCode().trim().isEmpty()) {
                        estExams.put(exam.getSubjectCode().toUpperCase(), exam);
                    } else {
                        unassignedEst.add(exam);
                    }
                } else if ("NORMAL".equalsIgnoreCase(exam.getExamType())) {
                    if (exam.getSubjectCode() != null && !exam.getSubjectCode().trim().isEmpty()) {
                        String key = exam.getSubjectCode().toUpperCase();
                        normalExamsBySubject.computeIfAbsent(key, k -> new ArrayList<>()).add(exam);
                    } else {
                        unassignedNormal.add(exam);
                    }
                }
            }

            // Build MST section
            mstSection.getChildren().clear();
            
            // Add timeline card first
            mstSection.getChildren().add(createTimelineCard(semesterId, "MST"));

            // Add capacity review button
            Button btnCapacityReview = UiKit.secondaryButton("Review topic priorities / capacity");
            btnCapacityReview.setTooltip(new Tooltip("Open the topic scope prioritization dialog to check and negotiate study capacity during the MST exam window."));
            final int finalSemesterId = semesterId;
            btnCapacityReview.setOnAction(e -> TopicTrimmingDialog.show(
                    finalSemesterId, "MST", topicDAO, examDAO,
                    capacityService, replanService, replanRunner, onDataChanged
            ));
            mstSection.getChildren().add(btnCapacityReview);
            
            // Check datesheet state for MST
            boolean mstDatesheetAnnounced = new com.learntrack.dao.ExamTimelineDAO().isDatesheetAnnounced(semesterId, "MST");
            
            // Collect all MST exams
            List<Exam> allMstExams = new ArrayList<>();
            for (SubjectInfo subject : subjects) {
                Exam existingExam = mstExams.get(subject.getSubjectCode().toUpperCase());
                if (existingExam != null) {
                    allMstExams.add(existingExam);
                }
            }
            allMstExams.addAll(unassignedMst);
            
            // Sort by effective date using ExamRowHelper comparator
            allMstExams.sort(com.learntrack.ui.forms.ExamRowHelper.getExamOrderingComparator());
            
            // Build rows
            for (Exam exam : allMstExams) {
                SubjectInfo subjectInfo = null;
                for (SubjectInfo s : subjects) {
                    if (s.getSubjectCode().equalsIgnoreCase(exam.getSubjectCode())) {
                        subjectInfo = s;
                        break;
                    }
                }
                if (subjectInfo == null) {
                    subjectInfo = new SubjectInfo(exam.getSubject(), exam.getSubjectCode() != null ? exam.getSubjectCode() : exam.getSubject(), false, false, true);
                }
                
                ExamRowView row = new ExamRowView(subjectInfo, "MST", this::handleRowSave, this::handleRowDelete, defaultPyqHours);
                row.setExam(exam);
                row.setDatesheetMode(mstDatesheetAnnounced);
                configureExamRow(row, subjectInfo);
                mstSection.getChildren().add(row.getView());
            }
            
            // Add rows for subjects without exams
            for (SubjectInfo subject : subjects) {
                if (!mstExams.containsKey(subject.getSubjectCode().toUpperCase())) {
                    ExamRowView row = new ExamRowView(subject, "MST", this::handleRowSave, this::handleRowDelete, defaultPyqHours);
                    row.setExam(null);
                    row.setDatesheetMode(mstDatesheetAnnounced);
                    configureExamRow(row, subject);
                    mstSection.getChildren().add(row.getView());
                }
            }

            // Build EST section
            estSection.getChildren().clear();
            
            // Add timeline card first
            estSection.getChildren().add(createTimelineCard(semesterId, "EST"));
            
            // Check datesheet state for EST
            boolean estDatesheetAnnounced = new com.learntrack.dao.ExamTimelineDAO().isDatesheetAnnounced(semesterId, "EST");
            
            // Collect all EST exams
            List<Exam> allEstExams = new ArrayList<>();
            for (SubjectInfo subject : subjects) {
                Exam existingExam = estExams.get(subject.getSubjectCode().toUpperCase());
                if (existingExam != null) {
                    allEstExams.add(existingExam);
                }
            }
            allEstExams.addAll(unassignedEst);
            
            // Sort by effective date using ExamRowHelper comparator
            allEstExams.sort(com.learntrack.ui.forms.ExamRowHelper.getExamOrderingComparator());
            
            // Build rows
            for (Exam exam : allEstExams) {
                SubjectInfo subjectInfo = null;
                for (SubjectInfo s : subjects) {
                    if (s.getSubjectCode().equalsIgnoreCase(exam.getSubjectCode())) {
                        subjectInfo = s;
                        break;
                    }
                }
                if (subjectInfo == null) {
                    subjectInfo = new SubjectInfo(exam.getSubject(), exam.getSubjectCode() != null ? exam.getSubjectCode() : exam.getSubject(), false, false, true);
                }
                
                ExamRowView row = new ExamRowView(subjectInfo, "EST", this::handleRowSave, this::handleRowDelete, defaultPyqHours);
                row.setExam(exam);
                row.setDatesheetMode(estDatesheetAnnounced);
                configureExamRow(row, subjectInfo);
                estSection.getChildren().add(row.getView());
            }
            
            // Add rows for subjects without exams
            for (SubjectInfo subject : subjects) {
                if (!estExams.containsKey(subject.getSubjectCode().toUpperCase())) {
                    ExamRowView row = new ExamRowView(subject, "EST", this::handleRowSave, this::handleRowDelete, defaultPyqHours);
                    row.setExam(null);
                    row.setDatesheetMode(estDatesheetAnnounced);
                    configureExamRow(row, subject);
                    estSection.getChildren().add(row.getView());
                }
            }

            // Build OTHER section (NORMAL exams)
            otherSection.getChildren().clear();
            for (SubjectInfo subject : subjects) {
                VBox subjectBlock = createNormalExamBlock(subject, normalExamsBySubject.get(subject.getSubjectCode().toUpperCase()));
                otherSection.getChildren().add(subjectBlock);
            }

            // Add unassigned NORMAL exams
            if (!unassignedNormal.isEmpty()) {
                Label unassignedLabel = new Label("Unassigned (legacy exams without subject code):");
                unassignedLabel.getStyleClass().addAll("text-danger", "section-title");
                otherSection.getChildren().add(unassignedLabel);

                for (Exam exam : unassignedNormal) {
                    SubjectInfo legacySubject = new SubjectInfo(
                            exam.getSubject(), exam.getSubject(), false, false, true
                    );
                    VBox legacyBlock = createNormalExamBlock(legacySubject, List.of(exam));
                    otherSection.getChildren().add(legacyBlock);
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            showError("Load Error", "Failed to load exams: " + e.getMessage());
        }
    }

    private VBox createNormalExamBlock(SubjectInfo subject, List<Exam> existingExams) {
        VBox block = new VBox(8);
        block.getStyleClass().add("card-flat");

        // Header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(subject.getDisplayName());
        nameLabel.getStyleClass().add("section-title");

        Label codeLabel = new Label("[" + subject.getSubjectCode() + "]");
        codeLabel.getStyleClass().add("subtle");

        Label badgeLabel = new Label(subject.getBadge());
        badgeLabel.getStyleClass().add("subtle");

        header.getChildren().addAll(nameLabel, codeLabel, badgeLabel);

        VBox rowsContainer = new VBox(6);
        rowsContainer.setPadding(new Insets(6, 0, 0, 0));

        // Add existing exams
        if (existingExams != null && !existingExams.isEmpty()) {
            for (Exam exam : existingExams) {
                ExamRowView row = new ExamRowView(subject, "NORMAL", this::handleRowSave, this::handleRowDelete, defaultPyqHours);
                row.setExam(exam);
                configureExamRow(row, subject);
                rowsContainer.getChildren().add(row.getView());
            }
        }

        // "+ Add evaluation" button
        Button addButton = UiKit.secondaryButton("+ Add evaluation");
        addButton.setOnAction(e -> {
            ExamRowView newRow = new ExamRowView(subject, "NORMAL", this::handleRowSave, this::handleRowDelete, defaultPyqHours);
            newRow.setExam(null);
            configureExamRow(newRow, subject);
            // Insert before the add button
            int addButtonIndex = rowsContainer.getChildren().indexOf(addButton);
            rowsContainer.getChildren().add(addButtonIndex, newRow.getView());
        });

        rowsContainer.getChildren().add(addButton);

        block.getChildren().addAll(header, rowsContainer);
        return block;
    }

    private void handleRowSave(ExamRowView row) {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                showError("No Active Semester", "Please set up an active semester first.");
                return;
            }

            SubjectInfo subjectInfo = row.getSubjectInfo();
            String examType = row.getExamType();
            String evalName = row.getEvalName(); // null for MST/EST, required for NORMAL

            // Validate evalName for NORMAL exams
            if ("NORMAL".equalsIgnoreCase(examType)) {
                if (evalName == null || evalName.isEmpty()) {
                    showError("Validation Error", "Evaluation name is required for normal evaluations.");
                    return;
                }
            }

            // Validate credits
            double credits;
            try {
                credits = row.getCredits();
            } catch (Exception e) {
                showError("Validation Error", "Please enter a valid credit value.");
                return;
            }
            if (credits <= 0.0) {
                showError("Validation Error", "Credits must be greater than 0.");
                return;
            }

            // Validate marks
            double evalMarks;
            try {
                evalMarks = row.getEvalMarks();
            } catch (NumberFormatException nfe) {
                showError("Input Error", "Please enter a valid numeric value for eval marks.");
                return;
            }

            double pyqHours = row.getPyqHours();
            int travel = row.getTravelMinutes();

            boolean isDatesheetMode = row.isDatesheetMode();
            LocalDate examDate = null;
            LocalTime startTime = null;
            LocalTime endTime = null;
            boolean tentative = false; // For MST/EST in tentative mode

            boolean isNormal = "NORMAL".equalsIgnoreCase(examType);

            // For NORMAL, date and times are always required
            if (isNormal) {
                examDate = row.getExamDate();
                if (examDate == null) {
                    showError("Validation Error", "Please select an exam date.");
                    return;
                }
                startTime = row.getStartTime();
                endTime = row.getEndTime();
                if (startTime == null || endTime == null) {
                    showError("Validation Error", "Please enter valid start and end times in HH:mm format.");
                    return;
                }
                if (!endTime.isAfter(startTime)) {
                    showError("Validation Error", "Exam end time must be after start time.");
                    return;
                }
            } else if (isDatesheetMode) {
                // MST/EST in datesheet mode: validate inline
                if (!row.validateDateFields()) {
                    return; // Validation error already shown inline
                }
                examDate = row.getExamDate();
                startTime = row.getStartTime();
                endTime = row.getEndTime();
                tentative = false; // Exact date
            } else {
                // MST/EST in tentative mode: will use timeline window start date
                tentative = true;
            }

            LocalDate winStart = row.getWindowStart(); // Deprecated, returns null for MST/EST
            LocalDate winEnd = row.getWindowEnd();   // Deprecated, returns null for MST/EST

            // For MST/EST: apply timeline window if in tentative mode
            boolean applyWindowOnCreate = !isNormal && !isDatesheetMode;

            Exam existingExam = row.getExistingExam();
            if (existingExam == null) {
                // Create new exam with full constructor (use subjectCode and evalName)
                Exam exam = new Exam(null, active.getSemesterId(),
                        subjectInfo.getDisplayName(), subjectInfo.getSubjectCode(),
                        examType, evalName, // evalName is null for MST/EST, set for NORMAL
                        credits, evalMarks, examDate, startTime, endTime, travel,
                        null, null, pyqHours); // Window will be applied by timelineService
                
                // Set tentative flag for MST/EST
                if (!isNormal) {
                    exam.setExamDateTentative(tentative);
                }
                
                // Apply timeline window for undated MST/EST (if timeline is set)
                if (applyWindowOnCreate) {
                    try {
                        timelineService.applyWindowToNewExam(active.getSemesterId(), exam);
                    } catch (SQLException ex) {
                        // Timeline not set yet - that's OK, will be set later
                    }
                }
                
                try {
                    examDAO.insert(exam);
                    
                    // If MST/EST in datesheet mode, recompute window from all exact dates
                    if (!isNormal && isDatesheetMode && examDate != null) {
                        timelineService.recomputeWindowFromDatesheet(active.getSemesterId(), examType);
                    }
                } catch (IllegalArgumentException e) {
                    // Catch validation errors from ExamDAO (duplicate evalName, etc.)
                    showError("Validation Error", e.getMessage());
                    return;
                }
            } else {
                // Update existing exam (set subjectCode if missing)
                boolean wasUndated = (existingExam.getExamDate() == null || existingExam.isExamDateTentative());
                boolean isBecomingDated = wasUndated && isDatesheetMode && examDate != null;
                
                existingExam.setSubject(subjectInfo.getDisplayName());
                if (existingExam.getSubjectCode() == null || existingExam.getSubjectCode().trim().isEmpty()) {
                    existingExam.setSubjectCode(subjectInfo.getSubjectCode());
                }
                existingExam.setExamType(examType);
                existingExam.setEvalName(evalName);
                existingExam.setCredits(credits);
                existingExam.setEvalMarks(evalMarks);
                existingExam.setExamDate(examDate);
                existingExam.setExamStartTime(startTime);
                existingExam.setExamEndTime(endTime);
                existingExam.setTravelTimeMinutes(travel);
                // Set tentative flag for MST/EST
                if (!isNormal) {
                    existingExam.setExamDateTentative(tentative);
                }
                // Don't set window dates for MST/EST - managed at type level
                existingExam.setPyqRemainingHours(pyqHours);
                try {
                    examDAO.update(existingExam);
                    
                    // Recompute window from datesheet when MST/EST exam gets exact date
                    if (!isNormal && isBecomingDated) {
                        timelineService.recomputeWindowFromDatesheet(active.getSemesterId(), examType);
                    }
                } catch (IllegalArgumentException e) {
                    // Catch validation errors from ExamDAO
                    showError("Validation Error", e.getMessage());
                    return;
                } catch (SQLException e) {
                    showError("Error", "Failed to update: " + e.getMessage());
                    return;
                }
            }

            Runnable afterSaveReplan = () -> {
                // Automated negotiation check if datesheet just released mid-semester
                if (!isNormal && isDatesheetMode && capacityService != null) {
                    checkAndTriggerNegotiationLoop(active.getSemesterId(), examType);
                }

                loadExams();

                if (onDataChanged != null) {
                    onDataChanged.run();
                }
            };

            if (replanRunner != null) {
                replanRunner.run(active.getSemesterId(), LocalDate.now(), afterSaveReplan, null);
            } else {
                replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
                afterSaveReplan.run();
            }

        } catch (NumberFormatException nfe) {
            showError("Input Error", "Please enter valid numeric values.");
        } catch (Exception ex) {
            showError("Database Error", "Failed to save exam: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void handleRowDelete(ExamRowView row) {
        Exam exam = row.getExistingExam();
        if (exam == null) {
            return;
        }

        if (UiKit.confirm(window(), "Delete exam",
                "Delete “" + exam.getSubject() + "” (" + exam.getExamType() + ")?",
                "The exam, its topics and its syllabus scope are removed. Logged study sessions stay in history.",
                "Delete exam")) {
            try {
                Semester active = semesterDAO.getActiveSemester();
                examDAO.delete(exam.getExamId());

                Runnable afterDeleteReplan = () -> {
                    loadExams();
                    if (onDataChanged != null) {
                        onDataChanged.run();
                    }
                };

                if (active != null && replanRunner != null) {
                    replanRunner.run(active.getSemesterId(), LocalDate.now(), afterDeleteReplan, null);
                } else {
                    if (active != null) {
                        replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
                    }
                    afterDeleteReplan.run();
                }
            } catch (SQLException e) {
                showError("Delete Error", e.getMessage());
            }
        }
    }

    private void configureExamRow(ExamRowView row, SubjectInfo subject) {
        if (replanRunner != null) {
            row.bindButtonsToReplanRunner(replanRunner.busyProperty());
        }
        
        row.setSyllabusButtonHandler(this::handleSyllabusButton);
        updateSyllabusButtonLabel(row, subject);
    }

    private void updateSyllabusButtonLabel(ExamRowView row, SubjectInfo subject) {
        Exam exam = row.getExistingExam();
        if (exam == null || exam.getExamId() == null) {
            row.updateSyllabusButtonLabel(0, 0);
            row.setScopeData(Collections.emptyList(), Collections.emptyList(), 
                            Collections.emptyMap(), Collections.emptyMap());
            return;
        }

        try {
            int selectedCount = examScopeDAO.countForExam(exam.getExamId());
            int totalCount = getTotalSubtopicCount(subject.getSubjectCode());
            row.updateSyllabusButtonLabel(selectedCount, totalCount);

            // Load scope data off FX thread
            if (selectedCount > 0) {
                loadScopeDataAsync(row, exam.getExamId(), subject.getSubjectCode());
            } else {
                row.setScopeData(Collections.emptyList(), Collections.emptyList(), 
                                Collections.emptyMap(), Collections.emptyMap());
            }
        } catch (SQLException e) {
            e.printStackTrace();
            row.updateSyllabusButtonLabel(0, 0);
            row.setScopeData(Collections.emptyList(), Collections.emptyList(), 
                            Collections.emptyMap(), Collections.emptyMap());
        }
    }

    private void loadScopeDataAsync(ExamRowView row, int examId, String subjectCode) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return loadScopeData(examId, subjectCode);
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        }).thenAcceptAsync(data -> {
            if (data != null) {
                row.refreshScopeView(data.selectedSubtopics, data.allUnits, 
                                    data.unitTotals, data.subtopicToTopic);
            }
        }, Platform::runLater);
    }

    private ScopeData loadScopeData(int examId, String subjectCode) throws SQLException {
        Semester active = semesterDAO.getActiveSemester();
        if (active == null) {
            return new ScopeData();
        }

        Set<Integer> selectedIds = examScopeDAO.findSubtopicIdsForExam(examId);
        List<SyllabusUnit> allUnits = syllabusDAO.findUnitsBySubject(active.getSemesterId(), subjectCode);
        List<SyllabusSubtopic> selectedSubtopics = new ArrayList<>();
        Map<Integer, Integer> unitTotals = new HashMap<>();
        Map<Integer, Topic> subtopicToTopic = new HashMap<>();

        // Collect selected subtopics and calculate unit totals
        for (SyllabusUnit unit : allUnits) {
            List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
            unitTotals.put(unit.getUnitId(), subtopics.size());

            for (SyllabusSubtopic sub : subtopics) {
                if (selectedIds.contains(sub.getSubtopicId())) {
                    selectedSubtopics.add(sub);
                    
                    // Load linked topic if exists
                    Topic topic = topicDAO.findByExamAndSubtopic(examId, sub.getSubtopicId());
                    if (topic != null) {
                        subtopicToTopic.put(sub.getSubtopicId(), topic);
                    }
                }
            }
        }

        ScopeData data = new ScopeData();
        data.selectedSubtopics = selectedSubtopics;
        data.allUnits = allUnits;
        data.unitTotals = unitTotals;
        data.subtopicToTopic = subtopicToTopic;
        return data;
    }

    private static class ScopeData {
        List<SyllabusSubtopic> selectedSubtopics = Collections.emptyList();
        List<SyllabusUnit> allUnits = Collections.emptyList();
        Map<Integer, Integer> unitTotals = Collections.emptyMap();
        Map<Integer, Topic> subtopicToTopic = Collections.emptyMap();
    }

    private int getTotalSubtopicCount(String subjectCode) throws SQLException {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                return 0;
            }
            List<SyllabusUnit> units = syllabusDAO.findUnitsBySubject(active.getSemesterId(), subjectCode);
            int total = 0;
            for (SyllabusUnit unit : units) {
                List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
                total += subtopics.size();
            }
            return total;
        } catch (SQLException e) {
            throw e;
        }
    }

    private void handleSyllabusButton(ExamRowView row) {
        Exam exam = row.getExistingExam();
        if (exam == null || exam.getExamId() == null) {
            return;
        }

        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                return;
            }

            SubjectInfo subject = row.getSubjectInfo();
            Stage stage = (Stage) mstSection.getScene().getWindow();

            ExamScopeDialog dialog = new ExamScopeDialog(
                    exam, subject.getSubjectCode(), active.getSemesterId(),
                    syllabusDAO, examDAO,
                    new com.learntrack.service.ExamScopeService(),
                    () -> {
                        // Trigger replan
                        if (replanRunner != null) {
                            replanRunner.run(active.getSemesterId(), LocalDate.now(), 
                                () -> {
                                    // Refresh scope view after replan completes
                                    updateSyllabusButtonLabel(row, subject);
                                }, 
                                err -> err.printStackTrace());
                        } else {
                            try {
                                replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                            // Refresh scope view after replan
                            updateSyllabusButtonLabel(row, subject);
                        }

                        // Notify data changed (triggers Subjects tab refresh)
                        if (onDataChanged != null) {
                            onDataChanged.run();
                        }
                    }
            );
            dialog.showAndWait(stage);

        } catch (Exception e) {
            e.printStackTrace();
            showError("Error", "Failed to open syllabus dialog: " + e.getMessage());
        }
    }

    private void checkAndTriggerNegotiationLoop(int semesterId, String examType) {
        CapacityNegotiationDialogHelper.runNegotiationLoop(
                semesterId, examType, LocalDate.now(),
                capacityService, replanService,
                onTrimRequested,
                onDataChanged
        );
    }

    private void showEnterAllDatesDialog(int semesterId, String examType) {
        try {
            // Get all exams of this type
            List<Exam> exams = examDAO.findBySemester(semesterId).stream()
                    .filter(e -> examType.equalsIgnoreCase(e.getExamType()))
                    .sorted(Comparator.comparing(e -> e.getSubject() != null ? e.getSubject() : ""))
                    .collect(java.util.stream.Collectors.toList());
            
            if (exams.isEmpty()) {
                showError("No Exams", "No exams found for type: " + examType);
                return;
            }

            // Create dialog
            Dialog<ButtonType> dialog = new Dialog<>();
            UiKit.styleDialog(dialog);
            dialog.setTitle("Enter All " + examType + " Dates");
            dialog.setHeaderText("Set exact exam dates for all subjects");
            
            // Create table
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(20));
            
            // Header row
            grid.add(new Label("Subject"), 0, 0);
            grid.add(new Label("Exam Date"), 1, 0);
            grid.add(new Label("Start Time"), 2, 0);
            grid.add(new Label("End Time"), 3, 0);
            
            // Store controls for each exam
            List<DatePicker> datePickers = new ArrayList<>();
            List<TextField> startFields = new ArrayList<>();
            List<TextField> endFields = new ArrayList<>();
            Label errorLabel = new Label();
            errorLabel.getStyleClass().add("text-danger");
            errorLabel.setVisible(false);
            
            int row = 1;
            for (Exam exam : exams) {
                Label subjectLabel = new Label(exam.getSubject());
                subjectLabel.getStyleClass().add("subject-code");
                
                DatePicker datePicker = new DatePicker();
                datePicker.setPrefWidth(140);
                if (exam.getExamDate() != null && !exam.isExamDateTentative()) {
                    datePicker.setValue(exam.getExamDate());
                }
                
                TextField startField = new TextField(
                    exam.getExamStartTime() != null ? DateUtils.formatTime(exam.getExamStartTime()) : "10:00"
                );
                startField.setPrefWidth(70);
                startField.setPromptText("HH:mm");
                
                TextField endField = new TextField(
                    exam.getExamEndTime() != null ? DateUtils.formatTime(exam.getExamEndTime()) : "12:00"
                );
                endField.setPrefWidth(70);
                endField.setPromptText("HH:mm");
                
                grid.add(subjectLabel, 0, row);
                grid.add(datePicker, 1, row);
                grid.add(startField, 2, row);
                grid.add(endField, 3, row);
                
                datePickers.add(datePicker);
                startFields.add(startField);
                endFields.add(endField);
                row++;
            }
            
            grid.add(errorLabel, 0, row, 4, 1);
            
            ScrollPane scrollPane = new ScrollPane(grid);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefHeight(400);
            
            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            
            Optional<ButtonType> result = dialog.showAndWait();
            
            if (result.isPresent() && result.get() == ButtonType.OK) {
                // Validate all rows
                errorLabel.setVisible(false);
                List<String> errors = new ArrayList<>();
                
                for (int i = 0; i < exams.size(); i++) {
                    LocalDate date = datePickers.get(i).getValue();
                    if (date != null) {
                        LocalTime start = DateUtils.parseTime(startFields.get(i).getText());
                        LocalTime end = DateUtils.parseTime(endFields.get(i).getText());
                        
                        String error = com.learntrack.ui.forms.ExamRowHelper.validateDateFields(date, start, end);
                        if (error != null) {
                            errors.add(exams.get(i).getSubject() + ": " + error);
                        }
                    }
                }
                
                if (!errors.isEmpty()) {
                    errorLabel.setText(String.join("; ", errors));
                    errorLabel.setVisible(true);
                    showError("Validation Error", String.join("\n", errors));
                    return;
                }
                
                // Save all in one transaction
                for (int i = 0; i < exams.size(); i++) {
                    Exam exam = exams.get(i);
                    LocalDate date = datePickers.get(i).getValue();
                    
                    if (date != null) {
                        // Save exact date
                        exam.setExamDate(date);
                        exam.setExamStartTime(DateUtils.parseTime(startFields.get(i).getText()));
                        exam.setExamEndTime(DateUtils.parseTime(endFields.get(i).getText()));
                        exam.setExamDateTentative(false);
                    }
                    // else: leave as tentative (empty row)
                    
                    examDAO.update(exam);
                }
                
                // Recompute window once
                timelineService.recomputeWindowFromDatesheet(semesterId, examType);
                
                // One replan, one refresh
                Runnable afterSave = () -> {
                    javafx.application.Platform.runLater(() -> {
                        loadExams();
                        if (onDataChanged != null) {
                            onDataChanged.run();
                        }
                    });
                };
                
                if (replanRunner != null) {
                    replanRunner.run(semesterId, LocalDate.now(), afterSave, null);
                } else {
                    new Thread(() -> {
                        try {
                            replanService.regenerateSemesterPlan(semesterId, LocalDate.now());
                            javafx.application.Platform.runLater(afterSave);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }).start();
                }
            }
            
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Error", "Failed to show dialog: " + ex.getMessage());
        }
    }

    private void showError(String title, String msg) {
        UiKit.error(window(), title, msg);
    }

    private Window window() {
        return mstPane != null && mstPane.getScene() != null ? mstPane.getScene().getWindow() : null;
    }
}
