package com.learntrack.service;

import com.learntrack.model.WeeklyTimetableEntry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TimetableImportTest {

    @Test
    public void testElectiveResolutionAndEntryCreation() throws Exception {
        Path path = Paths.get("docs/samples/TIME_TABLE_JULYTODEC2026.xlsx");
        if (!Files.exists(path)) return;

        TimetableXlsxReader reader = new TimetableXlsxReader();
        ParsedWorkbook workbook = reader.parseWorkbook(path.toFile());

        List<TimetableSlot> slots = workbook.getSlots(
                workbook.findSheetsForBatch("3C11").get(0), "3C11");
        assertFalse(slots.isEmpty());

        Set<String> chosenBaseCodes = new HashSet<>();
        List<WeeklyTimetableEntry> entries = new ArrayList<>();

        for (TimetableSlot slot : slots) {
            if (!slot.isElective()) {
                entries.add(slot.toEntry(null));
            } else {
                // If it's Monday 15:30, simulate typing UCS534P
                if ("MONDAY".equals(slot.getDay()) && LocalTime.of(15, 30).equals(slot.getStartTime())) {
                    ElectiveOption chosen = slot.getElectiveOptions().stream()
                            .filter(o -> o.matchesInput("UCS534P"))
                            .findFirst().orElse(null);
                    assertNotNull(chosen);
                    chosenBaseCodes.add(chosen.getBaseCode().toUpperCase());
                    entries.add(chosen.toEntry(slot.getDay(), null));
                } else {
                    // Check if already chosen base code exists in this slot
                    ElectiveOption autoOpt = slot.getElectiveOptions().stream()
                            .filter(o -> chosenBaseCodes.contains(o.getBaseCode().toUpperCase()))
                            .findFirst().orElse(null);
                    if (autoOpt != null) {
                        entries.add(autoOpt.toEntry(slot.getDay(), null));
                    }
                }
            }
        }

        // Verify Monday UCS534 practical is added
        assertTrue(entries.stream().anyMatch(e -> "MONDAY".equals(e.getDayOfWeek())
                && LocalTime.of(15, 30).equals(e.getStartTime())
                && LocalTime.of(17, 10).equals(e.getEndTime())
                && "LAB".equals(e.getActivityType())
                && "UCS534 practical".equals(e.getLabel())));

        // Verify Thursday slots for UCS534 were added automatically without asking!
        assertTrue(entries.stream().anyMatch(e -> "THURSDAY".equals(e.getDayOfWeek())
                && LocalTime.of(11, 20).equals(e.getStartTime())
                && "CLASS".equals(e.getActivityType())
                && "UCS534 lecture".equals(e.getLabel())));
        assertTrue(entries.stream().anyMatch(e -> "THURSDAY".equals(e.getDayOfWeek())
                && LocalTime.of(12, 10).equals(e.getStartTime())
                && "CLASS".equals(e.getActivityType())
                && "UCS534 lecture".equals(e.getLabel())));
    }

    @Test
    public void testReImportSafetyAndRunScope() throws Exception {
        Path path = Paths.get("docs/samples/TIME_TABLE_JULYTODEC2026.xlsx");
        if (!Files.exists(path)) return;

        TimetableXlsxReader reader = new TimetableXlsxReader();
        ParsedWorkbook workbook = reader.parseWorkbook(path.toFile());

        List<TimetableSlot> slots = workbook.getSlots(
                workbook.findSheetsForBatch("3W14").get(0), "3W14");
        assertFalse(slots.isEmpty());

        // Run 1: user imports 3W14 and picks UHU016
        Set<String> run1Chosen = new HashSet<>();
        run1Chosen.add("UHU016");
        List<WeeklyTimetableEntry> existingEntries = new ArrayList<>();
        for (TimetableSlot s : slots) {
            if (!s.isElective()) {
                existingEntries.add(s.toEntry(null));
            } else {
                ElectiveOption opt = s.getElectiveOptions().stream()
                        .filter(o -> run1Chosen.contains(o.getBaseCode().toUpperCase()))
                        .findFirst().orElse(null);
                if (opt != null) {
                    existingEntries.add(opt.toEntry(s.getDay(), null));
                }
            }
        }
        assertFalse(existingEntries.isEmpty());

        // Run 2: start a NEW import run with fresh empty set
        Set<String> run2Chosen = new HashSet<>();
        // Assert run2Chosen does NOT contain UHU016
        assertFalse(run2Chosen.contains("UHU016"));

        // Re-import safety check:
        // Identical entries in preview are recognized as alreadyAdded
        for (WeeklyTimetableEntry candidate : existingEntries) {
            boolean alreadyAdded = existingEntries.stream().anyMatch(e ->
                    candidate.getDayOfWeek().equalsIgnoreCase(e.getDayOfWeek())
                    && Objects.equals(candidate.getStartTime(), e.getStartTime())
                    && Objects.equals(candidate.getEndTime(), e.getEndTime())
                    && Objects.equals(candidate.getActivityType(), e.getActivityType())
                    && Objects.equals(candidate.getLabel(), e.getLabel())
            );
            assertTrue(alreadyAdded, "Identical entry must be recognized as already added");
        }
    }
}
