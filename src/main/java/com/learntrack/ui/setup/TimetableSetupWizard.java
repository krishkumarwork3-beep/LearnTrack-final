package com.learntrack.ui.setup;

import com.learntrack.dao.DateOverrideDAO;
import com.learntrack.dao.KeptSubjectDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.dao.SettingsDAO;
import com.learntrack.dao.SubjectNameDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.Semester;
import com.learntrack.model.WeekdayRoutine;
import com.learntrack.model.WeekdayStudyPreference;
import com.learntrack.model.WeeklyTimetableEntry;
import com.learntrack.service.FreeSlotService;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.ui.Theme;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DateUtils;
import com.learntrack.util.DurationFormat;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import com.learntrack.service.ElectiveOption;
import com.learntrack.service.ParsedWorkbook;
import com.learntrack.service.TimetableSlot;
import com.learntrack.service.TimetableXlsxReader;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import com.learntrack.service.ImprovementService;
import com.learntrack.service.ImprovementService.ImprovementSlot;
import com.learntrack.service.ImprovementService.ComponentOption;
import com.learntrack.service.ImprovementService.CrossComponentOverlap;
import com.learntrack.service.ImprovementService.BestBatchOption;
import com.learntrack.service.ImprovementService.AddPlan;
import com.learntrack.service.ImprovementService.PlannedSlot;
import com.learntrack.service.ImprovementService.SkippedSlot;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.concurrent.Task;
import javafx.scene.Cursor;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Setup Wizard for wake/sleep routines, recurring weekly timetable, and weekday study preferences.
 * User Guide Section 1 & Implementation Plan extension.
 * Features separate per-day views for wake/sleep routines and recurring commitments.
 */
public class TimetableSetupWizard {

    private static final Logger LOGGER = Logger.getLogger(TimetableSetupWizard.class.getName());

    private final TimetableDAO timetableDAO;
    private final SemesterDAO semesterDAO;
    private final DateOverrideDAO dateOverrideDAO;
    private final SemesterReplanService replanService;
    private final FreeSlotService freeSlotService;
    private final Runnable onComplete;
    private final com.learntrack.service.SubjectCleanupService subjectCleanupService = new com.learntrack.service.SubjectCleanupService();

    private void triggerUiRefresh() {
        // In tab mode nothing is persisted yet, so refreshing other views would only rebuild
        // them from stale database rows. Save triggers the single refresh.
        if (staged) {
            return;
        }
        if (onComplete != null) {
            javafx.application.Platform.runLater(onComplete);
        }
    }

