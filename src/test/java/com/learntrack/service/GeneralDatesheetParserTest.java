package com.learntrack.service;

import com.learntrack.model.DatesheetEntry;
import com.learntrack.model.ParsedDatesheet;
import com.learntrack.service.datesheet.ColumnBlockFormat;
import com.learntrack.service.datesheet.DatesheetTokenUtils;
import com.learntrack.service.datesheet.RowWiseFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class GeneralDatesheetParserTest {

    private static String estLayoutContent;
    private static Map<String, String[]> expectedCourses; // code -> [dateStr, timeStr]

    @BeforeAll
    static void setUp() throws Exception {
        try (InputStream is = GeneralDatesheetParserTest.class.getResourceAsStream("/est_2024_layout.txt")) {
            assertNotNull(is, "est_2024_layout.txt must be in test resources");
            estLayoutContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        expectedCourses = new HashMap<>();
        try (InputStream is = GeneralDatesheetParserTest.class.getResourceAsStream("/est_2024_expected.csv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            assertNotNull(is, "est_2024_expected.csv must be in test resources");
            String line = reader.readLine(); // header: code,date,time
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    expectedCourses.put(parts[0].trim().toUpperCase(), new String[]{parts[1].trim(), parts[2].trim()});
                }
            }
        }
    }

    @Test
    void testRowWiseFormatOnEstLayout() {
        RowWiseFormat format = new RowWiseFormat();
        ParsedDatesheet parsed = format.parse(estLayoutContent);

        assertEquals("2425ODDEST", parsed.getSheetCode());
        assertEquals("EST", parsed.getExamType());
        Set<String> parsedCodes = new HashSet<>();
        for (DatesheetEntry e : parsed.getEntries()) {
            parsedCodes.add(e.getCourseCode().toUpperCase());
        }
        for (String exp : expectedCourses.keySet()) {
            if (!parsedCodes.contains(exp)) {
                System.out.println("MISSING COURSE: " + exp);
            }
        }

        assertEquals(0, parsed.getSkippedLines(), "Skipped lines should be 0");
        assertEquals(546, parsed.getTotalUniqueCourses(), "Exactly 546 unique course codes expected");
        assertEquals(LocalDate.of(2024, 12, 2), parsed.getFirstDate());
        assertEquals(LocalDate.of(2024, 12, 28), parsed.getLastDate());

        // Check wrapped names joined: AH3401
        Optional<DatesheetEntry> ah3401 = parsed.getEntries().stream()
                .filter(e -> "AH3401".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(ah3401.isPresent(), "AH3401 must be present");
        assertEquals("HISTORY OF THE ANCIENT WORLD I: FROM STONE TO IRON", ah3401.get().getCourseName());

        // Check code that sits on weekday line: UNC302
        Optional<DatesheetEntry> unc302 = parsed.getEntries().stream()
                .filter(e -> "UNC302".equalsIgnoreCase(e.getCourseCode()))
                .findFirst();
        assertTrue(unc302.isPresent(), "UNC302 must be found on weekday line");
        assertEquals("OBJECT ORIENTED PROGRAMMING PRACTICES", unc302.get().getCourseName());

        // Check slot 2 cross-page continuation (AH2403 from page 1, UMT308 and UNC302 from page 2)
        assertTrue(parsed.getEntries().stream().anyMatch(e -> "AH2403".equalsIgnoreCase(e.getCourseCode())));
        assertTrue(parsed.getEntries().stream().anyMatch(e -> "UMT308".equalsIgnoreCase(e.getCourseCode())));

        // Check all 546 courses match expected CSV
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");

        for (DatesheetEntry entry : parsed.getEntries()) {
            String code = entry.getCourseCode().toUpperCase();
            assertTrue(expectedCourses.containsKey(code), "Unexpected course code parsed: " + code);
            String[] expected = expectedCourses.get(code);
            assertEquals(expected[0], entry.getDate().format(df), "Date mismatch for " + code);
            assertEquals(expected[1], entry.getStart().format(tf), "Time mismatch for " + code);
        }
    }

    @Test
    void testLeadingSpacesStrippedAndExtraBlankLines() {
        // Strip leading spaces from every line and add an extra blank line after every line
        StringBuilder sb = new StringBuilder();
        for (String line : estLayoutContent.split("\\r?\\n")) {
            sb.append(line.stripLeading()).append("\n\n");
        }
        String transformed = sb.toString();

        RowWiseFormat format = new RowWiseFormat();
        ParsedDatesheet parsed = format.parse(transformed);

        assertEquals("2425ODDEST", parsed.getSheetCode());
        assertEquals("EST", parsed.getExamType());
        assertEquals(0, parsed.getSkippedLines());
        assertEquals(546, parsed.getTotalUniqueCourses());
        assertEquals(LocalDate.of(2024, 12, 2), parsed.getFirstDate());
        assertEquals(LocalDate.of(2024, 12, 28), parsed.getLastDate());

        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");

        for (DatesheetEntry entry : parsed.getEntries()) {
            String code = entry.getCourseCode().toUpperCase();
            assertTrue(expectedCourses.containsKey(code));
            String[] expected = expectedCourses.get(code);
            assertEquals(expected[0], entry.getDate().format(df));
            assertEquals(expected[1], entry.getStart().format(tf));
        }
    }

    @Test
    void testColumnBlockFormat() {
        // Generate column-order text from layout content
        String columnText = generateColumnOrderText(estLayoutContent, false);

        ColumnBlockFormat format = new ColumnBlockFormat();
        ParsedDatesheet parsed = format.parse(columnText);

        assertEquals("2425ODDEST", parsed.getSheetCode());
        assertEquals("EST", parsed.getExamType());
        assertEquals("Column-block", parsed.getFormatName());

        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");

        // Every code assigned without flagging MUST equal the ground truth
        for (DatesheetEntry entry : parsed.getEntries()) {
            String code = entry.getCourseCode().toUpperCase();
            assertTrue(expectedCourses.containsKey(code), "Assigned course should be in expected list: " + code);
            String[] expected = expectedCourses.get(code);
            assertEquals(expected[0], entry.getDate().format(df), "Date mismatch for assigned code " + code);
            assertEquals(expected[1], entry.getStart().format(tf), "Time mismatch for assigned code " + code);
        }

        // Flagged codes must be ambiguous (not silently wrong)
        Map<String, List<DatesheetEntry>> ambiguous = parsed.getAmbiguous();
        for (Map.Entry<String, List<DatesheetEntry>> entry : ambiguous.entrySet()) {
            String code = entry.getKey().toUpperCase();
            assertTrue(expectedCourses.containsKey(code), "Ambiguous course should be known: " + code);
            assertFalse(entry.getValue().isEmpty(), "Candidate slots should not be empty for " + code);
        }

        System.out.println("ColumnBlockFormat: assigned=" + parsed.getEntries().size() +
                ", ambiguous=" + ambiguous.size() + ", totalUnique=" + parsed.getTotalUniqueCourses());
        
        // Verify counts match expected with tight bounds
        assertEquals(481, parsed.getEntries().size(), "Assigned courses should be 481");
        assertEquals(65, ambiguous.size(), "Ambiguous courses should be 65");
        assertEquals(546, parsed.getEntries().size() + ambiguous.size(), 
                "Total assigned + ambiguous should be 546");
        
        // Verify no code appears in both assigned and ambiguous
        Set<String> assignedCodes = new HashSet<>();
        for (DatesheetEntry e : parsed.getEntries()) {
            assignedCodes.add(e.getCourseCode().toUpperCase());
        }
        for (String ambigCode : ambiguous.keySet()) {
            assertFalse(assignedCodes.contains(ambigCode.toUpperCase()), 
                    "Code " + ambigCode + " appears in both assigned and ambiguous");
        }
    }

    @Test
    void testColumnBlockFormatScrambledTimesProducesAmbiguityWarnings() {
        // Generate column text with swapped times on page 1
        String scrambledText = generateColumnOrderText(estLayoutContent, true);

        ColumnBlockFormat format = new ColumnBlockFormat();
        ParsedDatesheet parsed = format.parse(scrambledText);

        // When times are swapped on page 1 (14:00 before 09:00 on 02/12/2024),
        // the codes of page 1 should be marked ambiguous with warnings, NOT silently assigned wrong dates
        assertFalse(parsed.getWarnings().isEmpty(), "Warnings must be reported for scrambled times");
        assertFalse(parsed.getAmbiguous().isEmpty(), "Page with scrambled times must mark codes as ambiguous");

        // AH3401 (which is on page 1) must be in ambiguous map, not in assigned entries
        assertTrue(parsed.getAmbiguous().containsKey("AH3401"), "AH3401 must be flagged as ambiguous when times scrambled");
        assertFalse(parsed.getEntries().stream().anyMatch(e -> "AH3401".equalsIgnoreCase(e.getCourseCode())),
                "AH3401 must not be silently assigned wrong time");
    }

    private String generateColumnOrderText(String layout, boolean scramblePageOneTimes) {
        // Split layout by page markers (Page N of M or form feeds)
        String[] pages = layout.split("(?=\\f|\\s+Page\\s+\\d+\\s+of\\s+\\d+)");
        StringBuilder result = new StringBuilder();

        for (int p = 0; p < pages.length; p++) {
            String page = pages[p];
            result.append("THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA\n");
            result.append("Date Sheet Code: 2425ODDEST\n");
            result.append("Sl No. Date / Day Time Course No Course Name\n\n");

            List<String> pageDates = new ArrayList<>();
            List<String> pageTimes = new ArrayList<>();
            List<String> pageCodes = new ArrayList<>();

            for (String rawLine : page.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                Matcher slot = RowWiseFormat.SLOT_START_PATTERN.matcher(line);
                if (slot.matches()) {
                    pageDates.add(slot.group(2).trim());
                    pageTimes.add(slot.group(3).trim());
                    String rem = slot.group(4).trim();
                    Matcher cm = DatesheetTokenUtils.COURSE_CODE_START_PATTERN.matcher(rem);
                    if (cm.matches()) {
                        pageCodes.add(cm.group(1).trim());
                    }
                    continue;
                }

                Matcher wm = DatesheetTokenUtils.WEEKDAY_START_PATTERN.matcher(line);
                if (wm.find()) {
                    String rem = line.substring(wm.end()).trim();
                    Matcher cm = DatesheetTokenUtils.COURSE_CODE_START_PATTERN.matcher(rem);
                    if (cm.matches()) {
                        pageCodes.add(cm.group(1).trim());
                    }
                    continue;
                }

                Matcher cm = DatesheetTokenUtils.COURSE_CODE_START_PATTERN.matcher(line);
                if (cm.matches()) {
                    pageCodes.add(cm.group(1).trim());
                }
            }

            if (p == 0 && scramblePageOneTimes && pageTimes.size() >= 2) {
                // Swap the two times on page 1 (14:00 before 09:00)
                String t0 = pageTimes.get(0);
                pageTimes.set(0, pageTimes.get(1));
                pageTimes.set(1, t0);
            }

            // Write column blocks
            for (String d : pageDates) {
                result.append(d).append("\n");
            }
            result.append("\n");
            for (String t : pageTimes) {
                result.append(t).append("\n");
            }
            result.append("\n");
            for (String c : pageCodes) {
                result.append(c).append("\n");
            }
            result.append("\n\f\n");
        }
        return result.toString();
    }

    @Test
    void testAutoDetection() throws Exception {
        // 1. MST-2026 parsed by Row-wise
        String mstText;
        try (InputStream is = getClass().getResourceAsStream("/datesheet_MST-2026-ODD.txt")) {
            mstText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        ParsedDatesheet mstParsed = DatesheetParser.parse(mstText);
        assertEquals("Row-wise", mstParsed.getFormatName());
        assertEquals("MST", mstParsed.getExamType());
        assertFalse(mstParsed.getEntries().isEmpty());

        // 2. EST layout parsed by Row-wise
        ParsedDatesheet estParsed = DatesheetParser.parse(estLayoutContent);
        assertEquals("Row-wise", estParsed.getFormatName());
        assertEquals("EST", estParsed.getExamType());
        assertEquals(546, estParsed.getTotalUniqueCourses());

        // 3. Column paste parsed by Column-block
        String columnText = generateColumnOrderText(estLayoutContent, false);
        ParsedDatesheet colParsed = DatesheetParser.parse(columnText);
        assertEquals("Column-block", colParsed.getFormatName());

        // 4. Garbage text returns empty result with warnings and no exception
        String garbage = "random text without any datesheet content\nsome more random text\nno dates or times";
        ParsedDatesheet garbageParsed = DatesheetParser.parse(garbage);
        assertNotNull(garbageParsed);
        assertTrue(garbageParsed.getEntries().isEmpty());
    }

    @Test
    void testDateTimeTokens() {
        // Dates: yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy, d MMM yyyy, Monday, 21 Sep 2026
        assertEquals(LocalDate.of(2026, 9, 21), DatesheetTokenUtils.parseDate("2026-09-21"));
        assertEquals(LocalDate.of(2024, 12, 2), DatesheetTokenUtils.parseDate("02/12/2024"));
        assertEquals(LocalDate.of(2024, 12, 2), DatesheetTokenUtils.parseDate("2/12/2024"));
        assertEquals(LocalDate.of(2024, 12, 2), DatesheetTokenUtils.parseDate("02-12-2024"));
        assertEquals(LocalDate.of(2024, 12, 2), DatesheetTokenUtils.parseDate("02.12.2024"));
        assertEquals(LocalDate.of(2026, 9, 21), DatesheetTokenUtils.parseDate("21 Sep 2026"));
        assertEquals(LocalDate.of(2024, 12, 2), DatesheetTokenUtils.parseDate("2 Dec 2024"));
        assertEquals(LocalDate.of(2026, 9, 21), DatesheetTokenUtils.parseDate("Monday, 21 Sep 2026"));
        assertEquals(LocalDate.of(2026, 9, 21), DatesheetTokenUtils.parseDate("Monday 21 Sep 2026"));

        // Times: 09:00, 9:00 AM, 1:00 PM, 13:00, 9.00 AM
        DatesheetTokenUtils.TimeSpan t1 = DatesheetTokenUtils.parseTimeOrRange("09:00");
        assertNotNull(t1);
        assertEquals(LocalTime.of(9, 0), t1.getStart());
        assertNull(t1.getEnd());

        DatesheetTokenUtils.TimeSpan t2 = DatesheetTokenUtils.parseTimeOrRange("9:00 AM");
        assertNotNull(t2);
        assertEquals(LocalTime.of(9, 0), t2.getStart());

        DatesheetTokenUtils.TimeSpan t3 = DatesheetTokenUtils.parseTimeOrRange("1:00 PM");
        assertNotNull(t3);
        assertEquals(LocalTime.of(13, 0), t3.getStart());

        DatesheetTokenUtils.TimeSpan t4 = DatesheetTokenUtils.parseTimeOrRange("13:00");
        assertNotNull(t4);
        assertEquals(LocalTime.of(13, 0), t4.getStart());

        DatesheetTokenUtils.TimeSpan t5 = DatesheetTokenUtils.parseTimeOrRange("9.00 AM");
        assertNotNull(t5);
        assertEquals(LocalTime.of(9, 0), t5.getStart());

        // Ranges: 09:00 - 11:00, 09:00 AM to 11:00 AM
        DatesheetTokenUtils.TimeSpan r1 = DatesheetTokenUtils.parseTimeOrRange("09:00 - 11:00");
        assertNotNull(r1);
        assertEquals(LocalTime.of(9, 0), r1.getStart());
        assertEquals(LocalTime.of(11, 0), r1.getEnd());

        DatesheetTokenUtils.TimeSpan r2 = DatesheetTokenUtils.parseTimeOrRange("09:00 AM to 11:00 AM");
        assertNotNull(r2);
        assertEquals(LocalTime.of(9, 0), r2.getStart());
        assertEquals(LocalTime.of(11, 0), r2.getEnd());
    }

    /**
     * "Count" sheet layout: every slot line is "serial date time total CODE (n) CODE (n)", the wrapped
     * lines carry only more "CODE (n)" tokens, and the table header puts "Candidates" on its own line.
     */
    @Test
    void testCountLayoutWithTotalsAndWrappedCodes() {
        String text =
                "THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA\n" +
                "Date Sheet Code: MST-2026-ODD\n" +
                "Sl No. Date Time Total Paper Code / Count\n" +
                "Candidates\n\n" +
                "1 2026-09-21 09:00 AM 67 UCS503 (30) UCS420 (25)\n" +
                "UCS303 (12)\n" +
                "2 2026-09-22 1:00 PM 17 UCT301 (17)\n" +
                "Page 1THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA\n" +
                "UCS802 (21)\n";

        ParsedDatesheet parsed = DatesheetParser.parse(text);

        assertEquals("Row-wise", parsed.getFormatName());
        assertEquals(5, parsed.getEntries().size());
        assertEquals(0, parsed.getSkippedLines(), "Headings and glued page markers are not data");
        assertTrue(parsed.getAmbiguous().isEmpty(), "Every code has a slot on the line: " + parsed.getAmbiguous());

        Map<String, DatesheetEntry> byCode = new HashMap<>();
        for (DatesheetEntry entry : parsed.getEntries()) {
            byCode.put(entry.getCourseCode(), entry);
        }

        assertEquals(LocalDate.of(2026, 9, 21), byCode.get("UCS503").getDate());
        assertEquals(LocalTime.of(9, 0), byCode.get("UCS503").getStart());
        assertEquals(30, byCode.get("UCS503").getCandidateCount(), "(n) is kept as metadata");
        assertEquals("", byCode.get("UCS503").getCourseName(), "A count is not a course name");

        assertEquals(LocalDate.of(2026, 9, 21), byCode.get("UCS303").getDate(),
                "A wrapped code belongs to the slot opened above it");
        assertEquals(LocalTime.of(9, 0), byCode.get("UCS303").getStart());
        assertEquals(LocalDate.of(2026, 9, 22), byCode.get("UCT301").getDate());
        assertEquals(LocalTime.of(13, 0), byCode.get("UCT301").getStart());
        assertEquals(LocalDate.of(2026, 9, 22), byCode.get("UCS802").getDate(),
                "A slot continues across the page header");
        assertEquals(LocalTime.of(13, 0), byCode.get("UCS802").getStart());
    }
}
