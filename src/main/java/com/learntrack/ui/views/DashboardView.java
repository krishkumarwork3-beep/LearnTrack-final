package com.learntrack.ui.views;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.service.DayScheduleService;
import com.learntrack.service.ExamScopeService;
import com.learntrack.service.ExamWindowService;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.service.SemesterService;
import com.learntrack.service.SkillProgressService;
import com.learntrack.service.TimetableEditService;
import com.learntrack.ui.UiKit;
import com.learntrack.ui.charts.FeasibilityBarView;
import com.learntrack.util.DateUtils;
import com.learntrack.util.DurationFormat;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Main daily dashboard: complete merged schedule view (commitments, sleep, study),
 * date navigation (prev, next, date picker, today), interactive progress logging,
 * class cancellation handler, and feasibility bar.
 */
public class DashboardView {

    private final DailyLogDAO dailyLogDAO;
    private final TopicDAO topicDAO;
    private final SkillDAO skillDAO;
    private final ExamDAO examDAO;
    private final SemesterDAO semesterDAO;
    private final TimetableDAO timetableDAO;
    private final DateOverrideDAO dateOverrideDAO;
    private final SemesterReplanService replanService;
    private final SemesterService semesterService;
    private final FeasibilityBarView feasibilityBarView;
    private final Runnable onDataChanged;

    private final TimetableEditService timetableEditService;
    private final com.learntrack.ui.ReplanRunner replanRunner;
    private final DayScheduleService dayScheduleService;
    private final ExamScopeService examScopeService;

