package com.learntrack.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link TimetableXlsxReader}.
 * All tests load the real sample file from docs/samples/.
 * Tests are skipped (not failed) when the sample file is absent.
 */
public class TimetableXlsxReaderTest {

    private static final Path SAMPLE = Paths.get("docs/samples/TIME_TABLE_JULYTODEC2026.xlsx");
    private static ParsedWorkbook WORKBOOK;

    @BeforeAll
    static void loadWorkbook() throws Exception {
        if (!Files.exists(SAMPLE)) return;
        WORKBOOK = new TimetableXlsxReader().parseWorkbook(SAMPLE.toFile());
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private static ParsedWorkbook wb() {
        return WORKBOOK;
    }

    private static boolean hasFile() {
        return WORKBOOK != null;
    }

    private static void assertSlot(List<TimetableSlot> slots, String day,
                                   LocalTime start, String codePrefix) {
        boolean found = slots.stream().anyMatch(s ->
                day.equals(s.getDay())
                && start.equals(s.getStartTime())
                && s.getSubjectCode() != null
                && s.getSubjectCode().startsWith(codePrefix));
        assertTrue(found,
                "Expected slot " + day + " " + start + " " + codePrefix
                + " but got: " + slots.stream()
                    .filter(s -> day.equals(s.getDay()))
                    .map(s -> s.getDay() + " " + s.getStartTime() + " " + s.getSubjectCode() + s.getSuffix())
                    .toList());
    }

    private static void assertSlot(List<TimetableSlot> slots, String day,
                                   LocalTime start, String codePrefix, char suffix) {
        boolean found = slots.stream().anyMatch(s ->
                day.equals(s.getDay())
                && start.equals(s.getStartTime())
                && s.getSubjectCode() != null
                && s.getSubjectCode().startsWith(codePrefix)
                && s.getSuffix() == suffix);
        assertTrue(found,
                "Expected slot " + day + " " + start + " " + codePrefix + suffix
                + " but got: " + slots.stream()
                    .filter(s -> day.equals(s.getDay()))
                    .map(s -> s.getDay() + " " + s.getStartTime() + " " + s.getSubjectCode() + s.getSuffix())
                    .toList());
    }

    private static void assertSlotEnd(List<TimetableSlot> slots, String day,
                                      LocalTime start, LocalTime end, String codePrefix) {
        boolean found = slots.stream().anyMatch(s ->
                day.equals(s.getDay())
                && start.equals(s.getStartTime())
                && end.equals(s.getEndTime())
                && s.getSubjectCode() != null
                && s.getSubjectCode().startsWith(codePrefix));
        assertTrue(found,
                "Expected slot " + day + " " + start + "-" + end + " " + codePrefix
                + " but got: " + slots.stream()
                    .filter(s -> day.equals(s.getDay()))
                    .map(s -> s.getDay() + " " + s.getStartTime() + "-" + s.getEndTime()
                        + " " + s.getSubjectCode() + s.getSuffix())
                    .toList());
    }

    // ------------------------------------------------------------------
    // a) 3RD YEAR A, batch 3W14
    // ------------------------------------------------------------------

    @Test
    void test3W14_3rdYearA() {
        if (!hasFile()) { System.out.println("Sample file absent, skipping test3W14_3rdYearA"); return; }

        List<String> sheets = wb().findSheetsForBatch("3W14");
        assertFalse(sheets.isEmpty(), "3W14 not found in any sheet");
        String sheet = sheets.get(0);
        assertTrue(sheet.trim().startsWith("3RD YEAR A"),
                "Expected '3RD YEAR A', got '" + sheet + "'");

        List<TimetableSlot> slots = wb().getSlots(sheet, "3W14");
        System.out.println("3W14 slots (" + slots.size() + "):");
        slots.forEach(s -> System.out.printf("  %s %s-%s %s%s%n",
                s.getDay(), s.getStartTime(), s.getEndTime(), s.getSubjectCode(), s.getSuffix()));

        assertFalse(slots.isEmpty(), "No slots found for 3W14");

        // Monday
        assertSlot(slots, "MONDAY", LocalTime.of(10, 30), "UME725", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(11, 20), "UML501", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(13, 50), "UMT802", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(14, 40), "URA521", 'L');

        // Tuesday, UMT802P 2 periods (09:40-11:20)
        assertSlotEnd(slots, "TUESDAY", LocalTime.of(9, 40), LocalTime.of(11, 20), "UMT802");

        // Wednesday
        assertSlot(slots, "WEDNESDAY", LocalTime.of(8, 50), "UME501", 'L');
        assertSlotEnd(slots, "WEDNESDAY", LocalTime.of(9, 40), LocalTime.of(11, 20), "UML501"); // 2 periods
        assertSlot(slots, "WEDNESDAY", LocalTime.of(11, 20), "UMT403", 'T'); // tutorial 1 period
        assertSlotEnd(slots, "WEDNESDAY", LocalTime.of(11, 20), LocalTime.of(12, 10), "UMT403");

        // Thursday
        assertSlot(slots, "THURSDAY", LocalTime.of(8, 50), "UHU018", 'L');
        assertSlot(slots, "THURSDAY", LocalTime.of(9, 40), "UME725", 'T'); // tutorial 1 period
        assertSlotEnd(slots, "THURSDAY", LocalTime.of(9, 40), LocalTime.of(10, 30), "UME725");
    }

    // ------------------------------------------------------------------
    // b) FIRST YEAR A, batch 1A11
    // ------------------------------------------------------------------

    @Test
    void test1A11_firstYearA() {
        if (!hasFile()) { System.out.println("Sample file absent, skipping test1A11_firstYearA"); return; }

        List<String> sheets = wb().findSheetsForBatch("1A11");
        assertFalse(sheets.isEmpty(), "1A11 not found in any sheet");
        String sheet = sheets.get(0);
        assertTrue(sheet.trim().startsWith("FIRST YEAR A"),
                "Expected 'FIRST YEAR A', got '" + sheet + "'");

        List<TimetableSlot> slots = wb().getSlots(sheet, "1A11");
        System.out.println("1A11 slots (" + slots.size() + "):");
        slots.forEach(s -> System.out.printf("  %s %s-%s %s%s%n",
                s.getDay(), s.getStartTime(), s.getEndTime(), s.getSubjectCode(), s.getSuffix()));

        assertFalse(slots.isEmpty(), "No slots found for 1A11");

        // Monday
        assertSlot(slots, "MONDAY", LocalTime.of(8, 50),  "UCB009", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(9, 40),  "UEN008", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(10, 30), "UES013", 'L');
        // UCB009P 2 periods Mon 11:20-13:00
        assertSlotEnd(slots, "MONDAY", LocalTime.of(11, 20), LocalTime.of(13, 0), "UCB009");
        // UES013T tutorial Monday 14:40
        assertSlot(slots, "MONDAY", LocalTime.of(14, 40), "UES013", 'T');

        // Wednesday, period 1 time from real time cell
        assertSlot(slots, "WEDNESDAY", LocalTime.of(8, 0), "UES103", 'L');

        // Wednesday, UES013P 2 periods 15:30-17:10
        assertSlotEnd(slots, "WEDNESDAY", LocalTime.of(15, 30), LocalTime.of(17, 10), "UES013");
    }

    // ------------------------------------------------------------------
    // c) Regression: 3RD YEAR B, batch 3C11
    // ------------------------------------------------------------------

    @Test
    void test3C11_regression_3rdYearB() {
        if (!hasFile()) { System.out.println("Sample file absent, skipping test3C11_regression"); return; }

        List<String> sheets = wb().findSheetsForBatch("3C11");
        assertFalse(sheets.isEmpty(), "3C11 not found in any sheet");
        String sheet = sheets.get(0);
        assertTrue(sheet.trim().startsWith("3RD YEAR B"),
                "Expected '3RD YEAR B', got '" + sheet + "'");

        List<TimetableSlot> slots = wb().getSlots(sheet, "3C11");
        System.out.println("3C11 slots (" + slots.size() + "):");
        slots.forEach(s -> System.out.printf("  %s %s-%s %s%s%n",
                s.getDay(), s.getStartTime(), s.getEndTime(), s.getSubjectCode(), s.getSuffix()));

        assertFalse(slots.isEmpty(), "No slots found for 3C11");

        // Monday
        assertSlot(slots, "MONDAY", LocalTime.of(8, 0),   "UCS503", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(8, 50),  "UCS615", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(9, 40),  "UCS510", 'L');
        assertSlot(slots, "MONDAY", LocalTime.of(10, 30), "UCS421", 'L');
        // UCS503P 2 periods 11:20-13:00
        assertSlotEnd(slots, "MONDAY", LocalTime.of(11, 20), LocalTime.of(13, 0), "UCS503");

        // Elective slot on Monday at 15:30
        boolean hasElective = slots.stream().anyMatch(s ->
                "MONDAY".equals(s.getDay())
                && LocalTime.of(15, 30).equals(s.getStartTime())
                && s.isElective());
        assertTrue(hasElective, "Expected elective slot Mon 15:30 for 3C11");
    }

    // ------------------------------------------------------------------
    // d) Every sheet with batch headers: layout detection + ≥90% coverage
    // ------------------------------------------------------------------

    @Test
    void testAllSheetsLayoutDetectionAndCoverage() {
        if (!hasFile()) { System.out.println("Sample file absent, skipping testAllSheetsLayoutDetectionAndCoverage"); return; }

        Map<String, List<String>> sheetBatches = wb().getSheetBatches();
        Map<String, Map<String, List<TimetableSlot>>> sheetBatchSlots = wb().getSheetBatchSlots();

        assertFalse(sheetBatches.isEmpty(), "No sheets with batch headers detected");

        for (String sheet : wb().getSheetNames()) {
            List<String> batches = sheetBatches.get(sheet);
            assertNotNull(batches, "No batch list for sheet '" + sheet + "'");
            assertFalse(batches.isEmpty(), "Empty batch list for sheet '" + sheet + "'");

            Map<String, List<TimetableSlot>> batchSlots = sheetBatchSlots.get(sheet);
            assertNotNull(batchSlots, "No slot map for sheet '" + sheet + "'");

            int totalBatches = batches.size();
            int batchesWithSlots = (int) batches.stream()
                    .filter(b -> {
                        List<TimetableSlot> sl = batchSlots.get(b);
                        return sl != null && !sl.isEmpty();
                    })
                    .count();

            double pct = totalBatches > 0 ? (100.0 * batchesWithSlots / totalBatches) : 0;
            System.out.printf("Sheet '%-20s': %d/%d batches have slots (%.1f%%)%n",
                    sheet, batchesWithSlots, totalBatches, pct);

            assertTrue(pct >= 90.0,
                    "Sheet '" + sheet + "': only " + batchesWithSlots + "/" + totalBatches
                    + " batches have slots (" + String.format("%.1f", pct) + "% < 90%)");
        }
    }

    // ------------------------------------------------------------------
    // Existing regression: elective resolution and entry creation (3C11)
    // ------------------------------------------------------------------

    @Test
    void testElectiveResolutionAndEntryCreation() throws Exception {
        if (!hasFile()) { System.out.println("Sample file absent, skipping testElectiveResolutionAndEntryCreation"); return; }

        List<TimetableSlot> slots = wb().getSlots("3RD YEAR B", "3C11");
        assertFalse(slots.isEmpty());

        java.util.Set<String> chosenBaseCodes = new java.util.HashSet<>();
        List<com.learntrack.model.WeeklyTimetableEntry> entries = new java.util.ArrayList<>();

        for (TimetableSlot slot : slots) {
            if (!slot.isElective()) {
                entries.add(slot.toEntry(null));
            } else {
                if ("MONDAY".equals(slot.getDay()) && LocalTime.of(15, 30).equals(slot.getStartTime())) {
                    ElectiveOption chosen = slot.getElectiveOptions().stream()
                            .filter(o -> o.matchesInput("UCS534P"))
                            .findFirst().orElse(null);
                    assertNotNull(chosen, "Expected UCS534P among elective options");
                    chosenBaseCodes.add(chosen.getBaseCode().toUpperCase());
                    entries.add(chosen.toEntry(slot.getDay(), null));
                } else {
                    ElectiveOption autoOpt = slot.getElectiveOptions().stream()
                            .filter(o -> chosenBaseCodes.contains(o.getBaseCode().toUpperCase()))
                            .findFirst().orElse(null);
                    if (autoOpt != null) entries.add(autoOpt.toEntry(slot.getDay(), null));
                }
            }
        }

        // Verify Monday UCS534 practical
        assertTrue(entries.stream().anyMatch(e ->
                "MONDAY".equals(e.getDayOfWeek())
                && LocalTime.of(15, 30).equals(e.getStartTime())
                && LocalTime.of(17, 10).equals(e.getEndTime())
                && "LAB".equals(e.getActivityType())
                && "UCS534 practical".equals(e.getLabel())));
    }

    // ------------------------------------------------------------------
    // Part 1: unit tests for periodCountFor rules
    // ------------------------------------------------------------------

    @Test
    void testPeriodCountFor_Lecture() {
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', null));
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', ""));
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', "LAB"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', "SPP/RSJ"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', "UCS503P"));
    }

    @Test
    void testPeriodCountFor_Practical() {
        // Practical: 2 periods unless below is another slot code
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', null));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', ""));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "   "));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "LAB"));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "SPP/RSJ"));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "F108"));
        // Another slot code below -> 1 period
        assertEquals(1, TimetableXlsxReader.periodCountFor('P', "UCS503L"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('P', "UMA022P"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('P', "UML501 T"));
    }

    @Test
    void testPeriodCountFor_Tutorial() {
        // Tutorial: 2 periods only if below is LAB or non-blank non-slot text
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', null));
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', ""));
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', "   "));
        assertEquals(2, TimetableXlsxReader.periodCountFor('T', "LAB"));
        assertEquals(2, TimetableXlsxReader.periodCountFor('T', "SPP/RSJ")); // Faculty
        assertEquals(2, TimetableXlsxReader.periodCountFor('T', "F108")); // Room/faculty
        // Another slot code below -> 1 period
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', "UCS503L"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', "UMA022P"));
    }

    @Test
    void testIsSlotStart() {
        // Valid slot codes
        assertTrue(TimetableXlsxReader.isSlotStart("UCS503L"));
        assertTrue(TimetableXlsxReader.isSlotStart("UMA022P"));
        assertTrue(TimetableXlsxReader.isSlotStart("UML501 T"));
        assertTrue(TimetableXlsxReader.isSlotStart("UCS534P/UCS538P"));
        assertTrue(TimetableXlsxReader.isSlotStart("UCS503/UMA022"));
        // Not slot codes
        assertFalse(TimetableXlsxReader.isSlotStart(null));
        assertFalse(TimetableXlsxReader.isSlotStart(""));
        assertFalse(TimetableXlsxReader.isSlotStart("   "));
        assertFalse(TimetableXlsxReader.isSlotStart("LAB"));
        assertFalse(TimetableXlsxReader.isSlotStart("SPP/RSJ")); // Teacher initials
        assertFalse(TimetableXlsxReader.isSlotStart("F108")); // Room
    }

    @Test
    void testEndTimeFor_Clamping() {
        LocalTime last = LocalTime.of(19, 40);
        // Normal case: start + 50min * periods
        assertEquals(LocalTime.of(9, 40), 
                TimetableXlsxReader.endTimeFor(LocalTime.of(8, 0), 2, "TEST", "test"));
        assertEquals(LocalTime.of(11, 20),
                TimetableXlsxReader.endTimeFor(LocalTime.of(9, 40), 2, "TEST", "test"));
        // Clamping at last period
        assertEquals(last,
                TimetableXlsxReader.endTimeFor(LocalTime.of(18, 50), 2, "TEST", "test"));
        assertEquals(last,
                TimetableXlsxReader.endTimeFor(LocalTime.of(19, 0), 2, "TEST", "test"));
    }

    @Test
    void testMergeBackToBackPtSlots() {
        // Two back-to-back 50-minute P slots with same code/suffix/room should merge
        TimetableSlot a = new TimetableSlot(
                "MONDAY", LocalTime.of(9, 40), LocalTime.of(10, 30),
                "UCS503", 'P', "LAB", "practical", "UCS503P", "F108", false, null);
        TimetableSlot b = new TimetableSlot(
                "MONDAY", LocalTime.of(10, 30), LocalTime.of(11, 20),
                "UCS503", 'P', "LAB", "practical", "UCS503P", "F108", false, null);
        
        List<TimetableSlot> merged = TimetableXlsxReader.mergeBackToBackPtSlots(List.of(a, b));
        assertEquals(1, merged.size());
        assertEquals(LocalTime.of(9, 40), merged.get(0).getStartTime());
        assertEquals(LocalTime.of(11, 20), merged.get(0).getEndTime());
        assertEquals("UCS503", merged.get(0).getSubjectCode());
        assertEquals('P', merged.get(0).getSuffix());
    }

    @Test
    void testCanMergeBackToBack() {
        TimetableSlot a = new TimetableSlot(
                "MONDAY", LocalTime.of(9, 40), LocalTime.of(10, 30),
                "UCS503", 'P', "LAB", "practical", "UCS503P", "F108", false, null);
        TimetableSlot b = new TimetableSlot(
                "MONDAY", LocalTime.of(10, 30), LocalTime.of(11, 20),
                "UCS503", 'P', "LAB", "practical", "UCS503P", "F108", false, null);
        assertTrue(TimetableXlsxReader.canMergeBackToBack(a, b));
        
        // Different room -> no merge
        TimetableSlot c = new TimetableSlot(
                "MONDAY", LocalTime.of(10, 30), LocalTime.of(11, 20),
                "UCS503", 'P', "LAB", "practical", "UCS503P", "E102", false, null);
        assertFalse(TimetableXlsxReader.canMergeBackToBack(a, c));
        
        // Not back-to-back times
        TimetableSlot d = new TimetableSlot(
                "MONDAY", LocalTime.of(11, 20), LocalTime.of(12, 10),
                "UCS503", 'P', "LAB", "practical", "UCS503P", "F108", false, null);
        assertFalse(TimetableXlsxReader.canMergeBackToBack(a, d));
        
        // Different day
        TimetableSlot e = new TimetableSlot(
                "TUESDAY", LocalTime.of(10, 30), LocalTime.of(11, 20),
                "UCS503", 'P', "LAB", "practical", "UCS503P", "F108", false, null);
        assertFalse(TimetableXlsxReader.canMergeBackToBack(a, e));
    }

    // ------------------------------------------------------------------
    // Part 1: durations, 3W13 concrete cases + workbook audit
    // ------------------------------------------------------------------

    @Test
    void test3W13_practicalDurations() {
        if (!hasFile()) { System.out.println("Sample file absent, skipping test3W13_practicalDurations"); return; }

        List<TimetableSlot> slots = wb().getSlots("3RD YEAR A", "3W13");
        assertFalse(slots.isEmpty(), "No slots for 3W13");

        assertSlotEnd(slots, "WEDNESDAY", LocalTime.of(15, 30), LocalTime.of(17, 10), "URA521");
        assertSlotEnd(slots, "WEDNESDAY", LocalTime.of(9, 40), LocalTime.of(11, 20), "UMT802");
        assertSlotEnd(slots, "TUESDAY", LocalTime.of(9, 40), LocalTime.of(11, 20), "UML501");
    }

    @Test
    void testWorkbookDurationAudit() {
        if (!hasFile()) { System.out.println("Sample file absent, skipping testWorkbookDurationAudit"); return; }

        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        List<String> remainingP50 = new ArrayList<>();
        int endBeforeStart = 0;
        int endsAfterLast = 0;

        for (String sheet : wb().getSheetNames()) {
            Map<String, List<TimetableSlot>> batchSlots = wb().getSheetBatchSlots().get(sheet);
            if (batchSlots == null) continue;
            for (var be : batchSlots.entrySet()) {
                String batch = be.getKey();
                for (TimetableSlot s : be.getValue()) {
                    if (s.getStartTime() == null || s.getEndTime() == null) continue;
                    if (s.getEndTime().isBefore(s.getStartTime())) endBeforeStart++;
                    if (s.getEndTime().isAfter(LocalTime.of(19, 40))) endsAfterLast++;

                    if (s.isElective()) {
                        for (ElectiveOption opt : s.getElectiveOptions()) {
                            long mins = java.time.Duration.between(opt.getStartTime(), opt.getEndTime()).toMinutes();
                            String key = "elective " + opt.getSuffix() + " " + mins + "min";
                            counts.merge(key, 1, Integer::sum);
                            if (opt.getSuffix() == 'P' && mins == 50) {
                                remainingP50.add(sheet + " | " + batch + " | " + s.getDay()
                                        + " | " + opt.getStartTime() + " | " + opt.getBaseCode() + "P (elective)");
                            }
                        }
                    } else {
                        long mins = java.time.Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
                        String key = s.getSuffix() + " " + mins + "min";
                        counts.merge(key, 1, Integer::sum);
                        if (s.getSuffix() == 'P' && mins == 50) {
                            remainingP50.add(sheet + " | " + batch + " | " + s.getDay()
                                    + " | " + s.getStartTime() + " | " + s.getSubjectCode() + "P");
                        }
                    }
                }
            }
        }

        System.out.println("=== Part 1 duration audit (suffix + minutes) ===");
        counts.forEach((k, v) -> System.out.println("  " + k + " = " + v));
        System.out.println("Remaining P 50min slots (" + remainingP50.size() + "):");
        remainingP50.forEach(line -> System.out.println("  " + line));

        int p50 = counts.getOrDefault("P 50min", 0);
        int electiveP50 = counts.getOrDefault("elective P 50min", 0);
        System.out.println("Non-elective P 50min = " + p50 + ", elective-option P 50min = " + electiveP50);
        assertTrue(p50 <= 20, "P 50min should drop to at most 20, was " + p50);
        assertEquals(0, endBeforeStart, "slots with end before start");
        assertEquals(0, endsAfterLast, "slots ending after 19:40");
    }

    @Test
    void testLectureRoomsPresentOnThirdYearSheets() {
        if (!hasFile()) { System.out.println("Sample file absent, skipping testLectureRoomsPresentOnThirdYearSheets"); return; }

        for (String wanted : List.of("3RD YEAR A", "3RD YEAR B")) {
            String sheet = wb().getSheetNames().stream()
                    .filter(n -> n.trim().equalsIgnoreCase(wanted))
                    .findFirst().orElse(null);
            assertNotNull(sheet, wanted);
            int lectures = 0;
            int missing = 0;
            List<String> misses = new ArrayList<>();
            Map<String, List<TimetableSlot>> batchSlots = wb().getSheetBatchSlots().get(sheet);
            assertNotNull(batchSlots, sheet);
            for (var be : batchSlots.entrySet()) {
                for (TimetableSlot s : be.getValue()) {
                    if (s.isElective() || s.getSuffix() != 'L') continue;
                    lectures++;
                    if (s.getRoom() == null || s.getRoom().isBlank()) {
                        missing++;
                        if (misses.size() < 40) {
                            misses.add(sheet + " | " + be.getKey() + " | " + s.getDay()
                                    + " " + s.getStartTime() + " " + s.getSubjectCode() + "L");
                        }
                    }
                }
            }
            double pct = lectures == 0 ? 0 : 100.0 * (lectures - missing) / lectures;
            System.out.printf("Sheet '%s' lecture rooms: %d/%d non-null (%.1f%%)%n",
                    sheet, lectures - missing, lectures, pct);
            System.out.println("Misses (" + missing + "):");
            misses.forEach(m -> System.out.println("  " + m));
            assertTrue(pct >= 95.0,
                    sheet + " lecture rooms only " + String.format("%.1f", pct) + "% (need >= 95%)");
        }
    }
}
