package com.learntrack.service;

import com.learntrack.model.DatesheetEntry;
import com.learntrack.model.ParsedDatesheet;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DatesheetParserTest {

    @Test
    void testParseSampleDatesheet() throws Exception {
        String content;
        try (InputStream is = getClass().getResourceAsStream("/datesheet_MST-2026-ODD.txt")) {
            assertNotNull(is, "datesheet_MST-2026-ODD.txt must be present in test resources");
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        ParsedDatesheet parsed = DatesheetParser.parse(content);

        // Assert sheetCode and type
        assertEquals("MST-2026-ODD", parsed.getSheetCode());
        assertEquals("MST", parsed.getExamType());

        // Assert UCS503 -> 2026-10-01 09:00 AM
        Optional<DatesheetEntry> ucs503 = parsed.getEntries().stream()
                .filter(e -> "UCS503".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs503.isPresent(), "UCS503 should be found");
        assertEquals(LocalDate.of(2026, 10, 1), ucs503.get().getDate());
        assertEquals(LocalTime.of(9, 0), ucs503.get().getStart());

        // Assert UCT301 -> 2026-09-22 1:00 PM (13:00)
        Optional<DatesheetEntry> uct301 = parsed.getEntries().stream()
                .filter(e -> "UCT301".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uct301.isPresent(), "UCT301 should be found");
        assertEquals(LocalDate.of(2026, 9, 22), uct301.get().getDate());
        assertEquals(LocalTime.of(13, 0), uct301.get().getStart());

        // Assert UCS303 -> 2026-09-25 4:30 PM (16:30)
        Optional<DatesheetEntry> ucs303 = parsed.getEntries().stream()
                .filter(e -> "UCS303".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs303.isPresent(), "UCS303 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), ucs303.get().getDate());
        assertEquals(LocalTime.of(16, 30), ucs303.get().getStart());

        // Assert UCS802 -> 2026-09-25 1:00 PM (13:00)
        Optional<DatesheetEntry> ucs802 = parsed.getEntries().stream()
                .filter(e -> "UCS802".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs802.isPresent(), "UCS802 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), ucs802.get().getDate());
        assertEquals(LocalTime.of(13, 0), ucs802.get().getStart());

        // Wrapped names are joined: UCE306 ARCHITECTURE DRAWING & BUILDING CONSTRUCTION
        Optional<DatesheetEntry> uce306 = parsed.getEntries().stream()
                .filter(e -> "UCE306".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uce306.isPresent(), "UCE306 should be found");
        assertEquals("ARCHITECTURE DRAWING & BUILDING CONSTRUCTION", uce306.get().getCourseName());

        // Glued courses are split: UEI401 TRANSDUCERS AND SENSOR and UMA401 PROBABILITY AND STATISTICS
        Optional<DatesheetEntry> uei401 = parsed.getEntries().stream()
                .filter(e -> "UEI401".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uei401.isPresent(), "UEI401 should be split");
        assertEquals("TRANSDUCERS AND SENSOR", uei401.get().getCourseName());

        Optional<DatesheetEntry> uma401 = parsed.getEntries().stream()
                .filter(e -> "UMA401".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uma401.isPresent(), "UMA401 should be split");
        assertEquals("PROBABILITY AND STATISTICS", uma401.get().getCourseName());

        // Duplicate code triggers warning and keeps first
        assertFalse(parsed.getWarnings().isEmpty(), "Warnings should report duplicate code");
        assertTrue(parsed.getWarnings().stream().anyMatch(w -> w.contains("UCS802")),
                "Warning should mention duplicate UCS802");

        // Stable total entry count
        assertEquals(12, parsed.getEntries().size());
    }

    /**
     * Multi-page excerpt with repeated headers, Page N lines, weekday-alone line (Friday),
     * weekday + course on same line (Monday UEC311...), wrapped course name, garbage line,
     * and multiple time formats (09:00 AM, 1:00 PM, 4:30 PM).
     * Tests parser robustness with real datesheet format.
     */
    @Test
    void testMultipageExcerptParserRobustness() throws Exception {
        String content;
        try (InputStream is = getClass().getResourceAsStream("/datesheet_multipage_excerpt.txt")) {
            assertNotNull(is, "datesheet_multipage_excerpt.txt must be present in test resources");
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        ParsedDatesheet parsed = DatesheetParser.parse(content);

        // Sheet code and type
        assertEquals("MST-2026-ODD", parsed.getSheetCode());
        assertEquals("MST", parsed.getExamType());

        // Slot 1: 2026-09-21 09:00 AM: NE4404, UEC311, UML501
        Optional<DatesheetEntry> ne4404 = parsed.getEntries().stream()
                .filter(e -> "NE4404".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ne4404.isPresent(), "NE4404 should be found");
        assertEquals(LocalDate.of(2026, 9, 21), ne4404.get().getDate());
        assertEquals(LocalTime.of(9, 0), ne4404.get().getStart());

        Optional<DatesheetEntry> uec311 = parsed.getEntries().stream()
                .filter(e -> "UEC311".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uec311.isPresent(), "UEC311 should be found");
        assertEquals(LocalDate.of(2026, 9, 21), uec311.get().getDate());
        assertEquals(LocalTime.of(9, 0), uec311.get().getStart());

        Optional<DatesheetEntry> uml501 = parsed.getEntries().stream()
                .filter(e -> "UML501".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uml501.isPresent(), "UML501 should be found");
        assertEquals(LocalDate.of(2026, 9, 21), uml501.get().getDate());
        assertEquals(LocalTime.of(9, 0), uml501.get().getStart());

        // Slot 2: 2026-09-21 4:30 PM (16:30): UAI301, UEE301, UCE306 (wrapped name)
        Optional<DatesheetEntry> uai301 = parsed.getEntries().stream()
                .filter(e -> "UAI301".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uai301.isPresent(), "UAI301 should be found");
        assertEquals(LocalDate.of(2026, 9, 21), uai301.get().getDate());
        assertEquals(LocalTime.of(16, 30), uai301.get().getStart());

        Optional<DatesheetEntry> uee301 = parsed.getEntries().stream()
                .filter(e -> "UEE301".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uee301.isPresent(), "UEE301 should be found");
        assertEquals(LocalDate.of(2026, 9, 21), uee301.get().getDate());
        assertEquals(LocalTime.of(16, 30), uee301.get().getStart());

        Optional<DatesheetEntry> uce306 = parsed.getEntries().stream()
                .filter(e -> "UCE306".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uce306.isPresent(), "UCE306 should be found");
        assertEquals(LocalDate.of(2026, 9, 21), uce306.get().getDate());
        assertEquals(LocalTime.of(16, 30), uce306.get().getStart());
        assertEquals("ARCHITECTURE DRAWING & BUILDING CONSTRUCTION", uce306.get().getCourseName(),
                "UCE306 wrapped name should be joined");

        // UCS537 after page header, belongs to previous slot (2026-09-21 16:30)
        Optional<DatesheetEntry> ucs537 = parsed.getEntries().stream()
                .filter(e -> "UCS537".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs537.isPresent(), "UCS537 should be found after page header");
        assertEquals(LocalDate.of(2026, 9, 21), ucs537.get().getDate());
        assertEquals(LocalTime.of(16, 30), ucs537.get().getStart());

        // Slot 3: 2026-09-25 1:00 PM (13:00): UCT703, ULC301, UEE307 (wrapped)
        Optional<DatesheetEntry> uct703 = parsed.getEntries().stream()
                .filter(e -> "UCT703".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uct703.isPresent(), "UCT703 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), uct703.get().getDate());
        assertEquals(LocalTime.of(13, 0), uct703.get().getStart());

        Optional<DatesheetEntry> ulc301 = parsed.getEntries().stream()
                .filter(e -> "ULC301".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ulc301.isPresent(), "ULC301 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), ulc301.get().getDate());
        assertEquals(LocalTime.of(13, 0), ulc301.get().getStart());

        Optional<DatesheetEntry> uee307 = parsed.getEntries().stream()
                .filter(e -> "UEE307".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uee307.isPresent(), "UEE307 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), uee307.get().getDate());
        assertEquals(LocalTime.of(13, 0), uee307.get().getStart());
        assertEquals("TRANSMISSION AND DISTRIBUTION OF ELECTRIC POWER", uee307.get().getCourseName(),
                "UEE307 wrapped name should be joined");

        // Slot 4: 2026-09-25 4:30 PM (16:30): UCS303 (weekday alone on its line)
        Optional<DatesheetEntry> ucs303 = parsed.getEntries().stream()
                .filter(e -> "UCS303".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs303.isPresent(), "UCS303 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), ucs303.get().getDate());
        assertEquals(LocalTime.of(16, 30), ucs303.get().getStart());

        // Slot 5: 2026-09-30 09:00 AM: PBT305, UME312, UMA401 (glued line split)
        Optional<DatesheetEntry> pbt305 = parsed.getEntries().stream()
                .filter(e -> "PBT305".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(pbt305.isPresent(), "PBT305 should be found");
        assertEquals(LocalDate.of(2026, 9, 30), pbt305.get().getDate());
        assertEquals(LocalTime.of(9, 0), pbt305.get().getStart());

        Optional<DatesheetEntry> ume312 = parsed.getEntries().stream()
                .filter(e -> "UME312".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ume312.isPresent(), "UME312 should be found");
        assertEquals(LocalDate.of(2026, 9, 30), ume312.get().getDate());
        assertEquals(LocalTime.of(9, 0), ume312.get().getStart());

        Optional<DatesheetEntry> uma401 = parsed.getEntries().stream()
                .filter(e -> "UMA401".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uma401.isPresent(), "UMA401 should be found (glued line split)");
        assertEquals(LocalDate.of(2026, 9, 30), uma401.get().getDate());
        assertEquals(LocalTime.of(9, 0), uma401.get().getStart());

        // Garbage line skipped
        assertEquals(1, parsed.getSkippedLines(), "Exactly 1 garbage line should be skipped");

        // Total entry count: 3 (slot 1) + 4 (slot 2, including UCS537 after page) + 3 (slot 3) + 1 (slot 4) + 3 (slot 5) = 14
        assertEquals(14, parsed.getEntries().size(), "Total entry count should be 14");
    }

    /**
     * Parsing the same text twice must produce identical results (deterministic).
     */
    @Test
    void testParseIsIdempotent() throws Exception {
        String content;
        try (InputStream is = getClass().getResourceAsStream("/datesheet_MST-2026-ODD.txt")) {
            assertNotNull(is);
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        ParsedDatesheet first = DatesheetParser.parse(content);
        ParsedDatesheet second = DatesheetParser.parse(content);

        assertEquals(first.getSheetCode(), second.getSheetCode());
        assertEquals(first.getExamType(), second.getExamType());
        assertEquals(first.getEntries().size(), second.getEntries().size());
        assertEquals(first.getWarnings().size(), second.getWarnings().size());

        for (int i = 0; i < first.getEntries().size(); i++) {
            DatesheetEntry e1 = first.getEntries().get(i);
            DatesheetEntry e2 = second.getEntries().get(i);
            assertEquals(e1.getCourseCode(), e2.getCourseCode(), "Entry " + i + " course code should match");
            assertEquals(e1.getCourseName(), e2.getCourseName(), "Entry " + i + " course name should match");
            assertEquals(e1.getDate(), e2.getDate(), "Entry " + i + " date should match");
            assertEquals(e1.getStart(), e2.getStart(), "Entry " + i + " start time should match");
        }
    }

    /**
     * Full real datesheet regression test with 386 entries.
     * Tests parser robustness with complete datesheet and variant formats (extra blank lines, stripped spaces).
     */
    @Test
    void testFullRealDatesheet() throws Exception {
        String content;
        try (InputStream is = getClass().getResourceAsStream("/datesheet_full_MST-2026-ODD.txt")) {
            assertNotNull(is, "datesheet_full_MST-2026-ODD.txt must be present in test resources");
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Parse the original datesheet
        ParsedDatesheet parsed = DatesheetParser.parse(content);

        // Sheet code and type
        assertEquals("MST-2026-ODD", parsed.getSheetCode(), "Sheet code should be MST-2026-ODD");
        assertEquals("MST", parsed.getExamType(), "Exam type should be MST");

        // Exactly 386 entries
        assertEquals(386, parsed.getEntries().size(), "Should have exactly 386 entries");

        // 0 skipped lines
        assertEquals(0, parsed.getSkippedLines(), "Should have 0 skipped lines");

        // 0 warnings
        assertEquals(0, parsed.getWarnings().size(), "Should have 0 warnings");

        // Spot check: UCS503 = 2026-10-01 09:00
        Optional<DatesheetEntry> ucs503 = parsed.getEntries().stream()
                .filter(e -> "UCS503".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs503.isPresent(), "UCS503 should be found");
        assertEquals(LocalDate.of(2026, 10, 1), ucs503.get().getDate(), "UCS503 date should be 2026-10-01");
        assertEquals(LocalTime.of(9, 0), ucs503.get().getStart(), "UCS503 time should be 09:00");

        // Spot check: UCT301 = 2026-09-22 13:00
        Optional<DatesheetEntry> uct301 = parsed.getEntries().stream()
                .filter(e -> "UCT301".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uct301.isPresent(), "UCT301 should be found");
        assertEquals(LocalDate.of(2026, 9, 22), uct301.get().getDate(), "UCT301 date should be 2026-09-22");
        assertEquals(LocalTime.of(13, 0), uct301.get().getStart(), "UCT301 time should be 13:00");

        // Spot check: UCS303 = 2026-09-25 16:30
        Optional<DatesheetEntry> ucs303 = parsed.getEntries().stream()
                .filter(e -> "UCS303".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs303.isPresent(), "UCS303 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), ucs303.get().getDate(), "UCS303 date should be 2026-09-25");
        assertEquals(LocalTime.of(16, 30), ucs303.get().getStart(), "UCS303 time should be 16:30");

        // Spot check: UCS802 = 2026-09-25 13:00
        Optional<DatesheetEntry> ucs802 = parsed.getEntries().stream()
                .filter(e -> "UCS802".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs802.isPresent(), "UCS802 should be found");
        assertEquals(LocalDate.of(2026, 9, 25), ucs802.get().getDate(), "UCS802 date should be 2026-09-25");
        assertEquals(LocalTime.of(13, 0), ucs802.get().getStart(), "UCS802 time should be 13:00");

        // Spot check: UCS420 = 2026-10-03 09:00
        Optional<DatesheetEntry> ucs420 = parsed.getEntries().stream()
                .filter(e -> "UCS420".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ucs420.isPresent(), "UCS420 should be found");
        assertEquals(LocalDate.of(2026, 10, 3), ucs420.get().getDate(), "UCS420 date should be 2026-10-03");
        assertEquals(LocalTime.of(9, 0), ucs420.get().getStart(), "UCS420 time should be 09:00");

        // Spot check: UCE306 name should be wrapped correctly
        Optional<DatesheetEntry> uce306 = parsed.getEntries().stream()
                .filter(e -> "UCE306".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(uce306.isPresent(), "UCE306 should be found");
        assertEquals("ARCHITECTURE DRAWING & BUILDING CONSTRUCTION", uce306.get().getCourseName(),
                "UCE306 name should be correctly joined");

        // Test with blank line after every line
        String withBlankLines = content.replaceAll("(?m)^(.*)$", "$1\n");
        ParsedDatesheet parsedWithBlanks = DatesheetParser.parse(withBlankLines);
        assertEquals(386, parsedWithBlanks.getEntries().size(),
                "Should still have 386 entries with extra blank lines");

        // Test with leading spaces stripped
        String withoutLeadingSpaces = content.replaceAll("(?m)^\\s+", "");
        ParsedDatesheet parsedWithoutLeading = DatesheetParser.parse(withoutLeadingSpaces);
        assertEquals(386, parsedWithoutLeading.getEntries().size(),
                "Should still have 386 entries with leading spaces stripped");
    }
}