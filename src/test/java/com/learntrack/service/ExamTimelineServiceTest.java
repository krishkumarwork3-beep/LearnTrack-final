package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamTimelineDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamTimelineService with tentative timeline logic.
 */
class ExamTimelineServiceTest {

    private static final String TEST_DB_PATH = "test_exam_timeline.db";

    private ExamDAO examDAO;
    private SemesterDAO semesterDAO;
    private ExamTimelineDAO timelineDAO;
    private ExamTimelineService timelineService;

    @BeforeEach
    void setUp() throws SQLException {
        // Use temporary test database
        new java.io.File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        examDAO = new ExamDAO();
        semesterDAO = new SemesterDAO();
        timelineDAO = new ExamTimelineDAO();
        timelineService = new ExamTimelineService(timelineDAO, examDAO);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new java.io.File(TEST_DB_PATH).delete();
    }

    @Test
    void testSaveTentativeWindowSetsAllExamsToWindowStart() throws SQLException {
        // Create semester and exams
        Semester semester = new Semester(null, "Test", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        Exam mst1 = new Exam(null, semesterId, "Math", "MATH101", "MST", null, 3.0, 20.0, 
            null, null, null, 30, null, null, 2.0);
        Exam mst2 = new Exam(null, semesterId, "Physics", "PHY102", "MST", null, 3.0, 20.0, 
            null, null, null, 30, null, null, 2.0);
        Exam est1 = new Exam(null, semesterId, "Math", "MATH101", "EST", null, 3.0, 20.0, 
            null, null, null, 30, null, null, 2.0);

        examDAO.insert(mst1);
        examDAO.insert(mst2);
        examDAO.insert(est1);

        // Save tentative window for MST
        LocalDate start = LocalDate.of(2024, 11, 10);
        LocalDate end = LocalDate.of(2024, 11, 20);
        int count = timelineService.saveTentativeWindow(semesterId, "MST", start, end);

        assertEquals(2, count, "Should update 2 MST exams");

        // Verify MST exams have tentative dates
        List<Exam> allExams = examDAO.findBySemester(semesterId);
        for (Exam exam : allExams) {
            if ("MST".equals(exam.getExamType())) {
                assertEquals(start, exam.getExamDate(), "MST exam should have tentative date = window start");
                assertTrue(exam.isExamDateTentative(), "MST exam should be marked tentative");
                assertEquals(start, exam.getWindowStartDate());
                assertEquals(end, exam.getWindowEndDate());
                assertNull(exam.getExamStartTime(), "Tentative exams should have no start time");
                assertNull(exam.getExamEndTime(), "Tentative exams should have no end time");
            } else {
                assertNull(exam.getExamDate(), "EST exam should remain undated");
                assertFalse(exam.isExamDateTentative());
            }
        }
    }

    @Test
    void testSaveTentativeWindowRejectsEndBeforeStart() {
        Semester semester = new Semester(null, "Test", "ACTIVE");
        try {
            semesterDAO.insert(semester);
            int semesterId = semester.getSemesterId();

            LocalDate start = LocalDate.of(2024, 11, 20);
            LocalDate end = LocalDate.of(2024, 11, 10);

            assertThrows(IllegalArgumentException.class, () -> {
                timelineService.saveTentativeWindow(semesterId, "MST", start, end);
            });
        } catch (SQLException e) {
            fail("Setup failed: " + e.getMessage());
        }
    }

    @Test
    void testNewProvisionedExamInheritsWindow() throws SQLException {
        // Create semester
        Semester semester = new Semester(null, "Test", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Set tentative window
        LocalDate start = LocalDate.of(2024, 11, 10);
        LocalDate end = LocalDate.of(2024, 11, 20);
        timelineDAO.setWindow(semesterId, "MST", start, end);
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", false);

        // Create new exam and apply window
        Exam newExam = new Exam(null, semesterId, "Chem", "CHEM103", "MST", null, 3.0, 20.0, 
            null, null, null, 30, null, null, 2.0);
        
        timelineService.applyWindowToNewExam(semesterId, newExam);

        // Verify window applied with tentative flag
        assertEquals(start, newExam.getExamDate());
        assertTrue(newExam.isExamDateTentative());
        assertEquals(start, newExam.getWindowStartDate());
        assertEquals(end, newExam.getWindowEndDate());
    }

    @Test
    void testDatesheetAnnouncedStatePersists() throws SQLException {
        Semester semester = new Semester(null, "Test", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Initially not announced
        assertFalse(timelineDAO.isDatesheetAnnounced(semesterId, "MST"));

        // Set announced
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);
        assertTrue(timelineDAO.isDatesheetAnnounced(semesterId, "MST"));

        // EST is independent
        assertFalse(timelineDAO.isDatesheetAnnounced(semesterId, "EST"));

        // Turn off
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", false);
        assertFalse(timelineDAO.isDatesheetAnnounced(semesterId, "MST"));
    }

    @Test
    void testResetToTentativeResetsExamDates() throws SQLException {
        Semester semester = new Semester(null, "Test", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Create exams with exact dates
        Exam mst1 = new Exam(null, semesterId, "Math", "MATH101", "MST", null, 3.0, 20.0, 
            LocalDate.of(2024, 11, 12), 
            java.time.LocalTime.of(10, 0), 
            java.time.LocalTime.of(12, 0), 
            30, null, null, 2.0);
        mst1.setExamDateTentative(false);
        examDAO.insert(mst1);

        // Set stored window
        LocalDate start = LocalDate.of(2024, 11, 10);
        LocalDate end = LocalDate.of(2024, 11, 20);
        timelineDAO.setWindow(semesterId, "MST", start, end);

        // Reset to tentative
        int count = timelineService.resetToTentative(semesterId, "MST");
        assertEquals(1, count);

        // Verify reset
        Exam updated = examDAO.findById(mst1.getExamId());
        assertEquals(start, updated.getExamDate(), "Should reset to window start");
        assertTrue(updated.isExamDateTentative(), "Should be marked tentative");
        assertNull(updated.getExamStartTime(), "Should clear start time");
        assertNull(updated.getExamEndTime(), "Should clear end time");
    }

    @Test
    void testMigrationForTentativeColumnIsIdempotent() throws SQLException {
        // Migration already ran in setUp via initSchema
        try (Connection conn = DBConnection.getConnection()) {
            assertTrue(DBConnection.tableHasColumn(conn, "exams", "exam_date_tentative"));
        }
    }

    @Test
    void testRecomputeWindowFromDatesheet_OnlyExactDates() throws SQLException {
        // Setup semester
        Semester sem = new Semester(null, "Fall 2024", "ACTIVE");
        semesterDAO.insert(sem);
        int semesterId = sem.getSemesterId();

        // Create exams: some exact, some tentative
        Exam exact1 = new Exam(null, semesterId, "Math", "MATH101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 12), null, null, 30, null, null, 2.0);
        exact1.setExamDateTentative(false);
        examDAO.insert(exact1);

        Exam exact2 = new Exam(null, semesterId, "Physics", "PHY101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 18), null, null, 30, null, null, 2.0);
        exact2.setExamDateTentative(false);
        examDAO.insert(exact2);

        Exam tentative1 = new Exam(null, semesterId, "Chemistry", "CHEM101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 10), null, null, 30, null, null, 2.0);
        tentative1.setExamDateTentative(true);
        examDAO.insert(tentative1);

        Exam tentative2 = new Exam(null, semesterId, "Biology", "BIO101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 20), null, null, 30, null, null, 2.0);
        tentative2.setExamDateTentative(true);
        examDAO.insert(tentative2);

        // Recompute window
        timelineService.recomputeWindowFromDatesheet(semesterId, "MST");

        // Verify derived window = min/max of exact dates only
        var window = timelineDAO.getWindow(semesterId, "MST");
        assertTrue(window.isPresent(), "Window should be computed");
        assertEquals(LocalDate.of(2024, 11, 12), window.get()[0], "Window start = earliest exact date");
        assertEquals(LocalDate.of(2024, 11, 18), window.get()[1], "Window end = latest exact date");
    }

    @Test
    void testRecomputeWindowFromDatesheet_NoExactDates() throws SQLException {
        // Setup semester
        Semester sem = new Semester(null, "Fall 2024", "ACTIVE");
        semesterDAO.insert(sem);
        int semesterId = sem.getSemesterId();

        // Set a stored window
        LocalDate storedStart = LocalDate.of(2024, 11, 10);
        LocalDate storedEnd = LocalDate.of(2024, 11, 20);
        timelineDAO.setWindow(semesterId, "MST", storedStart, storedEnd);

        // Create only tentative exams
        Exam tentative1 = new Exam(null, semesterId, "Math", "MATH101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 15), null, null, 30, null, null, 2.0);
        tentative1.setExamDateTentative(true);
        examDAO.insert(tentative1);

        // Recompute window
        timelineService.recomputeWindowFromDatesheet(semesterId, "MST");

        // Verify window unchanged (no exact dates)
        var window = timelineDAO.getWindow(semesterId, "MST");
        assertTrue(window.isPresent(), "Window should still exist");
        assertEquals(storedStart, window.get()[0], "Window start unchanged");
        assertEquals(storedEnd, window.get()[1], "Window end unchanged");
    }

    @Test
    void testRecomputeWindowFromDatesheet_IgnoresTentativeDates() throws SQLException {
        // Setup semester
        Semester sem = new Semester(null, "Fall 2024", "ACTIVE");
        semesterDAO.insert(sem);
        int semesterId = sem.getSemesterId();

        // One exact date
        Exam exact = new Exam(null, semesterId, "Math", "MATH101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 15), null, null, 30, null, null, 2.0);
        exact.setExamDateTentative(false);
        examDAO.insert(exact);

        // Tentative dates outside the range (should be ignored)
        Exam tentative1 = new Exam(null, semesterId, "Physics", "PHY101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 1), null, null, 30, null, null, 2.0);
        tentative1.setExamDateTentative(true);
        examDAO.insert(tentative1);

        Exam tentative2 = new Exam(null, semesterId, "Chemistry", "CHEM101", "MST", null,
                3.0, 20.0, LocalDate.of(2024, 11, 30), null, null, 30, null, null, 2.0);
        tentative2.setExamDateTentative(true);
        examDAO.insert(tentative2);

        // Recompute window
        timelineService.recomputeWindowFromDatesheet(semesterId, "MST");

        // Verify window = only the exact date (tentative dates ignored)
        var window = timelineDAO.getWindow(semesterId, "MST");
        assertTrue(window.isPresent(), "Window should be computed");
        assertEquals(LocalDate.of(2024, 11, 15), window.get()[0], "Window start = only exact date");
        assertEquals(LocalDate.of(2024, 11, 15), window.get()[1], "Window end = only exact date");
    }

}
