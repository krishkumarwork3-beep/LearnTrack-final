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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamScopeDAO.
 */
public class ExamScopeDAOTest {

    private static final String TEST_DB_PATH = "test_exam_scope_dao.db";
    private ExamScopeDAO examScopeDAO;
    private ExamDAO examDAO;
    private SyllabusDAO syllabusDAO;
    private int semesterId;
    private int examId1;
    private int examId2;
    private int unitId1;
    private int unitId2;
    private int subtopicId1;
    private int subtopicId2;
    private int subtopicId3;
    private int subtopicId4;

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
        examScopeDAO = new ExamScopeDAO();
        syllabusDAO = new SyllabusDAO();

        // Create exams
        Exam exam1 = new Exam();
        exam1.setSemesterId(semesterId);
        exam1.setSubject("Data Structures");
        exam1.setSubjectCode("UCS420");
        exam1.setExamType("MST");
        exam1.setCredits(4.0);
        exam1.setEvalMarks(20.0);
        examId1 = examDAO.insert(exam1);

        Exam exam2 = new Exam();
        exam2.setSemesterId(semesterId);
        exam2.setSubject("Data Structures");
        exam2.setSubjectCode("UCS420");
        exam2.setExamType("EST");
        exam2.setCredits(4.0);
        exam2.setEvalMarks(20.0);
        examId2 = examDAO.insert(exam2);

        // Create syllabus
        unitId1 = syllabusDAO.insertUnit(semesterId, "UCS420", "Arrays and Lists", 0);
        unitId2 = syllabusDAO.insertUnit(semesterId, "UCS420", "Trees and Graphs", 1);

        subtopicId1 = syllabusDAO.insertSubtopic(unitId1, "Introduction to Arrays", 0);
        subtopicId2 = syllabusDAO.insertSubtopic(unitId1, "Dynamic Arrays", 1);
        subtopicId3 = syllabusDAO.insertSubtopic(unitId2, "Binary Trees", 0);
        subtopicId4 = syllabusDAO.insertSubtopic(unitId2, "Graph Traversal", 1);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void testReplaceScope() throws SQLException {
        // Initially empty
        Set<Integer> scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertTrue(scope.isEmpty());

        // Add scope
        Set<Integer> newScope = Set.of(subtopicId1, subtopicId2);
        examScopeDAO.replaceScope(examId1, newScope);

        // Verify
        scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertEquals(2, scope.size());
        assertTrue(scope.contains(subtopicId1));
        assertTrue(scope.contains(subtopicId2));

        // Replace with different scope
        Set<Integer> updatedScope = Set.of(subtopicId3, subtopicId4);
        examScopeDAO.replaceScope(examId1, updatedScope);

        // Verify old scope is gone
        scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertEquals(2, scope.size());
        assertTrue(scope.contains(subtopicId3));
        assertTrue(scope.contains(subtopicId4));
        assertFalse(scope.contains(subtopicId1));
        assertFalse(scope.contains(subtopicId2));
    }

    @Test
    void testCopyScope() throws SQLException {
        // Set scope for exam1
        Set<Integer> scope1 = Set.of(subtopicId1, subtopicId3);
        examScopeDAO.replaceScope(examId1, scope1);

        // Copy to exam2
        examScopeDAO.copyScope(examId1, examId2);

        // Verify exam2 has same scope
        Set<Integer> scope2 = examScopeDAO.findSubtopicIdsForExam(examId2);
        assertEquals(2, scope2.size());
        assertTrue(scope2.contains(subtopicId1));
        assertTrue(scope2.contains(subtopicId3));

        // Verify they are independent
        examScopeDAO.replaceScope(examId1, Set.of(subtopicId2));
        scope2 = examScopeDAO.findSubtopicIdsForExam(examId2);
        assertEquals(2, scope2.size()); // exam2 unchanged
    }

    @Test
    void testDeleteScope() throws SQLException {
        // Add scope
        Set<Integer> scope = Set.of(subtopicId1, subtopicId2, subtopicId3);
        examScopeDAO.replaceScope(examId1, scope);

        // Delete
        examScopeDAO.deleteScope(examId1);

        // Verify empty
        scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertTrue(scope.isEmpty());
    }

    @Test
    void testCascadeOnExamDelete() throws SQLException {
        // Add scope
        Set<Integer> scope = Set.of(subtopicId1, subtopicId2);
        examScopeDAO.replaceScope(examId1, scope);

        // Verify scope exists
        assertEquals(2, examScopeDAO.findSubtopicIdsForExam(examId1).size());

        // Delete exam (should cascade to scope)
        examDAO.delete(examId1);

        // Verify scope is automatically deleted
        scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertTrue(scope.isEmpty());
    }

