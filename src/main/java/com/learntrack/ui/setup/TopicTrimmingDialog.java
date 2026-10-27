package com.learntrack.ui.setup;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.TopicDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.Topic;
import com.learntrack.service.ExamPeriodCapacityService;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.ui.ReplanRunner;
import com.learntrack.ui.Theme;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DurationFormat;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Dialog for trimming/prioritizing topics during exam period capacity negotiation.
 */
public class TopicTrimmingDialog {

    public static class TopicItem {
        private final Topic topic;
        private final String examSubject;
        private final SimpleBooleanProperty selected;

        public TopicItem(Topic topic, String examSubject) {
            this.topic = topic;
            this.examSubject = examSubject;
            this.selected = new SimpleBooleanProperty(topic.getImportantFlag() == 1);
            this.selected.addListener((obs, old, val) -> topic.setImportantFlag(val ? 1 : 0));
        }

        public Topic getTopic() {
            return topic;
        }

        public String getExamSubject() {
            return examSubject;
        }

        public String getTopicName() {
            return topic.getName();
        }

        public double getHoursNeeded() {
            return topic.getHoursNeeded();
        }

        public double getRemainingHours() {
            return topic.getRemainingHours();
        }

        public SimpleBooleanProperty selectedProperty() {
            return selected;
        }

        public boolean isSelected() {
            return selected.get();
        }

        public void setSelected(boolean val) {
            selected.set(val);
        }
    }

    public static void show(int semesterId, String batchType,
                            TopicDAO topicDAO, ExamDAO examDAO,
                            ExamPeriodCapacityService capacityService,
                            SemesterReplanService replanService,
                            ReplanRunner replanRunner,
                            Runnable onDataChanged) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Topic scope prioritization: " + batchType);
        dialog.setWidth(720);
        dialog.setHeight(550);

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        Label header = UiKit.pageTitle("Prioritise topics for the " + batchType + " window");

        Label desc = UiKit.subtle("Deselect topics you wish to drop from the intensive exam revision period. "
                + "Dropped topics can still be studied later.");

        Label statusLabel = new Label();
        statusLabel.setWrapText(true);
        ProgressBar capacityBar = new ProgressBar(0);
        capacityBar.setMaxWidth(Double.MAX_VALUE);
        capacityBar.setPrefHeight(12);

        ObservableList<TopicItem> items = FXCollections.observableArrayList();

        Runnable updateStatus = () -> {
            try {
                LocalDate today = LocalDate.now();
                double req = capacityService.getRequiredHours(semesterId, batchType, today);
                double avail = capacityService.getAvailableHours(semesterId, batchType, today);
                double ratio = avail > 0 ? req / avail : 1.0;
                capacityBar.setProgress(Math.min(ratio, 1.0));

                statusLabel.getStyleClass().removeAll("text-danger", "text-success");
                capacityBar.getStyleClass().removeAll("progress-bar-danger", "progress-bar-success");
                if (req > avail + 0.05) {
                    capacityBar.getStyleClass().add("progress-bar-danger");
                    statusLabel.getStyleClass().add("text-danger");
                    statusLabel.setText(String.format("Deficit: %s required exceeds %s capacity (short by %s).",
                            DurationFormat.format(req), DurationFormat.format(avail), DurationFormat.format(req - avail)));
                } else {
                    capacityBar.getStyleClass().add("progress-bar-success");
                    statusLabel.getStyleClass().add("text-success");
                    statusLabel.setText(String.format("Feasible: %s required fits within %s capacity.",
                            DurationFormat.format(req), DurationFormat.format(avail)));
                }
            } catch (SQLException e) {
                statusLabel.setText("Error: " + e.getMessage());
            }
        };

        // Table
        TableView<TopicItem> table = new TableView<>(items);
        table.setEditable(true);

        TableColumn<TopicItem, Boolean> selCol = new TableColumn<>("Keep");
        selCol.setCellValueFactory(p -> p.getValue().selectedProperty());
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setPrefWidth(60);

        TableColumn<TopicItem, String> subCol = new TableColumn<>("Subject");
        subCol.setCellValueFactory(new PropertyValueFactory<>("examSubject"));
        subCol.setPrefWidth(160);

        TableColumn<TopicItem, String> nameCol = new TableColumn<>("Topic");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("topicName"));
        nameCol.setPrefWidth(260);

        TableColumn<TopicItem, Double> remCol = new TableColumn<>("Remaining Hrs");
        remCol.setCellValueFactory(new PropertyValueFactory<>("remainingHoursValue"));
        remCol.setPrefWidth(110);

        table.getColumns().addAll(selCol, subCol, nameCol, remCol);
        VBox.setVgrow(table, Priority.ALWAYS);

        // Load topics
        try {
            List<Exam> exams = examDAO.findBatch(semesterId, batchType);
            for (Exam e : exams) {
                List<Topic> topics = topicDAO.findByExam(e.getExamId());
                for (Topic t : topics) {
                    items.add(new TopicItem(t, e.getSubject()));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        updateStatus.run();

        // Button bar
        Button btnSelectAll = UiKit.secondaryButton("Select all");
        btnSelectAll.setOnAction(e -> items.forEach(i -> i.setSelected(true)));

        Button btnDeselectAll = UiKit.secondaryButton("Deselect all");
        btnDeselectAll.setOnAction(e -> items.forEach(i -> i.setSelected(false)));

        Button btnApply = UiKit.primaryButton("Apply & Replan");
        btnApply.setOnAction(e -> {
            try {
                for (TopicItem item : items) {
                    Topic t = item.getTopic();
                    int flag = item.isSelected() ? 1 : 0;
                    topicDAO.updateImportantFlag(t.getTopicId(), flag);
                }

                Runnable afterReplan = () -> {
                    updateStatus.run();
                    if (onDataChanged != null) {
                        onDataChanged.run();
                    }
                    dialog.close();
                };

                if (replanRunner != null) {
                    replanRunner.run(semesterId, LocalDate.now(), afterReplan, null);
                } else if (replanService != null) {
                    replanService.regenerateSemesterPlan(semesterId, LocalDate.now());
                    afterReplan.run();
                } else {
                    afterReplan.run();
                }
            } catch (SQLException ex) {
                UiKit.error(dialog, "The topic priorities could not be saved.", ex);
            }
        });

        HBox buttonBox = new HBox(10, btnSelectAll, btnDeselectAll, new Region(), btnApply);
        HBox.setHgrow(buttonBox.getChildren().get(2), Priority.ALWAYS);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(header, desc, statusLabel, capacityBar, table, buttonBox);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        Theme.apply(scene, Theme.isDarkActive());
        dialog.setScene(scene);
        UiKit.closeOnEscape(dialog);
        dialog.showAndWait();
    }
}