    private LocalDate selectedDate = LocalDate.now();
    private Label dateHeader;
    private Label dateSubtitle;
    private static final java.time.format.DateTimeFormatter DAY_TITLE =
            java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.ENGLISH);
    private DatePicker datePicker;
    private ProgressBar todayProgressBar;
    private Label todayProgressLabel;
    private DayScheduleView dayScheduleView;
    private VBox scopeWarningBanner;
    private VBox undatedDisclosure;
    private FlowPane undatedExamsBox;
    /** Both disclosures start collapsed so the timetable gets the height. */
    private boolean scopeDetailsExpanded;
    private boolean undatedExpanded;
    private Button btnResize;
    private Button btnCancelClass;
    private final SkillSubtopicDAO skillSubtopicDAO;
    private final SkillProgressService skillProgressService;

    public DashboardView(DailyLogDAO dailyLogDAO, TopicDAO topicDAO, SkillDAO skillDAO,
                         ExamDAO examDAO, SemesterDAO semesterDAO, TimetableDAO timetableDAO,
                         DateOverrideDAO dateOverrideDAO, SemesterReplanService replanService,
                         SemesterService semesterService,
                         TimetableEditService timetableEditService,
                         FeasibilityBarView feasibilityBarView, Runnable onDataChanged) {
        this(dailyLogDAO, topicDAO, skillDAO, examDAO, semesterDAO, timetableDAO,
                dateOverrideDAO, replanService, semesterService, timetableEditService,
                feasibilityBarView, onDataChanged, null);
    }

    public DashboardView(DailyLogDAO dailyLogDAO, TopicDAO topicDAO, SkillDAO skillDAO,
                         ExamDAO examDAO, SemesterDAO semesterDAO, TimetableDAO timetableDAO,
                         DateOverrideDAO dateOverrideDAO, SemesterReplanService replanService,
                         SemesterService semesterService,
                         TimetableEditService timetableEditService,
                         FeasibilityBarView feasibilityBarView, Runnable onDataChanged,
                         com.learntrack.ui.ReplanRunner replanRunner) {
        this.dailyLogDAO = dailyLogDAO;
        this.topicDAO = topicDAO;
        this.skillDAO = skillDAO;
        this.examDAO = examDAO;
        this.semesterDAO = semesterDAO;
        this.timetableDAO = timetableDAO;
        this.dateOverrideDAO = dateOverrideDAO;
        this.replanService = replanService;
        this.semesterService = semesterService;
        this.timetableEditService = timetableEditService;
        this.feasibilityBarView = feasibilityBarView;
        this.onDataChanged = onDataChanged;
        this.replanRunner = replanRunner;
        this.skillSubtopicDAO = new SkillSubtopicDAO();
        this.skillProgressService = new SkillProgressService(this.skillSubtopicDAO);
        this.dayScheduleService = new DayScheduleService(
                timetableDAO, dateOverrideDAO, examDAO, dailyLogDAO,
                new ExamPeriodRoutineDAO(), topicDAO, skillDAO, this.skillSubtopicDAO, new ExamScopeService()
        );
        this.examScopeService = new ExamScopeService();
        if (this.replanService != null) {
            this.replanService.setCurrentTimeSupplier(LocalTime::now);
        }
    }

    public Node getView() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(16, 24, 16, 24));

        // Page header: fixed title, the date and exam-period note underneath.
        dateHeader = UiKit.pageTitle("Daily schedule");
        dateSubtitle = new Label();
        dateSubtitle.getStyleClass().add("page-description");
        VBox titleBox = new VBox(4, dateHeader, dateSubtitle);

        // Date Navigation bar
        HBox dateNavBox = new HBox(8);
        dateNavBox.setAlignment(Pos.CENTER_LEFT);

        Button btnPrev = UiKit.secondaryButton("Previous");
        btnPrev.setOnAction(e -> {
            selectedDate = selectedDate.minusDays(1);
            if (datePicker != null) datePicker.setValue(selectedDate);
            refreshData();
        });

        datePicker = new DatePicker(selectedDate);
        datePicker.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && !newV.equals(selectedDate)) {
                selectedDate = newV;
                refreshData();
            }
        });

        Button btnNext = UiKit.secondaryButton("Next");
        btnNext.setOnAction(e -> {
            selectedDate = selectedDate.plusDays(1);
            if (datePicker != null) datePicker.setValue(selectedDate);
            refreshData();
        });

        Button btnToday = UiKit.secondaryButton("Today");
        btnToday.setOnAction(e -> {
            selectedDate = LocalDate.now();
            if (datePicker != null) datePicker.setValue(selectedDate);
            refreshData();
        });

        dateNavBox.getChildren().addAll(btnPrev, datePicker, btnNext, btnToday);

        // Title on the left, day navigation on the right: one row instead of two.
        HBox headerRow = new HBox(12, titleBox, UiKit.spacer(), dateNavBox);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        // Scope warning: one collapsed line that expands on demand.
        scopeWarningBanner = new VBox();
        scopeWarningBanner.setVisible(false);
        scopeWarningBanner.setManaged(false);

        // Tentative exams: collapsed disclosure around the chip list.
        undatedExamsBox = new FlowPane(8, 6);
        undatedDisclosure = new VBox(6);
        undatedDisclosure.setVisible(false);
        undatedDisclosure.setManaged(false);

        // Slim today-progress block: caption plus a thin bar, no card around it.
        VBox progressBox = new VBox(2);
        progressBox.setMinWidth(220);
        progressBox.setMaxWidth(320);

        todayProgressBar = new ProgressBar(0.0);
        todayProgressBar.setMaxWidth(Double.MAX_VALUE);
        todayProgressBar.setPrefHeight(6);

        todayProgressLabel = new Label("Study progress: 0.0 / 0.0 h (0%)");
        todayProgressLabel.getStyleClass().addAll("status-caption", "tabular");

        progressBox.getChildren().addAll(todayProgressLabel, todayProgressBar);

        if (feasibilityBarView != null) {
            feasibilityBarView.setCompact(true);
            feasibilityBarView.setPrefWidth(430);
            feasibilityBarView.setMinWidth(300);
        }
        HBox statusRow = new HBox(12, progressBox, UiKit.spacer(),
                feasibilityBarView == null ? UiKit.spacer() : feasibilityBarView);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        // Top Header Box: every row above the timetable stays on a tight rhythm.
        VBox topBox = new VBox(8, headerRow, statusRow, scopeWarningBanner, undatedDisclosure);

        // DayScheduleView (replaces old single-source TableView)
        dayScheduleView = new DayScheduleView();
        dayScheduleView.getStyleClass().add("day-schedule");
        dayScheduleView.setOnStudyRowDoubleClicked(row -> {
            if (row.getDailyLogEntry() != null) {
                handleDoneNotYetDialog(row.getDailyLogEntry());
            }
        });
        dayScheduleView.setOnToggleDone(this::handleToggleDone);
        if (replanRunner != null) {
            dayScheduleView.bindBusyProperty(replanRunner.busyProperty());
            replanRunner.busyProperty().addListener((obs, oldV, newV) -> updateToolbarButtons());
        }
        dayScheduleView.getSelectedItems().addListener((javafx.collections.ListChangeListener<DayRow>) c -> updateToolbarButtons());

        // Action Toolbar: only [Resize Selected Sessions] [Cancel Selected Class]
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        btnResize = UiKit.secondaryButton("Resize sessions");
        btnResize.setTooltip(new Tooltip("Resize selected study sessions to a new duration"));
        btnResize.setOnAction(e -> handleResizeSelectedSessions());

        btnCancelClass = UiKit.dangerTextButton("Cancel class");
        btnCancelClass.setTooltip(new Tooltip("Cancel recurring class on " + DateUtils.formatDate(selectedDate) + " only"));
        btnCancelClass.setOnAction(e -> handleCancelOrRestoreClass());

        toolbar.getChildren().addAll(btnResize, btnCancelClass);

        Label hintLabel = UiKit.hint("Tick = done. Double-click a session to log partial progress.");
        hintLabel.getStyleClass().add("hint-compact");

        root.getChildren().addAll(topBox, toolbar, hintLabel, dayScheduleView);
        VBox.setVgrow(dayScheduleView, Priority.ALWAYS);

        refreshData();
        updateToolbarButtons();
        return root;
    }

    /**
     * One-line warning that expands into the list of exams, so a long banner cannot push the
     * timetable below the fold. The full list is only built when the user asks for it.
     */
    private void refreshScopeWarning(List<Exam> examsWithoutScope) {
        scopeWarningBanner.getChildren().clear();
        if (examsWithoutScope == null || examsWithoutScope.isEmpty()) {
            scopeWarningBanner.setVisible(false);
            scopeWarningBanner.setManaged(false);
            return;
        }
        int count = examsWithoutScope.size();
        String heading = count == 1 ? "1 exam has no syllabus scope" : count + " exams have no syllabus scope";
        String text;
        if (scopeDetailsExpanded) {
            text = examsWithoutScope.stream().map(this::examLabel)
                    .collect(java.util.stream.Collectors.joining(" · "))
                    + ". Open the Exams tab and set a scope for each of them.";
        } else {
            text = "No study sessions can be planned for them until a syllabus scope is set.";
        }
        Button toggle = UiKit.ghostButton(scopeDetailsExpanded ? "Hide" : "View");
        toggle.setOnAction(e -> {
            scopeDetailsExpanded = !scopeDetailsExpanded;
            refreshData();
        });
        HBox banner = UiKit.banner(UiKit.Kind.WARN, heading, text, toggle);
        banner.getStyleClass().add("banner-compact");
        scopeWarningBanner.getChildren().add(banner);
        scopeWarningBanner.setVisible(true);
        scopeWarningBanner.setManaged(true);
    }

    /** Tentative exams are collapsed by default and expand to the wrapping chip list. */
    private void refreshUndatedDisclosure(List<Exam> allExams) {
        undatedDisclosure.getChildren().clear();
        List<Exam> undated = allExams == null ? List.of() : allExams.stream()
                .filter(e -> e.getExamDate() == null)
                .collect(java.util.stream.Collectors.toList());
        if (undated.isEmpty()) {
            undatedDisclosure.setVisible(false);
            undatedDisclosure.setManaged(false);
            return;
        }

        Label title = new Label(undated.size() == 1 ? "1 tentative exam" : undated.size() + " tentative exams");
        title.getStyleClass().add("subtle");
        Button toggle = UiKit.ghostButton(undatedExpanded ? "Hide" : "Show");
        toggle.setOnAction(e -> {
            undatedExpanded = !undatedExpanded;
            refreshData();
        });
        HBox header = new HBox(8, title, UiKit.spacer(), toggle);
        header.setAlignment(Pos.CENTER_LEFT);

        undatedExamsBox.getChildren().clear();
        for (Exam exam : undated) {
            Label name = new Label(examLabel(exam));
            name.getStyleClass().add("subtle");
            HBox item = new HBox(6, name, UiKit.badge("Date TBD", UiKit.Kind.WARN));
            item.setAlignment(Pos.CENTER_LEFT);
            undatedExamsBox.getChildren().add(item);
        }
        undatedExamsBox.setVisible(undatedExpanded);
        undatedExamsBox.setManaged(undatedExpanded);

        undatedDisclosure.getChildren().addAll(header, undatedExamsBox);
        undatedDisclosure.setVisible(true);
        undatedDisclosure.setManaged(true);
    }

    private String examLabel(Exam exam) {
        String label = exam.getSubject() == null ? "" : exam.getSubject();
        if (exam.getExamType() != null && !exam.getExamType().isEmpty()) {
            label += " " + exam.getExamType();
        }
        if (exam.getEvalName() != null && !exam.getEvalName().isEmpty()) {
            label += " (" + exam.getEvalName() + ")";
        }
        return label.trim();
    }

    private String getItemDisplayTitle(DailyLogEntry entry) {
        try {
            if ("TOPIC_LEARN".equalsIgnoreCase(entry.getItemType()) || "TOPIC_REVISE".equalsIgnoreCase(entry.getItemType())) {
                Topic t = topicDAO.findById(entry.getItemId());
                return t != null ? t.getName() : "Topic #" + entry.getItemId();
            } else if ("SKILL_LEARN".equalsIgnoreCase(entry.getItemType()) || "SKILL_REVISE".equalsIgnoreCase(entry.getItemType())) {
                if (skillSubtopicDAO != null) {
                    SkillSubtopic st = skillSubtopicDAO.findById(entry.getItemId());
                    if (st != null) {
                        Skill s = skillDAO.findById(st.getSkillId());
                        String sName = (s != null && s.getName() != null) ? s.getName() : "Skill";
                        String mode = "SKILL_LEARN".equalsIgnoreCase(entry.getItemType()) ? "Learn" : "Revise";
                        return "Skill: " + sName + " › " + st.getName() + " (" + mode + ")";
                    }
                }
                return entry.getItemType() + " #" + entry.getItemId();
            } else if ("SKILL".equalsIgnoreCase(entry.getItemType())) {
                Skill s = skillDAO.findById(entry.getItemId());
                return s != null ? s.getName() : "Skill #" + entry.getItemId();
            } else if ("PYQ_PRACTICE".equalsIgnoreCase(entry.getItemType())) {
                Exam ex = examDAO.findById(entry.getItemId());
                return ex != null ? "PYQs: " + ex.getSubject() : "PYQ #" + entry.getItemId();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Item #" + entry.getItemId();
    }

    public static boolean canLogProgress(LocalDate sessionDate, LocalDate today) {
        if (sessionDate == null || today == null) {
            return false;
        }
        return !sessionDate.isAfter(today);
    }

    private void handleDoneNotYetDialog(DailyLogEntry log) {
        String itemType = log.getItemType();
        String title = getItemDisplayTitle(log);
        LocalDate sessionDate = (log.getLogDate() != null) ? log.getLogDate() : selectedDate;

        // Guard: cannot mark a session done before it happens
        if (!canLogProgress(sessionDate, LocalDate.now())) {
            showAlert(Alert.AlertType.INFORMATION, "Session Not Yet",
                "This session is planned for " + sessionDate + " which is in the future.\n" +
                "You can only log progress on today's or past sessions.");
            return;
        }

        if ("TOPIC_LEARN".equalsIgnoreCase(itemType)) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            UiKit.styleDialog(dialog);
            dialog.setTitle("Learning Progress");
            dialog.setHeaderText("Session: " + title);
            dialog.setContentText("Did you finish learning this topic?");

            ButtonType btnYes = new ButtonType("Yes, Done", ButtonBar.ButtonData.YES);
            ButtonType btnNotYet = new ButtonType("Not Yet", ButtonBar.ButtonData.NO);
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            dialog.getButtonTypes().setAll(btnYes, btnNotYet, btnCancel);
            Optional<ButtonType> result = dialog.showAndWait();

            if (result.isPresent() && result.get() == btnYes) {
                try {
                    Topic topic = topicDAO.findById(log.getItemId());
                    if (topic != null) {
                        topic.markLearningDone(sessionDate);
                        topicDAO.update(topic);
                    }
                    dailyLogDAO.updateStatus(log.getLogId(), "DONE");
                    replan();
                } catch (SQLException e) {
                    showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
                }
            } else if (result.isPresent() && result.get() == btnNotYet) {
                TextInputDialog input = new TextInputDialog("2.0");
                UiKit.styleDialog(input);
                input.setTitle("Estimate Remaining Hours");
                input.setHeaderText("How many more hours do you estimate you need to finish learning?");
                input.setContentText("Remaining Hours:");
                Optional<String> hoursResult = input.showAndWait();
                hoursResult.ifPresent(hrsStr -> {
                    try {
                        double hrs = Double.parseDouble(hrsStr.trim());
                        Topic topic = topicDAO.findById(log.getItemId());
                        if (topic != null) {
                            topic.markLearningNotYet(hrs);
                            topicDAO.update(topic);
                        }
                        dailyLogDAO.updateStatus(log.getLogId(), "CONTINUING");
                        replan();
                    } catch (Exception ex) {
                        showAlert(Alert.AlertType.ERROR, "Error", "Invalid number: " + ex.getMessage());
                    }
                });
            }

        } else if ("TOPIC_REVISE".equalsIgnoreCase(itemType)) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            UiKit.styleDialog(dialog);
            dialog.setTitle("Revision Progress");
            dialog.setHeaderText("Revision: " + title);
            dialog.setContentText("Did you complete this revision cycle?");

            ButtonType btnYes = new ButtonType("Yes, Done", ButtonBar.ButtonData.YES);
            ButtonType btnNotYet = new ButtonType("Not Yet", ButtonBar.ButtonData.NO);
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            dialog.getButtonTypes().setAll(btnYes, btnNotYet, btnCancel);
            Optional<ButtonType> result = dialog.showAndWait();

            if (result.isPresent() && result.get() == btnYes) {
                try {
                    Topic topic = topicDAO.findById(log.getItemId());
                    if (topic != null) {
                        topic.markRevisionDone(sessionDate);
                        topicDAO.update(topic);
                    }
                    dailyLogDAO.updateStatus(log.getLogId(), "DONE");
                    replan();
                } catch (SQLException e) {
                    showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
                }
            } else if (result.isPresent() && result.get() == btnNotYet) {
                TextInputDialog input = new TextInputDialog("1.0");
                UiKit.styleDialog(input);
                input.setTitle("Estimate Revision Hours Left");
                input.setHeaderText("Cycle remains open. How many more hours left for this cycle?");
                input.setContentText("Hours Left:");
                Optional<String> hoursResult = input.showAndWait();
                hoursResult.ifPresent(hrsStr -> {
                    try {
                        double hrs = Double.parseDouble(hrsStr.trim());
                        Topic topic = topicDAO.findById(log.getItemId());
                        if (topic != null) {
                            topic.markRevisionNotYet(hrs);
                            topicDAO.update(topic);
                        }
                        dailyLogDAO.updateStatus(log.getLogId(), "CONTINUING");
                        replan();
                    } catch (Exception ex) {
                        showAlert(Alert.AlertType.ERROR, "Error", "Invalid number: " + ex.getMessage());
                    }
                });
            }

        } else if ("SKILL_LEARN".equalsIgnoreCase(itemType)) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            UiKit.styleDialog(dialog);
            dialog.setTitle("Skill Subtopic Learning Progress");
            dialog.setHeaderText(title);
            dialog.setContentText("Did you finish learning this skill subtopic?");

            ButtonType btnYes = new ButtonType("Yes, Finished", ButtonBar.ButtonData.YES);
            ButtonType btnNotYet = new ButtonType("Not Yet", ButtonBar.ButtonData.NO);
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            dialog.getButtonTypes().setAll(btnYes, btnNotYet, btnCancel);
            Optional<ButtonType> result = dialog.showAndWait();

            if (result.isPresent() && result.get() == btnYes) {
                try {
                    SkillSubtopic st = skillSubtopicDAO.findById(log.getItemId());
                    if (st != null) {
                        // Use service to mark as learned (sets learned=1, learned_on, revision_due_date, learn_remaining_hours=0)
                        skillProgressService.logLearningProgress(st.getSubtopicId(), st.getLearnRemainingHours(), sessionDate);
                    }
                    dailyLogDAO.updateStatus(log.getLogId(), "DONE");
                    replan();
                } catch (SQLException e) {
                    showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
                }
            } else if (result.isPresent() && result.get() == btnNotYet) {
                TextInputDialog input = new TextInputDialog("1.0");
                UiKit.styleDialog(input);
                input.setTitle("Estimate Learn Hours Left");
                input.setHeaderText("How many more hours to finish learning this subtopic?");
                input.setContentText("Remaining Hours:");
                Optional<String> hoursResult = input.showAndWait();
                hoursResult.ifPresent(hrsStr -> {
                    try {
                        double hrs = Double.parseDouble(hrsStr.trim());
                        SkillSubtopic st = skillSubtopicDAO.findById(log.getItemId());
                        if (st != null) {
                            st.setLearnRemainingHours(Math.max(0.05, hrs));
                            skillSubtopicDAO.update(st);
                        }
                        dailyLogDAO.updateStatus(log.getLogId(), "CONTINUING");
                        replan();
                    } catch (Exception ex) {
                        showAlert(Alert.AlertType.ERROR, "Error", "Invalid number: " + ex.getMessage());
                    }
                });
            }

        } else if ("SKILL_REVISE".equalsIgnoreCase(itemType)) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            UiKit.styleDialog(dialog);
            dialog.setTitle("Skill Subtopic Revision Progress");
            dialog.setHeaderText(title);
            dialog.setContentText("Did you finish revising this skill subtopic?");

            ButtonType btnYes = new ButtonType("Yes, Finished", ButtonBar.ButtonData.YES);
            ButtonType btnNotYet = new ButtonType("Not Yet", ButtonBar.ButtonData.NO);
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            dialog.getButtonTypes().setAll(btnYes, btnNotYet, btnCancel);
            Optional<ButtonType> result = dialog.showAndWait();

            if (result.isPresent() && result.get() == btnYes) {
                try {
                    SkillSubtopic st = skillSubtopicDAO.findById(log.getItemId());
                    if (st != null) {
                        // Use service to mark as revised (sets revised=1, revised_on, revise_remaining_hours=0)
                        skillProgressService.logRevisionProgress(st.getSubtopicId(), st.getReviseRemainingHours(), sessionDate);
                    }
                    dailyLogDAO.updateStatus(log.getLogId(), "DONE");
                    replan();
                } catch (SQLException e) {
                    showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
                }
            } else if (result.isPresent() && result.get() == btnNotYet) {
                TextInputDialog input = new TextInputDialog("0.5");
                UiKit.styleDialog(input);
                input.setTitle("Estimate Revision Hours Left");
                input.setHeaderText("How many more hours to finish revising this subtopic?");
                input.setContentText("Remaining Hours:");
                Optional<String> hoursResult = input.showAndWait();
                hoursResult.ifPresent(hrsStr -> {
                    try {
                        double hrs = Double.parseDouble(hrsStr.trim());
                        SkillSubtopic st = skillSubtopicDAO.findById(log.getItemId());
                        if (st != null) {
                            st.setReviseRemainingHours(Math.max(0.05, hrs));
                            skillSubtopicDAO.update(st);
                        }
                        dailyLogDAO.updateStatus(log.getLogId(), "CONTINUING");
                        replan();
                    } catch (Exception ex) {
                        showAlert(Alert.AlertType.ERROR, "Error", "Invalid number: " + ex.getMessage());
                    }
                });
            }

        } else if ("SKILL".equalsIgnoreCase(itemType)) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            UiKit.styleDialog(dialog);
            dialog.setTitle("Skill Session Log");
            dialog.setHeaderText("Skill: " + title);
            dialog.setContentText("Did you practice this skill session?");

            ButtonType btnYes = new ButtonType("Yes, Practiced", ButtonBar.ButtonData.YES);
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            dialog.getButtonTypes().setAll(btnYes, btnCancel);
            Optional<ButtonType> result = dialog.showAndWait();

            if (result.isPresent() && result.get() == btnYes) {
                try {
                    skillDAO.updateLastPracticed(log.getItemId(), sessionDate);
                    dailyLogDAO.updateStatus(log.getLogId(), "DONE");
                    replan();
                } catch (SQLException e) {
                    showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
                }
            }

        } else if ("PYQ_PRACTICE".equalsIgnoreCase(itemType)) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            UiKit.styleDialog(dialog);
            dialog.setTitle("PYQ Practice Progress");
            dialog.setHeaderText("Exam PYQ: " + title);
            dialog.setContentText("Did you complete this previous-year questions practice?");

            ButtonType btnYes = new ButtonType("Yes, Completed", ButtonBar.ButtonData.YES);
            ButtonType btnNotYet = new ButtonType("Not Yet", ButtonBar.ButtonData.NO);
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            dialog.getButtonTypes().setAll(btnYes, btnNotYet, btnCancel);
            Optional<ButtonType> result = dialog.showAndWait();

            if (result.isPresent() && result.get() == btnYes) {
                try {
                    Exam exam = examDAO.findById(log.getItemId());
                    if (exam != null) {
                        exam.setPyqRemainingHours(0.0);
                        examDAO.update(exam);
                    }
                    dailyLogDAO.updateStatus(log.getLogId(), "DONE");
                    replan();
                } catch (SQLException e) {
                    showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
                }
            } else if (result.isPresent() && result.get() == btnNotYet) {
                TextInputDialog input = new TextInputDialog("1.0");
                UiKit.styleDialog(input);
                input.setTitle("Estimate PYQ Hours Left");
                input.setHeaderText("How many more hours of PYQ practice do you want to complete?");
                input.setContentText("Remaining Hours:");
                Optional<String> hoursResult = input.showAndWait();
                hoursResult.ifPresent(hrsStr -> {
                    try {
                        double hrs = Double.parseDouble(hrsStr.trim());
                        Exam exam = examDAO.findById(log.getItemId());
                        if (exam != null) {
                            exam.setPyqRemainingHours(hrs);
                            examDAO.update(exam);
                        }
                        dailyLogDAO.updateStatus(log.getLogId(), "CONTINUING");
                        replan();
                    } catch (Exception ex) {
                        showAlert(Alert.AlertType.ERROR, "Error", "Invalid number: " + ex.getMessage());
                    }
                });
            }
        }
    }

    private void handleToggleDone(DayRow row, boolean isDone) {
        if (row == null || row.getDailyLogEntry() == null) return;
        DailyLogEntry log = row.getDailyLogEntry();
        LocalDate sessionDate = log.getLogDate() != null ? log.getLogDate() : selectedDate;

        if (isDone) {
            if (!canLogProgress(sessionDate, LocalDate.now())) {
                showAlert(Alert.AlertType.INFORMATION, "Session In Future",
                        "This session is planned for " + sessionDate + " which is in the future.\n" +
                        "You can only log progress on today's or past sessions.");
                refreshData();
                return;
            }
            try {
                String itemType = log.getItemType();
                if ("TOPIC_LEARN".equalsIgnoreCase(itemType)) {
                    Topic topic = topicDAO.findById(log.getItemId());
                    if (topic != null) {
                        topic.markLearningDone(sessionDate);
                        topicDAO.update(topic);
                    }
                } else if ("TOPIC_REVISE".equalsIgnoreCase(itemType)) {
                    Topic topic = topicDAO.findById(log.getItemId());
                    if (topic != null) {
                        topic.markRevisionDone(sessionDate);
                        topicDAO.update(topic);
                    }
                } else if ("SKILL_LEARN".equalsIgnoreCase(itemType)) {
                    skillProgressService.logLearningProgress(log.getItemId(), log.getDurationHours(), sessionDate);
                } else if ("SKILL_REVISE".equalsIgnoreCase(itemType)) {
                    skillProgressService.logRevisionProgress(log.getItemId(), log.getDurationHours(), sessionDate);
                } else if ("PYQ_PRACTICE".equalsIgnoreCase(itemType)) {
                    Exam exam = examDAO.findById(log.getItemId());
                    if (exam != null) {
                        exam.setPyqRemainingHours(0.0);
                        examDAO.update(exam);
                    }
                } else if ("SKILL".equalsIgnoreCase(itemType)) {
                    skillDAO.updateLastPracticed(log.getItemId(), sessionDate);
                }
                dailyLogDAO.updateStatus(log.getLogId(), "DONE");
                replan();
            } catch (SQLException e) {
                showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
            }
        } else {
            // Revert session back to PENDING or PLANNED and reverse topic completion
            try {
                String newStatus = sessionDate.isAfter(LocalDate.now()) ? "PLANNED" : "PENDING";
                dailyLogDAO.updateStatus(log.getLogId(), newStatus);
                String itemType = log.getItemType();
                if ("TOPIC_LEARN".equalsIgnoreCase(itemType)) {
                    Topic topic = topicDAO.findById(log.getItemId());
                    if (topic != null) {
                        topic.setPhase("LEARNING");
                        double restored = Math.min(topic.getHoursNeeded(), topic.getRemainingHours() + log.getDurationHours());
                        topic.setRemainingHours(restored);
                        topicDAO.update(topic);
                    }
                } else if ("TOPIC_REVISE".equalsIgnoreCase(itemType)) {
                    Topic topic = topicDAO.findById(log.getItemId());
                    if (topic != null) {
                        double restored = Math.min(topic.getRevisionHoursNeeded(), topic.getRevisionRemainingHours() + log.getDurationHours());
                        topic.setRevisionRemainingHours(restored);
                        topicDAO.update(topic);
                    }
                } else if ("SKILL_LEARN".equalsIgnoreCase(itemType)) {
                    SkillSubtopic st = skillSubtopicDAO.findById(log.getItemId());
                    if (st != null) {
                        if (st.isLearned()) {
                            skillSubtopicDAO.unmarkLearned(st.getSubtopicId());
                        }
                        double restored = Math.min(st.getLearnHours(), st.getLearnRemainingHours() + log.getDurationHours());
                        st.setLearnRemainingHours(restored);
                        skillSubtopicDAO.update(st);
                    }
                } else if ("SKILL_REVISE".equalsIgnoreCase(itemType)) {
                    SkillSubtopic st = skillSubtopicDAO.findById(log.getItemId());
                    if (st != null) {
                        if (st.isRevised()) {
                            skillSubtopicDAO.unmarkRevised(st.getSubtopicId());
                        }
                        double restored = Math.min(st.getReviseHours(), st.getReviseRemainingHours() + log.getDurationHours());
                        st.setReviseRemainingHours(restored);
                        skillSubtopicDAO.update(st);
                    }
                } else if ("PYQ_PRACTICE".equalsIgnoreCase(itemType)) {
                    Exam exam = examDAO.findById(log.getItemId());
                    if (exam != null) {
                        exam.setPyqRemainingHours(Math.max(exam.getPyqRemainingHours(), log.getDurationHours()));
                        examDAO.update(exam);
                    }
                }
                replan();
            } catch (SQLException e) {
                showAlert(Alert.AlertType.ERROR, "Error", e.getMessage());
            }
        }
    }

    private void updateToolbarButtons() {
        if (btnResize == null || btnCancelClass == null) return;
        boolean isBusy = replanRunner != null && replanRunner.busyProperty().get();
        List<DayRow> selected = dayScheduleView != null ? dayScheduleView.getSelectedItems() : List.of();

        boolean hasResizable = selected.stream().anyMatch(r ->
                (!r.isFixed() && r.getDailyLogEntry() != null
                        && !"DONE".equalsIgnoreCase(r.getDailyLogEntry().getStatus()))
                        || isResizableFixed(r)
        );
        btnResize.setDisable(isBusy || !hasResizable);

        List<DayRow> cancellableSelected = selected.stream().filter(this::isCancellableClass).toList();
        boolean hasCancellable = !cancellableSelected.isEmpty();

        if (hasCancellable && cancellableSelected.stream().allMatch(DayRow::isCancelled)) {
            btnCancelClass.setText("Restore Selected Class");
            btnCancelClass.getStyleClass().removeAll("btn-danger", "btn-primary");
            btnCancelClass.getStyleClass().add("btn-primary");
            btnCancelClass.setTooltip(new Tooltip("Restore selected cancelled class on " + DateUtils.formatDate(selectedDate)));
        } else {
            btnCancelClass.setText("Cancel class");
            btnCancelClass.getStyleClass().removeAll("btn-danger", "btn-primary");
            btnCancelClass.getStyleClass().add("btn-danger");
            btnCancelClass.setTooltip(new Tooltip("Cancel recurring class on " + DateUtils.formatDate(selectedDate) + " only"));
        }
        btnCancelClass.setDisable(isBusy || !hasCancellable);
    }

    private boolean isCancellableClass(DayRow r) {
        if (r == null) return false;
        String t = r.getType();
        return ("CLASS".equalsIgnoreCase(t) || "LAB".equalsIgnoreCase(t) || "TUTORIAL".equalsIgnoreCase(t))
                && r.getTimetableEntryId() != null;
    }

    /** A class, lab or tutorial occurrence can be resized for this day only. */
    private boolean isResizableFixed(DayRow r) {
        return r != null && r.isFixed() && !r.isCancelled() && isCancellableClass(r)
                && r.getStart() != null;
    }

    private void handleResizeSelectedSessions() {
        List<DayRow> selected = new ArrayList<>(dayScheduleView.getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Select Study Sessions",
                    "Please select one or more scheduled study sessions to resize.");
            return;
        }

        List<DayRow> resizable = new ArrayList<>();
        List<DayRow> fixedResizable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (DayRow r : selected) {
            if (isResizableFixed(r)) {
                if (selectedDate.isBefore(LocalDate.now())) {
                    skipped.add(r.getLabel() + " (The date has passed)");
                } else {
                    fixedResizable.add(r);
                }
            } else if (r.isFixed()) {
                skipped.add(r.getLabel() + " (Fixed commitment)");
            } else if (r.getDailyLogEntry() != null && "DONE".equalsIgnoreCase(r.getDailyLogEntry().getStatus())) {
                skipped.add(r.getLabel() + " (Already completed)");
            } else if (r.getDailyLogEntry() != null) {
                resizable.add(r);
            }
        }

        if (resizable.isEmpty() && fixedResizable.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Cannot Resize",
                    "None of the selected rows are resizable.\n" +
                            (skipped.isEmpty() ? "" : "Skipped:\n• " + String.join("\n• ", skipped)));
            return;
        }

        if (!skipped.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Notice",
                    "The following selected row(s) cannot be resized and will be skipped:\n• " +
                            String.join("\n• ", skipped));
        }

        double currentDur = !resizable.isEmpty()
                ? resizable.get(0).getDurationHours()
                : fixedResizable.get(0).getDurationHours();
        TextInputDialog dialog = new TextInputDialog(DurationFormat.format(currentDur));
        UiKit.styleDialog(dialog);
        dialog.setTitle("Resize sessions");
        int count = resizable.size() + fixedResizable.size();
        dialog.setHeaderText("Resize " + count + " row(s) on " + DateUtils.formatDate(selectedDate) + "\n" +
                "Fixed classes change for this day only. Enter duration (e.g. 1h 30m, 90m, 1:30, 1.5; min 15m, max 8h):");
        dialog.setContentText("New duration:");

        Optional<String> res = dialog.showAndWait();
        if (res.isEmpty()) return;

        double newDurationHours;
        try {
            newDurationHours = DurationFormat.parse(res.get());
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Invalid Duration", "Could not parse duration: " + res.get());
            return;
        }

        if (newDurationHours < 0.25 || newDurationHours > 8.0) {
            showAlert(Alert.AlertType.ERROR, "Invalid Duration", "Duration must be between 15 minutes (0.25h) and 8 hours (8.0h).");
            return;
        }

        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null || active.getSemesterId() == null) {
                showAlert(Alert.AlertType.ERROR, "No Active Semester", "Cannot resize without an active semester.");
                return;
            }

            if (!fixedResizable.isEmpty()) {
                int minutes = (int) Math.round(newDurationHours * 60);
                for (DayRow row : fixedResizable) {
                    LocalTime newEnd = row.getStart().plusMinutes(minutes);
                    if (!newEnd.isAfter(row.getStart())) {
                        showAlert(Alert.AlertType.ERROR, "Invalid Duration",
                                "The new end time must be after the start of " + row.getLabel() + ".");
                        return;
                    }
                    for (DayRow other : dayScheduleView.getTable().getItems()) {
                        if (other == row || !other.isFixed() || other.isCancelled()
                                || other.getStart() == null || other.getEnd() == null) {
                            continue;
                        }
                        if (row.getStart().isBefore(other.getEnd()) && newEnd.isAfter(other.getStart())) {
                            showAlert(Alert.AlertType.INFORMATION, "Cannot Resize",
                                    row.getLabel() + " would overlap " + other.getLabel()
                                            + " (" + DateUtils.formatTime(other.getStart()) + " to "
                                            + DateUtils.formatTime(other.getEnd()) + ").");
                            return;
                        }
                    }
                    timetableEditService.resizeFixedOccurrence(active.getSemesterId(), selectedDate,
                            row.getTimetableEntryId(), row.getStart(), newEnd, row.getType(), row.getLabel());
                }
            }

            if (!resizable.isEmpty()) {
                List<Integer> logIds = resizable.stream().map(r -> r.getDailyLogEntry().getLogId()).toList();
                timetableEditService.resizeMultipleSessions(active.getSemesterId(), selectedDate, logIds, newDurationHours);
            }

            if (replanRunner != null) {
                replanRunner.run(active.getSemesterId(), LocalDate.now(), () -> {
                    refreshData();
                    if (onDataChanged != null) onDataChanged.run();
                }, null);
            } else {
                refreshData();
                if (onDataChanged != null) onDataChanged.run();
            }
        } catch (IllegalArgumentException iae) {
            showAlert(Alert.AlertType.ERROR, "Cannot Resize", iae.getMessage());
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Error", ex.getMessage());
        }
    }

    private void handleCancelOrRestoreClass() {
        List<DayRow> selected = new ArrayList<>(dayScheduleView.getSelectedItems());
        if (selected.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Select Class", "Please select a class, lab, or tutorial to cancel or restore.");
            return;
        }

        if (selectedDate.isBefore(LocalDate.now())) {
            showAlert(Alert.AlertType.INFORMATION, "Past Date", "Cannot cancel or restore classes for past dates.");
            return;
        }

        List<DayRow> cancellable = selected.stream().filter(this::isCancellableClass).toList();
        if (cancellable.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Invalid Selection", "Only rows of type CLASS, LAB, or TUTORIAL are cancellable.");
            return;
        }

        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null || active.getSemesterId() == null) {
                showAlert(Alert.AlertType.INFORMATION, "No Semester", "No active semester.");
                return;
            }
            int semId = active.getSemesterId();

            boolean allCancelled = cancellable.stream().allMatch(DayRow::isCancelled);
            if (allCancelled) {
                // Restore flow
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                UiKit.styleDialog(confirm);
                confirm.setTitle("Restore Class");
                confirm.setHeaderText("Restore class on " + DateUtils.formatDate(selectedDate) + "?");
                String labelText = cancellable.size() == 1
                        ? cancellable.get(0).getLabel() + " (" + DateUtils.formatTime(cancellable.get(0).getStart()) + " - " + DateUtils.formatTime(cancellable.get(0).getEnd()) + ")"
                        : cancellable.size() + " classes";
                confirm.setContentText("Restore " + labelText + " on " + DateUtils.formatDate(selectedDate) + "?\nThe class will be reinstated in your timetable.");
                ButtonType btnRestore = new ButtonType("Restore Class", ButtonBar.ButtonData.YES);
                ButtonType btnKeep = new ButtonType("Keep Cancelled", ButtonBar.ButtonData.CANCEL_CLOSE);
                confirm.getButtonTypes().setAll(btnRestore, btnKeep);

                Optional<ButtonType> res = confirm.showAndWait();
                if (res.isPresent() && res.get() == btnRestore) {
                    for (DayRow r : cancellable) {
                        semesterService.recordRemoveCancellation(selectedDate, r.getTimetableEntryId());
                    }
                    if (replanRunner != null) {
                        replanRunner.run(semId, LocalDate.now(), () -> {
                            refreshData();
                            if (onDataChanged != null) onDataChanged.run();
                        }, null);
                    } else {
                        replanService.regenerateSemesterPlan(semId, LocalDate.now());
                        refreshData();
                        if (onDataChanged != null) onDataChanged.run();
                    }
                }
                return;
            }

            // Cancellation flow
            // Guard: class already ended today cannot be cancelled
            if (selectedDate.equals(LocalDate.now())) {
                LocalTime now = LocalTime.now();
                for (DayRow r : cancellable) {
                    if (r.getEnd() != null && r.getEnd().isBefore(now)) {
                        showAlert(Alert.AlertType.INFORMATION, "Class Ended",
                                "Cannot cancel a class that has already ended today (" + r.getLabel() +
                                        " ended at " + DateUtils.formatTime(r.getEnd()) + ").");
                        return;
                    }
                }
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            UiKit.styleDialog(confirm);
            confirm.setTitle("Cancel Class");
            confirm.setHeaderText("Cancel class on " + DateUtils.formatDate(selectedDate) + " only?");
            String msg;
            if (cancellable.size() == 1) {
                DayRow r = cancellable.get(0);
                msg = String.format("Cancel %s (%s - %s) on %s only?\nYour normal timetable is unchanged on all other dates.",
                        r.getLabel(), DateUtils.formatTime(r.getStart()), DateUtils.formatTime(r.getEnd()), DateUtils.formatDate(selectedDate));
            } else {
                StringBuilder sb = new StringBuilder("Cancel the following classes on " + DateUtils.formatDate(selectedDate) + " only?\n");
                for (DayRow r : cancellable) {
                    sb.append("- ").append(r.getLabel()).append(" (")
                            .append(DateUtils.formatTime(r.getStart())).append(" - ")
                            .append(DateUtils.formatTime(r.getEnd())).append(")\n");
                }
                sb.append("Your normal timetable is unchanged on all other dates.");
                msg = sb.toString();
            }
            confirm.setContentText(msg);

            ButtonType btnCancel = new ButtonType("Cancel class", ButtonBar.ButtonData.YES);
            ButtonType btnKeep = new ButtonType("Keep", ButtonBar.ButtonData.CANCEL_CLOSE);
            confirm.getButtonTypes().setAll(btnCancel, btnKeep);

            Optional<ButtonType> res = confirm.showAndWait();
            if (res.isPresent() && res.get() == btnCancel) {
                for (DayRow r : cancellable) {
                    semesterService.recordCancellation(selectedDate, r.getTimetableEntryId());
                }
                if (replanRunner != null) {
                    replanRunner.run(semId, LocalDate.now(), () -> {
                        refreshData();
                        if (onDataChanged != null) onDataChanged.run();
                    }, null);
                } else {
                    replanService.regenerateSemesterPlan(semId, LocalDate.now());
                    refreshData();
                    if (onDataChanged != null) onDataChanged.run();
                }
            }
        } catch (SQLException ex) {
            showAlert(Alert.AlertType.ERROR, "Error", ex.getMessage());
        }
    }

    private void replan() {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                if (replanRunner != null) {
                    replanRunner.run(active.getSemesterId(), LocalDate.now(), () -> {
                        refreshData();
                        if (onDataChanged != null) {
                            onDataChanged.run();
                        }
                    }, null);
                    return;
                }
                replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
            }
            refreshData();
            if (onDataChanged != null) {
                onDataChanged.run();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void refreshData() {
        try {
            String dateStr = selectedDate.format(DAY_TITLE);
            Semester active = semesterDAO.getActiveSemester();

            // Label exam-period days: the weekly timetable is not followed on them.
            String headerText = dateStr;
            if (active != null && active.getSemesterId() != null) {
                try {
                    ExamWindowService examWindowService = new ExamWindowService();
                    String examType = examWindowService.examTypeOnDate(active.getSemesterId(), selectedDate).orElse(null);
                    if (examType != null) {
                        headerText += ": " + examType + " exam period, no classes";
                    }
                } catch (SQLException ignored) {
                }
            }
            if (dateSubtitle != null) {
                dateSubtitle.setText(headerText);
            }
            
            if (active != null && active.getSemesterId() != null) {
                List<DayRow> rows = dayScheduleService.build(active.getSemesterId(), selectedDate, true);
                if (dayScheduleView != null) {
                    dayScheduleView.setRows(rows);
                }

                // Update progress bar
                double plannedHours = 0.0;
                double doneHours = 0.0;
                for (DayRow r : rows) {
                    if (!r.isFixed() && !r.isCancelled()) {
                        plannedHours += r.getDurationHours();
                        DailyLogEntry log = r.getDailyLogEntry();
                        if (log != null && "DONE".equalsIgnoreCase(log.getStatus())) {
                            doneHours += r.getDurationHours();
                        }
                    }
                }
                double pct = plannedHours > 0 ? (doneHours / plannedHours) : 0.0;
                if (todayProgressBar != null) {
                    todayProgressBar.setProgress(Math.min(pct, 1.0));
                }
                if (todayProgressLabel != null) {
                    String prefix = selectedDate.equals(LocalDate.now()) ? "Today's Study Progress: " : "Study Progress (" + DateUtils.formatDate(selectedDate) + "): ";
                    todayProgressLabel.setText(String.format("%s%s / %s (%.0f%% completed)",
                            prefix, DurationFormat.format(doneHours), DurationFormat.format(plannedHours), pct * 100.0));
                }

                // Check for exams without syllabus scope
                if (scopeWarningBanner != null) {
                    refreshScopeWarning(examScopeService.findExamsWithoutScope(active.getSemesterId()));
                }

                // Check for undated exams
                if (undatedDisclosure != null) {
                    refreshUndatedDisclosure(examDAO.findBySemester(active.getSemesterId()));
                }
            } else {
                if (dayScheduleView != null) {
                    dayScheduleView.setRows(List.of());
                }
                if (scopeWarningBanner != null) {
                    scopeWarningBanner.setVisible(false);
                    scopeWarningBanner.setManaged(false);
                }
                if (undatedDisclosure != null) {
                    undatedDisclosure.setVisible(false);
                    undatedDisclosure.setManaged(false);
                }
            }

            if (feasibilityBarView != null) {
                feasibilityBarView.refresh();
            }
            updateToolbarButtons();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showAlert(Alert.AlertType type, String title, String msg) {
        if (type == Alert.AlertType.ERROR || type == Alert.AlertType.WARNING) {
            UiKit.error(dashboardWindow(), title, msg);
        } else {
            UiKit.toast(title + ": " + msg, UiKit.Kind.INFO);
        }
    }

    private javafx.stage.Window dashboardWindow() {
        return dayScheduleView != null && dayScheduleView.getScene() != null
                ? dayScheduleView.getScene().getWindow() : null;
    }
}
