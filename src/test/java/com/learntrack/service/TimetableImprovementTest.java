package com.learntrack.service;

import com.learntrack.model.WeeklyTimetableEntry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TimetableImprovementTest {

    @Test
    public void testImprovementRecommendations() throws Exception {
        Path path = Paths.get("docs/samples/TIME_TABLE_JULYTODEC2026.xlsx");
        if (!Files.exists(path)) return;

        TimetableXlsxReader reader = new TimetableXlsxReader();
        ParsedWorkbook workbook = reader.parseWorkbook(path.toFile());

        List<TimetableSlot> slots3C11 = workbook.getSlots(
                workbook.findSheetsForBatch("3C11").get(0), "3C11");
        
        // Build baseline from workbook slots (non-elective)
        List<WeeklyTimetableEntry> baseline = new ArrayList<>();
        for (TimetableSlot s : slots3C11) {
            if (!s.isElective()) {
                baseline.add(s.toEntry(null));
            }
        }
        
        // Empty soft entries (no meals)
        List<WeeklyTimetableEntry> softEntries = List.of();

        ImprovementService service = new ImprovementService();

        // 1. All components checked
        List<ImprovementService.BatchRecommendation> recsAll = service.findRecommendations(
                workbook, "UPH013", true, true, true, baseline, softEntries, "3C11"
        );
        assertFalse(recsAll.isEmpty());
        // Batches with 2 fitting components come before batches with fewer
        assertTrue(recsAll.get(0).getFittingComponentsCount() >= recsAll.get(recsAll.size() - 1).getFittingComponentsCount());

        // 2. Unchecking Tutorial and Practical (only lecture)
        List<ImprovementService.BatchRecommendation> recsLecOnly = service.findRecommendations(
                workbook, "UPH013", true, false, false, baseline, softEntries, "3C11"
        );
        assertFalse(recsLecOnly.isEmpty());
        // For lecture only, batches like 1D11 where lecture fits should have allSelectedFit == true
        ImprovementService.BatchRecommendation top = recsLecOnly.get(0);
        assertTrue(top.isAllSelectedFit());
        assertTrue(top.isRecommended());
        assertEquals("fits", top.getLecture().getStatus());
        assertEquals("not selected", top.getTutorial().getStatus());
        assertEquals("not selected", top.getPractical().getStatus());

        // 3. Unknown subject code throws exception
        assertThrows(NoSuchElementException.class, () -> {
            service.findRecommendations(workbook, "XYZ999", true, true, true, baseline, softEntries, "3C11");
        });
    }

    @Test
    public void testCrossBatchComponentsAndConflictCounts() throws Exception {
        Path path = Paths.get("docs/samples/TIME_TABLE_JULYTODEC2026.xlsx");
        if (!Files.exists(path)) return;

        TimetableXlsxReader reader = new TimetableXlsxReader();
        ParsedWorkbook workbook = reader.parseWorkbook(path.toFile());
        ImprovementService service = new ImprovementService();

        List<ImprovementService.BatchRecommendation> recs = service.findRecommendations(
                workbook, "UPH013", true, true, true, List.of(), List.of(), "3C11"
        );
        assertFalse(recs.isEmpty());

        // Find 1D11 (lecture), 1B18 (tutorial), 1D15 (practical)
        ImprovementService.BatchRecommendation rec1D11 = recs.stream()
                .filter(r -> "1D11".equalsIgnoreCase(r.getBatchName())).findFirst().orElse(null);
        ImprovementService.BatchRecommendation rec1B18 = recs.stream()
                .filter(r -> "1B18".equalsIgnoreCase(r.getBatchName())).findFirst().orElse(null);
        ImprovementService.BatchRecommendation rec1D15 = recs.stream()
                .filter(r -> "1D15".equalsIgnoreCase(r.getBatchName())).findFirst().orElse(null);

        assertNotNull(rec1D11, "1D11 should be found");
        assertTrue(rec1D11.getLecture().isOffered(), "1D11 offers lecture");
        assertEquals(0, rec1D11.getLecture().getAcademicConflictSlotCount());

        assertNotNull(rec1B18, "1B18 should be found");
        assertTrue(rec1B18.getTutorial().isOffered(), "1B18 offers tutorial");
        assertEquals(0, rec1B18.getTutorial().getAcademicConflictSlotCount());

        assertNotNull(rec1D15, "1D15 should be found");
        assertTrue(rec1D15.getPractical().isOffered(), "1D15 offers practical");
        assertEquals(0, rec1D15.getPractical().getAcademicConflictSlotCount());
    }

    @Test
    public void testBatch3W14AndCaseInsensitivity() throws Exception {
        Path path = Paths.get("docs/samples/TIME_TABLE_JULYTODEC2026.xlsx");
        if (!Files.exists(path)) return;

        TimetableXlsxReader reader = new TimetableXlsxReader();
        ParsedWorkbook workbook = reader.parseWorkbook(path.toFile());
        ImprovementService service = new ImprovementService();

        List<TimetableSlot> slots3W14 = workbook.getSlots("3RD YEAR A", "3W14");
        assertFalse(slots3W14.isEmpty(), "3W14 slots should not be empty");
        List<WeeklyTimetableEntry> baseline = new ArrayList<>();
        for (TimetableSlot s : slots3W14) {
            if (!s.isElective()) {
                baseline.add(s.toEntry(null));
            }
        }

        List<WeeklyTimetableEntry> softEntries = List.of();

        // Test uppercase
        List<ImprovementService.BatchRecommendation> recsUpper = service.findRecommendations(
                workbook, "UPH013", true, true, true, baseline, softEntries, "3W14"
        );
        assertFalse(recsUpper.isEmpty(), "Recommendations for 3W14 should not be empty");
        assertTrue(recsUpper.stream().noneMatch(r -> "3W14".equalsIgnoreCase(r.getBatchName())),
                "Recommendations must not contain own batch 3W14");

        // Test lowercase code and lowercase own batch
        List<ImprovementService.BatchRecommendation> recsLower = service.findRecommendations(
                workbook, "uph013", true, true, true, baseline, softEntries, "3w14"
        );
        assertFalse(recsLower.isEmpty(), "Recommendations for 3w14 should not be empty");
        assertTrue(recsLower.stream().noneMatch(r -> "3W14".equalsIgnoreCase(r.getBatchName())),
                "Recommendations must not contain own batch 3w14");

        // Assert uppercase and lowercase behave the same
        assertEquals(recsUpper.size(), recsLower.size(), "Result size should match for upper vs lower case");
        for (int i = 0; i < recsUpper.size(); i++) {
            assertEquals(recsUpper.get(i).getBatchName(), recsLower.get(i).getBatchName());
            assertEquals(recsUpper.get(i).getSheetName(), recsLower.get(i).getSheetName());
        }
    }

    @Test
    public void testDialogReproduction() throws Throwable {
        Path path = Paths.get("docs/samples/TIME_TABLE_JULYTODEC2026.xlsx");
        if (!Files.exists(path)) return;

        TimetableXlsxReader reader = new TimetableXlsxReader();
        ParsedWorkbook workbook = reader.parseWorkbook(path.toFile());
        ImprovementService service = new ImprovementService();

        List<TimetableSlot> slots3W14 = workbook.getSlots("3RD YEAR A", "3W14");
        List<WeeklyTimetableEntry> baseline = new ArrayList<>();
        for (TimetableSlot s : slots3W14) {
            if (!s.isElective()) {
                baseline.add(s.toEntry(null));
            }
        }

        List<WeeklyTimetableEntry> softEntries = List.of();

        List<ImprovementService.BatchRecommendation> recs = service.findRecommendations(
                workbook, "uph013", true, true, true, baseline, softEntries, "3W14"
        );

        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {}

        java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        javafx.application.Platform.runLater(() -> {
            try {
                // Instantiate the table and columns exactly as in TimetableSetupWizard
                javafx.scene.control.TableView<ImprovementService.BatchRecommendation> table =
                        new javafx.scene.control.TableView<>(javafx.collections.FXCollections.observableArrayList(recs));
                table.setColumnResizePolicy(javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

                javafx.beans.property.ObjectProperty<ImprovementService.BatchRecommendation> selectedLecBatch = new javafx.beans.property.SimpleObjectProperty<>(null);
                javafx.beans.property.ObjectProperty<ImprovementService.BatchRecommendation> selectedTutBatch = new javafx.beans.property.SimpleObjectProperty<>(null);
                javafx.beans.property.ObjectProperty<ImprovementService.BatchRecommendation> selectedPracBatch = new javafx.beans.property.SimpleObjectProperty<>(null);

                javafx.scene.control.ToggleGroup lecGroup = new javafx.scene.control.ToggleGroup();
                javafx.scene.control.ToggleGroup tutGroup = new javafx.scene.control.ToggleGroup();
                javafx.scene.control.ToggleGroup pracGroup = new javafx.scene.control.ToggleGroup();

                javafx.scene.control.TableColumn<ImprovementService.BatchRecommendation, String> colSheet = new javafx.scene.control.TableColumn<>("Sheet");
                colSheet.setCellValueFactory(f -> new javafx.beans.property.SimpleStringProperty(f.getValue().getSheetName()));
                colSheet.setComparator(String.CASE_INSENSITIVE_ORDER);

                javafx.scene.control.TableColumn<ImprovementService.BatchRecommendation, String> colBatch = new javafx.scene.control.TableColumn<>("Batch");
                colBatch.setCellValueFactory(f -> new javafx.beans.property.SimpleStringProperty(f.getValue().getBatchName()));
                colBatch.setComparator(String.CASE_INSENSITIVE_ORDER);

                table.getColumns().addAll(colSheet, colBatch);

                // Add conflict column
                javafx.scene.control.TableColumn<ImprovementService.BatchRecommendation, Number> colLecConf =
                        new javafx.scene.control.TableColumn<>("Lecture conflicts");
                colLecConf.setCellValueFactory(f -> {
                    ImprovementService.ComponentResult cr = f.getValue().getLecture();
                    if (!cr.isOffered()) return new javafx.beans.property.SimpleObjectProperty<>(-1);
                    return new javafx.beans.property.SimpleObjectProperty<>(cr.getAcademicConflictSlotCount());
                });
                colLecConf.setCellFactory(tc -> new javafx.scene.control.TableCell<>() {
                    @Override
                    protected void updateItem(Number val, boolean empty) {
                        super.updateItem(val, empty);
                        if (empty || val == null || getTableRow() == null || getTableRow().getItem() == null) {
                            setText(null);
                        } else {
                            ImprovementService.BatchRecommendation item = getTableRow().getItem();
                            setText(String.valueOf(val));
                        }
                    }
                });
                colLecConf.setComparator(java.util.Comparator.comparingInt(n -> (n == null || n.intValue() < 0) ? 9999 : n.intValue()));
                table.getColumns().add(colLecConf);

                // Add selection column
                javafx.scene.control.TableColumn<ImprovementService.BatchRecommendation, ImprovementService.BatchRecommendation> colLecSel =
                        new javafx.scene.control.TableColumn<>("Lecture");
                colLecSel.setCellValueFactory(f -> new javafx.beans.property.SimpleObjectProperty<>(f.getValue()));
                colLecSel.setCellFactory(tc -> new javafx.scene.control.TableCell<>() {
                    private final javafx.scene.control.RadioButton rb = new javafx.scene.control.RadioButton();
                    {
                        rb.setToggleGroup(lecGroup);
                    }
                    @Override
                    protected void updateItem(ImprovementService.BatchRecommendation item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                            setGraphic(null);
                        } else {
                            ImprovementService.BatchRecommendation rowItem = getTableRow().getItem();
                            setGraphic(rb);
                        }
                    }
                });
                colLecSel.setComparator((a, b) -> {
                    boolean aSel = (a == selectedLecBatch.get());
                    boolean bSel = (b == selectedLecBatch.get());
                    if (aSel != bSel) return aSel ? -1 : 1;
                    boolean aOff = a != null && a.getLecture().isOffered();
                    boolean bOff = b != null && b.getLecture().isOffered();
                    return Boolean.compare(!aOff, !bOff);
                });
                table.getColumns().add(colLecSel);

                // Dialog setup
                javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
                javafx.scene.control.ButtonType btnNext = new javafx.scene.control.ButtonType("Next \u2192", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                javafx.scene.control.ButtonType btnCancel = new javafx.scene.control.ButtonType("Cancel", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().addAll(btnNext, btnCancel);

                javafx.scene.Node nextNode = dialog.getDialogPane().lookupButton(btnNext);
                System.out.println("DEBUG: nextNode is " + nextNode);
                if (nextNode != null) {
                    nextNode.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                            () -> selectedLecBatch.get() == null && selectedTutBatch.get() == null && selectedPracBatch.get() == null,
                            selectedLecBatch, selectedTutBatch, selectedPracBatch
                    ));
                }

                // Render table in scene
                javafx.scene.Scene scene = new javafx.scene.Scene(table);
                javafx.stage.Stage stage = new javafx.stage.Stage();
                stage.setScene(scene);
                stage.show();

                // Sort columns to test comparators
                table.getSortOrder().add(colSheet);
                table.sort();
                table.getSortOrder().clear();
                table.getSortOrder().add(colBatch);
                table.sort();

                stage.close();
            } catch (Throwable t) {
                t.printStackTrace();
                err.set(t);
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        if (err.get() != null) {
            throw err.get();
        }
    }
}
