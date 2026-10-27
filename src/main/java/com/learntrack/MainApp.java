package com.learntrack;

import com.learntrack.dao.*;
import com.learntrack.model.Semester;
import com.learntrack.service.*;
import com.learntrack.ui.ReplanRunner;
import com.learntrack.ui.Theme;
import com.learntrack.ui.UiKit;
import com.learntrack.ui.charts.FeasibilityBarView;
import com.learntrack.ui.forms.ExamFormController;
import com.learntrack.ui.forms.SkillFormController;
import com.learntrack.ui.forms.TopicFormController;
import com.learntrack.ui.setup.ExamPeriodRoutineWizard;
import com.learntrack.ui.setup.TimetableSetupWizard;
import com.learntrack.ui.views.CalendarGridView;
import com.learntrack.ui.views.DashboardView;
import com.learntrack.ui.views.DatesheetAnalyserView;
import com.learntrack.util.DBConnection;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.*;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * LearnTrack JavaFX Desktop Application entry point.
 * Wires the DAO, Service, and UI layers together.
 * Plan Section 2, 14, 15.
 */
public class MainApp extends Application {

    private SemesterDAO semesterDAO;
    private TimetableDAO timetableDAO;
    private ExamPeriodRoutineDAO routineDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SkillDAO skillDAO;
    private DailyLogDAO dailyLogDAO;
    private SettingsDAO settingsDAO;

    private ScoringService scoringService;
    private PriorityQueueService priorityQueueService;
    private FreeSlotService freeSlotService;
    private ExamPeriodFreeSlotService examPeriodFreeSlotService;
    private SemesterReplanService replanService;
    private SemesterService semesterService;
    private ExamScopeService examScopeService;

    private DashboardView dashboardView;
    private CalendarGridView calendarGridView;
    private DatesheetAnalyserView datesheetAnalyserView;
    private ExamFormController examFormController;
    private TopicFormController topicFormController;
    private SkillFormController skillFormController;
    private FeasibilityBarView feasibilityBarView;

    private ReplanRunner replanRunner;
    private Stage primaryStage;
    private TabPane mainTabPane;
    private Tab timetableTab;
    private TimetableSetupWizard timetableWizard;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        primaryStage.setTitle("LearnTrack: Study Planner");
        UiKit.loadFonts();

        try {
            // 1. Initialize DB and schema
            try (Connection conn = DBConnection.getConnection()) {
                // Trigger schema check
            }

            // 2. Initialize DAOs
            semesterDAO = new SemesterDAO();
            timetableDAO = new TimetableDAO();
            routineDAO = new ExamPeriodRoutineDAO();
            dateOverrideDAO = new DateOverrideDAO();
            examDAO = new ExamDAO();
            topicDAO = new TopicDAO();
            skillDAO = new SkillDAO();
            dailyLogDAO = new DailyLogDAO();
            settingsDAO = new SettingsDAO();

            // 3. Initialize Services
            scoringService = new ScoringService();
            priorityQueueService = new PriorityQueueService(scoringService);
            freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
            examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);
            replanService = new SemesterReplanService(
                    timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                    freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService,
                    routineDAO
            );
            semesterService = new SemesterService(
                    semesterDAO, timetableDAO, routineDAO, examDAO, topicDAO, dailyLogDAO,
                    dateOverrideDAO, settingsDAO, replanService
            );
            examScopeService = new ExamScopeService();
            // Pass the exam-period free-slot service so feasibility capacity follows the same
            // window rule as the planner (no class hours inside a dated MST/EST window).
            FeasibilityService feasibilityService = new FeasibilityService(
                    topicDAO, examDAO, timetableDAO, freeSlotService,
                    new ExamScopeDAO(), scoringService, new ExamWindowService(), examPeriodFreeSlotService
            );
            ExamPeriodCapacityService capacityService = new ExamPeriodCapacityService(
                    examDAO, topicDAO, timetableDAO, settingsDAO, examPeriodFreeSlotService
            );
            TimetableEditService timetableEditService = new TimetableEditService(
                    dailyLogDAO, replanService, freeSlotService, examPeriodFreeSlotService, routineDAO, examDAO
            );

            replanRunner = new ReplanRunner(replanService);

