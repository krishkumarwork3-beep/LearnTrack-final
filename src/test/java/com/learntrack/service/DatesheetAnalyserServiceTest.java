package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DatesheetAnalyserServiceTest {

    private final String testDbPath = "target/test_datesheet_analyser.db";
    private ExamDAO examDAO;
    private SemesterDAO semesterDAO;
    private DatesheetAnalyserService analyserService;
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        examDAO = new ExamDAO();
        semesterDAO = new SemesterDAO();
        TimetableDAO timetableDAO = new TimetableDAO();
        SettingsDAO settingsDAO = new SettingsDAO();
        ExamTimelineService timelineService = new ExamTimelineService();
        SubjectListService subjectListService = new SubjectListService(timetableDAO, examDAO);

        analyserService = new DatesheetAnalyserService(
                examDAO, subjectListService, settingsDAO, timelineService, null
        );

        Semester sem = new Semester(null, "Odd 2026", "ACTIVE");
        semesterId = semesterDAO.insert(sem);

        // Provision user exams (MST tentative, and one EST)
        examDAO.insert(new Exam(null, semesterId, "Software Engineering", "UCS503", "MST", null,
                3.0, 20.0, LocalDate.of(2026, 9, 20), true, null, null, 30, null, null, 2.0));
        examDAO.insert(new Exam(null, semesterId, "Network Theory", "UCT301", "MST", null,
                3.0, 20.0, LocalDate.of(2026, 9, 20), true, null, null, 30, null, null, 2.0));
        examDAO.insert(new Exam(null, semesterId, "Operating Systems", "UCS303", "MST", null,
                3.0, 20.0, LocalDate.of(2026, 9, 20), true, null, null, 30, null, null, 2.0));
        examDAO.insert(new Exam(null, semesterId, "Basic Electrical", "UEE101", "MST", null,
                3.0, 20.0, LocalDate.of(2026, 9, 20), true, null, null, 30, null, null, 2.0));

        // EST exam for UCS503 (should NOT be modified when applying MST datesheet)
        examDAO.insert(new Exam(null, semesterId, "Software Engineering", "UCS503", "EST", null,
                3.0, 50.0, LocalDate.of(2026, 12, 1), true, null, null, 30, null, null, 2.0));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testAnalyseMatchesOnlyUserSubjectsAndReportsUnmatched() throws SQLException {
        String datesheetText =
                "Date Sheet Code: MST-2026-ODD\n" +
                "1 2026-10-01 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "NE4404 ENVIRONMENTAL CHEMISTRY\n" +
                "2 2026-10-02 1:00 PM UCT301 NETWORK THEORY\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, datesheetText);

        assertEquals("MST-2026-ODD", result.getSheetCode());
        assertEquals("MST", result.getExamType());

        // Should match only UCS503 and UCT301 (NE4404 is not a user subject)
        assertEquals(2, result.getMatchedRows().size());
        assertTrue(result.getMatchedRows().stream().anyMatch(r -> "UCS503".equalsIgnoreCase(r.getCourseCode())));
        assertTrue(result.getMatchedRows().stream().anyMatch(r -> "UCT301".equalsIgnoreCase(r.getCourseCode())));
        assertFalse(result.getMatchedRows().stream().anyMatch(r -> "NE4404".equalsIgnoreCase(r.getCourseCode())));

        // Unmatched should contain UCS303 and UEE101
        List<String> unmatchedCodes = result.getUnmatchedSubjects().stream()
                .map(SubjectInfo::getSubjectCode)
                .toList();
        assertTrue(unmatchedCodes.contains("UCS303"));
        assertTrue(unmatchedCodes.contains("UEE101"));
        assertFalse(unmatchedCodes.contains("UCS503"));
    }

    @Test
    void testClashDetection() throws SQLException {
        // Both UCS503 and UCT301 at the same date and time
        String clashText =
                "Date Sheet Code: MST-2026-ODD\n" +
                "1 2026-10-01 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "UCT301 NETWORK THEORY\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, clashText);

        assertEquals(2, result.getMatchedRows().size());
        boolean hasClashWarning = result.getWarnings().stream()
                .anyMatch(w -> w.toLowerCase().contains("clash") && w.contains("UCS503") && w.contains("UCT301"));
        assertTrue(hasClashWarning, "Warnings must report clash between UCS503 and UCT301: " + result.getWarnings());
    }

    @Test
    void testApplyIdempotentAndUpdatesExamFields() throws SQLException {
        String datesheetText =
                "Date Sheet Code: MST-2026-ODD\n" +
                "1 2026-10-01 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "2 2026-10-03 01:00 PM UCT301 NETWORK THEORY\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, datesheetText);
        assertEquals(2, result.getMatchedRows().size());

        int initialTotalExams = examDAO.findBySemester(semesterId).size();

        // Apply once
        analyserService.apply(semesterId, result.getMatchedRows());

        // Verify UCS503 MST updated
        List<Exam> ucs503Exams = examDAO.findBySubject(semesterId, "UCS503");
        Exam mst503 = ucs503Exams.stream().filter(e -> "MST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 1), mst503.getExamDate());
        assertEquals(LocalTime.of(9, 0), mst503.getExamStartTime());
        assertEquals(LocalTime.of(11, 0), mst503.getExamEndTime()); // 9:00 + 120 mins
        assertFalse(mst503.isExamDateTentative(), "Tentative flag must be cleared");

        // Verify UCS503 EST was NOT touched
        Exam est503 = ucs503Exams.stream().filter(e -> "EST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 1), est503.getExamDate());
        assertTrue(est503.isExamDateTentative());

        // Verify UCT301 MST updated
        Exam mst301 = examDAO.findBySubject(semesterId, "UCT301").stream()
                .filter(e -> "MST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 3), mst301.getExamDate());
        assertEquals(LocalTime.of(13, 0), mst301.getExamStartTime());
        assertEquals(LocalTime.of(15, 0), mst301.getExamEndTime());
        assertFalse(mst301.isExamDateTentative());

        // Apply a SECOND time (idempotent test)
        analyserService.apply(semesterId, result.getMatchedRows());

        int totalExamsAfterSecondApply = examDAO.findBySemester(semesterId).size();
        assertEquals(initialTotalExams, totalExamsAfterSecondApply, "Apply must be idempotent (no duplicate exams)");

        Exam mst503After = examDAO.findBySubject(semesterId, "UCS503").stream()
                .filter(e -> "MST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 1), mst503After.getExamDate());
        assertFalse(mst503After.isExamDateTentative());
    }
    @Test
    void testAmbiguousCourseWithoutResolutionIsNotApplied() throws SQLException {
        // Ambiguous UCS503 slot is only offered to the user; nothing is resolved or skipped.
        List<DatesheetMatchRow> rows = new ArrayList<>();
        rows.add(new DatesheetMatchRow("UCT301", "NETWORK THEORY", "Network Theory", "MST",
                LocalDate.of(2024, 11, 28), LocalTime.of(9, 0), LocalTime.of(11, 0), "Matched"));

        analyserService.apply(semesterId, rows, null, null, new HashMap<>(), new HashSet<>());

        Exam ucs503Mst = examDAO.findBySubject(semesterId, "UCS503").stream()
                .filter(e -> "MST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 20), ucs503Mst.getExamDate(),
                "Unresolved ambiguous subject must keep its old (tentative) date");
        assertTrue(ucs503Mst.isExamDateTentative(),
                "Unresolved ambiguous subject must stay tentative");
        assertNull(ucs503Mst.getExamStartTime(), "Unresolved ambiguous subject must not get a start time");
        assertNull(ucs503Mst.getExamEndTime(), "Unresolved ambiguous subject must not get an end time");

        // The non-ambiguous row was applied normally.
        Exam uct301Mst = examDAO.findBySubject(semesterId, "UCT301").stream()
                .filter(e -> "MST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2024, 11, 28), uct301Mst.getExamDate());
        assertEquals(LocalTime.of(9, 0), uct301Mst.getExamStartTime());
    }

    @Test
    void testAmbiguousResolutionWritesChosenSlot() throws SQLException {
        // EST datesheet: the chosen candidate must be written with the EST duration (default 180 min).
        List<DatesheetMatchRow> rows = new ArrayList<>();
        rows.add(new DatesheetMatchRow("UCT301", "NETWORK THEORY", "Network Theory", "EST",
                LocalDate.of(2024, 11, 29), LocalTime.of(9, 0), LocalTime.of(12, 0), "Matched"));

        DatesheetEntry chosen = new DatesheetEntry(2, LocalDate.of(2024, 12, 3),
                LocalTime.of(14, 0), "UCS503", "SOFTWARE ENGINEERING");
        Map<String, DatesheetEntry> resolutions = new HashMap<>();
        resolutions.put("UCS503", chosen);

        analyserService.apply(semesterId, rows, null, null, resolutions, new HashSet<>());

        Exam ucs503Est = examDAO.findBySubject(semesterId, "UCS503").stream()
                .filter(e -> "EST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2024, 12, 3), ucs503Est.getExamDate(),
                "The chosen candidate date must be written");
        assertEquals(LocalTime.of(14, 0), ucs503Est.getExamStartTime(),
                "The chosen candidate start time must be written");
        assertEquals(LocalTime.of(17, 0), ucs503Est.getExamEndTime(),
                "End time must be start + est_exam_duration_minutes (default 180)");
        assertFalse(ucs503Est.isExamDateTentative(), "A resolved exam is no longer tentative");

        // The MST exam of the same subject must not be touched by an EST apply.
        Exam ucs503Mst = examDAO.findBySubject(semesterId, "UCS503").stream()
                .filter(e -> "MST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 20), ucs503Mst.getExamDate());
        assertTrue(ucs503Mst.isExamDateTentative());
    }

    @Test
    void testSkippedAmbiguousCourseIsNotApplied() throws SQLException {
        // The user picked "(Skip this course)": even if a candidate is handed in, it must be ignored.
        List<DatesheetMatchRow> rows = new ArrayList<>();
        rows.add(new DatesheetMatchRow("UCT301", "NETWORK THEORY", "Network Theory", "EST",
                LocalDate.of(2024, 11, 29), LocalTime.of(9, 0), LocalTime.of(12, 0), "Matched"));

        DatesheetEntry unwanted = new DatesheetEntry(1, LocalDate.of(2024, 12, 2),
                LocalTime.of(9, 0), "UCS503", "SOFTWARE ENGINEERING");
        Map<String, DatesheetEntry> resolutions = new HashMap<>();
        resolutions.put("UCS503", unwanted);
        Set<String> skipped = new HashSet<>();
        skipped.add("UCS503");

        analyserService.apply(semesterId, rows, null, null, resolutions, skipped);

        Exam ucs503Est = examDAO.findBySubject(semesterId, "UCS503").stream()
                .filter(e -> "EST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 1), ucs503Est.getExamDate(),
                "A skipped ambiguous subject must keep its old date");
        assertTrue(ucs503Est.isExamDateTentative(), "A skipped ambiguous subject must stay tentative");
        assertNull(ucs503Est.getExamStartTime(), "A skipped ambiguous subject must not get a start time");
        assertNull(ucs503Est.getExamEndTime(), "A skipped ambiguous subject must not get an end time");

        // The other row is still applied, proving apply() actually ran.
        Exam uct301Est = examDAO.findBySubject(semesterId, "UCT301").stream()
                .filter(e -> "EST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2024, 11, 29), uct301Est.getExamDate());
    }

    @Test
    void testAmbiguousUserSubjectsContainsOnlyUserSubjectCodes() throws SQLException {
        // Column-block paste where dates/times/codes cannot be paired unambiguously.
        // UCS503 is a user subject; ZZ999 is not.
        String columnBlockText =
                "THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA\n" +
                "Date Sheet Code: MST-2026-ODD\n" +
                "Sl No. Date / Day Time Course No Course Name\n\n" +
                "02/12/2024\n" +
                "03/12/2024\n" +
                "09:00\n" +
                "14:00\n" +
                "UCS503\n" +
                "ZZ999\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, columnBlockText);

        Map<String, List<DatesheetEntry>> ambiguousUserSubjects = result.getAmbiguousUserSubjects();
        assertEquals(1, ambiguousUserSubjects.size(),
                "Only the user's ambiguous subject may be offered for a decision: " + ambiguousUserSubjects.keySet());
        assertTrue(ambiguousUserSubjects.containsKey("UCS503"));
        assertFalse(ambiguousUserSubjects.containsKey("ZZ999"),
                "Ambiguous codes that are not the user's subjects must not appear");

        List<DatesheetEntry> candidates = ambiguousUserSubjects.get("UCS503");
        assertNotNull(candidates);
        assertEquals(2, candidates.size(), "Both candidate slots must be offered");
        assertTrue(candidates.stream().anyMatch(c -> LocalDate.of(2024, 12, 2).equals(c.getDate())
                && LocalTime.of(9, 0).equals(c.getStart())));
        assertTrue(candidates.stream().anyMatch(c -> LocalDate.of(2024, 12, 3).equals(c.getDate())
                && LocalTime.of(14, 0).equals(c.getStart())));
    }
    @Test
    void testUnknownSheetCodeLeavesExamTypeUnset() throws SQLException {
        // AUXAUG26 names neither MST nor EST: the app must ask the user instead of assuming an MST.
        String datesheetText =
                "Date Sheet Code: AUXAUG26\n" +
                "1 2026-10-01 09:00 AM UCS503 SOFTWARE ENGINEERING\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, datesheetText);

        assertNull(result.getExamType(),
                "A sheet whose code names neither MST nor EST must not be assumed to be an MST");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("Choose MST or EST")),
                "The user must be asked to choose the exam type: " + result.getWarnings());
        assertEquals(1, result.getMatchedRows().size());

        // Once the user picks a type, the rows and durations follow it.
        DatesheetAnalysisResult chosen = analyserService.analyse(semesterId, datesheetText, "EST");
        assertEquals("EST", chosen.getExamType());
        assertEquals(1, chosen.getMatchedRows().size());
        assertEquals(LocalTime.of(12, 0), chosen.getMatchedRows().get(0).getEndTime(),
                "An EST datesheet uses the EST duration (default 180 minutes)");
    }

    @Test
    void testBatchSuffixedCodesAreAllOfferedAsCandidates() throws SQLException {
        // The user's subject UES101 is printed as UES101A / UES101B / UES101C: every slot must be
        // offered, nothing may be applied silently, and the chosen slot must reach the UES101 exam.
        examDAO.insert(new Exam(null, semesterId, "Engineering Drawing", "UES101", "MST", null,
                3.0, 20.0, LocalDate.of(2026, 9, 20), true, null, null, 30, null, null, 2.0));

        String datesheetText =
                "Date Sheet Code: MST-2026-ODD\n" +
                "35 2026-10-08 09:00 AM UES101A ENGINEERING DRAWING (BATCH A)\n" +
                "36 2026-10-08 1:00 PM UES101B ENGINEERING DRAWING (BATCH B)\n" +
                "37 2026-10-08 4:30 PM UES101C ENGINEERING DRAWING (BATCH C)\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, datesheetText);

        assertTrue(result.getMatchedRows().isEmpty(),
                "A subject printed under several batch codes must not be applied silently: "
                        + result.getMatchedRows());
        List<DatesheetEntry> candidates = result.getAmbiguousUserSubjects().get("UES101");
        assertNotNull(candidates, "UES101 must be offered for a decision");
        assertEquals(3, candidates.size(), "All batch slots must be offered: " + candidates);
        assertTrue(candidates.stream().anyMatch(c -> LocalTime.of(9, 0).equals(c.getStart())));
        assertTrue(candidates.stream().anyMatch(c -> LocalTime.of(13, 0).equals(c.getStart())));
        assertTrue(candidates.stream().anyMatch(c -> LocalTime.of(16, 30).equals(c.getStart())));
        assertFalse(result.getUnmatchedSubjects().stream()
                        .anyMatch(s -> "UES101".equalsIgnoreCase(s.getSubjectCode())),
                "A subject waiting for a decision is not unmatched");

        Map<String, DatesheetEntry> resolutions = new HashMap<>();
        resolutions.put("UES101", candidates.stream()
                .filter(c -> LocalTime.of(13, 0).equals(c.getStart())).findFirst().orElseThrow());
        analyserService.apply(semesterId, result.getMatchedRows(), result.getFirstDate(),
                result.getLastDate(), resolutions, new HashSet<>(), "MST");

        Exam ues101 = examDAO.findBySubject(semesterId, "UES101").stream()
                .filter(e -> "MST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 8), ues101.getExamDate());
        assertEquals(LocalTime.of(13, 0), ues101.getExamStartTime());
        assertFalse(ues101.isExamDateTentative(), "A resolved exam is no longer tentative");
    }

    @Test
    void testApplyUsesUserExamWindowNotWholeSheetRange() throws SQLException {
        // The sheet covers 2 Dec - 28 Dec (every batch); the user's own EST exams are on 3 and 12 Dec.
        String datesheetText =
                "Date Sheet Code: EST-2026-ODD\n" +
                "1 2026-12-02 09:00 AM ZZ999 SOME OTHER BATCH COURSE\n" +
                "2 2026-12-03 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "3 2026-12-12 01:00 PM UCT301 NETWORK THEORY\n" +
                "4 2026-12-28 09:00 AM ZZ998 LATER OTHER BATCH COURSE\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, datesheetText, "EST");
        assertEquals(2, result.getMatchedRows().size());
        assertEquals(LocalDate.of(2026, 12, 2), result.getFirstDate());
        assertEquals(LocalDate.of(2026, 12, 28), result.getLastDate());

        // The sheet's own range is handed in, as the view used to do.
        analyserService.apply(semesterId, result.getMatchedRows(), result.getFirstDate(),
                result.getLastDate(), new HashMap<>(), new HashSet<>(), "EST");

        ExamWindowService windowService = new ExamWindowService();
        LocalDate[] window = windowService.windowFor(semesterId, "EST");
        assertNotNull(window, "An applied datesheet must produce a window");
        assertEquals(LocalDate.of(2026, 12, 3), window[0],
                "Window start must be my first exam, not the sheet's first date");
        assertEquals(LocalDate.of(2026, 12, 12), window[1],
                "Window end must be my last exam, not the sheet's last date");

        // Days between my exams are exam days; days only other batches write on are not.
        assertTrue(windowService.isInDatedWindow(semesterId, LocalDate.of(2026, 12, 7)));
        assertFalse(windowService.isInDatedWindow(semesterId, LocalDate.of(2026, 12, 2)));
        assertFalse(windowService.isInDatedWindow(semesterId, LocalDate.of(2026, 12, 16)));
        assertFalse(windowService.isInDatedWindow(semesterId, LocalDate.of(2026, 12, 28)));

        // The Exams tab reads the same stored window.
        LocalDate[] stored = new ExamTimelineDAO().getWindow(semesterId, "EST").orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 3), stored[0]);
        assertEquals(LocalDate.of(2026, 12, 12), stored[1]);
    }

    @Test
    void testReapplyingDatesheetReplacesOldWindowAndDates() throws SQLException {
        String firstSheet =
                "Date Sheet Code: EST-2026-ODD\n" +
                "1 2026-12-03 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "2 2026-12-05 09:00 AM UCT301 NETWORK THEORY\n";
        DatesheetAnalysisResult first = analyserService.analyse(semesterId, firstSheet, "EST");
        analyserService.apply(semesterId, first.getMatchedRows(), new HashMap<>(), new HashSet<>(), "EST");

        ExamWindowService windowService = new ExamWindowService();
        assertEquals(LocalDate.of(2026, 12, 3), windowService.windowFor(semesterId, "EST")[0]);
        assertEquals(LocalDate.of(2026, 12, 5), windowService.windowFor(semesterId, "EST")[1]);

        // A corrected sheet: the same subjects were moved to new dates.
        String secondSheet =
                "Date Sheet Code: EST-2026-ODD\n" +
                "1 2026-12-08 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "2 2026-12-12 09:00 AM UCT301 NETWORK THEORY\n";
        DatesheetAnalysisResult second = analyserService.analyse(semesterId, secondSheet, "EST");
        analyserService.apply(semesterId, second.getMatchedRows(), new HashMap<>(), new HashSet<>(), "EST");

        LocalDate[] window = windowService.windowFor(semesterId, "EST");
        assertEquals(LocalDate.of(2026, 12, 8), window[0], "Re-applying must replace the old window start");
        assertEquals(LocalDate.of(2026, 12, 12), window[1], "Re-applying must replace the old window end");
        assertFalse(windowService.isInDatedWindow(semesterId, LocalDate.of(2026, 12, 3)),
                "The old exam dates must not survive a re-apply");

        Exam ucs503Est = examDAO.findBySubject(semesterId, "UCS503").stream()
                .filter(e -> "EST".equals(e.getExamType())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 8), ucs503Est.getExamDate(), "Exam dates must be replaced");
        assertEquals(LocalTime.of(9, 0), ucs503Est.getExamStartTime());
        assertFalse(ucs503Est.isExamDateTentative());
    }

    @Test
    void testUserExamWindowAndWarningFiltering() throws SQLException {
        String sheetWithIrrelevantWarnings =
                "Date Sheet Code: EST-2026-ODD\n" +
                "1 2026-12-01 09:00 AM ZZ101 INTRO TO OTHER\n" +
                "2 2026-12-05 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "3 2026-12-10 09:00 AM UCT301 NETWORK THEORY\n" +
                "4 2026-12-25 09:00 AM ZZ999 LATE OTHER COURSE\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, sheetWithIrrelevantWarnings, "EST");
        // User's exams are on 5 Dec and 10 Dec; sheet runs 1 Dec to 25 Dec.
        assertEquals(LocalDate.of(2026, 12, 1), result.getFirstDate());
        assertEquals(LocalDate.of(2026, 12, 25), result.getLastDate());
        assertEquals(LocalDate.of(2026, 12, 5), result.getUserFirstDate());
        assertEquals(LocalDate.of(2026, 12, 10), result.getUserLastDate());

        // Warnings for other courses or pages are dropped
        for (String w : result.getWarnings()) {
            assertFalse(w.startsWith("Page "), "Page-level warnings must be dropped");
            assertFalse(w.contains("ZZ101") || w.contains("ZZ999"), "Warnings for non-user courses must be dropped");
        }
    }

    @Test
    void testSheetSpanning2To28DecGivesUserRange3To12DecAndNoOtherCourseWarnings() throws SQLException {
        // The sheet runs 2 Dec - 28 Dec; only 3 Dec and 12 Dec belong to my subjects.
        String datesheetText =
                "Date Sheet Code: EST-2026-ODD\n" +
                "1 2026-12-02 09:00 AM ZZ999 SOME OTHER BATCH COURSE\n" +
                "2 2026-12-03 09:00 AM UCS503 SOFTWARE ENGINEERING\n" +
                "3 2026-12-12 01:00 PM UCT301 NETWORK THEORY\n" +
                "4 2026-12-28 09:00 AM ZZ998 LATER OTHER BATCH COURSE\n";

        DatesheetAnalysisResult result = analyserService.analyse(semesterId, datesheetText, "EST");

        // The sheet's own range is still reported
        assertEquals(LocalDate.of(2026, 12, 2), result.getFirstDate());
        assertEquals(LocalDate.of(2026, 12, 28), result.getLastDate());

        // My range is 3 Dec - 12 Dec
        assertEquals(LocalDate.of(2026, 12, 3), result.getUserFirstDate(),
                "User range must start at my first exam");
        assertEquals(LocalDate.of(2026, 12, 12), result.getUserLastDate(),
                "User range must end at my last exam");
        assertEquals(2, result.getMatchedRows().size());

        // No warning may talk about courses that are not mine
        for (String w : result.getWarnings()) {
            assertFalse(w.startsWith("Page "), "Page-level warnings must be dropped: " + w);
            assertFalse(w.contains("ZZ999") || w.contains("ZZ998"),
                    "Warnings about other courses must be dropped: " + w);
        }
    }
}
