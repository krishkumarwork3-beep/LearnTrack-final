package com.learntrack.dao;

import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamDAO including migration, backfill, and validation rules.
 */
public class ExamDAOTest {

    private static final String TEST_DB_PATH = "test_exam_dao.db";
    private ExamDAO examDAO;
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        
        SemesterDAO semesterDAO = new SemesterDAO();
        Semester semester = new Semester();
        semester.setName("Test Semester");
        semester.setStatus("ACTIVE");
        semesterId = semesterDAO.insert(semester);
        
        examDAO = new ExamDAO();
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void testMigrationAddsNewColumns() throws SQLException {
        // Migration is already run in setUp via initSchema
        try (Connection conn = DBConnection.getConnection()) {
            assertTrue(DBConnection.tableHasColumn(conn, "exams", "subject_code"));
            assertTrue(DBConnection.tableHasColumn(conn, "exams", "eval_name"));
        }
    }

    @Test
    void testBackfillMatchesSubjectCode() throws SQLException {
        // Create a subject in timetable
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO weekly_timetable (semester_id, day_of_week, start_time, end_time, " +
                    "activity_type, label, subject_code) VALUES (" + semesterId + ", 'MONDAY', '09:00', '10:00', " +
                    "'CLASS', 'Data Structures', 'UCS420')");
        }