            // 4. Check active semester or create default
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                active = new Semester(null, "Semester 1", "ACTIVE");
                int id = semesterDAO.insert(active);
                active.setSemesterId(id);
            }

            Runnable refreshAll = this::refreshAllViews;

            // An empty timetable no longer interrupts startup with a modal wizard: the Timetable
            // tab is opened once the window is built, and nothing is written until the user saves.
            boolean needsTimetableSetup = timetableDAO.findBySemester(active.getSemesterId()).isEmpty()
                    || timetableDAO.findRoutinesBySemester(active.getSemesterId()).isEmpty();

            // Fix 4: Mark stale PENDING/CONTINUING rows from past days as MISSED
            int missedCount = dailyLogDAO.markMissedBeforeDate(LocalDate.now());

            // Startup repair: resync topics if any untouched topic has stale hours
            try {
                boolean needsResync = checkForStaleTopicHours(active.getSemesterId());
                if (needsResync) {
                    System.out.println("Startup repair: resyncing stale topic hours...");
                    examScopeService.rebuildPlan(active.getSemesterId());
                }
            } catch (Exception e) {
                System.err.println("Startup repair failed: " + e.getMessage());
                e.printStackTrace();
            }

            // Check and prompt to clean up orphaned exam-only subjects
            checkAndPromptOrphanedSubjects(active.getSemesterId());

            // Startup repair: an announced datesheet's stored window must be the user's own exam span.
            // Older applies stored the whole sheet range, which hid the weekly timetable on days
            // without an exam of the user (see DatesheetAnalyserService.apply).
            try {
                ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
                ExamTimelineService examTimelineService = new ExamTimelineService();
                for (String examType : List.of("MST", "EST")) {
                    if (timelineDAO.isDatesheetAnnounced(active.getSemesterId(), examType)) {
                        examTimelineService.recomputeWindowFromDatesheet(active.getSemesterId(), examType);
                    }
                }
            } catch (Exception e) {
                System.err.println("Exam window repair failed: " + e.getMessage());
            }

            // 5. Unconditionally run regenerateSemesterPlan on EVERY app open (Plan Section 6 & Step 5)
            replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());

            // Fix 4: Notify user if any sessions were missed (non-blocking toast).
            if (missedCount > 0) {
                final int missed = missedCount;
                javafx.application.Platform.runLater(() -> UiKit.toast(
                        missed + " session(s) from earlier days were missed; your plan has been rebuilt.",
                        UiKit.Kind.INFO));
            }


            // 6. Build UI components
            feasibilityBarView = new FeasibilityBarView(feasibilityService, semesterDAO);

            dashboardView = new DashboardView(dailyLogDAO, topicDAO, skillDAO, examDAO, semesterDAO,
                    timetableDAO, dateOverrideDAO, replanService, semesterService, timetableEditService,
                    feasibilityBarView, refreshAll, replanRunner);

            DayScheduleService dayScheduleService = new DayScheduleService(
                    timetableDAO, dateOverrideDAO, examDAO, dailyLogDAO,
                    routineDAO, topicDAO, skillDAO
            );

            calendarGridView = new CalendarGridView(dailyLogDAO, examDAO, semesterDAO, dayScheduleService);

            ExamTimelineService examTimelineService = new ExamTimelineService();

            datesheetAnalyserView = new DatesheetAnalyserView(
                    semesterDAO, examDAO, topicDAO, timetableDAO, settingsDAO,
                    examTimelineService, capacityService, replanService, replanRunner, refreshAll
            );

            topicFormController = new TopicFormController(examDAO, semesterDAO, replanService, refreshAll, replanRunner);
            skillFormController = new SkillFormController(skillDAO, semesterDAO, replanService, refreshAll, replanRunner);

            // Main Window Layout
            TabPane tabPane = new TabPane();

            Tab tabDash = new Tab("Dashboard", dashboardView.getView());
            tabDash.setClosable(false);

            Tab tabCal = new Tab("Calendar", calendarGridView.getView());
            tabCal.setClosable(false);

            Tab tabTopics = new Tab("Subjects", topicFormController.getView());
            tabTopics.setClosable(false);

            Tab tabSkills = new Tab("Skills", skillFormController.getView());
            tabSkills.setClosable(false);

            Tab tabDatesheetAnalyser = new Tab("Datesheet Analyser", datesheetAnalyserView.getView());
            tabDatesheetAnalyser.setClosable(false);

            // onTrimRequested now opens the topic trimming dialog directly via ExamFormController
            Runnable onTrimRequested = () -> {
                tabPane.getSelectionModel().select(tabDatesheetAnalyser);
            };

            examFormController = new ExamFormController(examDAO, timetableDAO, semesterDAO, routineDAO, replanService,
                    settingsDAO, capacityService, refreshAll, onTrimRequested, replanRunner);

            Tab tabExams = new Tab("Exams", examFormController.getView());
            tabExams.setClosable(false);

            timetableWizard = new TimetableSetupWizard(
                    timetableDAO, semesterDAO, dateOverrideDAO, replanService, freeSlotService, refreshAll
            );
            timetableTab = new Tab("Timetable", timetableWizard.createTabContent(
                    createRebuildPlanButton(), createExamRoutineButton()));
            timetableTab.setClosable(false);

            Tab tabSettings = new Tab("Settings", createSettingsView());
            tabSettings.setClosable(false);

            tabPane.getTabs().addAll(
                    tabDash, tabCal, tabExams, tabTopics, tabSkills, tabDatesheetAnalyser,
                    timetableTab, tabSettings
            );
            mainTabPane = tabPane;

            // Tab switch listeners to refresh views
            tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab == tabTopics && topicFormController != null) {
                    topicFormController.loadExams();
                }
                if (replanRunner != null && replanRunner.isBusy()) return;
                if (newTab == tabDash) dashboardView.refreshData();
                else if (newTab == tabCal) calendarGridView.refreshCalendar();
                else if (newTab == tabExams) examFormController.loadExams();
                else if (newTab == tabSkills) skillFormController.loadSkills();
            });

            ProgressBar replanBar = new ProgressBar();
            replanBar.setMaxWidth(Double.MAX_VALUE);
            replanBar.setPrefHeight(6);
            replanBar.visibleProperty().bind(replanRunner.busyProperty());
            replanBar.managedProperty().bind(replanRunner.busyProperty());

            Label wordmark = new Label("LearnTrack");
            wordmark.getStyleClass().add("wordmark");
            HBox appBar = new HBox(wordmark);
            appBar.getStyleClass().add("app-bar");
            appBar.setAlignment(Pos.CENTER_LEFT);

            VBox mainLayout = new VBox(appBar, replanBar, tabPane);
            VBox.setVgrow(tabPane, Priority.ALWAYS);

            // Toast overlay sits above the content at the bottom-right of the window.
            VBox toastLayer = new VBox();
            toastLayer.getStyleClass().add("toast-layer");
            toastLayer.setMouseTransparent(true);
            toastLayer.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            StackPane.setAlignment(toastLayer, Pos.BOTTOM_RIGHT);
            UiKit.setToastLayer(toastLayer);

            StackPane appRoot = new StackPane(mainLayout, toastLayer);

            Scene scene = new Scene(appRoot,
                    readDoubleSetting("ui_window_width", 1100),
                    readDoubleSetting("ui_window_height", 750));
            Theme.apply(scene, Theme.isDark(readSetting(Theme.SETTING_KEY, "light")));

            // Keyboard: Ctrl+1 .. Ctrl+8 jump straight to a tab.
            List<Tab> orderedTabs = List.of(
                    tabDash, tabCal, tabExams, tabTopics, tabSkills, tabDatesheetAnalyser,
                    timetableTab, tabSettings);
            for (int i = 0; i < orderedTabs.size(); i++) {
                final Tab target = orderedTabs.get(i);
                scene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.valueOf("DIGIT" + (i + 1))),
                        () -> tabPane.getSelectionModel().select(target));
            }

            // Restore the last open tab, then remember every change.
            int lastTab = (int) readDoubleSetting("ui_last_tab", 0);
            tabPane.getSelectionModel().select(Math.max(0, Math.min(lastTab, orderedTabs.size() - 1)));
            tabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldV, newV) ->
                    writeSetting("ui_last_tab", String.valueOf(newV.intValue())));

            primaryStage.setScene(scene);
            primaryStage.setMinWidth(980);
            primaryStage.setMinHeight(640);
            restoreWindowBounds();
            primaryStage.setOnCloseRequest(e -> saveWindowBounds());
            primaryStage.show();

            if (needsTimetableSetup) {
                tabPane.getSelectionModel().select(timetableTab);
                UiKit.toast("Add your weekly commitments and sleep routine, then save.", UiKit.Kind.INFO);
            }

        } catch (Exception e) {
            e.printStackTrace();
            UiKit.error(null, "LearnTrack could not start.", e);
        }
    }

    private Node createSettingsView() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(24));

        HBox header = UiKit.pageHeader("Settings",
                "Study preferences, appearance and semester lifecycle. Everything here is stored locally.",
                null);

        // ---- Study & planning ----------------------------------------------
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(260);
        ColumnConstraints controlCol = new ColumnConstraints();
        controlCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, controlCol);

        Spinner<Double> pyqHoursSpinner = new Spinner<>(0.5, 10.0, 2.0, 0.5);
        Spinner<Integer> examDurationSpinner = new Spinner<>(30, 360, 120, 15);
        Spinner<Integer> estExamDurationSpinner = new Spinner<>(30, 360, 180, 15);

        try {
            pyqHoursSpinner.getValueFactory().setValue(settingsDAO.getDouble("pyq_practice_hours", 2.0));
            examDurationSpinner.getValueFactory().setValue(settingsDAO.getInt("mst_exam_duration_minutes", 120));
            estExamDurationSpinner.getValueFactory().setValue(settingsDAO.getInt("est_exam_duration_minutes", 180));
        } catch (Exception e) {
            e.printStackTrace();
        }

        addSettingRow(grid, 0, "Default PYQ practice hours",
                "Hours assumed for previous-year paper practice.", pyqHoursSpinner);
        addSettingRow(grid, 1, "MST exam duration (minutes)",
                "Length used for internal exam sittings.", examDurationSpinner);
        addSettingRow(grid, 2, "EST exam duration (minutes)",
                "Length used for end-semester exam sittings.", estExamDurationSpinner);

        Button btnSaveSettings = UiKit.primaryButton("Save Settings");
        if (replanRunner != null) {
            btnSaveSettings.disableProperty().bind(replanRunner.busyProperty());
        }
        btnSaveSettings.setOnAction(e -> {
            try {
                settingsDAO.set("pyq_practice_hours", String.valueOf(pyqHoursSpinner.getValue()));
                settingsDAO.set("mst_exam_duration_minutes", String.valueOf(examDurationSpinner.getValue()));
                settingsDAO.set("est_exam_duration_minutes", String.valueOf(estExamDurationSpinner.getValue()));

                Runnable afterReplan = () -> {
                    refreshAllViews();
                    UiKit.toast("Settings updated and semester replanned.");
                };

                Semester active = semesterDAO.getActiveSemester();
                if (active != null) {
                    if (replanRunner != null) {
                        replanRunner.run(active.getSemesterId(), LocalDate.now(), afterReplan, null);
                    } else {
                        replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
                        afterReplan.run();
                    }
                } else {
                    afterReplan.run();
                }
            } catch (Exception ex) {
                UiKit.error(primaryStage, "Settings could not be saved.", ex);
            }
        });

        HBox planningActions = new HBox(10, btnSaveSettings);
        planningActions.setAlignment(Pos.CENTER_RIGHT);
        VBox planningCard = UiKit.card(
                UiKit.sectionTitle("Study & planning"),
                UiKit.subtle("Defaults used by the planner."),
                grid,
                planningActions);

        // ---- Appearance -----------------------------------------------------
        ComboBox<String> themeChoice = new ComboBox<>();
        themeChoice.getItems().addAll("Light", "Dark");
        themeChoice.setPrefWidth(160);
        themeChoice.setValue(Theme.isDark(readSetting(Theme.SETTING_KEY, "light")) ? "Dark" : "Light");
        themeChoice.setOnAction(e -> {
            boolean dark = "Dark".equals(themeChoice.getValue());
            Theme.apply(primaryStage.getScene(), dark);
            writeSetting(Theme.SETTING_KEY, dark ? "dark" : "light");
            UiKit.toast(dark ? "Dark theme enabled." : "Light theme enabled.", UiKit.Kind.INFO);
        });

        GridPane appearanceGrid = new GridPane();
        appearanceGrid.setHgap(16);
        appearanceGrid.setVgap(12);
        addSettingRow(appearanceGrid, 0, "Theme",
                "Applies immediately and is remembered on the next launch.", themeChoice);
        appearanceGrid.getColumnConstraints().addAll(labelCol, controlCol);

        Button btnResetWindow = UiKit.secondaryButton("Reset window size & position");
        btnResetWindow.setOnAction(e -> resetWindowBounds());
        GridPane windowGrid = new GridPane();
        windowGrid.setHgap(16);
        windowGrid.setVgap(12);
        addSettingRow(windowGrid, 0, "Window layout",
                "Size, position and the last open tab are restored automatically.", btnResetWindow);
        windowGrid.getColumnConstraints().addAll(labelCol, controlCol);

        VBox appearanceCard = UiKit.card(
                UiKit.sectionTitle("Appearance & window"),
                UiKit.subtle("Light is the default. Dark mode keeps the same layout and contrast rules."),
                appearanceGrid,
                windowGrid);

        // Semester Rollover Card
        Button btnNewSemester = UiKit.dangerTextButton("Start New Semester…");
        btnNewSemester.setOnAction(e -> handleStartNewSemester());

        VBox rolloverCard = UiKit.card(
                UiKit.sectionTitle("Start new semester"),
                UiKit.subtle("Starting a new semester permanently deletes the previous semester's exams, topics and weekly "
                        + "timetable commitments. Skills and skill logs are never touched and will be preserved."),
                btnNewSemester);
        rolloverCard.getStyleClass().add("banner-danger");

        VBox content = new VBox(16, header, planningCard, appearanceCard, rolloverCard);
        content.setPadding(new Insets(24));
        content.getStyleClass().addAll("page", "content-narrow");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        return scroll;
    }

    /** Plan maintenance lives with the timetable now that Settings no longer carries it. */
    private Button createRebuildPlanButton() {
        Button btnRebuildPlan = UiKit.secondaryButton("Rebuild study plan");
        btnRebuildPlan.setTooltip(new Tooltip("Re-sync topic hours and regenerate every planned session from today"));
        if (replanRunner != null) {
            btnRebuildPlan.disableProperty().bind(replanRunner.busyProperty());
        }
        btnRebuildPlan.setOnAction(e -> {
            try {
                Semester active = semesterDAO.getActiveSemester();
                if (active == null) {
                    UiKit.toast("No active semester found.", UiKit.Kind.WARN);
                    return;
                }

                boolean proceed = UiKit.confirm(primaryStage, "Rebuild Study Plan",
                        "Rebuild the whole plan from today?",
                        "This will:\n" +
                        "1. Re-sync all topic hours from syllabus subtopics\n" +
                        "2. Delete all PLANNED study sessions\n" +
                        "3. Delete PENDING/MISSED PYQ sessions\n" +
                        "4. Regenerate the full plan from today\n\n" +
                        "DONE and CONTINUING sessions are preserved.",
                        "Rebuild Plan");
                if (!proceed) {
                    return;
                }

                com.learntrack.service.ExamScopeService.RebuildPlanResult result =
                        examScopeService.rebuildPlan(active.getSemesterId());

                Runnable afterRebuild = () -> {
                    refreshAllViews();
                    UiKit.toast("Plan rebuilt: " + result.getTotal() + " old session(s) removed.");
                };

                if (replanRunner != null) {
                    replanRunner.run(active.getSemesterId(), LocalDate.now(), afterRebuild, null);
                } else {
                    replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
                    afterRebuild.run();
                }
            } catch (Exception ex) {
                UiKit.error(primaryStage, "The study plan could not be rebuilt.", ex);
            }
        });
        return btnRebuildPlan;
    }

    private Button createExamRoutineButton() {
        Button button = UiKit.secondaryButton("Exam period routine…");
        button.setTooltip(new Tooltip("Choose what the day looks like during the exam window"));
        button.setOnAction(e -> {
            try {
                ExamPeriodRoutineWizard wizard = new ExamPeriodRoutineWizard(
                        routineDAO, semesterDAO, replanService, this::refreshAllViews);
                wizard.show(primaryStage);
            } catch (Exception ex) {
                UiKit.error(primaryStage, "The exam period routine could not be opened.", ex);
            }
        });
        return button;
    }

    private static void addSettingRow(GridPane grid, int row, String label, String hint, Node control) {
        Label title = new Label(label);
        title.getStyleClass().add("subtle");
        VBox text = new VBox(1, title, UiKit.hint(hint));
        GridPane.setValignment(text, javafx.geometry.VPos.CENTER);
        grid.add(text, 0, row);
        grid.add(control, 1, row);
    }

    // ------------------------------------------------------------------ persisted UI state

    private String readSetting(String key, String fallback) {
        try {
            return settingsDAO == null ? fallback : settingsDAO.get(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void writeSetting(String key, String value) {
        try {
            if (settingsDAO != null) {
                settingsDAO.set(key, value);
            }
        } catch (Exception ignored) {
            // UI preferences are best-effort; never block the app on them.
        }
    }

    private double readDoubleSetting(String key, double fallback) {
        try {
            return Double.parseDouble(readSetting(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void restoreWindowBounds() {
        double x = readDoubleSetting("ui_window_x", Double.NaN);
        double y = readDoubleSetting("ui_window_y", Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            Rectangle2D bounds = new Rectangle2D(x, y, primaryStage.getWidth(), primaryStage.getHeight());
            for (Screen screen : Screen.getScreens()) {
                if (screen.getVisualBounds().intersects(bounds)) {
                    primaryStage.setX(x);
                    primaryStage.setY(y);
                    return;
                }
            }
        }
        primaryStage.centerOnScreen();
    }

    private void saveWindowBounds() {
        if (primaryStage == null || primaryStage.isMaximized() || primaryStage.isIconified()) {
            return;
        }
        writeSetting("ui_window_width", String.valueOf(primaryStage.getWidth()));
        writeSetting("ui_window_height", String.valueOf(primaryStage.getHeight()));
        writeSetting("ui_window_x", String.valueOf(primaryStage.getX()));
        writeSetting("ui_window_y", String.valueOf(primaryStage.getY()));
    }

    private void resetWindowBounds() {
        try {
            if (settingsDAO != null) {
                settingsDAO.delete("ui_window_x");
                settingsDAO.delete("ui_window_y");
            }
        } catch (Exception ignored) {
        }
        writeSetting("ui_window_width", "1100");
        writeSetting("ui_window_height", "750");
        primaryStage.setMaximized(false);
        primaryStage.setWidth(1100);
        primaryStage.setHeight(750);
        primaryStage.centerOnScreen();
        UiKit.toast("Window layout reset.", UiKit.Kind.INFO);
    }

    private void handleStartNewSemester() {
        TextInputDialog nameDialog = new TextInputDialog("Spring 2027");
        UiKit.styleDialog(nameDialog);
        nameDialog.setTitle("New Semester");
        nameDialog.setHeaderText("Enter name for the new academic semester:");
        nameDialog.setContentText("Semester Name:");
        Optional<String> nameResult = nameDialog.showAndWait();

        if (nameResult.isPresent() && !nameResult.get().trim().isEmpty()) {
            String newName = nameResult.get().trim();

            // Fix 8b: Count unfinished topics and include them in the warning (Plan §16)
            int unfinishedTopicCount = 0;
            try {
                Semester activeSem = semesterDAO.getActiveSemester();
                if (activeSem != null) {
                    java.util.List<com.learntrack.model.Topic> topics = topicDAO.findBySemester(activeSem.getSemesterId());
                    for (com.learntrack.model.Topic t : topics) {
                        if (t.getRemainingHours() > 0) unfinishedTopicCount++;
                    }
                }
            } catch (Exception ignored) {}

            String topicWarn = unfinishedTopicCount > 0
                    ? unfinishedTopicCount + " topic(s) still have study hours left and are deleted with them.\n"
                    : "";

            String message = "Starting “" + newName + "” permanently deletes, for the old semester:\n"
                    + "- every topic and its remaining study hours\n"
                    + "- every exam and its dates\n"
                    + "- every weekly timetable entry and syllabus\n"
                    + topicWarn
                    + "Your skills, skill logs and study history are kept. This cannot be undone.";

            if (UiKit.confirm(primaryStage, "Start New Semester",
                    "Start “" + newName + "” and archive the current semester?", message, "Start New Semester")) {
                try {
                    int newSemId = semesterService.startNewSemester(newName);
                    replanService.regenerateSemesterPlan(newSemId, LocalDate.now());

                    refreshAllViews();

                    // The Timetable tab is the single place to set up the new semester.
                    if (timetableWizard != null) {
                        timetableWizard.reloadFromDb();
                    }
                    if (mainTabPane != null && timetableTab != null) {
                        mainTabPane.getSelectionModel().select(timetableTab);
                    }

                } catch (Exception ex) {
                    UiKit.error(primaryStage, "The new semester could not be started.", ex);
                }
            }
        }
    }

    /**
     * Check if any untouched topics have hours_needed that differs from their subtopic's hours_to_cover.
     * Returns true if resync is needed.
     */
    private boolean checkForStaleTopicHours(int semesterId) throws Exception {
        try (Connection conn = DBConnection.getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM topics t " +
                     "JOIN exams e ON t.exam_id = e.exam_id " +
                     "JOIN syllabus_subtopics s ON t.subtopic_id = s.subtopic_id " +
                     "WHERE e.semester_id = ? " +
                     "AND ABS(t.hours_needed - s.hours_to_cover) > 0.01 " +
                     "AND t.topic_id NOT IN (" +
                     "  SELECT DISTINCT item_id FROM daily_log " +
                     "  WHERE item_type IN ('TOPIC_LEARN', 'TOPIC_REVISE') " +
                     "  AND status IN ('DONE', 'CONTINUING')" +
                     ")")) {
            stmt.setInt(1, semesterId);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    private void checkAndPromptOrphanedSubjects(int semesterId) {
        try {
            com.learntrack.service.SubjectCleanupService cleanupService = new com.learntrack.service.SubjectCleanupService();
            List<String> orphaned = cleanupService.findOrphanedExamSubjects(semesterId);
            if (orphaned != null && !orphaned.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                UiKit.styleDialog(alert);
                alert.setTitle("Clean Up Old Subjects");
                alert.setHeaderText("Old subjects detected: " + String.join(", ", orphaned));
                alert.setContentText("The following subjects from earlier timetable versions have leftover exam entries:\n"
                        + String.join(", ", orphaned)
                        + "\n\nDo you want to remove them completely? If you choose 'Keep Subjects', they will be kept in your Subjects list.");
                ButtonType btnDelete = new ButtonType("Remove Completely", ButtonBar.ButtonData.YES);
                ButtonType btnKeep = new ButtonType("Keep Subjects", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(btnDelete, btnKeep);

                Optional<ButtonType> choice = alert.showAndWait();
                if (choice.isPresent() && choice.get() == btnDelete) {
                    for (String code : orphaned) {
                        cleanupService.removeSubjectCompletely(semesterId, code);
                    }
                    if (replanService != null) {
                        replanService.regenerateSemesterPlan(semesterId, LocalDate.now());
                    }
                    refreshAllViews();
                } else if (choice.isPresent() && choice.get() == btnKeep) {
                    for (String code : orphaned) {
                        com.learntrack.dao.KeptSubjectDAO.upsert(semesterId, code, code);
                    }
                    refreshAllViews();
                }
            }
        } catch (Exception ex) {
            System.err.println("Failed to check/cleanup orphaned subjects: " + ex.getMessage());
        }
    }

    private void refreshAllViews() {
        if (dashboardView != null) dashboardView.refreshData();
        if (calendarGridView != null) calendarGridView.refreshCalendar();
        if (examFormController != null) examFormController.loadExams();
        if (topicFormController != null) topicFormController.loadExams();
        if (skillFormController != null) skillFormController.loadSkills();
        if (feasibilityBarView != null) feasibilityBarView.refresh();
    }

    @Override
    public void stop() {
        if (replanRunner != null) {
            replanRunner.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
