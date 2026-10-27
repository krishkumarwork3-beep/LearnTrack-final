package com.learntrack.ui.charts;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.dao.TopicDAO;
import com.learntrack.model.Semester;
import com.learntrack.service.FeasibilityService;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DurationFormat;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Feasibility Bar for NORMAL exam backlog.
 * Plan Section 13.
 */
public class FeasibilityBarView extends VBox {

    private final FeasibilityService feasibilityService;
    private final SemesterDAO semesterDAO;

    private final ProgressBar progressBar;
    private final Label statusLabel;
    /** Full card layout used on its own; the dashboard asks for the compact one-liner instead. */
    private final VBox cardLayout;
    private final HBox compactLayout;
    private final HBox barBox;

    public FeasibilityBarView(FeasibilityService feasibilityService, SemesterDAO semesterDAO) {
        this.feasibilityService = feasibilityService;
        this.semesterDAO = semesterDAO;

        setSpacing(6);
        getStyleClass().add("card");

        Label title = UiKit.sectionTitle("Semester study feasibility");
        Label caption = UiKit.hint("Needed study hours against the free time left before your deadlines.");

        progressBar = new ProgressBar(0.0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(14);

        statusLabel = new Label("Checking feasibility…");
        statusLabel.getStyleClass().add("subtle");
        statusLabel.setWrapText(true);

        barBox = new HBox(10, progressBar);
        barBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        cardLayout = new VBox(6, title, caption, barBox, statusLabel);
        compactLayout = new HBox(10);
        compactLayout.setAlignment(Pos.CENTER_LEFT);

        getChildren().add(cardLayout);
        refresh();
    }

    /**
     * Compact mode drops the card, the heading and the caption, leaving a slim bar next to the
     * status sentence. Used by the dashboard status row so the timetable keeps the height.
     */
    public void setCompact(boolean compact) {
        getStyleClass().removeAll("feasibility-compact", "card");
        getChildren().clear();
        statusLabel.getStyleClass().remove("status-caption");
        if (compact) {
            getStyleClass().add("feasibility-compact");
            statusLabel.getStyleClass().add("status-caption");
            progressBar.setPrefWidth(120);
            progressBar.setMaxWidth(120);
            progressBar.setPrefHeight(8);
            compactLayout.getChildren().setAll(progressBar, statusLabel);
            HBox.setHgrow(statusLabel, Priority.ALWAYS);
            getChildren().add(compactLayout);
        } else {
            getStyleClass().add("card");
            progressBar.setPrefWidth(Region.USE_COMPUTED_SIZE);
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBar.setPrefHeight(14);
            barBox.getChildren().setAll(progressBar);
            getChildren().add(cardLayout);
        }
    }

    public FeasibilityBarView(TopicDAO topicDAO, ExamDAO examDAO, TimetableDAO timetableDAO, SemesterDAO semesterDAO,
                              com.learntrack.service.FreeSlotService freeSlotService) {
        this(new FeasibilityService(topicDAO, examDAO, timetableDAO, freeSlotService), semesterDAO);
    }

    public void refresh() {
        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null || active.getSemesterId() == null) {
                progressBar.setProgress(0);
                statusLabel.setText("No active semester.");
                setStatus("", null);
                return;
            }
            int semId = active.getSemesterId();
            LocalDate today = LocalDate.now();

            if (feasibilityService == null) {
                return;
            }

            FeasibilityService.FeasibilityResult res = feasibilityService.calculateFeasibility(semId, today);
            double progress = res.getRatio();
            progressBar.setProgress(progress);

            double pct = res.getCapacityHours() > 0
                    ? (res.getNeededHours() / res.getCapacityHours()) * 100.0 : 0.0;

            if (!res.isFeasible()) {
                setStatus("Deficit: " + DurationFormat.format(res.getNeededHours()) + " needed exceeds "
                                + DurationFormat.format(res.getCapacityHours()) + " free before your deadlines ("
                                + String.format("%.0f", pct) + "% loaded).",
                        UiKit.Kind.DANGER);
            } else if (progress >= 0.85) {
                setStatus("Tight: " + DurationFormat.format(res.getNeededHours()) + " needed against "
                                + DurationFormat.format(res.getCapacityHours()) + " free ("
                                + String.format("%.0f", pct) + "% loaded).",
                        UiKit.Kind.WARN);
            } else {
                setStatus("On track: " + DurationFormat.format(res.getNeededHours()) + " needed against "
                                + DurationFormat.format(res.getCapacityHours()) + " free ("
                                + String.format("%.0f", pct) + "% loaded).",
                        UiKit.Kind.SUCCESS);
            }

        } catch (SQLException e) {
            setStatus("Feasibility could not be calculated: " + UiKit.plainLanguage(e.getMessage()), UiKit.Kind.WARN);
        }
    }

    /** Status is always an icon + words + colour, never colour alone. */
    private void setStatus(String text, UiKit.Kind kind) {
        progressBar.getStyleClass().removeAll(
                "progress-bar-success", "progress-bar-warning", "progress-bar-danger");
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        statusLabel.setText(text == null ? "" : text);
        if (kind == null) {
            return;
        }
        switch (kind) {
            case DANGER:
                progressBar.getStyleClass().add("progress-bar-danger");
                statusLabel.getStyleClass().add("text-danger");
                break;
            case WARN:
                progressBar.getStyleClass().add("progress-bar-warning");
                break;
            default:
                progressBar.getStyleClass().add("progress-bar-success");
                statusLabel.getStyleClass().add("text-success");
                break;
        }
    }
}
