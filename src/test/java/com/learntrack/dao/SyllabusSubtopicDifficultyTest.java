package com.learntrack.dao;

import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.Semester;
import com.learntrack.model.Topic;
import com.learntrack.util.DBConnection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for syllabus subtopic difficulty feature.
 */
public class SyllabusSubtopicDifficultyTest {

    private static final String TEST_DB_PATH = "test_syllabus_difficulty.db";
    private SyllabusDAO syllabusDAO;
    private TopicDAO topicDAO;
    private ExamDAO examDAO;
    private int semesterId;
    private int unitId;

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

        syllabusDAO = new SyllabusDAO();
        topicDAO = new TopicDAO();
        examDAO = new ExamDAO();

        unitId = syllabusDAO.insertUnit(semesterId, "UCS420", "Test Unit", 0);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void testMigrationIdempotent() throws SQLException {
        // initSchema already runs the migration in setUp
        // Running initSchema again should be idempotent
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
            // Should not throw exception
        }
    }

    @Test
    void testDifficultyRoundTrip() throws SQLException {
        // Insert with difficulty 7
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Test Subtopic", 0, 7);

        // Retrieve
        SyllabusSubtopic subtopic = syllabusDAO.findSubtopicById(subtopicId);
        assertNotNull(subtopic);
        assertEquals(7, subtopic.getDifficulty());
        assertEquals("Test Subtopic", subtopic.getName());
    }

    @Test
    void testDefaultDifficulty() throws SQLException {
        // Insert without difficulty (uses overload with default)
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Default Subtopic", 0);

        // Should default to 5
        SyllabusSubtopic subtopic = syllabusDAO.findSubtopicById(subtopicId);
        assertNotNull(subtopic);
        assertEquals(5, subtopic.getDifficulty());
    }

    @Test
    void testUpdateDifficulty() throws SQLException {
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Test Subtopic", 0, 3);

        // Update difficulty
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 9);

        // Verify
        SyllabusSubtopic subtopic = syllabusDAO.findSubtopicById(subtopicId);
        assertEquals(9, subtopic.getDifficulty());
    }

    @Test
    void testDifficultyClampingInDAO() throws SQLException {
        // Insert with out-of-range values
        int subtopicId1 = syllabusDAO.insertSubtopic(unitId, "Low", 0, -5);
        int subtopicId2 = syllabusDAO.insertSubtopic(unitId, "High", 1, 100);

        // Should be clamped
        assertEquals(1, syllabusDAO.findSubtopicById(subtopicId1).getDifficulty());
        assertEquals(10, syllabusDAO.findSubtopicById(subtopicId2).getDifficulty());
    }

    @Test
    void testUpdateLinkedTopicDifficultiesForUntouchedTopics() throws SQLException {
        // Create exam
        var exam = new com.learntrack.model.Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Data Structures");
        exam.setSubjectCode("UCS420");
        exam.setExamType("MST");
        exam.setCredits(4.0);
        exam.setEvalMarks(20.0);
        int examId = examDAO.insert(exam);

        // Create subtopic with difficulty 4
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Subtopic", 0, 4);

        // Create untouched topic linked to this subtopic (LEARNING phase, remaining == needed)
        Topic topic = new Topic(null, examId, "Topic", 4, 2, 10.0, 10.0, "LEARNING",
                                1.5, 1.5, 7, null, 1, subtopicId);
        int topicId = topicDAO.insert(topic);

        // Update subtopic difficulty to 8
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 8);
        syllabusDAO.updateLinkedTopicDifficulties(subtopicId, 8);

        // Topic difficulty should be updated to toTopicDifficulty(8) = 4
        Topic updated = topicDAO.findById(topicId);
        assertEquals(4, updated.getDifficulty());
    }

    @Test
    void testUpdateLinkedTopicDifficultiesIgnoresTouchedTopics() throws SQLException {
        // Create exam
        var exam = new com.learntrack.model.Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Data Structures");
        exam.setSubjectCode("UCS420");
        exam.setExamType("MST");
        exam.setCredits(4.0);
        exam.setEvalMarks(20.0);
        int examId = examDAO.insert(exam);

        // Create subtopic with difficulty 4
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Subtopic", 0, 4);

        // Create touched topic (remaining != needed)
        Topic topic = new Topic(null, examId, "Topic", 4, 2, 10.0, 5.0, "LEARNING",
                                1.5, 1.5, 7, null, 1, subtopicId);
        int topicId = topicDAO.insert(topic);

        // Update subtopic difficulty to 8
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 8);
        syllabusDAO.updateLinkedTopicDifficulties(subtopicId, 8);

        // Topic difficulty should NOT be updated (still 2)
        Topic updated = topicDAO.findById(topicId);
        assertEquals(2, updated.getDifficulty());
    }

    @Test
    void testUpdateLinkedTopicDifficultiesIgnoresRevisingTopics() throws SQLException {
        // Create exam
        var exam = new com.learntrack.model.Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Data Structures");
        exam.setSubjectCode("UCS420");
        exam.setExamType("MST");
        exam.setCredits(4.0);
        exam.setEvalMarks(20.0);
        int examId = examDAO.insert(exam);

        // Create subtopic with difficulty 4
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Subtopic", 0, 4);

        // Create topic in REVISING phase
        Topic topic = new Topic(null, examId, "Topic", 4, 2, 10.0, 10.0, "REVISING",
                                1.5, 1.5, 7, null, 1, subtopicId);
        int topicId = topicDAO.insert(topic);

        // Update subtopic difficulty to 8
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 8);
        syllabusDAO.updateLinkedTopicDifficulties(subtopicId, 8);

        // Topic difficulty should NOT be updated (still 2)
        Topic updated = topicDAO.findById(topicId);
        assertEquals(2, updated.getDifficulty());
    }

    @Test
    void testFindSubtopicsByUnitIncludesDifficulty() throws SQLException {
        syllabusDAO.insertSubtopic(unitId, "Subtopic 1", 0, 3);
        syllabusDAO.insertSubtopic(unitId, "Subtopic 2", 1, 7);
        syllabusDAO.insertSubtopic(unitId, "Subtopic 3", 2, 10);

        List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(unitId);
        assertEquals(3, subtopics.size());
        assertEquals(3, subtopics.get(0).getDifficulty());
        assertEquals(7, subtopics.get(1).getDifficulty());
        assertEquals(10, subtopics.get(2).getDifficulty());
    }
}