    private static final List<String> DAYS = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    );

    /** Default sort order for entries: start time ascending, end time as tie-breaker. */
    private static final Comparator<WeeklyTimetableEntry> ENTRY_TIME_COMPARATOR =
            Comparator.comparing(WeeklyTimetableEntry::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                      .thenComparing(WeeklyTimetableEntry::getEndTime, Comparator.nullsLast(Comparator.naturalOrder()));

    // Per-day wake and sleep fields
    private final Map<String, TextField> perDayWakeFields = new LinkedHashMap<>();
    private final Map<String, TextField> perDaySleepFields = new LinkedHashMap<>();
    private final Map<String, Tab> dayTabs = new LinkedHashMap<>();

    // Master list of all weekly commitments across all days
    private final ObservableList<WeeklyTimetableEntry> entriesList = FXCollections.observableArrayList();

    // Excel import & improvement state
    private Stage wizardStage;
    private final TimetableXlsxReader timetableXlsxReader = new TimetableXlsxReader();
    private ParsedWorkbook loadedWorkbook;     // parsed file, kept for improvement feature
    private String loadedSheet;               // sheet used during last import run (local use only)
    private String loadedBatch;               // batch used during last import run (local use only)
    // COMMITTED own-batch state (set only after >= 1 entry is actually added from Excel)
    private String importedBatch;             // canonical spelling from workbook header
    private String importedSheet;             // sheet name from workbook
    private String importedFilePath;          // cached copy path
    private Button btnImportExcel;            // kept as field for enable/disable
    private Button btnAddImprovement;         // kept as field for enable/disable
    private Button btnClearBatch;             // kept as field for enable/disable
    private final SettingsDAO settingsDAO = new SettingsDAO();
    private final Map<String, String> subjectCodeRenames = new LinkedHashMap<>();
    private final Map<String, String> nameToSubjectCode = new LinkedHashMap<>();
    private final ImprovementService improvementService = new ImprovementService();

    // Free-time review
    private final Map<String, Spinner<Double>> studyHourSpinners = new LinkedHashMap<>();
    private final Map<String, Label> freeHourLabels = new LinkedHashMap<>();

    // Tab mode: edits stay in memory until the single Save action at the bottom writes them.
    private boolean staged;
    private boolean closeOnSave = true;
    private final Set<String> stagedRemovedSubjects = new LinkedHashSet<>();
    private final Map<String, String> stagedKeptSubjects = new LinkedHashMap<>();
    /** Fingerprint of the state the last successful Save left behind; Undo restores exactly this. */
    private String savedStateFingerprint;

    public TimetableSetupWizard(TimetableDAO timetableDAO, SemesterDAO semesterDAO,
                                SemesterReplanService replanService,
                                FreeSlotService freeSlotService,
                                Runnable onComplete) {
        this(timetableDAO, semesterDAO, null, replanService, freeSlotService, onComplete);
    }

    public TimetableSetupWizard(TimetableDAO timetableDAO, SemesterDAO semesterDAO,
                                DateOverrideDAO dateOverrideDAO,
                                SemesterReplanService replanService,
                                FreeSlotService freeSlotService,
                                Runnable onComplete) {
        this.timetableDAO = timetableDAO;
        this.semesterDAO = semesterDAO;
        this.dateOverrideDAO = dateOverrideDAO;
        this.replanService = replanService;
        this.freeSlotService = freeSlotService;
        this.onComplete = onComplete;

        for (String day : DAYS) {
            perDayWakeFields.put(day, new TextField("07:00"));
            perDaySleepFields.put(day, new TextField("23:00"));
        }

        entriesList.addListener((javafx.collections.ListChangeListener<WeeklyTimetableEntry>) c -> {
            updateTabIndicators();
            updateButtonStates();
        });
    }

    public void show(Stage ownerStage) {
        Stage stage = new Stage();
        this.wizardStage = stage;
        staged = false;
        closeOnSave = true;
        stage.initOwner(ownerStage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Timetable & Study Targets");

        Scene scene = new Scene(buildBody(), 920, 720);
        Theme.apply(scene, Theme.isDarkActive());
        stage.setScene(scene);
        // Esc leaves without saving, the same as the window close button.
        UiKit.closeOnEscape(stage);
        stage.showAndWait();
    }

    /**
     * The wizard body as a normal tab. Every edit stays in memory until Save is pressed once;
     * leading footer actions (plan rebuild, exam routine) are supplied by the main window.
     */
    public Node createTabContent(Node... footerActions) {
        staged = true;
        closeOnSave = false;
        return buildBody(footerActions);
    }

    /** Drops staged edits and reloads the tab for the active semester (after rollover, and on Undo). */
    public void reloadFromDb() {
        stagedRemovedSubjects.clear();
        stagedKeptSubjects.clear();
        loadExistingData();
        updateFreeHoursReview();
    }

    /**
     * Undoes everything changed since the last successful Save timetable. The database still holds
     * that save (the tab stages edits in memory), so the restore is a reload: Clear Batch and
     * Clear Entire Week included, because neither writes anything while staged.
     */
    private void handleUndoToLastSave() {
        if (!staged) {
            return;
        }
        String current = stagedFingerprint();
        if (savedStateFingerprint != null && savedStateFingerprint.equals(current)) {
            UiKit.toast("Nothing to undo: nothing changed since the last save.", UiKit.Kind.WARN);
            return;
        }
        boolean sure = UiKit.confirm(wizardStage, "Undo changes",
                "Restore the timetable as it was at your last save?",
                "Everything changed since the last Save timetable is discarded, including Clear Batch "
                        + "and Clear Entire Week.",
                "Undo");
        if (!sure) {
            return;
        }
        reloadFromDb();
        UiKit.toast("Changes made since the last save were undone.");
    }

    /**
     * Everything the tab stages, as one string: entries, wake/sleep, study hours, the import batch
     * and the queued subject changes. Kept as text so editing an entry already in the list still
     * counts as a difference.
     */
    private String stagedFingerprint() {
        StringBuilder state = new StringBuilder();
        List<WeeklyTimetableEntry> ordered = new ArrayList<>(entriesList);
        ordered.sort(Comparator
                .comparingInt((WeeklyTimetableEntry e) -> DAYS.indexOf(
                        e.getDayOfWeek() != null ? e.getDayOfWeek().toUpperCase() : ""))
                .thenComparing(ENTRY_TIME_COMPARATOR));
        for (WeeklyTimetableEntry e : ordered) {
            state.append(e.getDayOfWeek()).append('|')
                    .append(e.getStartTime()).append('-').append(e.getEndTime()).append('|')
                    .append(e.getActivityType()).append('|')
                    .append(e.getLabel()).append('|')
                    .append(e.getRoom()).append('|')
                    .append(e.getSubjectCode()).append('|')
                    .append(e.getEntryId()).append('\n');
        }
        for (String day : DAYS) {
            TextField wake = perDayWakeFields.get(day);
            TextField sleep = perDaySleepFields.get(day);
            Spinner<Double> hours = studyHourSpinners.get(day);
            state.append(day).append('|')
                    .append(wake != null ? wake.getText() : null).append('-')
                    .append(sleep != null ? sleep.getText() : null).append('|')
                    .append(hours != null && hours.getValue() != null ? hours.getValue() : null)
                    .append('\n');
        }
        state.append(importedBatch).append('|').append(importedSheet).append('|')
                .append(importedFilePath).append('\n');
        state.append(stagedRemovedSubjects).append('\n')
                .append(stagedKeptSubjects).append('\n');
        return state.toString();
    }

    private VBox buildBody(Node... footerActions) {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab tab1 = new Tab("Routine & commitments", createTimetableStepNode());
        tab1.setClosable(false);
        Tab tab2 = new Tab("Free time", createStudyTargetStepNode());
        tab2.setClosable(false);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == tab2) {
                updateFreeHoursReview();
            }
        });
        tabPane.getTabs().addAll(tab1, tab2);

        loadExistingData();
        updateButtonStates();
        updateFreeHoursReview();

        Button save = UiKit.primaryButton("Save timetable");
        save.setOnAction(e -> saveAll());

        Button undo = UiKit.secondaryButton("Undo");
        undo.setTooltip(new Tooltip(
                "Restore the timetable as it was the last time you saved: undoes Clear Batch, "
                        + "Clear Entire Week and any other change made since that save."));
        undo.setOnAction(e -> handleUndoToLastSave());
        // Only the tab stages edits; the modal wizard writes straight to the database, where an
        // undo would have to resurrect deleted subjects, so it does not offer one.
        undo.setVisible(staged);
        undo.setManaged(staged);

        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_LEFT);
        if (footerActions != null) {
            for (Node action : footerActions) {
                if (action != null) {
                    footer.getChildren().add(action);
                }
            }
        }
        footer.getChildren().addAll(UiKit.spacer(),
                UiKit.hint(staged ? "Changes stay on this screen until you save." : "Save applies the timetable and rebuilds the plan."),
                undo, save);

        VBox root = new VBox(12, tabPane, footer);
        root.setPadding(new Insets(16));
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        return root;
    }

    private Node createTimetableStepNode() {
        VBox vbox = new VBox(12);
        vbox.setPadding(new Insets(14));

        Label header = new Label("Step 1: Daily Sleep/Wake Routine & Commitments");
        header.getStyleClass().add("page-title");
        Label sub = new Label("Select each day to configure its wake/sleep routine and fixed commitments.");
        sub.getStyleClass().add("subtle");

        TabPane dayTabPane = new TabPane();
        dayTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        for (String day : DAYS) {
            String shortName = day.substring(0, 1) + day.substring(1, 3).toLowerCase();
            Tab dayTab = new Tab(shortName, createDayViewNode(day));
            dayTab.setClosable(false);
            dayTabs.put(day, dayTab);
            dayTabPane.getTabs().add(dayTab);
        }

        Button btnClearWeek = new Button("Clear Entire Week");
        btnClearWeek.getStyleClass().add("btn-danger-soft");
        btnClearWeek.setOnAction(e -> handleClearEntireWeek());

        btnClearBatch = new Button("Clear Batch");
        btnClearBatch.getStyleClass().add("btn-danger-soft");
        btnClearBatch.setOnAction(e -> handleClearBatch());

        btnImportExcel = new Button("Import from Excel");
        btnImportExcel.getStyleClass().add("check-strong");
        btnImportExcel.setOnAction(e -> handleImportFromExcel());

        btnAddImprovement = new Button("Add Improvement Subject…");
        btnAddImprovement.setOnAction(e -> handleAddImprovementSubject());

        Button btnRenameSubjects = new Button("Rename Subjects…");
        btnRenameSubjects.setOnAction(e -> handleRenameSubjects());

        Button btnRemoveSubject = new Button("Remove Subject…");
        btnRemoveSubject.setOnAction(e -> handleRemoveSubject());

        // A wrapping bar keeps every label whole; an HBox clipped them at 980 px.
        FlowPane bottomBar = new FlowPane(8, 8,
                btnImportExcel, btnAddImprovement, btnRenameSubjects, btnRemoveSubject,
                btnClearBatch, btnClearWeek);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(6, 0, 0, 0));

        vbox.getChildren().addAll(header, sub, dayTabPane, bottomBar);
        VBox.setVgrow(dayTabPane, Priority.ALWAYS);

        ScrollPane sp = new ScrollPane(vbox);
        sp.setFitToWidth(true);
        installTableWheelRouting(sp);
        return sp;
    }

    /**
     * Wheel routing for a table inside the page scroll. Consuming every wheel event over the
     * table used to leave the wheel dead, and letting both panes react made it jump. Here the
     * table scrolls while it can, then the page takes over at its top or bottom edge, so one
     * gesture moves exactly one scroller.
     */
    private void installTableWheelRouting(ScrollPane page) {
        page.addEventFilter(ScrollEvent.SCROLL, event -> {
            TableView<?> table = ancestorTable(event.getTarget());
            if (table == null || event.getDeltaY() == 0) {
                return; // the page handles its own wheel normally
            }
            ScrollBar bar = verticalBar(table);
            boolean tableCanScroll = bar != null && bar.isVisible()
                    && bar.getVisibleAmount() < 0.999;
            if (!tableCanScroll) {
                scrollPage(page, event.getDeltaY());
                event.consume();
                return;
            }

            double viewport = table.getHeight();
            double amount = Math.max(0.001, bar.getVisibleAmount());
            double rangePx = viewport * (1.0 / amount - 1.0);
            double step = rangePx > 1 ? event.getDeltaY() / rangePx
                    : Math.signum(event.getDeltaY()) * 0.05;
            double next = clamp(bar.getValue() - step, bar.getMin(), bar.getMax());
            if (Math.abs(next - bar.getValue()) > 1e-6) {
                bar.setValue(next);
            } else {
                // At the table's edge: continue the same gesture on the page.
                scrollPage(page, event.getDeltaY());
            }
            event.consume();
        });
    }

    private static void scrollPage(ScrollPane page, double deltaY) {
        double contentHeight = page.getContent() != null
                ? page.getContent().getLayoutBounds().getHeight() : 0;
        double viewportHeight = page.getViewportBounds().getHeight();
        double scrollable = contentHeight - viewportHeight;
        if (scrollable <= 1) {
            return;
        }
        page.setVvalue(clamp(page.getVvalue() - deltaY / scrollable, 0, 1));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static TableView<?> ancestorTable(Object target) {
        if (!(target instanceof Node)) {
            return null;
        }
        for (Node node = (Node) target; node != null; node = node.getParent()) {
            if (node instanceof TableView) {
                return (TableView<?>) node;
            }
        }
        return null;
    }

    private static ScrollBar verticalBar(TableView<?> table) {
        if (table == null) {
            return null;
        }
        Node bar = table.lookup(".scroll-bar:vertical");
        return bar instanceof ScrollBar ? (ScrollBar) bar : null;
    }

    private Node createDayViewNode(String day) {
        VBox dayBox = new VBox(12);
        dayBox.setPadding(new Insets(12));

        String displayName = day.substring(0, 1) + day.substring(1).toLowerCase();

        // 1. Wake & Sleep Card for this day
        VBox routineCard = new VBox(10);
        routineCard.getStyleClass().add("card-flat");

        Label routineHeader = new Label(displayName + ": wake-up and sleep schedule");
        routineHeader.getStyleClass().add("section-title");

        TextField wakeField = perDayWakeFields.get(day);
        wakeField.setPrefWidth(70);
        wakeField.setPromptText("07:00");

        TextField sleepField = perDaySleepFields.get(day);
        sleepField.setPrefWidth(70);
        sleepField.setPromptText("23:00");

        Button btnApplyAll = new Button("Apply Wake/Sleep to All Days");
        btnApplyAll.setOnAction(e -> applyWakeSleepToAllDays(day));

        Button btnCopyDay = new Button("Copy " + displayName + " to Other Days…");
        btnCopyDay.setOnAction(e -> openCopyDayDialog(day));

        HBox routineRow = new HBox(10);
        routineRow.setAlignment(Pos.CENTER_LEFT);
        routineRow.getChildren().addAll(
                new Label("Wake-up Time:"), wakeField,
                new Label("Sleep Time:"), sleepField,
                btnApplyAll,
                btnCopyDay
        );

        routineCard.getChildren().addAll(routineHeader, routineRow);

        // 2. Fixed Commitments Card for this day
        VBox commitmentCard = new VBox(10);
        commitmentCard.getStyleClass().add("card-flat");

        Label comHeader = new Label("Add Fixed Commitment for " + displayName + " (Classes, Labs, Meals, Other)");
        comHeader.getStyleClass().add("section-title");

        GridPane formGrid = new GridPane();
        formGrid.setHgap(8);
        formGrid.setVgap(8);
        formGrid.setAlignment(Pos.CENTER_LEFT);

        TextField startTimeField = new TextField("09:00");
        startTimeField.setPromptText("HH:mm");
        startTimeField.setPrefWidth(96);

        TextField endTimeField = new TextField("10:00");
        endTimeField.setPromptText("HH:mm");
        endTimeField.setPrefWidth(96);

        ComboBox<String> typeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "CLASS", "TUTORIAL", "LAB", "MEAL", "OTHER"
        ));
        typeCombo.setValue("CLASS");
        typeCombo.setPrefWidth(140);

        TextField labelField = new TextField();
        labelField.setPromptText("e.g. CS101 Lecture");
        labelField.setPrefWidth(240);

        TextField roomField = new TextField();
        roomField.setPromptText("e.g. F108");
        roomField.setPrefWidth(110);

        // 3. TableView for this day (filtered by day, then sorted by start time, end time)
        FilteredList<WeeklyTimetableEntry> dayEntries = new FilteredList<>(entriesList,
                e -> day.equalsIgnoreCase(e.getDayOfWeek()));
        SortedList<WeeklyTimetableEntry> sortedDayEntries = new SortedList<>(dayEntries, ENTRY_TIME_COMPARATOR);
        TableView<WeeklyTimetableEntry> table = new TableView<>(sortedDayEntries);
        // A bounded height gives the table its own scrollbar; without it the table grew and
        // the outer page scroll handled the wheel instead.
        table.setPrefHeight(280);
        table.setMinHeight(200);
        table.setMaxHeight(280);
        // Matches -fx-cell-size so the virtual flow can lay rows out without measuring each one.
        table.setFixedCellSize(44);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<WeeklyTimetableEntry, LocalTime> colStart = new TableColumn<>("Start");
        colStart.setCellValueFactory(new PropertyValueFactory<>("startTime"));

        TableColumn<WeeklyTimetableEntry, LocalTime> colEnd = new TableColumn<>("End");
        colEnd.setCellValueFactory(new PropertyValueFactory<>("endTime"));

        TableColumn<WeeklyTimetableEntry, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(new PropertyValueFactory<>("activityType"));

        TableColumn<WeeklyTimetableEntry, String> colLabel = new TableColumn<>("Label");
        colLabel.setCellValueFactory(new PropertyValueFactory<>("label"));

        TableColumn<WeeklyTimetableEntry, String> colRoom = new TableColumn<>("Room");
        colRoom.setCellValueFactory(f -> new SimpleStringProperty(
                f.getValue() != null ? f.getValue().getRoomDisplay() : ""));

        table.getColumns().addAll(colStart, colEnd, colType, colLabel, colRoom);
        // Prevent column header clicks from overriding the default time-based sort
        table.setSortPolicy(t -> false);

        Button btnAdd = new Button("Add Entry");
        btnAdd.getStyleClass().add("btn-primary");
        btnAdd.setOnAction(e -> {
            try {
                LocalTime st = DateUtils.parseTime(startTimeField.getText());
                LocalTime et = DateUtils.parseTime(endTimeField.getText());
                if (st == null || et == null) {
                    showError("Invalid Time", "Please enter valid times in HH:mm format.");
                    return;
                }
                if (!et.isAfter(st)) {
                    showError("Invalid Time Range", "End time must be strictly after start time (" + st + "). Cross-midnight commitments are not permitted here.");
                    return;
                }

                // Check awake window and warn (do not block) if outside
                Map<String, WeekdayRoutine> currentRoutines = getRoutinesFromUI();
                boolean outsideAwake = isOutsideAwake(day, st, et, currentRoutines);

                if (outsideAwake) {
                    UiKit.toast("Commitment [" + st + " - " + et + "] falls outside the awake window on "
                            + displayName + ". It was added but may overlap sleep.", UiKit.Kind.WARN);
                }

                // Overlap check: block if new entry overlaps any existing entry on this day
                List<WeeklyTimetableEntry> overlaps = findOverlaps(day, st, et);
                if (!overlaps.isEmpty()) {
                    StringBuilder msg = new StringBuilder("Overlaps with: ");
                    for (int i = 0; i < overlaps.size(); i++) {
                        if (i > 0) msg.append(", ");
                        msg.append(formatEntryLabel(overlaps.get(i)));
                    }
                    showError("Overlapping Entry", msg.toString());
                    return;
                }

                String roomText = roomField.getText() != null ? roomField.getText().trim() : "";
                WeeklyTimetableEntry entry = new WeeklyTimetableEntry(
                        null, 0, day, st, et, typeCombo.getValue(), labelField.getText(),
                        roomText.isEmpty() ? null : roomText
                );
                entriesList.add(entry);
                labelField.clear();
                roomField.clear();
            } catch (Exception ex) {
                showError("Error", "Failed to add entry: " + ex.getMessage());
            }
        });

        Button btnRemove = new Button("Remove Selected");
        btnRemove.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        btnRemove.setOnAction(e -> {
            List<WeeklyTimetableEntry> sel = new ArrayList<>(table.getSelectionModel().getSelectedItems());
            if (!sel.isEmpty()) {
                handleEntryDeletion(sel);
            }
        });

        Button btnEdit = new Button("Edit Selected");
        // Enabled only when exactly one row is selected
        btnEdit.disableProperty().bind(
                Bindings.size(table.getSelectionModel().getSelectedItems()).isNotEqualTo(1)
        );
        btnEdit.setOnAction(e -> {
            WeeklyTimetableEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) {
                openEditCommitmentDialog(day, sel, table);
            }
        });

        // Double-click a row to edit it
        table.setRowFactory(tv -> {
            TableRow<WeeklyTimetableEntry> tableRow = new TableRow<>();
            tableRow.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !tableRow.isEmpty()) {
                    openEditCommitmentDialog(day, tableRow.getItem(), table);
                }
            });
            return tableRow;
        });

        Button btnCopySelected = new Button("Copy Selected to Other Days…");
        btnCopySelected.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        btnCopySelected.setOnAction(e -> {
            List<WeeklyTimetableEntry> sel = new ArrayList<>(table.getSelectionModel().getSelectedItems());
            if (!sel.isEmpty()) {
                openCopySelectedDialog(day, sel);
            }
        });

        Button btnClearDay = new Button("Clear " + displayName);
        btnClearDay.getStyleClass().add("btn-danger-soft");
        btnClearDay.setOnAction(e -> {
            if (UiKit.confirm(wizardStage, "Clear commitments",
                    "Clear all commitments for " + displayName + "?",
                    "Every class, tutorial, lab, meal and other commitment on " + displayName
                            + " is removed. The wake/sleep times are kept.",
                    "Clear " + displayName)) {
                // Clear commitments only, keep wake/sleep intact (preferred design)
                entriesList.removeIf(ent -> day.equalsIgnoreCase(ent.getDayOfWeek()));
                updateTabIndicators();
            }
        });

        formGrid.addRow(0, new Label("Start:"), startTimeField, new Label("End:"), endTimeField, new Label("Type:"), typeCombo);
        formGrid.addRow(1, new Label("Label:"), labelField, new Label("Room:"), roomField);

        // A wrapping action row keeps every button label whole at the minimum window width.
        FlowPane entryActions = new FlowPane(8, 8,
                btnAdd, btnEdit, btnRemove, btnCopySelected, btnClearDay);
        entryActions.setAlignment(Pos.CENTER_LEFT);

        commitmentCard.getChildren().addAll(comHeader, formGrid, entryActions);

        dayBox.getChildren().addAll(routineCard, commitmentCard, table);
        VBox.setVgrow(table, Priority.NEVER);

        return dayBox;
    }

    private void updateTabIndicators() {
        for (String day : DAYS) {
            long count = entriesList.stream()
                    .filter(e -> day.equalsIgnoreCase(e.getDayOfWeek()))
                    .count();
            Tab tab = dayTabs.get(day);
            if (tab != null) {
                String shortName = day.substring(0, 1) + day.substring(1, 3).toLowerCase();
                tab.setText(shortName + " (" + count + ")");
            }
        }
    }

    private void applyWakeSleepToAllDays(String sourceDay) {
        String wake = perDayWakeFields.get(sourceDay).getText();
        String sleep = perDaySleepFields.get(sourceDay).getText();
        for (String day : DAYS) {
            perDayWakeFields.get(day).setText(wake);
            perDaySleepFields.get(day).setText(sleep);
        }
        UiKit.toast("Applied " + sourceDay + "'s wake-up (" + wake + ") and sleep (" + sleep + ") times to all 7 days.");
    }

    private void openCopyDayDialog(String sourceDay) {
        Dialog<ButtonType> dialog = new Dialog<>();
        UiKit.styleDialog(dialog);
        if (wizardStage != null) dialog.initOwner(wizardStage);
        String sourceDisplayName = sourceDay.substring(0, 1) + sourceDay.substring(1).toLowerCase();
        dialog.setTitle("Copy schedule from " + sourceDisplayName);
        dialog.setHeaderText("Copy " + sourceDisplayName + "'s schedule to other days");

        VBox content = new VBox(12);
        content.setPadding(new Insets(14));

        Label lblInfo = new Label("Select target days to copy onto:");
        lblInfo.getStyleClass().add("check-strong");

        Map<String, CheckBox> targetChecks = new LinkedHashMap<>();
        GridPane daysGrid = new GridPane();
        daysGrid.setHgap(12);
        daysGrid.setVgap(8);
        int col = 0;
        int row = 0;
        for (String d : DAYS) {
            if (d.equalsIgnoreCase(sourceDay)) continue;
            String dName = d.substring(0, 1) + d.substring(1).toLowerCase();
            CheckBox cb = new CheckBox(dName);
            targetChecks.put(d, cb);
            daysGrid.add(cb, col, row);
            col++;
            if (col > 2) {
                col = 0;
                row++;
            }
        }

        HBox selectBtns = new HBox(10);
        Button btnSelectAll = new Button("Select All");
        btnSelectAll.setOnAction(e -> targetChecks.values().forEach(cb -> cb.setSelected(true)));
        Button btnDeselectAll = new Button("Deselect All");
        btnDeselectAll.setOnAction(e -> targetChecks.values().forEach(cb -> cb.setSelected(false)));
        selectBtns.getChildren().addAll(btnSelectAll, btnDeselectAll);

        CheckBox copyRoutinesCheck = new CheckBox("Copy wake-up and sleep times");
        copyRoutinesCheck.setSelected(true);

        CheckBox copyCommitmentsCheck = new CheckBox("Copy commitments (classes, meals, etc.)");
        copyCommitmentsCheck.setSelected(true);

        CheckBox replaceCommitmentsCheck = new CheckBox("Replace existing commitments on target days");
        replaceCommitmentsCheck.setSelected(true);
        replaceCommitmentsCheck.disableProperty().bind(copyCommitmentsCheck.selectedProperty().not());

        content.getChildren().addAll(lblInfo, daysGrid, selectBtns, new Separator(), copyRoutinesCheck, copyCommitmentsCheck, replaceCommitmentsCheck);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            List<String> selectedTargets = new ArrayList<>();
            for (Map.Entry<String, CheckBox> entry : targetChecks.entrySet()) {
                if (entry.getValue().isSelected()) {
                    selectedTargets.add(entry.getKey());
                }
            }
            if (selectedTargets.isEmpty()) {
                return;
            }

            String sourceWake = perDayWakeFields.get(sourceDay).getText();
            String sourceSleep = perDaySleepFields.get(sourceDay).getText();

            List<WeeklyTimetableEntry> sourceEntries = entriesList.stream()
                    .filter(e -> sourceDay.equalsIgnoreCase(e.getDayOfWeek()))
                    .toList();

            int copiedCount = 0;
            int skippedOverlaps = 0;
            List<String> overlapSkipMessages = new ArrayList<>();

            for (String target : selectedTargets) {
                String tgtDisplayName = target.substring(0, 1) + target.substring(1, 3).toLowerCase();
                if (copyRoutinesCheck.isSelected()) {
                    perDayWakeFields.get(target).setText(sourceWake);
                    perDaySleepFields.get(target).setText(sourceSleep);
                }
                if (copyCommitmentsCheck.isSelected()) {
                    if (replaceCommitmentsCheck.isSelected()) {
                        entriesList.removeIf(e -> target.equalsIgnoreCase(e.getDayOfWeek()));
                    }
                    for (WeeklyTimetableEntry se : sourceEntries) {
                        // When not replacing, check for overlaps on target day and skip if found
                        if (!replaceCommitmentsCheck.isSelected()) {
                            List<WeeklyTimetableEntry> overlaps = findOverlaps(target, se.getStartTime(), se.getEndTime());
                            if (!overlaps.isEmpty()) {
                                String srcLabel = formatEntryLabel(se);
                                String ovNames = overlaps.stream().map(this::formatEntryLabel).reduce((a, b) -> a + ", " + b).orElse("");
                                overlapSkipMessages.add(tgtDisplayName + ": skipped " + srcLabel + " (overlaps " + ovNames + ")");
                                skippedOverlaps++;
                                continue;
                            }
                        }
                        entriesList.add(se.copyToDay(target));
                        copiedCount++;
                    }
                }
            }
            updateTabIndicators();

            // Show summary if any overlaps were skipped during copy
            if (skippedOverlaps > 0) {
                StringBuilder msg = new StringBuilder("Copy completed.");
                if (copiedCount > 0) {
                    msg.append(" ").append(copiedCount).append(copiedCount == 1 ? " entry" : " entries").append(" copied.");
                }
                msg.append("\n\nSkipped due to overlaps:\n\u2022 ");
                msg.append(String.join("\n\u2022 ", overlapSkipMessages));
                UiKit.toast(msg.toString(), UiKit.Kind.INFO);
            }
        }
    }

    private void openCopySelectedDialog(String sourceDay, List<WeeklyTimetableEntry> selectedEntries) {
        if (selectedEntries == null || selectedEntries.isEmpty()) {
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        UiKit.styleDialog(dialog);
        if (wizardStage != null) dialog.initOwner(wizardStage);
        String sourceDisplayName = sourceDay.substring(0, 1) + sourceDay.substring(1).toLowerCase();
        dialog.setTitle("Copy Selected Entries");
        dialog.setHeaderText("Copy " + selectedEntries.size() + (selectedEntries.size() == 1 ? " entry" : " entries") +
                " from " + sourceDisplayName + " to other days");

        VBox content = new VBox(12);
        content.setPadding(new Insets(14));

        Label lblInfo = new Label("Select target days to copy onto:");
        lblInfo.getStyleClass().add("check-strong");

        Map<String, CheckBox> targetChecks = new LinkedHashMap<>();
        GridPane daysGrid = new GridPane();
        daysGrid.setHgap(16);
        daysGrid.setVgap(10);
        int col = 0;
        int row = 0;
        for (String d : DAYS) {
            String dName = d.substring(0, 1) + d.substring(1).toLowerCase();
            CheckBox cb = new CheckBox(dName);
            if (d.equalsIgnoreCase(sourceDay)) {
                cb.setDisable(true);
                cb.setText(dName + " (Current)");
            }
            targetChecks.put(d, cb);
            daysGrid.add(cb, col, row);
            col++;
            if (col > 3) {
                col = 0;
                row++;
            }
        }

        HBox selectBtns = new HBox(10);
        Button btnSelectAll = new Button("Select All");
        btnSelectAll.setOnAction(e -> {
            for (CheckBox cb : targetChecks.values()) {
                if (!cb.isDisable()) cb.setSelected(true);
            }
        });

        Button btnWeekdays = new Button("Weekdays only");
        btnWeekdays.setOnAction(e -> {
            for (Map.Entry<String, CheckBox> entry : targetChecks.entrySet()) {
                if (!entry.getValue().isDisable()) {
                    boolean isWeekday = !"SATURDAY".equalsIgnoreCase(entry.getKey()) && !"SUNDAY".equalsIgnoreCase(entry.getKey());
                    entry.getValue().setSelected(isWeekday);
                }
            }
        });

        Button btnDeselectAll = new Button("Deselect All");
        btnDeselectAll.setOnAction(e -> {
            for (CheckBox cb : targetChecks.values()) {
                if (!cb.isDisable()) cb.setSelected(false);
            }
        });

        selectBtns.getChildren().addAll(btnSelectAll, btnWeekdays, btnDeselectAll);

        content.getChildren().addAll(lblInfo, daysGrid, selectBtns);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            List<String> selectedTargets = new ArrayList<>();
            for (Map.Entry<String, CheckBox> entry : targetChecks.entrySet()) {
                if (!entry.getValue().isDisable() && entry.getValue().isSelected()) {
                    selectedTargets.add(entry.getKey());
                }
            }
            if (selectedTargets.isEmpty()) {
                return;
            }

            int copiedCount = 0;
            int skippedDuplicates = 0;
            int skippedOverlaps = 0;
            List<String> overlapSkipMessages = new ArrayList<>();

            for (String targetDay : selectedTargets) {
                String tgtShortName = targetDay.substring(0, 1) + targetDay.substring(1, 3).toLowerCase();
                // Snapshot for duplicate checking (refreshed as entries are added)
                List<WeeklyTimetableEntry> currentDayEntries = new ArrayList<>(
                        entriesList.stream()
                                .filter(e -> targetDay.equalsIgnoreCase(e.getDayOfWeek()))
                                .toList()
                );

                for (WeeklyTimetableEntry src : selectedEntries) {
                    // Duplicate check: if target day already has identical entry (same start, end, type, label), skip it
                    boolean isDuplicate = currentDayEntries.stream().anyMatch(tgt ->
                            Objects.equals(tgt.getStartTime(), src.getStartTime()) &&
                            Objects.equals(tgt.getEndTime(), src.getEndTime()) &&
                            Objects.equals(
                                    tgt.getActivityType() == null ? "" : tgt.getActivityType().trim(),
                                    src.getActivityType() == null ? "" : src.getActivityType().trim()
                            ) &&
                            Objects.equals(
                                    tgt.getLabel() == null ? "" : tgt.getLabel().trim(),
                                    src.getLabel() == null ? "" : src.getLabel().trim()
                            )
                    );

                    if (isDuplicate) {
                        skippedDuplicates++;
                        continue;
                    }

                    // Overlap check: skip entries that would overlap existing entries on target day
                    List<WeeklyTimetableEntry> overlaps = findOverlaps(targetDay, src.getStartTime(), src.getEndTime());
                    if (!overlaps.isEmpty()) {
                        String srcLabel = formatEntryLabel(src);
                        String ovNames = overlaps.stream().map(this::formatEntryLabel).reduce((a, b) -> a + ", " + b).orElse("");
                        overlapSkipMessages.add(tgtShortName + ": skipped " + srcLabel + " (overlaps " + ovNames + ")");
                        skippedOverlaps++;
                        continue;
                    }

                    WeeklyTimetableEntry newEntry = src.copyToDay(targetDay);
                    entriesList.add(newEntry);
                    currentDayEntries.add(newEntry);
                    copiedCount++;
                }
            }

            updateTabIndicators();
            updateFreeHoursReview();

            StringBuilder sb = new StringBuilder();
            int targetCount = selectedTargets.size();
            String entriesWord = copiedCount == 1 ? "entry" : "entries";
            String daysWord = targetCount == 1 ? "day" : "days";

            if (copiedCount > 0) {
                sb.append("Copied ").append(copiedCount).append(" ").append(entriesWord)
                        .append(" to ").append(targetCount).append(" ").append(daysWord);
            } else {
                sb.append("0 entries copied to ").append(targetCount).append(" ").append(daysWord);
            }

            List<String> skipNotes = new ArrayList<>();
            if (skippedDuplicates > 0) {
                skipNotes.add(skippedDuplicates + (skippedDuplicates == 1 ? " duplicate" : " duplicates") + " skipped");
            }
            if (skippedOverlaps > 0) {
                skipNotes.add(skippedOverlaps + (skippedOverlaps == 1 ? " overlap" : " overlaps") + " skipped");
            }
            if (!skipNotes.isEmpty()) {
                sb.append(" (").append(String.join(", ", skipNotes)).append(")");
            }
            sb.append(".");

            if (!overlapSkipMessages.isEmpty()) {
                sb.append("\n\nSkipped due to overlaps:\n• ");
                sb.append(String.join("\n• ", overlapSkipMessages));
            }

            UiKit.toast(sb.toString(), UiKit.Kind.INFO);
        }
    }

    private Node createStudyTargetStepNode() {
        VBox vbox = new VBox(16);
        vbox.setPadding(new Insets(16));

        Label header = new Label("Free time each day");
        header.getStyleClass().add("page-title");
        Label sub = new Label("Computed from your commitments and wake/sleep routine. "
                + "The planner fills this time, so check that it looks right before saving.");
        sub.getStyleClass().add("subtle");
        sub.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(10);
        grid.setAlignment(Pos.TOP_LEFT);

        Label dayHeader = new Label("Weekday");
        dayHeader.getStyleClass().add("table-header-cell");
        Label freeHeader = new Label("Computed free hours");
        freeHeader.getStyleClass().add("table-header-cell");
        grid.add(dayHeader, 0, 0);
        grid.add(freeHeader, 1, 0);

        int row = 1;
        for (String day : DAYS) {
            Label dayLbl = new Label(day.substring(0, 1) + day.substring(1).toLowerCase());
            dayLbl.getStyleClass().add("cell-strong");

            Label freeLbl = new Label("24.0 h");
            freeLbl.getStyleClass().add("cell-number");
            freeHourLabels.put(day, freeLbl);

            // Targets are no longer edited here; the spinner only carries the stored value so
            // saving the timetable never overwrites a preference the user did not touch.
            Spinner<Double> spinner = new Spinner<>(0.0, 16.0, 4.0, 0.5);
            spinner.setEditable(true);
            studyHourSpinners.put(day, spinner);

            grid.addRow(row, dayLbl, freeLbl);
            row++;
        }

        Label note = UiKit.hint("Study targets are kept from your saved preferences. "
                + "Change commitments on the previous tab to free more time.");
        ScrollPane scroll = new ScrollPane(new VBox(16, header, sub, grid, note));
        scroll.setFitToWidth(true);
        return scroll;
    }

    public Map<String, WeekdayRoutine> getRoutinesFromUI() {
        Map<String, WeekdayRoutine> map = new LinkedHashMap<>();
        for (String day : DAYS) {
            LocalTime wake = DateUtils.parseTime(perDayWakeFields.get(day).getText());
            LocalTime sleep = DateUtils.parseTime(perDaySleepFields.get(day).getText());
            if (wake == null) wake = LocalTime.of(7, 0);
            if (sleep == null) sleep = LocalTime.of(23, 0);
            map.put(day, new WeekdayRoutine(0, day, wake, sleep));
        }
        return map;
    }

    private void updateFreeHoursReview() {
        if (freeSlotService == null) return;
        Map<String, WeekdayRoutine> currentRoutines = getRoutinesFromUI();
        for (String day : DAYS) {
            double freeHours = freeSlotService.computeFreeHoursForDay(day, currentRoutines, entriesList);
            Label freeLbl = freeHourLabels.get(day);
            if (freeLbl != null) {
                freeLbl.setText(DurationFormat.format(freeHours));
            }
        }
    }

    private void loadExistingData() {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                int semId = active.getSemesterId();
                entriesList.setAll(timetableDAO.findBySemester(semId));

                // Load subject_names into maps
                Map<String, String> storedRenames = SubjectNameDAO.findAllAsMap(semId);
                subjectCodeRenames.clear();
                subjectCodeRenames.putAll(storedRenames);
                
                // Build nameToSubjectCode: reverse map + code-to-code for all entries with subjectCode
                nameToSubjectCode.clear();
                for (Map.Entry<String, String> e : storedRenames.entrySet()) {
                    nameToSubjectCode.put(e.getValue(), e.getKey()); // name -> code
                }
                
                // Backfill subjectCode from label base for entries with null subjectCode
                Pattern codePattern = Pattern.compile("^[A-Za-z]{2,5}\\d{2,4}[A-Za-z]?$");
                for (WeeklyTimetableEntry entry : entriesList) {
                    if (entry.getSubjectCode() == null && entry.getLabel() != null) {
                        // Extract base from label
                        String label = entry.getLabel().trim();
                        Pattern labelPattern = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
                        Matcher m = labelPattern.matcher(label);
                        if (m.matches()) {
                            String base = m.group(1).trim();
                            // Check if base looks like a code
                            if (codePattern.matcher(base).matches()) {
                                entry.setSubjectCode(base.toUpperCase());
                            }
                        }
                    }
                    
                    // Add code-to-code mapping for all entries with non-null subjectCode
                    if (entry.getSubjectCode() != null) {
                        nameToSubjectCode.putIfAbsent(entry.getSubjectCode(), entry.getSubjectCode());
                    }
                }

                List<WeekdayRoutine> routines = timetableDAO.findRoutinesBySemester(semId);
                if (routines != null && !routines.isEmpty()) {
                    for (WeekdayRoutine r : routines) {
                        if (r.getDayOfWeek() != null) {
                            String dayKey = r.getDayOfWeek().toUpperCase();
                            if (perDayWakeFields.containsKey(dayKey)) {
                                perDayWakeFields.get(dayKey).setText(DateUtils.formatTime(r.getWakeTime()));
                            }
                            if (perDaySleepFields.containsKey(dayKey)) {
                                perDaySleepFields.get(dayKey).setText(DateUtils.formatTime(r.getSleepTime()));
                            }
                        }
                    }
                } else {
                    for (String day : DAYS) {
                        perDayWakeFields.get(day).setText("07:00");
                        perDaySleepFields.get(day).setText("23:00");
                    }
                }

                Map<String, Double> prefs = timetableDAO.findStudyPreferencesBySemester(semId);
                for (String day : DAYS) {
                    if (studyHourSpinners.containsKey(day)) {
                        // Reset first so a reload never carries values from another semester.
                        studyHourSpinners.get(day).getValueFactory().setValue(4.0);
                        if (prefs.containsKey(day)) {
                            studyHourSpinners.get(day).getValueFactory().setValue(prefs.get(day));
                        }
                    }
                }

                // Load persisted import metadata (Section D)
                String savedBatch = settingsDAO.get("tt_import_batch_" + semId, null);
                String savedSheet = settingsDAO.get("tt_import_sheet_" + semId, null);
                String savedFile = settingsDAO.get("tt_import_file_" + semId, null);

                boolean hasAcademicEntries = entriesList.stream().anyMatch(e -> {
                    if (e == null || e.getActivityType() == null) return false;
                    String t = e.getActivityType().trim().toUpperCase();
                    return "CLASS".equals(t) || "TUTORIAL".equals(t) || "LAB".equals(t);
                });

                if (savedBatch != null && !savedBatch.isBlank() && hasAcademicEntries) {
                    this.importedBatch = savedBatch;
                    this.importedSheet = savedSheet;
                    this.importedFilePath = savedFile;
                } else {
                    this.importedBatch = null;
                    this.importedSheet = null;
                    this.importedFilePath = null;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        updateTabIndicators();
        updateButtonStates();
        // The database is the state of the last save: remember it so Undo has a target.
        savedStateFingerprint = stagedFingerprint();
    }

    private void saveAll() {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null) {
                active = new Semester(null, "Current Semester", "ACTIVE");
                int id = semesterDAO.insert(active);
                active.setSemesterId(id);
            }
            int semId = active.getSemesterId();

            // Removals the user confirmed while editing, applied only now.
            if (staged) {
                for (Map.Entry<String, String> kept : stagedKeptSubjects.entrySet()) {
                    if (!stagedRemovedSubjects.contains(kept.getKey())) {
                        KeptSubjectDAO.upsert(semId, kept.getKey(), kept.getValue());
                    }
                }
                for (String code : stagedRemovedSubjects) {
                    subjectCleanupService.removeSubjectCompletely(semId, code);
                }
            }

            // Save routines
            Map<String, WeekdayRoutine> uiRoutines = getRoutinesFromUI();
            List<WeekdayRoutine> routinesToSave = new ArrayList<>(uiRoutines.values());
            for (WeekdayRoutine r : routinesToSave) {
                r.setSemesterId(semId);
            }
            timetableDAO.saveRoutines(semId, routinesToSave);

            // Save timetable entries in sorted order (ID-preserving; returns removed ids)
            List<WeeklyTimetableEntry> entriesToSave = new ArrayList<>(entriesList);
            entriesToSave.sort(Comparator.comparingInt((WeeklyTimetableEntry e) ->
                    DAYS.indexOf(e.getDayOfWeek() != null ? e.getDayOfWeek().toUpperCase() : ""))
                    .thenComparing(ENTRY_TIME_COMPARATOR));
            for (WeeklyTimetableEntry ent : entriesToSave) {
                ent.setSemesterId(semId);
            }
            Set<Integer> removedIds = timetableDAO.saveTimetableEntries(semId, entriesToSave);

            // Clean up orphaned CANCEL overrides for removed entries (Fix 2)
            if (dateOverrideDAO != null && !removedIds.isEmpty()) {
                for (int removedId : removedIds) {
                    dateOverrideDAO.deleteByEntryId(removedId);
                }
            }

            // Save study preferences
            List<WeekdayStudyPreference> prefs = new ArrayList<>();
            for (String day : DAYS) {
                double hours = studyHourSpinners.get(day).getValue();
                prefs.add(new WeekdayStudyPreference(semId, day, hours));
            }
            timetableDAO.saveStudyPreferences(semId, prefs);

            // Save subject_names (replace the semester's rows with current non-empty renames)
            SubjectNameDAO.deleteAllForSemester(semId);
            for (Map.Entry<String, String> entry : subjectCodeRenames.entrySet()) {
                String code = entry.getKey();
                String name = entry.getValue();
                if (name != null && !name.trim().isEmpty() && !name.equalsIgnoreCase(code)) {
                    SubjectNameDAO.upsert(semId, code, name);
                }
            }

            // Persist import metadata in settings table (Section D)
            if (importedBatch != null) {
                settingsDAO.set("tt_import_file_" + semId, importedFilePath != null ? importedFilePath : "");
                settingsDAO.set("tt_import_batch_" + semId, importedBatch);
                settingsDAO.set("tt_import_sheet_" + semId, importedSheet != null ? importedSheet : "");
            } else {
                settingsDAO.delete("tt_import_file_" + semId);
                settingsDAO.delete("tt_import_batch_" + semId);
                settingsDAO.delete("tt_import_sheet_" + semId);
            }

            // Trigger regeneration
            replanService.regenerateSemesterPlan(semId, LocalDate.now());

            stagedRemovedSubjects.clear();
            stagedKeptSubjects.clear();

            if (closeOnSave && wizardStage != null) {
                wizardStage.close();
            }
            if (onComplete != null) {
                onComplete.run();
            }
            if (staged) {
                UiKit.toast("Timetable saved. The study plan was rebuilt.");
            }
            savedStateFingerprint = stagedFingerprint();
        } catch (Exception ex) {
            showError("Save Error", "Failed to save timetable setup: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ staging helpers

    /** Confirms a subject removal in the registry, or queues it for the single Save. */
    private void removeSubjects(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return;
        }
        if (staged) {
            stagedKeptSubjects.keySet().removeAll(codes);
            stagedRemovedSubjects.addAll(codes);
            return;
        }
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                int semId = active.getSemesterId();
                for (String code : codes) {
                    subjectCleanupService.removeSubjectCompletely(semId, code);
                }
                if (replanService != null) {
                    replanService.regenerateSemesterPlan(semId, LocalDate.now());
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to remove subjects: " + ex.getMessage(), ex);
        }
    }

    /** Remembers a subject that should survive without timetable entries. */
    private void rememberKeptSubject(String code, String displayName) {
        if (code == null || code.isBlank()) {
            return;
        }
        if (staged) {
            stagedRemovedSubjects.remove(code);
            stagedKeptSubjects.put(code, displayName != null ? displayName : code);
            return;
        }
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                KeptSubjectDAO.upsert(active.getSemesterId(), code, displayName != null ? displayName : code);
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to keep subject: " + ex.getMessage(), ex);
        }
    }

    /** Modal mode persists edits as they happen; tab mode waits for Save. */
    private void persistEntriesOnly() {
        if (staged) {
            return;
        }
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                timetableDAO.saveTimetableEntries(active.getSemesterId(), entriesList);
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to save timetable entries: " + ex.getMessage(), ex);
        }
    }

    private void persistEntriesAndReplan() {
        if (staged) {
            return;
        }
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                timetableDAO.saveTimetableEntries(active.getSemesterId(), entriesList);
                if (replanService != null) {
                    replanService.regenerateSemesterPlan(active.getSemesterId(), LocalDate.now());
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to save timetable entries: " + ex.getMessage(), ex);
        }
    }

    private void renameSubjectOnDisk(int semId, String code, String newName) {
        try {
            if (staged) {
                if (KeptSubjectDAO.exists(semId, code)) {
                    stagedKeptSubjects.put(code, newName == null || newName.isEmpty() ? code : newName);
                }
                return;
            }
            if (KeptSubjectDAO.exists(semId, code)) {
                KeptSubjectDAO.upsert(semId, code, newName == null || newName.isEmpty() ? code : newName);
            }
            if (newName == null || newName.isEmpty() || newName.equalsIgnoreCase(code)) {
                SubjectNameDAO.delete(semId, code);
            } else {
                SubjectNameDAO.upsert(semId, code, newName);
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to rename subject: " + ex.getMessage(), ex);
        }
    }

    /**
     * Returns all existing entries on the given day that overlap with the time range [start, end).
     * Two entries overlap when: existingStart < end AND start < existingEnd (strict comparison,
     * so back-to-back slots like 13:10-13:40 then 13:40-15:10 are allowed and NOT flagged).
     */
    private List<WeeklyTimetableEntry> findOverlaps(String day, LocalTime start, LocalTime end) {
        if (start == null || end == null) return List.of();
        return entriesList.stream()
                .filter(e -> day.equalsIgnoreCase(e.getDayOfWeek()))
                .filter(e -> e.getStartTime() != null && e.getEndTime() != null)
                .filter(e -> start.isBefore(e.getEndTime()) && e.getStartTime().isBefore(end))
                .toList();
    }

    private static String roomsOf(ComponentOption opt) {
        if (opt == null || opt.getSlots() == null) return "";
        LinkedHashSet<String> rooms = new LinkedHashSet<>();
        for (ImprovementSlot s : opt.getSlots()) {
            if (s.getRoom() != null && !s.getRoom().isBlank()) rooms.add(s.getRoom());
        }
        return String.join(", ", rooms);
    }

    /** Formats an entry's label for display in overlap/error messages, e.g. "Lunch (13:10-13:40)". */
    private String formatEntryLabel(WeeklyTimetableEntry e) {
        String label = (e.getLabel() != null && !e.getLabel().isBlank()) ? e.getLabel() : e.getActivityType();
        return label + " (" + e.getStartTime() + "-" + e.getEndTime() + ")";
    }

    /**
     * Opens the "Edit Commitment" dialog for a single selected entry.
     * Validates input, checks for overlaps (excluding the entry itself), and applies
     * the edit atomically, with trimming, removal or splitting of conflicting entries.
     */
    private void openEditCommitmentDialog(String day, WeeklyTimetableEntry entry,
                                          TableView<WeeklyTimetableEntry> table) {
        String displayName = day.substring(0, 1) + day.substring(1).toLowerCase();

        Dialog<ButtonType> dialog = new Dialog<>();
        UiKit.styleDialog(dialog);
        if (wizardStage != null) dialog.initOwner(wizardStage);
        dialog.setTitle("Edit Commitment (" + displayName + ")");
        dialog.setHeaderText("Edit commitment on " + displayName + ". The day cannot be changed here.");

        // ---- Form fields pre-filled from existing entry ----
        TextField dlgStart = new TextField(
                entry.getStartTime() != null ? entry.getStartTime().toString() : "");
        dlgStart.setPromptText("HH:mm");
        dlgStart.setPrefWidth(80);

        TextField dlgEnd = new TextField(
                entry.getEndTime() != null ? entry.getEndTime().toString() : "");
        dlgEnd.setPromptText("HH:mm");
        dlgEnd.setPrefWidth(80);

        ComboBox<String> dlgType = new ComboBox<>(FXCollections.observableArrayList(
                "CLASS", "TUTORIAL", "LAB", "MEAL", "OTHER"));
        dlgType.setValue(entry.getActivityType() != null ? entry.getActivityType() : "CLASS");

        TextField dlgLabel = new TextField(
                entry.getLabel() != null ? entry.getLabel() : "");
        dlgLabel.setPromptText("e.g. CS101 Lecture");
        dlgLabel.setPrefWidth(180);

        TextField dlgRoom = new TextField(entry.getRoomDisplay());
        dlgRoom.setPromptText("optional");
        dlgRoom.setPrefWidth(100);

        GridPane dlgGrid = new GridPane();
        dlgGrid.setHgap(10);
        dlgGrid.setVgap(10);
        dlgGrid.setPadding(new Insets(16));
        dlgGrid.setAlignment(Pos.CENTER_LEFT);
        dlgGrid.addRow(0, new Label("Start (HH:mm):"), dlgStart);
        dlgGrid.addRow(1, new Label("End (HH:mm):"),   dlgEnd);
        dlgGrid.addRow(2, new Label("Type:"),           dlgType);
        dlgGrid.addRow(3, new Label("Label:"),          dlgLabel);
        dlgGrid.addRow(4, new Label("Room:"),           dlgRoom);

        dialog.getDialogPane().setContent(dlgGrid);

        ButtonType btnSave   = new ButtonType("Save",   ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(btnSave, btnCancel);

        // Consume the Save button press ourselves so we can validate and keep the dialog open
        javafx.scene.Node saveNode = dialog.getDialogPane().lookupButton(btnSave);
        saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();   // prevent the dialog from closing automatically

            LocalTime newStart = DateUtils.parseTime(dlgStart.getText().trim());
            LocalTime newEnd   = DateUtils.parseTime(dlgEnd.getText().trim());

            if (newStart == null || newEnd == null) {
                showError("Invalid Time", "Please enter valid times in HH:mm format.");
                return;
            }
            if (!newEnd.isAfter(newStart)) {
                showError("Invalid Time Range",
                        "End time must be strictly after Start time.");
                return;
            }

            String newType  = dlgType.getValue();
            String newLabelText = dlgLabel.getText().trim();
            String newRoomText = dlgRoom.getText() != null ? dlgRoom.getText().trim() : "";
            String newRoom = newRoomText.isEmpty() ? null : newRoomText;

            // If nothing changed, just close
            boolean unchanged = newStart.equals(entry.getStartTime())
                    && newEnd.equals(entry.getEndTime())
                    && newType.equals(entry.getActivityType())
                    && newLabelText.equals(entry.getLabel() != null ? entry.getLabel() : "")
                    && Objects.equals(newRoom, entry.getRoom() == null || entry.getRoom().isBlank() ? null : entry.getRoom());
            if (unchanged) {
                dialog.close();
                return;
            }

            // Overlap check: exclude the entry being edited
            List<WeeklyTimetableEntry> rawOverlaps = findOverlaps(day, newStart, newEnd)
                    .stream()
                    .filter(o -> o != entry)   // identity comparison, so the entry itself is excluded
                    .toList();

            boolean applied = applyEditWithOverlapHandling(
                    day, entry, newStart, newEnd, newType, newLabelText, newRoom, rawOverlaps, table);

            if (applied) {
                dialog.close();
            }
            // If !applied (user chose No), stay on the dialog
        });

        dialog.showAndWait();
    }

    /**
     * Applies the edited entry (and adjusts/removes conflicting entries) atomically.
     *
     * @return true if the edit was applied (dialog should close), false if the user
     *         chose not to proceed (dialog should stay open).
     */
    private boolean applyEditWithOverlapHandling(
            String day,
            WeeklyTimetableEntry entry,
            LocalTime newStart, LocalTime newEnd,
            String newType, String newLabel, String newRoom,
            List<WeeklyTimetableEntry> overlaps,
            TableView<WeeklyTimetableEntry> table) {

        if (!overlaps.isEmpty()) {
            // Build a human-readable description of what will happen to each conflicting entry
            StringBuilder msg = new StringBuilder("This overlaps with:\n");

            // Compute the adjusted state for each conflicting entry (for the confirmation message)
            // Key: original entry -> list of adjusted replacements (empty list = will be removed)
            Map<WeeklyTimetableEntry, List<WeeklyTimetableEntry>> plan = new LinkedHashMap<>();

            for (WeeklyTimetableEntry o : overlaps) {
                LocalTime oStart = o.getStartTime();
                LocalTime oEnd   = o.getEndTime();
                List<WeeklyTimetableEntry> replacements = new ArrayList<>();

                boolean oStartsBeforeE = oStart.isBefore(newStart);
                boolean oEndsAfterE    = oEnd.isAfter(newEnd);

                if (oStartsBeforeE && oEndsAfterE) {
                    // Case d: E is fully inside O, so split O into two pieces
                    WeeklyTimetableEntry left  = o.withTimes(day, oStart, newStart);
                    WeeklyTimetableEntry right = o.withTimes(day, newEnd, oEnd);
                    // Only keep pieces that are at least 1 minute long
                    if (java.time.Duration.between(oStart, newStart).toMinutes() >= 1) {
                        replacements.add(left);
                    }
                    if (java.time.Duration.between(newEnd, oEnd).toMinutes() >= 1) {
                        replacements.add(right);
                    }
                } else if (!oEndsAfterE && oStartsBeforeE) {
                    // Case a: O starts before E and ends inside E, so trim O's end to E's start
                    if (java.time.Duration.between(oStart, newStart).toMinutes() >= 1) {
                        replacements.add(o.withTimes(day, oStart, newStart));
                    }
                    // otherwise it is too short and gets deleted (empty replacements list)
                } else if (!oStartsBeforeE && oEndsAfterE) {
                    // Case b: O starts inside E and ends after E, so trim O's start to E's end
                    if (java.time.Duration.between(newEnd, oEnd).toMinutes() >= 1) {
                        replacements.add(o.withTimes(day, newEnd, oEnd));
                    }
                }
                // Case c: O is fully inside E, so replacements stays empty and it will be deleted

                plan.put(o, replacements);

                // Append a human-readable description of what happens
                msg.append("  \u2022 ").append(formatEntryLabel(o)).append(" \u2192 ");
                if (replacements.isEmpty()) {
                    msg.append("will be removed (fully covered)");
                } else if (replacements.size() == 1) {
                    WeeklyTimetableEntry r = replacements.get(0);
                    msg.append("will become ").append(r.getStartTime()).append("-").append(r.getEndTime());
                } else {
                    // split
                    msg.append("will be split into ");
                    for (int i = 0; i < replacements.size(); i++) {
                        if (i > 0) msg.append(" and ");
                        WeeklyTimetableEntry r = replacements.get(i);
                        msg.append(r.getStartTime()).append("-").append(r.getEndTime());
                    }
                }
                msg.append("\n");
            }
            msg.append("\nApply the edit and adjust these entries?");

            ButtonType btnYes = new ButtonType("Yes, adjust", ButtonBar.ButtonData.YES);
            ButtonType btnNo  = new ButtonType("No, leave as is", ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            if (wizardStage != null) confirm.initOwner(wizardStage);
            UiKit.styleDialog(confirm.getDialogPane());
            confirm.setTitle("Overlapping Entries");
            confirm.setHeaderText("Overlap detected");
            confirm.setContentText(msg.toString());
            confirm.getButtonTypes().setAll(btnYes, btnNo);

            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isEmpty() || choice.get() != btnYes) {
                return false;   // user chose No, so nothing changes
            }

            // ---- Atomic apply: compute the new state, then swap in one operation ----
            // Start with a snapshot of all entries
            List<WeeklyTimetableEntry> newList = new ArrayList<>(entriesList);

            // Remove the entry being edited
            newList.remove(entry);

            // For each conflicting entry: remove and add replacements
            for (Map.Entry<WeeklyTimetableEntry, List<WeeklyTimetableEntry>> planEntry : plan.entrySet()) {
                newList.remove(planEntry.getKey());
                newList.addAll(planEntry.getValue());
            }

            // Add the edited version of the original entry
            WeeklyTimetableEntry edited = new WeeklyTimetableEntry(
                    entry.getEntryId(), entry.getSemesterId(), day, newStart, newEnd, newType, newLabel, newRoom);
            newList.add(edited);

            entriesList.setAll(newList);

            // Re-select the edited entry in the table (after the list refreshes)
            javafx.application.Platform.runLater(() -> {
                // Find the entry in the sorted view by identity equals (id-based) or by value
                for (WeeklyTimetableEntry e : table.getItems()) {
                    if (e.getStartTime().equals(newStart)
                            && e.getEndTime().equals(newEnd)
                            && day.equalsIgnoreCase(e.getDayOfWeek())) {
                        table.getSelectionModel().select(e);
                        table.scrollTo(e);
                        break;
                    }
                }
            });

        } else {
            // No overlap: apply directly by mutating the entry in place
            entry.setStartTime(newStart);
            entry.setEndTime(newEnd);
            entry.setActivityType(newType);
            entry.setLabel(newLabel);
            entry.setRoom(newRoom);

            // Force the ObservableList to fire an update so the table and tab counts refresh.
            // We do this by replacing the element with itself (set at its index).
            int idx = entriesList.indexOf(entry);
            if (idx >= 0) {
                entriesList.set(idx, entry);
            }

            javafx.application.Platform.runLater(() -> {
                table.getSelectionModel().select(entry);
                table.scrollTo(entry);
            });
        }

        updateTabIndicators();
        updateFreeHoursReview();
        return true;
    }

    private boolean isOutsideAwake(String day, LocalTime st, LocalTime et, Map<String, WeekdayRoutine> routines) {
        if (routines == null || st == null || et == null) return false;
        WeekdayRoutine r = routines.get(day);
        if (r == null) return false;
        LocalTime wake = r.getWakeTime();
        LocalTime sleep = r.getSleepTime();
        if (wake == null || sleep == null) return false;
        if (sleep.isAfter(wake)) {
            return st.isBefore(wake) || et.isAfter(sleep);
        } else {
            String prevDay = FreeSlotService.getPreviousDayOfWeek(day);
            WeekdayRoutine prevR = routines.get(prevDay);
            LocalTime prevSleep = (prevR != null && !prevR.getSleepTime().isAfter(prevR.getWakeTime()))
                    ? prevR.getSleepTime() : LocalTime.MIN;
            return st.isBefore(wake) && et.isAfter(prevSleep);
        }
    }

    public static List<WeeklyTimetableEntry> removeAcademicEntries(List<WeeklyTimetableEntry> entries) {
        if (entries == null) return new ArrayList<>();
        List<WeeklyTimetableEntry> kept = new ArrayList<>();
        for (WeeklyTimetableEntry e : entries) {
            if (e == null) continue;
            String type = e.getActivityType();
            if (type == null) {
                kept.add(e);
                continue;
            }
            String t = type.trim().toUpperCase();
            if (!"CLASS".equals(t) && !"TUTORIAL".equals(t) && !"LAB".equals(t)) {
                kept.add(e);
            }
        }
        return kept;
    }

    private void updateButtonStates() {
        boolean hasAcademicEntries = entriesList.stream().anyMatch(e -> {
            if (e == null || e.getActivityType() == null) return false;
            String t = e.getActivityType().trim().toUpperCase();
            return "CLASS".equals(t) || "TUTORIAL".equals(t) || "LAB".equals(t);
        });

        if (btnImportExcel != null) {
            if (importedBatch != null) {
                btnImportExcel.setDisable(true);
                btnImportExcel.setTooltip(new Tooltip(
                        "Imported batch " + importedBatch + ". Click Clear Batch to import a different one."));
            } else {
                btnImportExcel.setDisable(false);
                btnImportExcel.setTooltip(null);
            }
        }

        if (btnAddImprovement != null) {
            if (importedBatch != null) {
                btnAddImprovement.setDisable(false);
                btnAddImprovement.setTooltip(null);
            } else {
                btnAddImprovement.setDisable(true);
                if (hasAcademicEntries) {
                    btnAddImprovement.setTooltip(new Tooltip("Timetable was not imported from Excel. Import your timetable first."));
                } else {
                    btnAddImprovement.setTooltip(new Tooltip("Import your timetable first"));
                }
            }
        }

        if (btnClearBatch != null) {
            btnClearBatch.setDisable(!hasAcademicEntries && importedBatch == null);
        }
    }

    private void setBusy(boolean busy) {
        if (wizardStage != null && wizardStage.getScene() != null) {
            wizardStage.getScene().setCursor(busy ? Cursor.WAIT : Cursor.DEFAULT);
        }
        if (btnImportExcel != null) btnImportExcel.setDisable(busy || importedBatch != null);
        if (btnAddImprovement != null) btnAddImprovement.setDisable(busy || importedBatch == null);
        if (btnClearBatch != null) btnClearBatch.setDisable(busy);
    }

    private void handleClearBatch() {
        try {
            handleClearBatchImpl();
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Clear Batch Failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void handleClearBatchImpl() throws Exception {
        String batchName = (importedBatch != null ? importedBatch : "");
        String batchPart = (importedBatch != null ? " for batch " + importedBatch : "");
        boolean confirmed = UiKit.confirm(wizardStage, "Clear batch",
                "Clear all class, tutorial and lab entries" + batchPart + "?",
                "Every subject in the batch is removed completely (they will not be kept for exams or studies). "
                        + "Meals, sleep and other personal commitments are kept.",
                "Clear batch");
        if (confirmed) {
            List<WeeklyTimetableEntry> toRemove = new ArrayList<>();
            for (WeeklyTimetableEntry e : entriesList) {
                if (e != null && e.getActivityType() != null) {
                    String type = e.getActivityType().trim().toUpperCase();
                    if ("CLASS".equals(type) || "TUTORIAL".equals(type) || "LAB".equals(type)) {
                        toRemove.add(e);
                    }
                }
            }
            
            // Remove subjects completely (kept_subjects, subject_names, subject_credits, exams, topics, non-DONE daily_logs)
            if (!toRemove.isEmpty()) {
                removeSubjects(subjectCodesIn(toRemove, nameToSubjectCode));
            }
            
            List<WeeklyTimetableEntry> kept = removeAcademicEntries(entriesList);
            entriesList.setAll(kept);
            persistEntriesOnly();
            importedBatch = null;
            importedSheet = null;
            importedFilePath = null;
            loadedBatch = null;
            loadedSheet = null;
            loadedWorkbook = null;
            updateButtonStates();
            updateTabIndicators();
            updateFreeHoursReview();
            triggerUiRefresh();
        }
    }

    private void handleClearEntireWeek() {
        try {
            handleClearEntireWeekImpl();
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Clear Week Failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void handleClearEntireWeekImpl() throws Exception {
        boolean confirmed = UiKit.confirm(wizardStage, "Clear entire week",
                "Clear the entire weekly timetable?",
                "All subjects are removed completely and this cannot be undone. Meals, sleep and other personal "
                        + "commitments are kept.",
                "Clear entire week");
        if (!confirmed) {
            return;
        }
        {
            // Collect all academic entries
            List<WeeklyTimetableEntry> allAcademic = new ArrayList<>();
            for (WeeklyTimetableEntry e : entriesList) {
                if (e != null && e.getActivityType() != null) {
                    String type = e.getActivityType().trim().toUpperCase();
                    if ("CLASS".equals(type) || "TUTORIAL".equals(type) || "LAB".equals(type)) {
                        allAcademic.add(e);
                    }
                }
            }
            
            // Remove subjects completely (kept_subjects, subject_names, subject_credits, exams, topics, non-DONE daily_logs)
            if (!allAcademic.isEmpty()) {
                removeSubjects(subjectCodesIn(allAcademic, nameToSubjectCode));
            }
            
            entriesList.clear();
            for (String d : DAYS) {
                perDayWakeFields.get(d).setText("07:00");
                perDaySleepFields.get(d).setText("23:00");
            }
            persistEntriesOnly();
            importedBatch = null;
            importedSheet = null;
            importedFilePath = null;
            loadedBatch = null;
            loadedSheet = null;
            loadedWorkbook = null;
            updateButtonStates();
            updateTabIndicators();
            updateFreeHoursReview();
            triggerUiRefresh();
        }
    }

    /**
     * Extract subject codes from timetable entries with labels matching
     * pattern "&lt;base&gt; lecture|tutorial|practical".
     */
    static Set<String> subjectCodesIn(List<WeeklyTimetableEntry> entries, Map<String, String> nameToSubjectCode) {
        Pattern p = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
        Set<String> codes = new LinkedHashSet<>();
        for (WeeklyTimetableEntry e : entries) {
            if (e != null && e.getLabel() != null) {
                Matcher m = p.matcher(e.getLabel().trim());
                if (m.matches()) {
                    String base = m.group(1).trim();
                    String code = nameToSubjectCode.getOrDefault(base, base);
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    private void handleImportFromExcel() {
        try {
            if (importedBatch != null) {
                showInfo("Import Timetable", "Imported batch " + importedBatch + ". Click Clear Batch to import a different one.");
                return;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select Timetable Excel File");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files (*.xlsx)", "*.xlsx"));
            File file = fileChooser.showOpenDialog(wizardStage);
            if (file == null) return;

            setBusy(true);
            Task<ParsedWorkbook> parseTask = new Task<>() {
                @Override
                protected ParsedWorkbook call() throws Exception {
                    return timetableXlsxReader.parseWorkbook(file);
                }
            };
            parseTask.setOnSucceeded(e -> {
                setBusy(false);
                ParsedWorkbook workbook = parseTask.getValue();
                processImportWithParsedWorkbook(file, workbook);
            });
            parseTask.setOnFailed(e -> {
                setBusy(false);
                Throwable ex = parseTask.getException();
                LOGGER.log(Level.SEVERE, "Failed to read Excel file", ex);
                showError("Import Error", "Could not read Excel file: " + (ex != null ? ex.getMessage() : "Unknown error"));
            });
            Thread thread = new Thread(parseTask, "ExcelImportParserThread");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception ex) {
            setBusy(false);
            LOGGER.log(Level.SEVERE, "Import flow failed", ex);
            showError("Import Error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void processImportWithParsedWorkbook(File file, ParsedWorkbook workbook) {
        try {
            TextInputDialog batchDialog = new TextInputDialog();
            if (wizardStage != null) batchDialog.initOwner(wizardStage);
            UiKit.styleDialog(batchDialog);
            batchDialog.setTitle("Import Timetable");
            batchDialog.setHeaderText("Import timetable from " + file.getName());
            batchDialog.setContentText("Enter your batch (e.g. 3C11):");
            Optional<String> batchResult = batchDialog.showAndWait();
            if (batchResult.isEmpty() || batchResult.get().trim().isEmpty()) {
                updateButtonStates();
                return;
            }
            String typedBatch = batchResult.get().trim();

            List<String> matchingSheets = workbook.findSheetsForBatch(typedBatch);
            if (matchingSheets.isEmpty()) {
                List<String> similar = workbook.findSimilarBatches(typedBatch);
                StringBuilder msg = new StringBuilder("Batch '" + typedBatch + "' was not found in the workbook.");
                if (!similar.isEmpty()) {
                    msg.append("\n\nDid you mean:\n• ").append(String.join("\n• ", similar));
                }
                showError("Batch Not Found", msg.toString());
                return;
            }

            String chosenSheet;
            if (matchingSheets.size() == 1) {
                chosenSheet = matchingSheets.get(0);
            } else {
                ChoiceDialog<String> sheetChoiceDialog = new ChoiceDialog<>(matchingSheets.get(0), matchingSheets);
                if (wizardStage != null) sheetChoiceDialog.initOwner(wizardStage);
                sheetChoiceDialog.setTitle("Select Sheet");
                sheetChoiceDialog.setHeaderText("Batch '" + typedBatch + "' was found on multiple sheets:");
                sheetChoiceDialog.setContentText("Choose sheet:");
                Optional<String> res = sheetChoiceDialog.showAndWait();
                if (res.isEmpty()) return;
                chosenSheet = res.get();
            }

            String canonicalBatch = workbook.getCanonicalBatchName(chosenSheet, typedBatch);
            if (canonicalBatch == null) canonicalBatch = typedBatch;

            List<TimetableSlot> slots = workbook.getSlots(chosenSheet, typedBatch);
            if (slots == null || slots.isEmpty()) {
                String summary = workbook.getDetectionSummary(chosenSheet, typedBatch);
                String msg = (summary != null)
                        ? summary
                        : "No timetable slots found for batch " + typedBatch + " on sheet '" + chosenSheet + "'.";
                showError("No Entries", msg);
                return;
            }

            List<WeeklyTimetableEntry> candidateEntries = new ArrayList<>();
            boolean skipAllElectives = false;
            Set<String> runChosenElectiveBaseCodes = new LinkedHashSet<>();

            for (TimetableSlot slot : slots) {
                if (!slot.isElective()) {
                    candidateEntries.add(slot.toEntry(subjectCodeRenames));
                } else {
                    if (skipAllElectives) continue;

                    ElectiveOption autoOpt = null;
                    for (ElectiveOption opt : slot.getElectiveOptions()) {
                        if (runChosenElectiveBaseCodes.contains(opt.getBaseCode().toUpperCase())) {
                            autoOpt = opt;
                            break;
                        }
                    }

                    if (autoOpt != null) {
                        candidateEntries.add(autoOpt.toEntry(slot.getDay(), subjectCodeRenames));
                    } else {
                        ElectiveChoice choice = promptElectiveSlot(slot, runChosenElectiveBaseCodes);
                        if (choice == null || choice.isCancelled()) {
                            return;
                        }
                        if (choice.isSkipAll()) {
                            skipAllElectives = true;
                        } else if (choice.getEntry() != null) {
                            candidateEntries.add(choice.getEntry());
                        }
                    }
                }
            }

            if (candidateEntries.isEmpty()) {
                showError("No Entries", "No entries were selected to import.");
                return;
            }

            // Direct addition without preview dialog (Requirement A.3)
            int addedCount = 0;
            int skippedDuplicates = 0;
            int mealOtherOverlapCount = 0;
            List<String> mealOtherOverlapDescriptions = new ArrayList<>();

            for (WeeklyTimetableEntry entry : candidateEntries) {
                boolean isDup = entriesList.stream().anyMatch(e -> isIdenticalEntry(e, entry));
                if (isDup) {
                    skippedDuplicates++;
                    continue;
                }

                List<WeeklyTimetableEntry> softs = findMealOtherOverlaps(entry.getDayOfWeek(), entry.getStartTime(), entry.getEndTime());
                if (!softs.isEmpty()) {
                    mealOtherOverlapCount += softs.size();
                    for (WeeklyTimetableEntry s : softs) {
                        mealOtherOverlapDescriptions.add(formatEntryLabel(entry) + " overlaps " + formatEntryLabel(s));
                    }
                }

                entriesList.add(entry);
                addedCount++;
            }

            if (addedCount > 0) {
                // Cache workbook copy in imported/timetable_<semId>.xlsx (Requirement D)
                try {
                    Semester active = semesterDAO.getActiveSemester();
                    int semId = (active != null && active.getSemesterId() != null) ? active.getSemesterId() : 0;
                    File importedDir = new File("imported");
                    if (!importedDir.exists()) importedDir.mkdirs();
                    File destFile = new File(importedDir, "timetable_" + semId + ".xlsx");
                    Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    this.importedFilePath = destFile.getPath();
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Could not cache imported Excel file: " + ex.getMessage(), ex);
                    this.importedFilePath = file.getAbsolutePath();
                }

                this.importedBatch = canonicalBatch;
                this.importedSheet = chosenSheet;
                this.loadedWorkbook = workbook;
                this.loadedBatch = canonicalBatch;
                this.loadedSheet = chosenSheet;

                updateButtonStates();
                updateTabIndicators();
                updateFreeHoursReview();

                StringBuilder sb = new StringBuilder("Added ").append(addedCount).append(addedCount == 1 ? " entry" : " entries");
                sb.append(", skipped ").append(skippedDuplicates).append(" duplicates");
                if (mealOtherOverlapCount > 0) {
                    sb.append(", ").append(mealOtherOverlapCount).append(" overlaps with meal/other");
                    if (mealOtherOverlapDescriptions.size() <= 3) {
                        sb.append(": ").append(String.join(", ", mealOtherOverlapDescriptions));
                    } else {
                        sb.append(": ").append(String.join(", ", mealOtherOverlapDescriptions.subList(0, 3))).append(", ...");
                    }
                }
                persistEntriesAndReplan();
                showInfo("Import Complete", sb.toString());
                triggerUiRefresh();
            } else {
                showInfo("Import Summary", "No entries were added (all " + skippedDuplicates + " were duplicates).");
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed processing imported workbook", ex);
            showError("Import Error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static class ElectiveChoice {
        private final WeeklyTimetableEntry entry;
        private final boolean skipAll;
        private final boolean cancelled;

        public ElectiveChoice(WeeklyTimetableEntry entry, boolean skipAll, boolean cancelled) {
            this.entry = entry;
            this.skipAll = skipAll;
            this.cancelled = cancelled;
        }

        public WeeklyTimetableEntry getEntry() { return entry; }
        public boolean isSkipAll() { return skipAll; }
        public boolean isCancelled() { return cancelled; }
    }

    private ElectiveChoice promptElectiveSlot(TimetableSlot slot, Set<String> runChosenCodes) {
        Dialog<ButtonType> dialog = new Dialog<>();
        UiKit.styleDialog(dialog);
        if (wizardStage != null) dialog.initOwner(wizardStage);
        String shortDay = slot.getDay().substring(0, 1) + slot.getDay().substring(1, 3).toLowerCase();
        dialog.setTitle("Elective Slot");
        dialog.setHeaderText("Elective slot (" + shortDay + " " + slot.getStartTime() + "): select your option");

        VBox content = new VBox(12);
        content.setPadding(new Insets(14));

        Label lblPrompt = new Label("Choose which elective to attend:");
        lblPrompt.getStyleClass().add("check-strong");

        ComboBox<ElectiveOption> comboBox = new ComboBox<>(FXCollections.observableArrayList(slot.getElectiveOptions()));
        comboBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ElectiveOption opt) {
                if (opt == null) return "";
                StringBuilder sb = new StringBuilder();
                sb.append(opt.getBaseCode()).append(" - ").append(opt.getActivityType());
                sb.append(" (").append(opt.getStartTime()).append("-").append(opt.getEndTime());
                if (opt.getRoom() != null && !opt.getRoom().isBlank()) {
                    sb.append(", room: ").append(opt.getRoom());
                }
                sb.append(")");
                return sb.toString();
            }
            @Override
            public ElectiveOption fromString(String string) { return null; }
        });
        if (!slot.getElectiveOptions().isEmpty()) {
            comboBox.getSelectionModel().select(0);
        }
        comboBox.setMaxWidth(Double.MAX_VALUE);

        content.getChildren().addAll(lblPrompt, comboBox);
        dialog.getDialogPane().setContent(content);

        ButtonType btnAdd = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnSkip = new ButtonType("Skip this slot", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType btnSkipAll = new ButtonType("Skip all remaining electives", ButtonBar.ButtonData.NO);

        dialog.getDialogPane().getButtonTypes().addAll(btnAdd, btnSkip, btnSkipAll);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent()) {
            if (result.get() == btnAdd) {
                ElectiveOption chosen = comboBox.getValue();
                if (chosen != null) {
                    runChosenCodes.add(chosen.getBaseCode().toUpperCase());
                    return new ElectiveChoice(chosen.toEntry(slot.getDay(), subjectCodeRenames), false, false);
                }
            } else if (result.get() == btnSkipAll) {
                return new ElectiveChoice(null, true, false);
            } else if (result.get() == btnSkip) {
                return new ElectiveChoice(null, false, false);
            }
        }
        return new ElectiveChoice(null, false, true);
    }

    private boolean isIdenticalEntry(WeeklyTimetableEntry a, WeeklyTimetableEntry b) {
        if (a == null || b == null) return false;
        if (!a.getDayOfWeek().equalsIgnoreCase(b.getDayOfWeek())) return false;
        if (!Objects.equals(a.getStartTime(), b.getStartTime())) return false;
        if (!Objects.equals(a.getEndTime(), b.getEndTime())) return false;
        String aType = a.getActivityType() != null ? a.getActivityType().trim() : "";
        String bType = b.getActivityType() != null ? b.getActivityType().trim() : "";
        if (!aType.equalsIgnoreCase(bType)) return false;
        String aLabel = a.getLabel() != null ? a.getLabel().trim() : "";
        String bLabel = b.getLabel() != null ? b.getLabel().trim() : "";
        return aLabel.equalsIgnoreCase(bLabel);
    }

    private List<WeeklyTimetableEntry> findMealOtherOverlaps(String dayOfWeek, LocalTime start, LocalTime end) {
        List<WeeklyTimetableEntry> overlaps = new ArrayList<>();
        if (dayOfWeek == null || start == null || end == null) return overlaps;
        for (WeeklyTimetableEntry e : entriesList) {
            if (dayOfWeek.equalsIgnoreCase(e.getDayOfWeek()) && e.getStartTime() != null && e.getEndTime() != null) {
                if (start.isBefore(e.getEndTime()) && e.getStartTime().isBefore(end)) {
                    String t = e.getActivityType() != null ? e.getActivityType().toUpperCase() : "";
                    if ("MEAL".equals(t) || "OTHER".equals(t) || "SLEEP".equals(t)) {
                        overlaps.add(e);
                    }
                }
            }
        }
        return overlaps;
    }

    private void handleAddImprovementSubject() {
        try {
            handleAddImprovementSubjectImpl();
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Improvement Check Failed", ex);
            showError("Improvement Check Failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void handleAddImprovementSubjectImpl() {
        if (importedBatch == null) {
            showInfo("Add Improvement Subject", "Import your timetable first.");
            return;
        }

        if (loadedWorkbook == null) {
            if (importedFilePath == null || !new File(importedFilePath).exists()) {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Locate Timetable Excel File for Improvement Subject");
                fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files (*.xlsx)", "*.xlsx"));
                File file = fileChooser.showOpenDialog(wizardStage);
                if (file == null) return;
                importedFilePath = file.getAbsolutePath();
            }
        }

        Dialog<ButtonType> inputDialog = new Dialog<>();
        UiKit.styleDialog(inputDialog);
        if (wizardStage != null) inputDialog.initOwner(wizardStage);
        inputDialog.setTitle("Add improvement subject, batch " + importedBatch);
        inputDialog.setHeaderText("Find batches offering an improvement subject that fit your timetable.");

        VBox content = new VBox(10);
        content.setPadding(new Insets(14));

        TextField txtCode = new TextField();
        txtCode.setPromptText("e.g. UCS503");
        txtCode.setPrefWidth(140);

        CheckBox cbLec = new CheckBox("Lecture (L)");
        cbLec.setSelected(true);
        CheckBox cbTut = new CheckBox("Tutorial (T)");
        cbTut.setSelected(true);
        CheckBox cbPrac = new CheckBox("Practical (P)");
        cbPrac.setSelected(true);

        HBox checks = new HBox(12, cbLec, cbTut, cbPrac);

        content.getChildren().addAll(
                new Label("Subject Base Code:"), txtCode,
                new Label("Components to take:"), checks
        );
        inputDialog.getDialogPane().setContent(content);

        ButtonType btnCheck = new ButtonType("Check", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        inputDialog.getDialogPane().getButtonTypes().addAll(btnCheck, btnCancel);

        Node checkBtnNode = inputDialog.getDialogPane().lookupButton(btnCheck);
        checkBtnNode.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String rawCode = txtCode.getText();
            if (rawCode == null || rawCode.trim().isEmpty()) {
                event.consume();
                showError("Validation Error", "Please enter a subject base code.");
                return;
            }
            if (!cbLec.isSelected() && !cbTut.isSelected() && !cbPrac.isSelected()) {
                event.consume();
                showError("Validation Error", "At least one component (Lecture, Tutorial, Practical) must be selected.");
            }
        });

        Optional<ButtonType> res = inputDialog.showAndWait();
        if (res.isEmpty() || res.get() != btnCheck) return;

        final String cleanCode = ImprovementService.cleanSubjectCode(txtCode.getText());
        final boolean incLec = cbLec.isSelected();
        final boolean incTut = cbTut.isSelected();
        final boolean incPrac = cbPrac.isSelected();
        
        // Build included codes set (from personal timetable + kept subjects)
        final Set<String> includedCodes = new HashSet<>();
        for (WeeklyTimetableEntry entry : entriesList) {
            String code = determineSubjectCode(entry, nameToSubjectCode);
            if (code != null) {
                includedCodes.add(code.toUpperCase());
            }
        }
        
        // Add kept subjects
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                Map<String, String> keptMap = KeptSubjectDAO.findAllAsMap(active.getSemesterId());
                for (String code : keptMap.keySet()) {
                    includedCodes.add(code.toUpperCase());
                }
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "Could not load kept subjects", ex);
        }
        
        final List<WeeklyTimetableEntry> personalEntriesCopy = new ArrayList<>(entriesList);
        final Map<String, String> renamesCopy = new HashMap<>(subjectCodeRenames);
        final String studentBatch = importedBatch;
        final String studentSheet = importedSheet;
        final String filePath = importedFilePath;

        setBusy(true);

        Task<Map<Character, List<ComponentOption>>> searchTask = new Task<>() {
            @Override
            protected Map<Character, List<ComponentOption>> call() throws Exception {
                if (loadedWorkbook == null) {
                    if (filePath == null || !new File(filePath).exists()) {
                        throw new FileNotFoundException("Imported Excel file was not found: " + filePath);
                    }
                    ParsedWorkbook parsed = timetableXlsxReader.parseWorkbook(new File(filePath));
                    boolean batchFound = false;
                    for (Map<String, List<TimetableSlot>> batchMap : parsed.getSheetBatchSlots().values()) {
                        if (batchMap.containsKey(studentBatch)) {
                            batchFound = true;
                            break;
                        }
                    }
                    if (!batchFound) {
                        throw new IllegalArgumentException("The Excel workbook does not contain your imported batch: " + studentBatch);
                    }
                    loadedWorkbook = parsed;
                }
                
                // Build university baseline
                List<WeeklyTimetableEntry> universityBaseline = ImprovementService.buildUniversityBaseline(
                        loadedWorkbook, studentBatch, studentSheet, includedCodes, cleanCode, 
                        personalEntriesCopy, renamesCopy
                );
                
                // Extract personal soft entries (MEAL/SLEEP/OTHER only)
                List<WeeklyTimetableEntry> personalSoftEntries = new ArrayList<>();
                for (WeeklyTimetableEntry entry : personalEntriesCopy) {
                    String type = entry.getActivityType();
                    if (type != null) {
                        String upperType = type.toUpperCase();
                        if ("MEAL".equals(upperType) || "SLEEP".equals(upperType) || "OTHER".equals(upperType)) {
                            personalSoftEntries.add(entry);
                        }
                    }
                }
                
                return improvementService.findComponentOptions(
                        loadedWorkbook, cleanCode, incLec, incTut, incPrac, 
                        universityBaseline, personalSoftEntries, studentBatch
                );
            }
        };

        searchTask.setOnSucceeded(e -> {
            setBusy(false);
            Map<Character, List<ComponentOption>> options = searchTask.getValue();
            int totalOffered = (incLec ? options.getOrDefault('L', List.of()).size() : 0)
                    + (incTut ? options.getOrDefault('T', List.of()).size() : 0)
                    + (incPrac ? options.getOrDefault('P', List.of()).size() : 0);
            if (totalOffered == 0) {
                showInfo("No Batches Found", "No batch offers the selected components for subject " + cleanCode + ".");
                return;
            }
            openImprovementResultsDialog(cleanCode, incLec, incTut, incPrac, options, includedCodes);
        });

        searchTask.setOnFailed(e -> {
            setBusy(false);
            Throwable ex = searchTask.getException();
            LOGGER.log(Level.SEVERE, "Improvement search failed", ex);
            Throwable root = ex;
            while (root.getCause() != null && !(root instanceof NoSuchElementException)) {
                root = root.getCause();
            }
            if (root instanceof NoSuchElementException) {
                showInfo("Subject Not Found", "Subject " + cleanCode + " was not found in the workbook.");
            } else {
                showError("Improvement Check Failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        });

        Thread thread = new Thread(searchTask, "ImprovementSearchThread");
        thread.setDaemon(true);
        thread.start();
    }

    private void openImprovementResultsDialog(
            String code, boolean incLec, boolean incTut, boolean incPrac,
            Map<Character, List<ComponentOption>> options, Set<String> includedCodes) {

        Dialog<ButtonType> dialog = new Dialog<>();
        UiKit.styleDialog(dialog);
        if (wizardStage != null) dialog.initOwner(wizardStage);
        dialog.setTitle("Improvement subject recommendations for " + code);
        
        // Build baseline summary for header
        StringBuilder baselineSummary = new StringBuilder();
        StringBuilder fullList = new StringBuilder();
        int count = 0;
        int improvementCount = 0;
        List<String> sampleCodes = new ArrayList<>();
        List<String> keptCodes = new ArrayList<>();
        
        // Get kept subjects
        Set<String> keptSet = new HashSet<>();
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                Map<String, String> keptMap = KeptSubjectDAO.findAllAsMap(active.getSemesterId());
                keptSet.addAll(keptMap.keySet());
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "Could not load kept subjects for summary", ex);
        }
        
        // Count subjects and collect samples
        for (String includedCode : includedCodes) {
            if (includedCode != null && !includedCode.equalsIgnoreCase(code)) {
                count++;
                boolean isKept = keptSet.contains(includedCode.toUpperCase());
                if (isKept) {
                    keptCodes.add(includedCode);
                }
                if (sampleCodes.size() < 3) {
                    sampleCodes.add(includedCode);
                }
                if (fullList.length() > 0) fullList.append(", ");
                fullList.append(includedCode);
                if (isKept) {
                    fullList.append(" (kept)");
                }
            }
        }
        
        // Count improvement subjects already added (rough estimate from personal entries)
        for (WeeklyTimetableEntry entry : entriesList) {
            String type = entry.getActivityType();
            if (type != null && ("CLASS".equals(type.toUpperCase()) || "TUTORIAL".equals(type.toUpperCase()) || "LAB".equals(type.toUpperCase()))) {
                String entryCode = determineSubjectCode(entry, nameToSubjectCode);
                if (entryCode != null) {
                    // Check if this is likely an improvement subject (not in our included list but is academic)
                    if (!includedCodes.contains(entryCode.toUpperCase()) && !entryCode.equalsIgnoreCase(code)) {
                        improvementCount++;
                        break; // Just need to know there are some
                    }
                }
            }
        }
        
        baselineSummary.append("Batches teaching ").append(code).append(" (your batch: ").append(importedBatch).append("). ");
        baselineSummary.append("Conflicts are checked against the university timetable of batch ").append(importedBatch);
        baselineSummary.append(": ").append(count).append(" subject").append(count != 1 ? "s" : "");
        if (!sampleCodes.isEmpty()) {
            baselineSummary.append(" (").append(String.join(", ", sampleCodes));
            if (count > sampleCodes.size()) {
                baselineSummary.append(", ...");
            }
            if (!keptCodes.isEmpty()) {
                baselineSummary.append(" + kept: ").append(String.join(", ", keptCodes));
            }
            baselineSummary.append(")");
        }
        if (improvementCount > 0) {
            baselineSummary.append(" and improvement slots already added");
        }
        baselineSummary.append(".");
        
        Label headerLabel = new Label(baselineSummary.toString());
        headerLabel.setWrapText(true);
        headerLabel.setMaxWidth(880);
        if (count > 0) {
            Tooltip fullListTooltip = new Tooltip("Baseline subjects: " + fullList.toString());
            fullListTooltip.setWrapText(true);
            fullListTooltip.setMaxWidth(600);
            Tooltip.install(headerLabel, fullListTooltip);
        }
        dialog.getDialogPane().setHeader(headerLabel);

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));
        root.setPrefWidth(900);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Map<Character, TableView<ComponentOption>> tableMap = new LinkedHashMap<>();

        // Filled in once all four tables exist: "Clear selection" clears every table so the
        // summary returns to "None selected." instead of leaving another tab still picked.
        final Runnable[] clearAll = new Runnable[1];

        if (incLec) {
            tabPane.getTabs().add(createTabForComponent('L', "Lecture", options.getOrDefault('L', List.of()), tableMap, clearAll));
        }
        if (incTut) {
            tabPane.getTabs().add(createTabForComponent('T', "Tutorial", options.getOrDefault('T', List.of()), tableMap, clearAll));
        }
        if (incPrac) {
            tabPane.getTabs().add(createTabForComponent('P', "Practical", options.getOrDefault('P', List.of()), tableMap, clearAll));
        }

        // Best Batch tab
        List<BestBatchOption> bestBatches = ImprovementService.rankBestBatches(options, incLec, incTut, incPrac);
        TableView<BestBatchOption> bestBatchTable = createBestBatchTableView(bestBatches);
        Tab bestBatchTab = new Tab("Best Batch", bestBatchTable);
        tabPane.getTabs().add(bestBatchTab);

        // Summary panel at bottom
        VBox summaryBox = new VBox(6);
        summaryBox.getStyleClass().add("card-flat");

        Label lblSummaryHeader = new Label("Selected Components:");
        lblSummaryHeader.getStyleClass().add("check-strong");

        Label lblSelectedSummary = new Label("None selected.");
        lblSelectedSummary.setWrapText(true);

        Label lblOverlapWarning = new Label();
        lblOverlapWarning.getStyleClass().add("text-danger");
        lblOverlapWarning.setWrapText(true);

        summaryBox.getChildren().addAll(lblSummaryHeader, lblSelectedSummary, lblOverlapWarning);

        root.getChildren().addAll(tabPane, summaryBox);
        dialog.getDialogPane().setContent(root);

        ButtonType btnAdd = new ButtonType("Add to Timetable", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(btnAdd, btnCancel);

        Node addBtnNode = dialog.getDialogPane().lookupButton(btnAdd);
        addBtnNode.setDisable(true);

        Runnable updateSummary = () -> {
            List<ComponentOption> chosen = new ArrayList<>();
            for (TableView<ComponentOption> tv : tableMap.values()) {
                ComponentOption sel = tv.getSelectionModel().getSelectedItem();
                if (sel != null) chosen.add(sel);
            }

            if (chosen.isEmpty()) {
                lblSelectedSummary.setText("None selected.");
                lblOverlapWarning.setText("");
                addBtnNode.setDisable(true);
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (ComponentOption opt : chosen) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("- ").append(opt.getComponentName()).append(": Batch ").append(opt.getBatchName())
                  .append(" (").append(opt.getSheetName()).append(")");
                if (opt.isConflicts()) {
                    sb.append(": has conflicts");
                } else {
                    sb.append(", ").append(opt.getSlotsSummary());
                }
                String rooms = roomsOf(opt);
                if (!rooms.isEmpty()) sb.append("  Room: ").append(rooms);
            }
            lblSelectedSummary.setText(sb.toString());

            List<CrossComponentOverlap> crossOverlaps = ImprovementService.findCrossComponentOverlaps(chosen);
            if (!crossOverlaps.isEmpty()) {
                lblOverlapWarning.setText("Cross-component conflict: " + crossOverlaps.get(0).getDescription());
                addBtnNode.setDisable(true);
            } else {
                lblOverlapWarning.setText("");
                // Enable add even if individual components have conflicts (we'll handle selective add)
                addBtnNode.setDisable(false);
            }
        };

        clearAll[0] = () -> {
            for (TableView<ComponentOption> tv : tableMap.values()) {
                tv.getSelectionModel().clearSelection();
            }
            bestBatchTable.getSelectionModel().clearSelection();
            updateSummary.run();
        };

        // When Best Batch row is selected, update the component tables and summary
        bestBatchTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                if (incLec && newV.getLecture() != null) {
                    TableView<ComponentOption> lecTable = tableMap.get('L');
                    if (lecTable != null) {
                        lecTable.getSelectionModel().select(newV.getLecture());
                        lecTable.scrollTo(newV.getLecture());
                    }
                }
                if (incTut && newV.getTutorial() != null) {
                    TableView<ComponentOption> tutTable = tableMap.get('T');
                    if (tutTable != null) {
                        tutTable.getSelectionModel().select(newV.getTutorial());
                        tutTable.scrollTo(newV.getTutorial());
                    }
                }
                if (incPrac && newV.getPractical() != null) {
                    TableView<ComponentOption> pracTable = tableMap.get('P');
                    if (pracTable != null) {
                        pracTable.getSelectionModel().select(newV.getPractical());
                        pracTable.scrollTo(newV.getPractical());
                    }
                }
            }
            updateSummary.run();
        });

        for (TableView<ComponentOption> tv : tableMap.values()) {
            tv.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> updateSummary.run());
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == btnAdd) {
            List<ComponentOption> chosen = new ArrayList<>();
            for (TableView<ComponentOption> tv : tableMap.values()) {
                ComponentOption sel = tv.getSelectionModel().getSelectedItem();
                if (sel != null) chosen.add(sel);
            }
            applyChosenImprovementComponentsWithConflictHandling(code, chosen);
        }
    }

    private Tab createTabForComponent(
            char compChar, String compName, List<ComponentOption> items,
            Map<Character, TableView<ComponentOption>> tableMap, Runnable[] clearAll) {

        TableView<ComponentOption> table = createComponentTableView(items);
        tableMap.put(compChar, table);

        Button btnClear = new Button("Clear selection");

        btnClear.setOnAction(e -> {
            if (clearAll[0] != null) {
                clearAll[0].run();
            } else {
                table.getSelectionModel().clearSelection();
            }
        });

        HBox topBar = new HBox(btnClear);
        topBar.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(6, topBar, table);
        content.setPadding(new Insets(8));
        VBox.setVgrow(table, Priority.ALWAYS);

        Tab tab = new Tab(compName + " (" + items.size() + ")", content);
        return tab;
    }

    private TableView<ComponentOption> createComponentTableView(List<ComponentOption> items) {
        TableView<ComponentOption> table = new TableView<>(FXCollections.observableArrayList(items));
        table.setPrefHeight(280);
        table.setPrefWidth(820);
        table.getStyleClass().add("pick-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        TableColumn<ComponentOption, String> colSheet = new TableColumn<>("Sheet");
        colSheet.setPrefWidth(130);
        colSheet.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getSheetName()));

        TableColumn<ComponentOption, String> colBatch = new TableColumn<>("Batch");
        colBatch.setPrefWidth(80);
        colBatch.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getBatchName()));

        TableColumn<ComponentOption, String> colSlots = new TableColumn<>("Slots");
        colSlots.setPrefWidth(240);
        colSlots.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getSlotsSummary()));

        TableColumn<ComponentOption, String> colRoom = new TableColumn<>("Room");
        colRoom.setPrefWidth(80);
        colRoom.setCellValueFactory(f -> new SimpleStringProperty(roomsOf(f.getValue())));

        TableColumn<ComponentOption, Integer> colConflicts = new TableColumn<>("Conflicts");
        colConflicts.setPrefWidth(70);
        colConflicts.setCellValueFactory(f -> new javafx.beans.property.SimpleObjectProperty<>(f.getValue().getConflictCount()));
        colConflicts.getStyleClass().add("cell-center");

        TableColumn<ComponentOption, String> colStatus = new TableColumn<>("Status");
        colStatus.setPrefWidth(220);
        colStatus.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getStatus()));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    ComponentOption opt = getTableRow().getItem();
                    if (opt != null && opt.isConflicts()) {
                        Tooltip tooltip = new Tooltip(item);
                        tooltip.setWrapText(true);
                        tooltip.setMaxWidth(400);
                        setTooltip(tooltip);
                    } else {
                        setTooltip(null);
                    }
                }
            }
        });

        table.getColumns().addAll(colSheet, colBatch, colSlots, colRoom, colConflicts, colStatus);

        // row-picked mirrors the selection so the chosen batch stays obvious even when the
        // table itself has lost focus (another tab, or a click in the summary).
        table.setRowFactory(tv -> new TableRow<>() {
            {
                selectedProperty().addListener((obs, oldV, isSel) -> {
                    applyRowStyle(this, getItem(), isEmpty(), isSel);
                    togglePicked(this, isSel);
                });
            }
            @Override
            protected void updateItem(ComponentOption item, boolean empty) {
                super.updateItem(item, empty);
                applyRowStyle(this, item, empty, isSelected());
                togglePicked(this, isSelected());
            }
        });

        return table;
    }

    private TableView<BestBatchOption> createBestBatchTableView(List<BestBatchOption> items) {
        TableView<BestBatchOption> table = new TableView<>(FXCollections.observableArrayList(items));
        table.setPrefHeight(280);
        table.getStyleClass().add("pick-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        TableColumn<BestBatchOption, Integer> colRank = new TableColumn<>("Rank");
        colRank.setPrefWidth(50);
        colRank.setCellValueFactory(f -> new javafx.beans.property.SimpleObjectProperty<>(
                table.getItems().indexOf(f.getValue()) + 1));
        colRank.getStyleClass().add("cell-center");

        TableColumn<BestBatchOption, String> colSheet = new TableColumn<>("Sheet");
        colSheet.setPrefWidth(120);
        colSheet.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getSheetName()));

        TableColumn<BestBatchOption, String> colBatch = new TableColumn<>("Batch");
        colBatch.setPrefWidth(70);
        colBatch.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getBatchName()));

        TableColumn<BestBatchOption, String> colLecStatus = new TableColumn<>("Lecture");
        colLecStatus.setPrefWidth(100);
        colLecStatus.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getLectureStatus()));

        TableColumn<BestBatchOption, String> colTutStatus = new TableColumn<>("Tutorial");
        colTutStatus.setPrefWidth(100);
        colTutStatus.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getTutorialStatus()));

        TableColumn<BestBatchOption, String> colPracStatus = new TableColumn<>("Practical");
        colPracStatus.setPrefWidth(100);
        colPracStatus.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getPracticalStatus()));

        TableColumn<BestBatchOption, Integer> colLecConf = new TableColumn<>("Lec Conf");
        colLecConf.setPrefWidth(65);
        colLecConf.setCellValueFactory(f -> new javafx.beans.property.SimpleObjectProperty<>(f.getValue().getLectureConflicts()));
        colLecConf.getStyleClass().add("cell-center");

        TableColumn<BestBatchOption, Integer> colTutConf = new TableColumn<>("Tut Conf");
        colTutConf.setPrefWidth(65);
        colTutConf.setCellValueFactory(f -> new javafx.beans.property.SimpleObjectProperty<>(f.getValue().getTutorialConflicts()));
        colTutConf.getStyleClass().add("cell-center");

        TableColumn<BestBatchOption, Integer> colPracConf = new TableColumn<>("Prac Conf");
        colPracConf.setPrefWidth(70);
        colPracConf.setCellValueFactory(f -> new javafx.beans.property.SimpleObjectProperty<>(f.getValue().getPracticalConflicts()));
        colPracConf.getStyleClass().add("cell-center");

        TableColumn<BestBatchOption, String> colRooms = new TableColumn<>("Rooms");
        colRooms.setPrefWidth(110);
        colRooms.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getRooms()));

        TableColumn<BestBatchOption, Integer> colTotal = new TableColumn<>("Total");
        colTotal.setPrefWidth(55);
        colTotal.setCellValueFactory(f -> new javafx.beans.property.SimpleObjectProperty<>(f.getValue().getTotalConflicts()));
        colTotal.getStyleClass().add("cell-center");

        table.getColumns().addAll(colRank, colSheet, colBatch, colLecStatus, colTutStatus, colPracStatus,
                colLecConf, colTutConf, colPracConf, colRooms, colTotal);

        table.setRowFactory(tv -> new TableRow<>() {
            {
                selectedProperty().addListener((obs, oldV, isSel) -> {
                    if (isSel) {
                        if (!getStyleClass().contains("row-selected")) getStyleClass().add("row-selected");
                    } else {
                        getStyleClass().remove("row-selected");
                    }
                    togglePicked(this, isSel);
                });
            }
            @Override
            protected void updateItem(BestBatchOption item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-row-deficit", "status-row-tight", "status-row-fits", "row-selected");
                if (isSelected()) {
                    getStyleClass().add("row-selected");
                }
                togglePicked(this, isSelected());
                if (empty || item == null) {
                    return;
                }
                if (item.getPracticalConflicts() > 0) {
                    getStyleClass().add("status-row-deficit");
                } else if (item.getTotalConflicts() > 0) {
                    getStyleClass().add("status-row-tight");
                } else {
                    getStyleClass().add("status-row-fits");
                }
            }
        });

        return table;
    }

    /** Adds or removes the strong pick highlight; kept separate so both row types share it. */
    private static void togglePicked(TableRow<?> row, boolean picked) {
        if (picked) {
            if (!row.getStyleClass().contains("row-picked")) {
                row.getStyleClass().add("row-picked");
            }
        } else {
            row.getStyleClass().remove("row-picked");
        }
    }

    private void applyRowStyle(TableRow<ComponentOption> row, ComponentOption item, boolean empty, boolean isSel) {
        row.getStyleClass().removeAll(
                "status-row-deficit", "status-row-tight", "status-row-fits",
                "status-row-deficit-selected", "status-row-tight-selected", "status-row-fits-selected",
                "row-selected");
        if (empty || item == null) {
            row.setDisable(false);
            row.setTooltip(null);
        } else if (item.isConflicts()) {
            row.setDisable(false);  // Allow selection even with conflicts
            row.setTooltip(new Tooltip(item.getStatus()));
            if (isSel) {
                row.getStyleClass().addAll("status-row-deficit-selected", "row-selected");
            } else {
                row.getStyleClass().add("status-row-deficit");
            }
        } else if (item.isNeedsAdjustment()) {
            row.setDisable(false);
            row.setTooltip(new Tooltip(item.getStatus()));
            if (isSel) {
                row.getStyleClass().addAll("status-row-tight-selected", "row-selected");
            } else {
                row.getStyleClass().add("status-row-tight");
            }
        } else { // fits
            row.setDisable(false);
            row.setTooltip(new Tooltip("Fits your timetable"));
            if (isSel) {
                row.getStyleClass().addAll("status-row-fits-selected", "row-selected");
            } else {
                row.getStyleClass().add("status-row-fits");
            }
        }
    }

    private void applyChosenImprovementComponents(String code, List<ComponentOption> chosenList) {
        int totalAdjusted = 0;
        List<String> addedSummaries = new ArrayList<>();
        List<String> skippedSummaries = new ArrayList<>();

        for (ComponentOption comp : chosenList) {
            String baseName = subjectCodeRenames.getOrDefault(code, code);
            String compTypeWord = comp.getComponentType() == 'L' ? "lecture" : (comp.getComponentType() == 'T' ? "tutorial" : "practical");
            String compLabel = baseName + " " + compTypeWord;
            String actType = comp.getComponentType() == 'L' ? "CLASS" : (comp.getComponentType() == 'T' ? "TUTORIAL" : "LAB");

            if (comp.isNeedsAdjustment()) {
                int adjCount = confirmAndApplyComponentOptionAdjustment(comp, compLabel, actType);
                if (adjCount < 0) {
                    skippedSummaries.add(compLabel + " (" + comp.getBatchName() + ", user cancelled adjustment)");
                } else {
                    totalAdjusted += adjCount;
                    addedSummaries.add(compLabel + " (" + comp.getBatchName() + ")");
                }
            } else {
                int addedSlots = 0;
                for (ImprovementSlot s : comp.getSlots()) {
                    WeeklyTimetableEntry entry = s.toEntry(subjectCodeRenames);
                    boolean isDup = entriesList.stream().anyMatch(e -> isIdenticalEntry(e, entry));
                    if (!isDup) {
                        entriesList.add(entry);
                        addedSlots++;
                    }
                }
                if (addedSlots > 0) {
                    addedSummaries.add(compLabel + " (" + comp.getBatchName() + ")");
                }
            }
        }

        updateTabIndicators();
        updateFreeHoursReview();

        StringBuilder sb = new StringBuilder();
        if (!addedSummaries.isEmpty()) {
            sb.append("Added: ").append(String.join(", ", addedSummaries));
        } else {
            sb.append("0 components added.");
        }
        if (totalAdjusted > 0) {
            sb.append("\nAdjusted ").append(totalAdjusted).append(totalAdjusted == 1 ? " entry" : " entries").append(".");
        }
        if (!skippedSummaries.isEmpty()) {
            sb.append("\nSkipped: ").append(String.join(", ", skippedSummaries)).append(".");
        }
        showInfo("Improvement Subject Added", sb.toString());
    }

    private int confirmAndApplyComponentOptionAdjustment(ComponentOption comp, String compLabel, String actType) {
        StringBuilder msg = new StringBuilder("Adding " + comp.getComponentName() + " from batch " + comp.getBatchName() + " overlaps with:\n");

        Map<WeeklyTimetableEntry, List<WeeklyTimetableEntry>> adjustmentPlan = new LinkedHashMap<>();

        for (ImprovementSlot slot : comp.getSlots()) {
            LocalTime eStart = slot.getStartTime();
            LocalTime eEnd = slot.getEndTime();

            List<WeeklyTimetableEntry> softs = slot.getSoftConflicts();
            for (WeeklyTimetableEntry o : softs) {
                if (adjustmentPlan.containsKey(o)) continue;

                LocalTime oStart = o.getStartTime();
                LocalTime oEnd = o.getEndTime();
                List<WeeklyTimetableEntry> replacements = new ArrayList<>();

                boolean oStartsBeforeE = oStart.isBefore(eStart);
                boolean oEndsAfterE = oEnd.isAfter(eEnd);

                if (oStartsBeforeE && oEndsAfterE) {
                    WeeklyTimetableEntry left = o.withTimes(o.getDayOfWeek(), oStart, eStart);
                    WeeklyTimetableEntry right = o.withTimes(o.getDayOfWeek(), eEnd, oEnd);
                    if (java.time.Duration.between(oStart, eStart).toMinutes() >= 1) replacements.add(left);
                    if (java.time.Duration.between(eEnd, oEnd).toMinutes() >= 1) replacements.add(right);
                } else if (!oEndsAfterE && oStartsBeforeE) {
                    if (java.time.Duration.between(oStart, eStart).toMinutes() >= 1) {
                        replacements.add(o.withTimes(o.getDayOfWeek(), oStart, eStart));
                    }
                } else if (!oStartsBeforeE && oEndsAfterE) {
                    if (java.time.Duration.between(eEnd, oEnd).toMinutes() >= 1) {
                        replacements.add(o.withTimes(o.getDayOfWeek(), eEnd, oEnd));
                    }
                }

                adjustmentPlan.put(o, replacements);

                msg.append("  \u2022 ").append(formatEntryLabel(o)).append(" \u2192 ");
                if (replacements.isEmpty()) {
                    msg.append("will be removed (fully covered)");
                } else if (replacements.size() == 1) {
                    WeeklyTimetableEntry r = replacements.get(0);
                    msg.append("will become ").append(r.getStartTime()).append("-").append(r.getEndTime());
                } else {
                    WeeklyTimetableEntry r1 = replacements.get(0);
                    WeeklyTimetableEntry r2 = replacements.get(1);
                    msg.append("will be split into ").append(r1.getStartTime()).append("-").append(r1.getEndTime())
                       .append(" and ").append(r2.getStartTime()).append("-").append(r2.getEndTime());
                }
                msg.append("\n");
            }
        }

        msg.append("\nApply the edit and adjust these entries?");

        ButtonType btnYes = new ButtonType("Yes, adjust", ButtonBar.ButtonData.YES);
        ButtonType btnNo = new ButtonType("No, leave as is", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        if (wizardStage != null) confirm.initOwner(wizardStage);
        UiKit.styleDialog(confirm.getDialogPane());
        confirm.setTitle("Overlapping Personal Commitments");
        confirm.setHeaderText("Soft conflict detected (MEAL / OTHER)");
        confirm.setContentText(msg.toString());
        confirm.getButtonTypes().setAll(btnYes, btnNo);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != btnYes) {
            return -1;
        }

        List<WeeklyTimetableEntry> newList = new ArrayList<>(entriesList);
        for (Map.Entry<WeeklyTimetableEntry, List<WeeklyTimetableEntry>> ap : adjustmentPlan.entrySet()) {
            newList.remove(ap.getKey());
            newList.addAll(ap.getValue());
        }
        for (ImprovementSlot slot : comp.getSlots()) {
            WeeklyTimetableEntry entry = slot.toEntry(subjectCodeRenames);
            boolean isDup = newList.stream().anyMatch(e -> isIdenticalEntry(e, entry));
            if (!isDup) {
                newList.add(entry);
            }
        }
        entriesList.setAll(newList);
        persistEntriesAndReplan();
        triggerUiRefresh();
        return adjustmentPlan.size();
    }



    public static class RenameItem {
        private boolean selected;
        private String code;  // Changed from final to allow updates for editable codes
        private final String nameInTimetable;
        private String newName;
        private final boolean codeEditable;

        public RenameItem(String code, String nameInTimetable, String newName, boolean codeEditable) {
            this.selected = true;
            this.code = code;
            this.nameInTimetable = nameInTimetable;
            this.newName = newName;
            this.codeEditable = codeEditable;
        }

        public boolean isSelected() { return selected; }
        public void setSelected(boolean val) { selected = val; }
        public String getCode() { return code; }
        public void setCode(String val) { code = val; }  // Added setter for editable codes
        public String getNameInTimetable() { return nameInTimetable; }
        public String getNewName() { return newName; }
        public void setNewName(String val) { newName = val; }
        public boolean isCodeEditable() { return codeEditable; }
    }

    private void applyChosenImprovementComponentsWithConflictHandling(String code, List<ComponentOption> chosenList) {
        // Use AddPlan service to determine what can be added and what will be skipped
        AddPlan plan = ImprovementService.planAdd(chosenList);
        
        if (plan.isEmpty()) {
            showInfo("Nothing to Add", "All selected slots conflict with existing classes/labs/tutorials.");
            return;
        }

        // Build confirmation message
        StringBuilder msg = new StringBuilder();
        if (!plan.getToAdd().isEmpty()) {
            msg.append("Will add (").append(plan.getToAdd().size()).append(" slots):\n");
            for (PlannedSlot ps : plan.getToAdd()) {
                ImprovementSlot slot = ps.getSlot();
                msg.append("  - ").append(slot.getFormattedLabel(subjectCodeRenames))
                   .append(" ").append(ImprovementService.shortDay(slot.getDay()))
                   .append(" ").append(slot.getStartTime()).append("-").append(slot.getEndTime());
                if (ps.isNeedsAdjustment()) {
                    msg.append(" (will adjust MEAL/OTHER)");
                }
                msg.append("\n");
            }
        }
        
        if (!plan.getSkipped().isEmpty()) {
            msg.append("\nWill skip (").append(plan.getSkipped().size()).append(" conflicting slots):\n");
            for (SkippedSlot ss : plan.getSkipped()) {
                ImprovementSlot slot = ss.getSlot();
                msg.append("  - ").append(slot.getFormattedLabel(subjectCodeRenames))
                   .append(" ").append(ImprovementService.shortDay(slot.getDay()))
                   .append(" ").append(slot.getStartTime()).append("-").append(slot.getEndTime())
                   .append(" (").append(ss.getReason()).append(")\n");
            }
        }

        msg.append("\nProceed?");

        ButtonType btnYes = new ButtonType("Yes, Add", ButtonBar.ButtonData.YES);
        ButtonType btnNo = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg.toString(), btnYes, btnNo);
        if (wizardStage != null) confirm.initOwner(wizardStage);
        UiKit.styleDialog(confirm.getDialogPane());
        confirm.setHeaderText("Confirm Add with Conflicts");
        Optional<ButtonType> res = confirm.showAndWait();
        
        if (res.isEmpty() || res.get() != btnYes) {
            return;
        }

        // Apply the plan
        int adjustedCount = 0;
        for (PlannedSlot ps : plan.getToAdd()) {
            ImprovementSlot slot = ps.getSlot();
            if (ps.isNeedsAdjustment()) {
                // Handle MEAL/OTHER adjustments
                for (WeeklyTimetableEntry soft : slot.getSoftConflicts()) {
                    LocalTime eStart = slot.getStartTime();
                    LocalTime eEnd = slot.getEndTime();
                    LocalTime oStart = soft.getStartTime();
                    LocalTime oEnd = soft.getEndTime();
                    
                    boolean oStartsBeforeE = oStart.isBefore(eStart);
                    boolean oEndsAfterE = oEnd.isAfter(eEnd);
                    
                    if (oStartsBeforeE && oEndsAfterE) {
                        // Split
                        WeeklyTimetableEntry left = soft.withTimes(soft.getDayOfWeek(), oStart, eStart);
                        WeeklyTimetableEntry right = soft.withTimes(soft.getDayOfWeek(), eEnd, oEnd);
                        entriesList.remove(soft);
                        if (java.time.Duration.between(oStart, eStart).toMinutes() >= 1) entriesList.add(left);
                        if (java.time.Duration.between(eEnd, oEnd).toMinutes() >= 1) entriesList.add(right);
                        adjustedCount++;
                    } else if (!oEndsAfterE && oStartsBeforeE) {
                        // Trim end
                        if (java.time.Duration.between(oStart, eStart).toMinutes() >= 1) {
                            soft.setEndTime(eStart);
                        } else {
                            entriesList.remove(soft);
                        }
                        adjustedCount++;
                    } else if (!oStartsBeforeE && oEndsAfterE) {
                        // Trim start
                        if (java.time.Duration.between(eEnd, oEnd).toMinutes() >= 1) {
                            soft.setStartTime(eEnd);
                        } else {
                            entriesList.remove(soft);
                        }
                        adjustedCount++;
                    } else {
                        // Fully covered - remove
                        entriesList.remove(soft);
                        adjustedCount++;
                    }
                }
            }
            
            // Add the slot
            WeeklyTimetableEntry entry = slot.toEntry(subjectCodeRenames);
            boolean isDup = entriesList.stream().anyMatch(e -> isIdenticalEntry(e, entry));
            if (!isDup) {
                entriesList.add(entry);
            }
        }

        updateTabIndicators();
        updateFreeHoursReview();
        persistEntriesAndReplan();

        StringBuilder resultMsg = new StringBuilder();
        resultMsg.append("Added ").append(plan.getToAdd().size()).append(" slots");
        if (!plan.getSkipped().isEmpty()) {
            resultMsg.append(", skipped ").append(plan.getSkipped().size()).append(" conflicting slots");
        }
        if (adjustedCount > 0) {
            resultMsg.append(", adjusted ").append(adjustedCount).append(" MEAL/OTHER entries");
        }
        resultMsg.append(".");
        showInfo("Improvement Subject Added", resultMsg.toString());
        triggerUiRefresh();
    }

    private void handleRenameSubjects() {
        try {
            handleRenameSubjectsImpl();
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Rename Failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * Determines the subject code for an entry, in order of precedence:
     * 1. entry.getSubjectCode() if not null
     * 2. label base if it looks like a code (pattern: ^[A-Za-z]{2,5}\d{2,4}[A-Za-z]?$)
     * 3. nameToSubjectCode lookup
     * 4. null (unknown)
     */
    static String determineSubjectCode(WeeklyTimetableEntry entry, Map<String, String> nameToSubjectCode) {
        if (entry.getSubjectCode() != null) {
            return entry.getSubjectCode();
        }
        
        if (entry.getLabel() != null) {
            Pattern labelPattern = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
            Matcher m = labelPattern.matcher(entry.getLabel().trim());
            if (m.matches()) {
                String base = m.group(1).trim();
                Pattern codePattern = Pattern.compile("^[A-Za-z]{2,5}\\d{2,4}[A-Za-z]?$");
                if (codePattern.matcher(base).matches()) {
                    return base.toUpperCase();
                }
                if (nameToSubjectCode != null && nameToSubjectCode.containsKey(base)) {
                    return nameToSubjectCode.get(base);
                }
            }
        }
        
        return null;
    }

    /**
     * Applies a rename to all matching entries. Matches by subjectCode first,
     * then falls back to label base (case-insensitive, normalized whitespace).
     * 
     * @param entries List of entries to process (mutated in place)
     * @param subjectCode The subject code being renamed
     * @param newName The new display name (empty or equal to code means reset)
     * @param nameToSubjectCode Map for looking up codes from old names (should contain current state BEFORE this rename)
     * @return Number of entries relabelled
     */
    static int applyRename(List<WeeklyTimetableEntry> entries, String subjectCode, String newName, 
                          Map<String, String> nameToSubjectCode) {
        Pattern labelPattern = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
        String normalizedCode = subjectCode.toUpperCase().trim();
        String normalizedNewName = (newName == null || newName.trim().isEmpty()) ? "" : newName.trim();
        boolean isReset = normalizedNewName.isEmpty() || normalizedNewName.equalsIgnoreCase(normalizedCode);
        String displayName = isReset ? normalizedCode : normalizedNewName;
        
        // Build set of all names that currently represent this subject (for matching)
        Set<String> currentNamesForSubject = new HashSet<>();
        currentNamesForSubject.add(normalizedCode); // Always includes the code itself
        
        // Add any current custom names from the map
        for (Map.Entry<String, String> e : nameToSubjectCode.entrySet()) {
            if (normalizedCode.equalsIgnoreCase(e.getValue())) {
                currentNamesForSubject.add(e.getKey().toUpperCase().trim());
            }
        }
        
        int count = 0;
        for (WeeklyTimetableEntry entry : entries) {
            boolean matches = false;
            String suffix = null;
            
            // Match by subjectCode first
            if (entry.getSubjectCode() != null && normalizedCode.equalsIgnoreCase(entry.getSubjectCode().trim())) {
                matches = true;
                // Still need to extract suffix from label if present
                if (entry.getLabel() != null) {
                    // Normalize whitespace: replace all whitespace characters (including non-breaking space U+00A0) with regular space
                    String normalizedLabel = entry.getLabel().trim().replaceAll("[\\s\\u00A0\\u2007\\u202F]+", " ");
                    Matcher m = labelPattern.matcher(normalizedLabel);
                    if (m.matches()) {
                        suffix = m.group(2).toLowerCase();
                    }
                }
            }
            
            // Fall back to label base match
            if (!matches && entry.getLabel() != null) {
                // Normalize whitespace: replace all whitespace characters (including non-breaking space U+00A0) with regular space
                String normalizedLabel = entry.getLabel().trim().replaceAll("[\\s\\u00A0\\u2007\\u202F]+", " ");
                Matcher m = labelPattern.matcher(normalizedLabel);
                if (m.matches()) {
                    String base = m.group(1).trim();
                    if (currentNamesForSubject.contains(base.toUpperCase())) {
                        matches = true;
                        suffix = m.group(2).toLowerCase();
                    }
                }
            }
            
            if (matches && suffix != null) {
                // Set subjectCode if it was null
                if (entry.getSubjectCode() == null) {
                    entry.setSubjectCode(normalizedCode);
                }
                
                // Update label
                entry.setLabel(displayName + " " + suffix);
                count++;
            }
        }
        
        return count;
    }

    private void handleRenameSubjectsImpl() throws SQLException {
        Pattern labelPattern = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
        
        // Collect all distinct subjects with their codes
        Map<String, String> subjectToName = new LinkedHashMap<>(); // code -> name in timetable
        Set<String> seenCodes = new LinkedHashSet<>();
        
        for (WeeklyTimetableEntry e : entriesList) {
            String code = determineSubjectCode(e, nameToSubjectCode);
            if (code != null && !seenCodes.contains(code)) {
                seenCodes.add(code);
                // Determine name in timetable
                String name = code;
                if (e.getLabel() != null) {
                    Matcher m = labelPattern.matcher(e.getLabel().trim());
                    if (m.matches()) {
                        name = m.group(1).trim();
                    }
                }
                subjectToName.put(code, name);
            }
        }

        if (subjectToName.isEmpty()) {
            showError("No Subjects", "No subject entries matching '<base> lecture|tutorial|practical' found in the timetable.");
            return;
        }

        // Union with kept subjects
        Semester active = semesterDAO.getActiveSemester();
        if (active != null && active.getSemesterId() != null) {
            Map<String, String> keptMap = KeptSubjectDAO.findAllAsMap(active.getSemesterId());
            for (Map.Entry<String, String> entry : keptMap.entrySet()) {
                String code = entry.getKey();
                String displayName = entry.getValue();
                // Only add if not already in timetable
                if (!seenCodes.contains(code)) {
                    subjectToName.put(code, displayName + " (not in timetable)");
                }
            }
        }

        // Build items list
        List<RenameItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : subjectToName.entrySet()) {
            String code = entry.getKey();
            String nameInTimetable = entry.getValue();
            String currentName = subjectCodeRenames.getOrDefault(code, nameInTimetable);
            boolean codeEditable = (code.equals(nameInTimetable) && !code.matches("^[A-Za-z]{2,5}\\d{2,4}[A-Za-z]?$"));
            items.add(new RenameItem(code, nameInTimetable, currentName, codeEditable));
        }

        // Build dialog with GridPane
        Dialog<ButtonType> dialog = new Dialog<>();
        UiKit.styleDialog(dialog);
        if (wizardStage != null) dialog.initOwner(wizardStage);
        dialog.setTitle("Rename Subjects");
        dialog.setHeaderText("Rename subject codes or names across all weekly commitments.");

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));

        HBox topBtns = new HBox(10);
        Button btnSelAll = new Button("Select All");
        Button btnDeselAll = new Button("Select None");
        topBtns.getChildren().addAll(btnSelAll, btnDeselAll);

        // Header row
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(4);
        
        Label hdrRename = new Label("Rename");
        hdrRename.getStyleClass().add("table-header-cell");
        hdrRename.setMinWidth(60);
        
        Label hdrCode = new Label("Code");
        hdrCode.getStyleClass().add("table-header-cell");
        hdrCode.setMinWidth(80);
        
        Label hdrNameIn = new Label("Name in timetable");
        hdrNameIn.getStyleClass().add("table-header-cell");
        hdrNameIn.setMinWidth(200);
        
        Label hdrNewName = new Label("New name");
        hdrNewName.getStyleClass().add("table-header-cell");
        hdrNewName.setMinWidth(200);
        
        grid.add(hdrRename, 0, 0);
        grid.add(hdrCode, 1, 0);
        grid.add(hdrNameIn, 2, 0);
        grid.add(hdrNewName, 3, 0);

        // Data rows
        List<CheckBox> checkBoxes = new ArrayList<>();
        int row = 1;
        for (RenameItem item : items) {
            CheckBox cb = new CheckBox();
            cb.setSelected(item.isSelected());
            cb.selectedProperty().addListener((obs, o, n) -> item.setSelected(n));
            checkBoxes.add(cb);
            grid.add(cb, 0, row);

            if (item.isCodeEditable()) {
                TextField codeField = new TextField(item.getCode());
                codeField.setPromptText("Enter code");
                codeField.setMinWidth(80);
                codeField.textProperty().addListener((obs, o, n) -> item.setCode(n));
                grid.add(codeField, 1, row);
            } else {
                Label codeLabel = new Label(item.getCode());
                codeLabel.setMinWidth(80);
                grid.add(codeLabel, 1, row);
            }

            Label nameLabel = new Label(item.getNameInTimetable());
            nameLabel.setMinWidth(200);
            nameLabel.setMaxWidth(200);
            nameLabel.getStyleClass().add("ellipsis-label");
            Tooltip.install(nameLabel, new Tooltip(item.getNameInTimetable()));
            grid.add(nameLabel, 2, row);

            TextField newNameField = new TextField(item.getNewName());
            newNameField.setMinWidth(200);
            newNameField.textProperty().addListener((obs, o, n) -> item.setNewName(n));
            grid.add(newNameField, 3, row);

            row++;
        }

        btnSelAll.setOnAction(e -> {
            for (CheckBox cb : checkBoxes) cb.setSelected(true);
        });
        btnDeselAll.setOnAction(e -> {
            for (CheckBox cb : checkBoxes) cb.setSelected(false);
        });

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(400);
        scroll.setPrefWidth(650);

        content.getChildren().addAll(topBtns, scroll);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(780, 480);

        ButtonType btnApply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(btnApply, btnCancel);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == btnApply) {
            // Validate and apply
            Map<String, String> codeToNewName = new HashMap<>();
            for (RenameItem item : items) {
                if (!item.isSelected()) continue;
                
                String code = item.getCode();
                if (item.isCodeEditable() && (code == null || code.trim().isEmpty())) {
                    showError("Validation Error", "Subject '" + item.getNameInTimetable() + "' requires a code.");
                    return;
                }
                code = code.toUpperCase().trim();
                
                String newName = item.getNewName() != null ? item.getNewName().trim() : "";
                if (newName.length() > 60) {
                    newName = newName.substring(0, 60);
                }
                
                // Check for name clashes (same name for different code)
                if (!newName.isEmpty() && !newName.equalsIgnoreCase(code)) {
                    for (RenameItem other : items) {
                        if (other == item) continue;
                        String otherCode = other.getCode().toUpperCase().trim();
                        if (newName.equalsIgnoreCase(otherCode) || newName.equalsIgnoreCase(other.getNewName())) {
                            showError("Name Clash", "The name '" + newName + "' conflicts with another subject.");
                            return;
                        }
                    }
                }
                
                codeToNewName.put(code, newName);
            }

            // Apply renames
            Map<String, Integer> renameResults = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : codeToNewName.entrySet()) {
                String code = entry.getKey();
                String newName = entry.getValue();
                
                // Apply the rename to entries BEFORE updating maps
                int count = applyRename(entriesList, code, newName, nameToSubjectCode);
                renameResults.put(code, count);
                
                // Now update maps for future operations
                if (newName.isEmpty() || newName.equalsIgnoreCase(code)) {
                    // Reset: remove rename
                    subjectCodeRenames.remove(code);
                    // Remove old name mappings
                    nameToSubjectCode.entrySet().removeIf(e -> code.equalsIgnoreCase(e.getValue()) && !code.equalsIgnoreCase(e.getKey()));
                } else {
                    subjectCodeRenames.put(code, newName);
                    // Remove old name mappings for this code
                    nameToSubjectCode.entrySet().removeIf(e -> code.equalsIgnoreCase(e.getValue()) && !code.equalsIgnoreCase(e.getKey()));
                    // Add new mapping
                    nameToSubjectCode.put(newName, code);
                }
                nameToSubjectCode.put(code, code); // code-to-code
            }

            // Update kept_subjects.display_name for kept subjects that were renamed.
            // In tab mode this is staged and written by Save.
            if (active != null && active.getSemesterId() != null) {
                int semId = active.getSemesterId();
                for (Map.Entry<String, String> entry : codeToNewName.entrySet()) {
                    renameSubjectOnDisk(semId, entry.getKey(), entry.getValue());
                }
            }

            // Refresh UI
            updateTabIndicators();
            updateFreeHoursReview();
            refreshAllDayTables();

            // Report results
            int totalRenamed = renameResults.values().stream().mapToInt(Integer::intValue).sum();
            List<String> warnings = new ArrayList<>();
            for (Map.Entry<String, Integer> resultEntry : renameResults.entrySet()) {
                if (resultEntry.getValue() == 0) {
                    warnings.add(resultEntry.getKey());
                }
            }

            if (!warnings.isEmpty()) {
                String warnMsg = "Renamed in settings but found no timetable entries to relabel:\n" + String.join(", ", warnings);
                showError("Partial Rename", warnMsg);
            } else if (totalRenamed > 0) {
                UiKit.toast("Renamed " + totalRenamed + " matching timetable entries.");
            }
            triggerUiRefresh();
        }
    }

    /**
     * Refreshes all day table views to reflect label changes.
     */
    private void refreshAllDayTables() {
        for (Tab tab : dayTabs.values()) {
            if (tab.getContent() instanceof VBox) {
                VBox vbox = (VBox) tab.getContent();
                for (Node node : vbox.getChildren()) {
                    if (node instanceof TableView) {
                        ((TableView<?>) node).refresh();
                    }
                }
            }
        }
    }

    private void handleRemoveSubject() {
        try {
            handleRemoveSubjectImpl();
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Remove Failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void handleEntryDeletion(List<WeeklyTimetableEntry> toDelete) {
        try {
            handleEntryDeletionImpl(toDelete);
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Deletion Failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void handleEntryDeletionImpl(List<WeeklyTimetableEntry> toDelete) throws Exception {
        // Extract subject codes from entries being deleted
        Pattern p = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
        Set<String> deletedSubjects = new HashSet<>();
        
        for (WeeklyTimetableEntry e : toDelete) {
            if (e.getLabel() != null) {
                Matcher m = p.matcher(e.getLabel().trim());
                if (m.matches()) {
                    String base = m.group(1).trim();
                    String code = nameToSubjectCode.getOrDefault(base, base);
                    deletedSubjects.add(code);
                }
            }
        }

        // Check which subjects will have zero entries left after deletion
        Map<String, String> lastSubjects = new LinkedHashMap<>(); // code -> displayName
        
        for (String subjectCode : deletedSubjects) {
            int remainingCount = 0;
            String displayName = null;
            
            for (WeeklyTimetableEntry e : entriesList) {
                if (toDelete.contains(e)) continue; // Skip entries being deleted
                
                if (e.getLabel() != null) {
                    Matcher m = p.matcher(e.getLabel().trim());
                    if (m.matches()) {
                        String base = m.group(1).trim();
                        String code = nameToSubjectCode.getOrDefault(base, base);
                        if (code.equals(subjectCode)) {
                            remainingCount++;
                            if (displayName == null) displayName = base;
                        }
                    }
                }
            }
            
            if (remainingCount == 0) {
                // Find display name from the deleted entries
                if (displayName == null) {
                    for (WeeklyTimetableEntry e : toDelete) {
                        if (e.getLabel() != null) {
                            Matcher m = p.matcher(e.getLabel().trim());
                            if (m.matches()) {
                                String base = m.group(1).trim();
                                String code = nameToSubjectCode.getOrDefault(base, base);
                                if (code.equals(subjectCode)) {
                                    displayName = base;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (displayName != null) {
                    lastSubjects.put(subjectCode, displayName);
                }
            }
        }

        // Ask keep question for subjects that will have zero entries
        Map<String, Boolean> keepDecisions = new HashMap<>();
        for (Map.Entry<String, String> entry : lastSubjects.entrySet()) {
            String code = entry.getKey();
            String displayName = entry.getValue();
            
            Alert keepDialog = new Alert(Alert.AlertType.CONFIRMATION);
            if (wizardStage != null) keepDialog.initOwner(wizardStage);
            UiKit.styleDialog(keepDialog.getDialogPane());
            keepDialog.setTitle("Last Entry for Subject");
            keepDialog.setHeaderText("Subject: " + displayName);
            keepDialog.setContentText("You're deleting the last timetable entry for this subject. Keep it for exam/study tracking?");
            
            ButtonType btnKeep = new ButtonType("Keep for Exams/Studies", ButtonBar.ButtonData.YES);
            ButtonType btnRemove = new ButtonType("Remove Completely", ButtonBar.ButtonData.NO);
            ButtonType btnCancel = new ButtonType("Cancel Deletion", ButtonBar.ButtonData.CANCEL_CLOSE);
            keepDialog.getButtonTypes().setAll(btnKeep, btnRemove, btnCancel);

            Optional<ButtonType> choice = keepDialog.showAndWait();
            if (!choice.isPresent() || choice.get() == btnCancel) {
                return; // User cancelled the entire deletion
            }
            
            keepDecisions.put(code, choice.get() == btnKeep);
        }

        // Perform deletion
        entriesList.removeAll(toDelete);
        
        // Update kept_subjects registry / cleanup
        for (Map.Entry<String, Boolean> decision : keepDecisions.entrySet()) {
            String code = decision.getKey();
            String displayName = lastSubjects.get(code);
            if (decision.getValue()) {
                rememberKeptSubject(code, displayName);
            } else {
                removeSubjects(Collections.singleton(code));
            }
        }
        persistEntriesAndReplan();
        triggerUiRefresh();
    }

    private void handleRemoveSubjectImpl() throws Exception {
        // Extract distinct subject codes from timetable
        Pattern p = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
        Map<String, String> subjectToDisplayName = new LinkedHashMap<>();
        Set<String> inTimetable = new HashSet<>();
        
        for (WeeklyTimetableEntry e : entriesList) {
            if (e.getLabel() != null) {
                Matcher m = p.matcher(e.getLabel().trim());
                if (m.matches()) {
                    String base = m.group(1).trim();
                    String origCode = nameToSubjectCode.getOrDefault(base, base);
                    subjectToDisplayName.putIfAbsent(origCode, base);
                    inTimetable.add(origCode);
                }
            }
        }

        // Union with kept subjects
        Semester active = semesterDAO.getActiveSemester();
        if (active != null && active.getSemesterId() != null) {
            Map<String, String> keptMap = KeptSubjectDAO.findAllAsMap(active.getSemesterId());
            for (Map.Entry<String, String> entry : keptMap.entrySet()) {
                String code = entry.getKey();
                String displayName = entry.getValue();
                if (!inTimetable.contains(code)) {
                    subjectToDisplayName.put(code, displayName + " (not in timetable)");
                }
            }
        }

        if (subjectToDisplayName.isEmpty()) {
            showError("No Subjects", "No subject entries found.");
            return;
        }

        // Show selection dialog
        Dialog<String> dialog = new Dialog<>();
        UiKit.styleDialog(dialog);
        if (wizardStage != null) dialog.initOwner(wizardStage);
        dialog.setTitle("Remove Subject");
        dialog.setHeaderText("Select a subject to remove:");

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));

        ListView<String> listView = new ListView<>();
        listView.setPrefHeight(200);
        listView.setPrefWidth(400);
        
        for (Map.Entry<String, String> entry : subjectToDisplayName.entrySet()) {
            String code = entry.getKey();
            String displayName = entry.getValue();
            listView.getItems().add(displayName + " (" + code + ")");
        }

        content.getChildren().add(listView);
        dialog.getDialogPane().setContent(content);

        ButtonType btnRemove = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(btnRemove, btnCancel);

        dialog.setResultConverter(btn -> {
            if (btn == btnRemove && listView.getSelectionModel().getSelectedItem() != null) {
                return listView.getSelectionModel().getSelectedItem();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        if (result.isPresent() && result.get() != null) {
            String selected = result.get();
            // Extract code from "DisplayName (CODE)" format
            int openParen = selected.lastIndexOf('(');
            int closeParen = selected.lastIndexOf(')');
            if (openParen < 0 || closeParen < 0) return;
            
            String subjectCode = selected.substring(openParen + 1, closeParen).trim();
            String displayName = selected.substring(0, openParen).trim();
            
            // Check if subject is in timetable
            boolean isInTimetable = inTimetable.contains(subjectCode);

            if (!isInTimetable) {
                // Subject is kept-only, just ask if they want to remove it completely
                Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
                if (wizardStage != null) confirmDialog.initOwner(wizardStage);
                UiKit.styleDialog(confirmDialog.getDialogPane());
                confirmDialog.setTitle("Remove Kept Subject");
                confirmDialog.setHeaderText("Subject: " + displayName);
                confirmDialog.setContentText("This subject is not in the timetable. Remove it completely from the kept subjects?");
                
                ButtonType btnRemoveKept = new ButtonType("Remove Completely", ButtonBar.ButtonData.YES);
                ButtonType btnCancelKept = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                confirmDialog.getButtonTypes().setAll(btnRemoveKept, btnCancelKept);

                Optional<ButtonType> choice = confirmDialog.showAndWait();
                if (choice.isPresent() && choice.get() == btnRemoveKept) {
                    removeSubjects(Collections.singleton(subjectCode));
                    showInfo("Subject Removed", "Removed " + displayName + " from kept subjects.");
                    triggerUiRefresh();
                }
                return;
            }

            // Subject is in timetable - ask if user wants to keep it for exams/studies
            Alert keepDialog = new Alert(Alert.AlertType.CONFIRMATION);
            if (wizardStage != null) keepDialog.initOwner(wizardStage);
            UiKit.styleDialog(keepDialog.getDialogPane());
            keepDialog.setTitle("Keep Subject?");
            keepDialog.setHeaderText("Subject: " + displayName);
            keepDialog.setContentText("Do you want to keep this subject for exam/study tracking even though it's being removed from the timetable?");
            
            ButtonType btnKeep = new ButtonType("Keep for Exams/Studies", ButtonBar.ButtonData.YES);
            ButtonType btnRemoveCompletely = new ButtonType("Remove Completely", ButtonBar.ButtonData.NO);
            ButtonType btnCancelRemove = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            keepDialog.getButtonTypes().setAll(btnKeep, btnRemoveCompletely, btnCancelRemove);

            Optional<ButtonType> keepChoice = keepDialog.showAndWait();
            if (!keepChoice.isPresent() || keepChoice.get() == btnCancelRemove) {
                return; // User cancelled
            }

            boolean keep = keepChoice.get() == btnKeep;

            // Remove all timetable entries for this subject
            int removedCount = 0;
            List<WeeklyTimetableEntry> updated = new ArrayList<>();
            for (WeeklyTimetableEntry e : entriesList) {
                if (e.getLabel() != null) {
                    Matcher m = p.matcher(e.getLabel().trim());
                    if (m.matches()) {
                        String base = m.group(1).trim();
                        String code = nameToSubjectCode.getOrDefault(base, base);
                        if (code.equals(subjectCode)) {
                            removedCount++;
                            continue; // Skip this entry (remove it)
                        }
                    }
                }
                updated.add(e);
            }

            entriesList.setAll(updated);
            
            // Update kept_subjects registry or remove completely
            if (keep) {
                rememberKeptSubject(subjectCode, displayName);
            } else {
                removeSubjects(Collections.singleton(subjectCode));
            }
            persistEntriesAndReplan();

            updateTabIndicators();
            updateFreeHoursReview();

            String keepMsg = keep ? " (kept for exams/studies)" : " (removed completely)";
            UiKit.toast("Removed " + removedCount + " timetable entries for " + displayName + keepMsg);
            triggerUiRefresh();
        }
    }

    private void showError(String title, String msg) {
        UiKit.error(wizardStage, title, msg);
    }

    private void showInfo(String title, String msg) {
        UiKit.toast(title + ": " + msg, UiKit.Kind.INFO);
    }
}