    @Test
    void testCascadeOnSubtopicDelete() throws SQLException {
        // Add scope including subtopicId1
        Set<Integer> scope = Set.of(subtopicId1, subtopicId2, subtopicId3);
        examScopeDAO.replaceScope(examId1, scope);

        // Delete subtopic1 (should cascade from scope)
        syllabusDAO.deleteSubtopic(subtopicId1);

        // Verify subtopicId1 removed from scope
        scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertEquals(2, scope.size());
        assertFalse(scope.contains(subtopicId1));
        assertTrue(scope.contains(subtopicId2));
        assertTrue(scope.contains(subtopicId3));
    }

    @Test
    void testCascadeOnUnitDelete() throws SQLException {
        // Add scope including subtopics from unit1
        Set<Integer> scope = Set.of(subtopicId1, subtopicId2, subtopicId3);
        examScopeDAO.replaceScope(examId1, scope);

        // Delete unit1 (should cascade to its subtopics and their scope entries)
        syllabusDAO.deleteUnit(unitId1);

        // Verify only subtopicId3 (from unit2) remains
        scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertEquals(1, scope.size());
        assertTrue(scope.contains(subtopicId3));
    }

    @Test
    void testCountForExam() throws SQLException {
        assertEquals(0, examScopeDAO.countForExam(examId1));

        examScopeDAO.replaceScope(examId1, Set.of(subtopicId1, subtopicId2));
        assertEquals(2, examScopeDAO.countForExam(examId1));

        examScopeDAO.replaceScope(examId1, Set.of(subtopicId1, subtopicId2, subtopicId3, subtopicId4));
        assertEquals(4, examScopeDAO.countForExam(examId1));
    }

    @Test
    void testEmptyScope() throws SQLException {
        // Replace with empty set
        examScopeDAO.replaceScope(examId1, Set.of());

        Set<Integer> scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertTrue(scope.isEmpty());
        assertEquals(0, examScopeDAO.countForExam(examId1));
    }

    @Test
    void testMultipleExamsIndependentScopes() throws SQLException {
        // Set different scopes for two exams
        examScopeDAO.replaceScope(examId1, Set.of(subtopicId1, subtopicId2));
        examScopeDAO.replaceScope(examId2, Set.of(subtopicId3, subtopicId4));

        // Verify independence
        Set<Integer> scope1 = examScopeDAO.findSubtopicIdsForExam(examId1);
        Set<Integer> scope2 = examScopeDAO.findSubtopicIdsForExam(examId2);

        assertEquals(2, scope1.size());
        assertEquals(2, scope2.size());
        assertTrue(scope1.contains(subtopicId1));
        assertFalse(scope1.contains(subtopicId3));
        assertTrue(scope2.contains(subtopicId3));
        assertFalse(scope2.contains(subtopicId1));
    }

    @Test
    void testPartialUnitSelection() throws SQLException {
        // Create a unit with multiple subtopics
        int unitId = syllabusDAO.insertUnit(semesterId, "UCS420", "Advanced Topics", 0);
        int sub1 = syllabusDAO.insertSubtopic(unitId, "Graph Algorithms", 0);
        int sub2 = syllabusDAO.insertSubtopic(unitId, "Dynamic Programming", 1);
        int sub3 = syllabusDAO.insertSubtopic(unitId, "Greedy Algorithms", 2);

        // Select all three subtopics initially
        Set<Integer> fullSelection = Set.of(sub1, sub2, sub3);
        examScopeDAO.replaceScope(examId1, fullSelection);

        // Verify all three are selected
        Set<Integer> scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertEquals(3, scope.size());
        assertTrue(scope.containsAll(fullSelection));

        // Deselect one subtopic (partial unit selection)
        Set<Integer> partialSelection = Set.of(sub1, sub3); // Exclude sub2
        examScopeDAO.replaceScope(examId1, partialSelection);

        // Reload and verify partial selection persists
        scope = examScopeDAO.findSubtopicIdsForExam(examId1);
        assertEquals(2, scope.size());
        assertTrue(scope.contains(sub1));
        assertFalse(scope.contains(sub2)); // This one was deselected
        assertTrue(scope.contains(sub3));

        // Count should match
        assertEquals(2, examScopeDAO.countForExam(examId1));
    }
}
