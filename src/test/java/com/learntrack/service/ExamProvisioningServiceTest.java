package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.dao.SettingsDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.model.WeeklyTimetableEntry;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExamProvisioningService.
 * Uses a temporary in-memory database for isolation.
 */
class ExamProvisioningServiceTest {

    private static final String TEST_DB_PATH = "test_exam_provisioning.db";

    private ExamDAO examDAO;
    private SemesterDAO semesterDAO;
    private TimetableDAO timetableDAO;
    private SettingsDAO settingsDAO;
    private SubjectListService subjectListService;
    private ExamTimelineService timelineService;
    private ExamProvisioningService provisioningService;

    @BeforeEach
    void setUp() throws SQLException {
        // Use temporary test database
        new java.io.File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        // Initialize DAOs
        examDAO = new ExamDAO();
        semesterDAO = new SemesterDAO();
        timetableDAO = new TimetableDAO();
        settingsDAO = new SettingsDAO();
        subjectListService = new SubjectListService(timetableDAO, examDAO);
        timelineService = new ExamTimelineService();

        // Initialize service
        provisioningService = new ExamProvisioningService(examDAO, subjectListService, settingsDAO, timelineService);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new java.io.File(TEST_DB_PATH).delete();
    }

    @Test
    void testEnsureExamsForSemester_CreatesExamsForTimetableSubjects() throws SQLException {
        // Create semester
        Semester semester = new Semester(null, "Test Semester", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Add subjects to timetable
        WeeklyTimetableEntry entry1 = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "Mathematics Lecture", "R101", "MATH101");
        WeeklyTimetableEntry entry2 = new WeeklyTimetableEntry(null, semesterId, "TUESDAY",
                LocalTime.of(10, 0), LocalTime.of(11, 0), "TUTORIAL", "Physics Tutorial", "R102", "PHY102");
        timetableDAO.insertEntry(entry1);
        timetableDAO.insertEntry(entry2);

        // Run provisioning
        provisioningService.ensureExamsForSemester(semesterId);

        // Verify exams were created
        List<Exam> allExams = examDAO.findBySemester(semesterId);
        assertEquals(4, allExams.size(), "Should create 2 MST + 2 EST = 4 exams");

        // Verify MST exams
        List<Exam> mstExams = allExams.stream()
                .filter(e -> "MST".equals(e.getExamType()))
                .toList();
        assertEquals(2, mstExams.size(), "Should have 2 MST exams");
        assertTrue(mstExams.stream().anyMatch(e -> "MATH101".equals(e.getSubjectCode())));
        assertTrue(mstExams.stream().anyMatch(e -> "PHY102".equals(e.getSubjectCode())));

        // Verify EST exams
        List<Exam> estExams = allExams.stream()
                .filter(e -> "EST".equals(e.getExamType()))
                .toList();
        assertEquals(2, estExams.size(), "Should have 2 EST exams");
        assertTrue(estExams.stream().anyMatch(e -> "MATH101".equals(e.getSubjectCode())));
        assertTrue(estExams.stream().anyMatch(e -> "PHY102".equals(e.getSubjectCode())));

        // Verify defaults
        Exam mathMst = mstExams.stream().filter(e -> "MATH101".equals(e.getSubjectCode())).findFirst().orElse(null);
        assertNotNull(mathMst);
        assertEquals(3.0, mathMst.getCredits(), 0.01, "Credits should be 3.0");
        assertEquals(20.0, mathMst.getEvalMarks(), 0.01, "Eval marks should be 20.0");
        assertEquals(30, mathMst.getTravelTimeMinutes(), "Travel should be 30 minutes");
        assertNull(mathMst.getExamDate(), "Exam date should be null");
        assertNull(mathMst.getEvalName(), "Eval name should be null for MST");
    }