        // Create an exam with OLD schema (no subject_code) to simulate existing data
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO exams (semester_id, subject, exam_type, credits, eval_marks) " +
                    "VALUES (" + semesterId + ", 'UCS420', 'MST', 4.0, 20.0)");
        }

        // Trigger backfill by reinitializing (migration checks if column exists)
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.backfillExamSubjectCodes(conn);
        }

        // Verify backfill matched the subject code
        List<Exam> exams = examDAO.findBySemester(semesterId);
        assertEquals(1, exams.size());
        assertEquals("UCS420", exams.get(0).getSubjectCode());
    }

    @Test
    void testBackfillMatchesSubjectName() throws SQLException {
        // Create a subject name mapping
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO subject_names (semester_id, subject_code, subject_name) " +
                    "VALUES (" + semesterId + ", 'UCS503', 'Artificial Intelligence')");
        }

        // Create exam with display name that matches
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO exams (semester_id, subject, exam_type, credits, eval_marks) " +
                    "VALUES (" + semesterId + ", 'ARTIFICIAL INTELLIGENCE', 'EST', 4.0, 20.0)");
        }

        // Trigger backfill
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.backfillExamSubjectCodes(conn);
        }

        // Verify backfill matched (case-insensitive)
        List<Exam> exams = examDAO.findBySemester(semesterId);
        assertEquals(1, exams.size());
        assertEquals("UCS503", exams.get(0).getSubjectCode());
    }

    @Test
    void testBackfillLeavesNullWhenNoMatch() throws SQLException {
        // Create exam with unrecognized subject
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO exams (semester_id, subject, exam_type, credits, eval_marks) " +
                    "VALUES (" + semesterId + ", 'Unknown Subject', 'NORMAL', 3.0, 10.0)");
        }

        // Trigger backfill
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.backfillExamSubjectCodes(conn);
        }

        // Verify subject_code remains NULL
        List<Exam> exams = examDAO.findBySemester(semesterId);
        assertEquals(1, exams.size());
        assertNull(exams.get(0).getSubjectCode());
    }

    @Test
    void testSubjectCodePreferredButNotStrictlyRequired() throws SQLException {
        // For backward compatibility, allow NULL subject_code
        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Data Structures");
        exam.setSubjectCode(null); // Allowed for backward compatibility
        exam.setExamType("NORMAL");
        exam.setEvalName("Quiz 1");
        exam.setCredits(2.0);
        exam.setEvalMarks(5.0);

        assertDoesNotThrow(() -> examDAO.insert(exam));
    }

    @Test
    void testOneMstPerSubject() throws SQLException {
        // Insert first MST
        Exam mst1 = new Exam();
        mst1.setSemesterId(semesterId);
        mst1.setSubject("Data Structures");
        mst1.setSubjectCode("UCS420");
        mst1.setExamType("MST");
        mst1.setCredits(4.0);
        mst1.setEvalMarks(20.0);
        examDAO.insert(mst1);

        // Try to insert second MST for same subject
        Exam mst2 = new Exam();
        mst2.setSemesterId(semesterId);
        mst2.setSubject("Data Structures");
        mst2.setSubjectCode("UCS420");
        mst2.setExamType("MST");
        mst2.setCredits(4.0);
        mst2.setEvalMarks(20.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            examDAO.insert(mst2);
        });
        assertTrue(ex.getMessage().contains("Only one MST allowed per subject"));
    }

    @Test
    void testOneEstPerSubject() throws SQLException {
        // Insert first EST
        Exam est1 = new Exam();
        est1.setSemesterId(semesterId);
        est1.setSubject("AI");
        est1.setSubjectCode("UCS503");
        est1.setExamType("EST");
        est1.setCredits(4.0);
        est1.setEvalMarks(20.0);
        examDAO.insert(est1);

        // Try to insert second EST for same subject
        Exam est2 = new Exam();
        est2.setSemesterId(semesterId);
        est2.setSubject("AI");
        est2.setSubjectCode("UCS503");
        est2.setExamType("EST");
        est2.setCredits(4.0);
        est2.setEvalMarks(20.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            examDAO.insert(est2);
        });
        assertTrue(ex.getMessage().contains("Only one EST allowed per subject"));
    }

    @Test
    void testMstCannotHaveEvalName() {
        Exam mst = new Exam();
        mst.setSemesterId(semesterId);
        mst.setSubject("Data Structures");
        mst.setSubjectCode("UCS420");
        mst.setExamType("MST");
        mst.setEvalName("Mid Term"); // Not allowed for MST
        mst.setCredits(4.0);
        mst.setEvalMarks(20.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            examDAO.insert(mst);
        });
        assertTrue(ex.getMessage().contains("MST exams cannot have an evaluation name"));
    }

    @Test
    void testNormalExamRequiresEvalNameWhenSubjectCodeIsSet() {
        Exam normal = new Exam();
        normal.setSemesterId(semesterId);
        normal.setSubject("Data Structures");
        normal.setSubjectCode("UCS420"); // Subject code is set
        normal.setExamType("NORMAL");
        normal.setEvalName(null); // Missing eval_name
        normal.setCredits(3.0);
        normal.setEvalMarks(10.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            examDAO.insert(normal);
        });
        assertTrue(ex.getMessage().contains("Other evaluations require a name"));
    }

    @Test
    void testNormalExamAllowsNoEvalNameForBackwardCompatibility() throws SQLException {
        // For backward compatibility, NORMAL exams without subject_code don't require eval_name
        Exam normal = new Exam();
        normal.setSemesterId(semesterId);
        normal.setSubject("Data Structures");
        normal.setSubjectCode(null); // No subject code
        normal.setExamType("NORMAL");
        normal.setEvalName(null); // No eval_name - allowed for backward compatibility
        normal.setCredits(3.0);
        normal.setEvalMarks(10.0);

        // Should not throw exception
        int examId = examDAO.insert(normal);
        assertTrue(examId > 0);
    }

    @Test
    void testUniqueEvalNamePerSubject() throws SQLException {
        // Insert first evaluation
        Exam eval1 = new Exam();
        eval1.setSemesterId(semesterId);
        eval1.setSubject("Data Structures");
        eval1.setSubjectCode("UCS420");
        eval1.setExamType("NORMAL");
        eval1.setEvalName("Lab Viva");
        eval1.setCredits(2.0);
        eval1.setEvalMarks(5.0);
        examDAO.insert(eval1);

        // Try to insert second evaluation with same name (case-insensitive)
        Exam eval2 = new Exam();
        eval2.setSemesterId(semesterId);
        eval2.setSubject("Data Structures");
        eval2.setSubjectCode("UCS420");
        eval2.setExamType("NORMAL");
        eval2.setEvalName("LAB VIVA"); // Same name, different case
        eval2.setCredits(2.0);
        eval2.setEvalMarks(5.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            examDAO.insert(eval2);
        });
        assertTrue(ex.getMessage().contains("evaluation with this name already exists"));
    }

    @Test
    void testSameEvalNameInDifferentSubjects() throws SQLException {
        // Insert evaluation for first subject
        Exam eval1 = new Exam();
        eval1.setSemesterId(semesterId);
        eval1.setSubject("Data Structures");
        eval1.setSubjectCode("UCS420");
        eval1.setExamType("NORMAL");
        eval1.setEvalName("Quiz 1");
        eval1.setCredits(2.0);
        eval1.setEvalMarks(5.0);
        examDAO.insert(eval1);

        // Insert evaluation with same name for different subject - should succeed
        Exam eval2 = new Exam();
        eval2.setSemesterId(semesterId);
        eval2.setSubject("AI");
        eval2.setSubjectCode("UCS503");
        eval2.setExamType("NORMAL");
        eval2.setEvalName("Quiz 1");
        eval2.setCredits(2.0);
        eval2.setEvalMarks(5.0);

        assertDoesNotThrow(() -> examDAO.insert(eval2));
        
        List<Exam> exams = examDAO.findBySemester(semesterId);
        assertEquals(2, exams.size());
    }

    @Test
    void testFindBySubject() throws SQLException {
        // Create exams for same subject
        Exam mst = new Exam();
        mst.setSemesterId(semesterId);
        mst.setSubject("Data Structures");
        mst.setSubjectCode("UCS420");
        mst.setExamType("MST");
        mst.setCredits(4.0);
        mst.setEvalMarks(20.0);
        examDAO.insert(mst);

        Exam est = new Exam();
        est.setSemesterId(semesterId);
        est.setSubject("Data Structures");
        est.setSubjectCode("UCS420");
        est.setExamType("EST");
        est.setCredits(4.0);
        est.setEvalMarks(20.0);
        examDAO.insert(est);

        Exam eval1 = new Exam();
        eval1.setSemesterId(semesterId);
        eval1.setSubject("Data Structures");
        eval1.setSubjectCode("UCS420");
        eval1.setExamType("NORMAL");
        eval1.setEvalName("Lab Viva");
        eval1.setCredits(2.0);
        eval1.setEvalMarks(5.0);
        examDAO.insert(eval1);

        // Create exam for different subject
        Exam otherMst = new Exam();
        otherMst.setSemesterId(semesterId);
        otherMst.setSubject("AI");
        otherMst.setSubjectCode("UCS503");
        otherMst.setExamType("MST");
        otherMst.setCredits(4.0);
        otherMst.setEvalMarks(20.0);
        examDAO.insert(otherMst);

        // Find by subject
        List<Exam> ucs420Exams = examDAO.findBySubject(semesterId, "UCS420");
        assertEquals(3, ucs420Exams.size());
        
        // Verify order: MST, EST, then NORMAL
        assertEquals("MST", ucs420Exams.get(0).getExamType());
        assertEquals("EST", ucs420Exams.get(1).getExamType());
        assertEquals("NORMAL", ucs420Exams.get(2).getExamType());
    }

    @Test
    void testGetDisplayTitle() {
        Exam mst = new Exam();
        mst.setSubject("Data Structures");
        mst.setExamType("MST");
        assertEquals("Data Structures", mst.getDisplayTitle());

        Exam eval = new Exam();
        eval.setSubject("Data Structures");
        eval.setExamType("NORMAL");
        eval.setEvalName("Lab Viva");
        assertEquals("Data Structures - Lab Viva", eval.getDisplayTitle());
    }

    @Test
    void testUpdateValidation() throws SQLException {
        // Insert an exam
        Exam exam1 = new Exam();
        exam1.setSemesterId(semesterId);
        exam1.setSubject("Data Structures");
        exam1.setSubjectCode("UCS420");
        exam1.setExamType("MST");
        exam1.setCredits(4.0);
        exam1.setEvalMarks(20.0);
        int id1 = examDAO.insert(exam1);

        // Insert another exam for different subject
        Exam exam2 = new Exam();
        exam2.setSemesterId(semesterId);
        exam2.setSubject("AI");
        exam2.setSubjectCode("UCS503");
        exam2.setExamType("MST");
        exam2.setCredits(4.0);
        exam2.setEvalMarks(20.0);
        int id2 = examDAO.insert(exam2);

        // Try to update exam2 to have same subject and type as exam1
        Exam updated = examDAO.findById(id2);
        updated.setSubjectCode("UCS420");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            examDAO.update(updated);
        });
        assertTrue(ex.getMessage().contains("Only one MST allowed per subject"));
    }

    @Test
    void testUpdateOwnRecordDoesNotConflict() throws SQLException {
        // Insert an exam
        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Data Structures");
        exam.setSubjectCode("UCS420");
        exam.setExamType("MST");
        exam.setCredits(4.0);
        exam.setEvalMarks(20.0);
        exam.setWindowStartDate(LocalDate.of(2026, 11, 1));
        int id = examDAO.insert(exam);

        // Update the same exam - should not conflict with itself
        Exam updated = examDAO.findById(id);
        updated.setWindowEndDate(LocalDate.of(2026, 11, 10));

        assertDoesNotThrow(() -> examDAO.update(updated));
        
        Exam reloaded = examDAO.findById(id);
        assertEquals(LocalDate.of(2026, 11, 10), reloaded.getWindowEndDate());
    }

    @Test
    void testMultipleNormalExamsPlusMstEstOnOneSubject() throws SQLException {
        // Create MST for Data Structures
        Exam mst = new Exam();
        mst.setSemesterId(semesterId);
        mst.setSubject("Data Structures");
        mst.setSubjectCode("UCS420");
        mst.setExamType("MST");
        mst.setCredits(4.0);
        mst.setEvalMarks(20.0);
        examDAO.insert(mst);

        // Create EST for Data Structures
        Exam est = new Exam();
        est.setSemesterId(semesterId);
        est.setSubject("Data Structures");
        est.setSubjectCode("UCS420");
        est.setExamType("EST");
        est.setCredits(4.0);
        est.setEvalMarks(20.0);
        examDAO.insert(est);

        // Create first NORMAL exam for Data Structures
        Exam normal1 = new Exam();
        normal1.setSemesterId(semesterId);
        normal1.setSubject("Data Structures");
        normal1.setSubjectCode("UCS420");
        normal1.setExamType("NORMAL");
        normal1.setEvalName("Lab Viva");
        normal1.setCredits(2.0);
        normal1.setEvalMarks(5.0);
        normal1.setExamDate(LocalDate.of(2026, 10, 20));
        normal1.setExamStartTime(LocalTime.of(10, 0));
        normal1.setExamEndTime(LocalTime.of(11, 0));
        examDAO.insert(normal1);

        // Create second NORMAL exam for Data Structures
        Exam normal2 = new Exam();
        normal2.setSemesterId(semesterId);
        normal2.setSubject("Data Structures");
        normal2.setSubjectCode("UCS420");
        normal2.setExamType("NORMAL");
        normal2.setEvalName("Quiz 1");
        normal2.setCredits(1.0);
        normal2.setEvalMarks(5.0);
        normal2.setExamDate(LocalDate.of(2026, 10, 15));
        normal2.setExamStartTime(LocalTime.of(9, 0));
        normal2.setExamEndTime(LocalTime.of(10, 0));
        examDAO.insert(normal2);

        // Verify all 4 exams are returned for this subject
        List<Exam> subjectExams = examDAO.findBySubject(semesterId, "UCS420");
        assertEquals(4, subjectExams.size());

        // Verify ordering: MST, EST, then NORMAL (sorted by eval_name)
        assertEquals("MST", subjectExams.get(0).getExamType());
        assertEquals("EST", subjectExams.get(1).getExamType());
        assertEquals("NORMAL", subjectExams.get(2).getExamType());
        assertEquals("NORMAL", subjectExams.get(3).getExamType());

        // Verify unique eval names
        assertTrue(subjectExams.get(2).getEvalName().equals("Lab Viva") || 
                   subjectExams.get(2).getEvalName().equals("Quiz 1"));
        assertTrue(subjectExams.get(3).getEvalName().equals("Lab Viva") || 
                   subjectExams.get(3).getEvalName().equals("Quiz 1"));
        assertNotEquals(subjectExams.get(2).getEvalName(), subjectExams.get(3).getEvalName());

        // Verify display titles
        assertEquals("Data Structures", mst.getDisplayTitle());
        assertEquals("Data Structures", est.getDisplayTitle());
        assertEquals("Data Structures - Lab Viva", normal1.getDisplayTitle());
        assertEquals("Data Structures - Quiz 1", normal2.getDisplayTitle());
    }
}
