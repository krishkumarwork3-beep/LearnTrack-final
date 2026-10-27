package com.learntrack.ui.setup;

import com.learntrack.service.ExamPeriodCapacityService;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DurationFormat;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Shared UI helper for the study capacity negotiation loop.
 * Prompts user when study demands in an exam window exceed available capacity.
 * Detects whether increasing extra hours can resolve the deficit or whether
 * capacity is already bounded by free time, guiding the user straight to trimming.
 * Loops until resolved or cancelled.
 * Plan Section 9 & User Guide Section 6.
 */
public class CapacityNegotiationDialogHelper {

    public static void runNegotiationLoop(int semesterId,
                                          String batchType,
                                          LocalDate today,
                                          ExamPeriodCapacityService capacityService,
                                          SemesterReplanService replanService,
                                          Runnable onTrimRequested,
                                          Runnable onDataChanged) {
        try {
            if (capacityService == null || replanService == null) {
                return;
            }
            if (!capacityService.isBatchDated(semesterId, batchType)) {
                return;
            }

            double req = capacityService.getRequiredHours(semesterId, batchType, today);
            double avail = capacityService.getAvailableHours(semesterId, batchType, today);

            if (req <= avail + 0.05) {
                return; // Feasible, no negotiation required
            }

            double deficit = req - avail;
            boolean canExtraHoursHelp = capacityService.canExtraHoursResolveDeficit(semesterId, batchType, today);
            double currentExtra = capacityService.getExtraHours(batchType);
            double suggested = capacityService.calculateSuggestedExtraHoursPerDay(semesterId, batchType, today);

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Study capacity negotiation: " + batchType);
            alert.setHeaderText(String.format("Deficit detected: %s needed > %s available (short by %s)",
                    DurationFormat.format(req), DurationFormat.format(avail), DurationFormat.format(deficit)));
            UiKit.styleDialog(alert.getDialogPane());

            if (!canExtraHoursHelp) {
                // Free time ceiling reached: extra hours cannot resolve deficit
                alert.setContentText(String.format(
                        "Notice: Available study capacity has reached the maximum free time ceiling across the %s window.\n" +
                        "Increasing daily study hours cannot add any more capacity.\n\n" +
                        "Please select 'Trim Topics' to deselect lower-priority topics.",
                        batchType
                ));

                ButtonType btnTrim = new ButtonType("Trim Topics", ButtonBar.ButtonData.YES);
                ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(btnTrim, btnCancel);

                Optional<ButtonType> res = alert.showAndWait();
                if (res.isPresent() && res.get() == btnTrim) {
                    if (onTrimRequested != null) {
                        onTrimRequested.run();
                    }
                }
            } else {
                // Extra hours can help close deficit
                double newExtra = currentExtra + suggested;
                alert.setContentText(String.format(
                        "Would you like to increase daily study hours by %s/day during the %s window?\n" +
                        "(Current extra: %s/day -> New extra: %s/day)\n\n" +
                        "Select 'Increase Hours' to save and replan, or 'Trim Topics' to deselect lower-priority topics.",
                        DurationFormat.format(suggested), batchType, DurationFormat.format(currentExtra), DurationFormat.format(newExtra)
                ));

                ButtonType btnIncrease = new ButtonType("Increase Hours (+" + DurationFormat.format(suggested) + "/day)", ButtonBar.ButtonData.YES);
                ButtonType btnTrim = new ButtonType("Trim Topics", ButtonBar.ButtonData.NO);
                ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(btnIncrease, btnTrim, btnCancel);

                Optional<ButtonType> res = alert.showAndWait();
                if (res.isPresent() && res.get() == btnIncrease) {
                    capacityService.setExtraHours(batchType, newExtra);
                    replanService.regenerateSemesterPlan(semesterId, today);
                    if (onDataChanged != null) {
                        onDataChanged.run();
                    }
                    // Loop to re-check if now feasible or if further negotiation is needed
                    runNegotiationLoop(semesterId, batchType, today, capacityService, replanService, onTrimRequested, onDataChanged);
                } else if (res.isPresent() && res.get() == btnTrim) {
                    if (onTrimRequested != null) {
                        onTrimRequested.run();
                    }
                }
            }

        } catch (SQLException e) {
            UiKit.error(null, "Capacity negotiation could not continue.", e);
        }
    }
}