    @Test
    void testEnsureExamsForSemester_IsIdempotent() throws SQLException {
        // Create semester
        Semester semester = new Semester(null, "Test Semester", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Add subject to timetable
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "Chemistry Lecture", "R103", "CHEM103");
        timetableDAO.insertEntry(entry);

        // Run provisioning first time
        provisioningService.ensureExamsForSemester(semesterId);
        List<Exam> firstRun = examDAO.findBySemester(semesterId);
        assertEquals(2, firstRun.size(), "First run should create 2 exams");

        // Run provisioning second time
        provisioningService.ensureExamsForSemester(semesterId);
        List<Exam> secondRun = examDAO.findBySemester(semesterId);
        assertEquals(2, secondRun.size(), "Second run should not create duplicates");

        // Verify exam IDs are the same
        assertEquals(firstRun.get(0).getExamId(), secondRun.get(0).getExamId());
        assertEquals(firstRun.get(1).getExamId(), secondRun.get(1).getExamId());
    }

    @Test
    void testEnsureExamsForSemester_DoesNotDuplicateExistingExams() throws SQLException {
        // Create semester
        Semester semester = new Semester(null, "Test Semester", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Add subject to timetable
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "Biology Lecture", "R104", "BIO104");
        timetableDAO.insertEntry(entry);

        // Manually create MST exam
        Exam manualMst = new Exam(null, semesterId, "Biology", "BIO104", "MST", null, 4.0, 25.0,
                null, null, null, 45, null, null, 3.0);
        examDAO.insert(manualMst);

        // Run provisioning
        provisioningService.ensureExamsForSemester(semesterId);

        // Verify only EST was created, MST was not duplicated
        List<Exam> allExams = examDAO.findBySemester(semesterId);
        assertEquals(2, allExams.size(), "Should have MST (existing) + EST (new)");

        List<Exam> mstExams = allExams.stream()
                .filter(e -> "MST".equals(e.getExamType()) && "BIO104".equals(e.getSubjectCode()))
                .toList();
        assertEquals(1, mstExams.size(), "Should have only 1 MST exam");
        assertEquals(4.0, mstExams.get(0).getCredits(), 0.01, "Existing MST credits should be unchanged");

        List<Exam> estExams = allExams.stream()
                .filter(e -> "EST".equals(e.getExamType()) && "BIO104".equals(e.getSubjectCode()))
                .toList();
        assertEquals(1, estExams.size(), "Should have created 1 EST exam");
        assertEquals(3.0, estExams.get(0).getCredits(), 0.01, "New EST should have default credits");
    }

    @Test
    void testEnsureExamsForSemester_DoesNotCreateForNonTimetableSubjects() throws SQLException {
        // Create semester
        Semester semester = new Semester(null, "Test Semester", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Add non-academic entry (MEAL)
        WeeklyTimetableEntry meal = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(12, 0), LocalTime.of(13, 0), "MEAL", "Lunch", null, null);
        timetableDAO.insertEntry(meal);

        // Run provisioning
        provisioningService.ensureExamsForSemester(semesterId);

        // Verify no exams were created
        List<Exam> allExams = examDAO.findBySemester(semesterId);
        assertEquals(0, allExams.size(), "Should not create exams for non-academic activities");
    }

    @Test
    void testEnsureExamsForSemester_DoesNotCreateNormalEvaluations() throws SQLException {
        // Create semester
        Semester semester = new Semester(null, "Test Semester", "ACTIVE");
        semesterDAO.insert(semester);
        int semesterId = semester.getSemesterId();

        // Add subject to timetable
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "English Lecture", "R105", "ENG105");
        timetableDAO.insertEntry(entry);

        // Run provisioning
        provisioningService.ensureExamsForSemester(semesterId);

        // Verify only MST and EST were created, no NORMAL
        List<Exam> allExams = examDAO.findBySemester(semesterId);
        assertEquals(2, allExams.size(), "Should create only MST and EST");

        List<Exam> normalExams = allExams.stream()
                .filter(e -> "NORMAL".equals(e.getExamType()))
                .toList();
        assertEquals(0, normalExams.size(), "Should not create NORMAL evaluations automatically");
    }
}
