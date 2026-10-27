package com.learntrack.ui.setup;

import com.learntrack.dao.ExamPeriodRoutineDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.model.ExamPeriodRoutineEntry;
import com.learntrack.model.Semester;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.ui.Theme;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DateUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Setup Wizard for exam-period routine (meals, sleep, other).
 * Shown after the first MST/EST datesheet arrives.
 * Plan Section 4 & 14, User Guide Section 6.
 */
public class ExamPeriodRoutineWizard {

    private final ExamPeriodRoutineDAO routineDAO;
    private final SemesterDAO semesterDAO;
    private final SemesterReplanService replanService;
    private final Runnable onComplete;

    private final ObservableList<ExamPeriodRoutineEntry> routineList = FXCollections.observableArrayList();

    public ExamPeriodRoutineWizard(ExamPeriodRoutineDAO routineDAO, SemesterDAO semesterDAO,
                                   SemesterReplanService replanService, Runnable onComplete) {
        this.routineDAO = routineDAO;
        this.semesterDAO = semesterDAO;
        this.replanService = replanService;
        this.onComplete = onComplete;
    }

    public void show(Stage ownerStage) {
        Stage stage = new Stage();
        stage.initOwner(ownerStage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Exam Period Daily Routine Setup");

        VBox root = new VBox(14);
        root.setPadding(new Insets(18));

        Label header = UiKit.pageTitle("Exam-period routine");

        Label desc = UiKit.subtle("A datesheet has been entered, so regular classes pause during the exam period. "
                + "Set the daily routine (meals, sleep, fixed commitments) that applies to every exam-window day.");

        TableView<ExamPeriodRoutineEntry> table = new TableView<>(routineList);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ExamPeriodRoutineEntry, LocalTime> colStart = new TableColumn<>("Start Time");
        colStart.setCellValueFactory(new PropertyValueFactory<>("startTime"));

        TableColumn<ExamPeriodRoutineEntry, LocalTime> colEnd = new TableColumn<>("End Time");
        colEnd.setCellValueFactory(new PropertyValueFactory<>("endTime"));

        TableColumn<ExamPeriodRoutineEntry, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(new PropertyValueFactory<>("activityType"));

        TableColumn<ExamPeriodRoutineEntry, String> colLabel = new TableColumn<>("Label");
        colLabel.setCellValueFactory(new PropertyValueFactory<>("label"));

        table.getColumns().addAll(colStart, colEnd, colType, colLabel);

        // Input Form
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setAlignment(Pos.CENTER_LEFT);

        TextField startField = new TextField("08:00");
        startField.setPrefWidth(80);
        TextField endField = new TextField("09:00");
        endField.setPrefWidth(80);

        ComboBox<String> typeCombo = new ComboBox<>(FXCollections.observableArrayList("MEAL", "SLEEP", "OTHER"));
        typeCombo.setValue("MEAL");

        TextField labelField = new TextField("Breakfast");

        Button btnAdd = UiKit.primaryButton("Add Routine Block");
        btnAdd.setOnAction(e -> {
            try {
                LocalTime st = DateUtils.parseTime(startField.getText());
                LocalTime et = DateUtils.parseTime(endField.getText());
                if (st == null || et == null) {
                    showError("Invalid Time", "Please enter times in HH:mm format.");
                    return;
                }
                routineList.add(new ExamPeriodRoutineEntry(null, 0, st, et, typeCombo.getValue(), labelField.getText()));
                labelField.clear();
            } catch (Exception ex) {
                showError("Error", "Invalid time values: " + ex.getMessage());
            }
        });

        Button btnRemove = UiKit.secondaryButton("Remove Selected");
        btnRemove.setOnAction(e -> {
            ExamPeriodRoutineEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) {
                routineList.remove(sel);
            }
        });

        grid.addRow(0, new Label("Start:"), startField, new Label("End:"), endField, new Label("Type:"), typeCombo);
        grid.addRow(1, new Label("Label:"), labelField, btnAdd, btnRemove);

        // Button bar
        Button btnSave = UiKit.primaryButton("Save Routine & Apply Exam Schedule");
        btnSave.setOnAction(e -> saveAndClose(stage));

        HBox btnBox = new HBox(btnSave);
        btnBox.setAlignment(Pos.CENTER_RIGHT);

        loadData();

        root.getChildren().addAll(header, desc, table, grid, btnBox);
        VBox.setVgrow(table, Priority.ALWAYS);

        Scene scene = new Scene(root, 720, 520);
        Theme.apply(scene, Theme.isDarkActive());
        stage.setScene(scene);
        UiKit.closeOnEscape(stage);
        stage.showAndWait();
    }

    private void loadData() {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                List<ExamPeriodRoutineEntry> existing = routineDAO.findBySemester(active.getSemesterId());
                if (!existing.isEmpty()) {
                    routineList.setAll(existing);
                } else {
                    // Seed standard defaults if empty
                    routineList.addAll(
                            new ExamPeriodRoutineEntry(null, active.getSemesterId(), LocalTime.of(8, 0), LocalTime.of(9, 0), "MEAL", "Breakfast"),
                            new ExamPeriodRoutineEntry(null, active.getSemesterId(), LocalTime.of(13, 0), LocalTime.of(14, 0), "MEAL", "Lunch"),
                            new ExamPeriodRoutineEntry(null, active.getSemesterId(), LocalTime.of(20, 0), LocalTime.of(21, 0), "MEAL", "Dinner"),
                            new ExamPeriodRoutineEntry(null, active.getSemesterId(), LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Night Sleep")
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void saveAndClose(Stage stage) {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active != null && active.getSemesterId() != null) {
                int semId = active.getSemesterId();
                List<ExamPeriodRoutineEntry> toSave = new ArrayList<>();
                for (ExamPeriodRoutineEntry ent : routineList) {
                    ent.setSemesterId(semId);
                    toSave.add(ent);
                }
                routineDAO.saveRoutineEntries(semId, toSave);
                replanService.regenerateSemesterPlan(semId, LocalDate.now());
            }
            stage.close();
            if (onComplete != null) {
                onComplete.run();
            }
        } catch (Exception ex) {
            showError("Save Error", "Failed to save exam routine: " + ex.getMessage());
        }
    }

    private void showError(String title, String msg) {
        UiKit.error(null, title, msg);
    }
}
