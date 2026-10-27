package com.learntrack.dao;

import com.learntrack.model.Semester;
import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.SyllabusUnit;
import com.learntrack.model.Topic;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for difficulty update functionality in SyllabusDAO.
 */
public class SyllabusDifficultyUpdateTest {

    private static final String TEST_DB_PATH = "test_syllabus_difficulty.db";

    private SyllabusDAO syllabusDAO;
    private TopicDAO topicDAO;
    private SemesterDAO semesterDAO;
    private ExamDAO examDAO;

    @BeforeEach
    void setUp() throws SQLException {
        // Use temporary test database
        new java.io.File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        syllabusDAO = new SyllabusDAO();
        topicDAO = new TopicDAO();
        semesterDAO = new SemesterDAO();
        examDAO = new ExamDAO();
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new java.io.File(TEST_DB_PATH).delete();
    }

    @Test
    void testBulkUpdateSetsAllSubtopicsInUnit() throws SQLException {
        // Setup semester
        Semester sem = new Semester(null, "Fall 2024", "ACTIVE");
        semesterDAO.insert(sem);
        int semesterId = sem.getSemesterId();

        // Create unit with subtopics
        int unitId = syllabusDAO.insertUnit(semesterId, "MATH101", "Unit 1", 0);
        int sub1 = syllabusDAO.insertSubtopic(unitId, "Subtopic A", 0);
        int sub2 = syllabusDAO.insertSubtopic(unitId, "Subtopic B", 1);
        int sub3 = syllabusDAO.insertSubtopic(unitId, "Subtopic C", 2);

        // Initial difficulties vary
        syllabusDAO.updateSubtopicDifficulty(sub1, 3);
        syllabusDAO.updateSubtopicDifficulty(sub2, 5);
        syllabusDAO.updateSubtopicDifficulty(sub3, 7);

        // Bulk update all to 8
        List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(unitId);
        for (SyllabusSubtopic subtopic : subtopics) {
            syllabusDAO.updateSubtopicDifficulty(subtopic.getSubtopicId(), 8);
        }

        // Verify all are now 8
        subtopics = syllabusDAO.findSubtopicsByUnit(unitId);
        assertEquals(3, subtopics.size());
        for (SyllabusSubtopic subtopic : subtopics) {
            assertEquals(8, subtopic.getDifficulty(), "All subtopics should be difficulty 8");
        }
    }

    @Test
    void testUpdateTouchesOnlyUntouchedLinkedTopics() throws SQLException {
        // Setup semester and exam
        Semester sem = new Semester(null, "Fall 2024", "ACTIVE");
        semesterDAO.insert(sem);
        int semesterId = sem.getSemesterId();

        com.learntrack.model.Exam exam = new com.learntrack.model.Exam(
                null, semesterId, "Math", "MATH101", "MST", null,
                3.0, 20.0, null, null, null, 30, null, null, 10.0
        );
        examDAO.insert(exam);
        int examId = exam.getExamId();

        // Create subtopic
        int unitId = syllabusDAO.insertUnit(semesterId, "MATH101", "Unit 1", 0);
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Subtopic A", 0);
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 5);

        // Create topics linked to subtopic
        // Topic 1: untouched (remaining_hours == hours_needed AND phase == LEARNING)
        Topic topic1 = new Topic(null, examId, "Topic 1", 5, 3, 4.0, 4.0, "LEARNING", 1.0, 1.0, 7, null, 1, subtopicId);
        topicDAO.insert(topic1);

        // Topic 2: touched (remaining_hours < hours_needed)
        Topic topic2 = new Topic(null, examId, "Topic 2", 5, 3, 4.0, 2.0, "LEARNING", 1.0, 0.5, 7, null, 1, subtopicId);
        topicDAO.insert(topic2);

        // Topic 3: not in LEARNING phase
        Topic topic3 = new Topic(null, examId, "Topic 3", 5, 3, 4.0, 4.0, "REVISION", 1.0, 1.0, 7, null, 1, subtopicId);
        topicDAO.insert(topic3);

        // Update subtopic difficulty from 5 to 8
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 8);
        syllabusDAO.updateLinkedTopicDifficulties(subtopicId, 8);

        // Verify: only topic1 should be updated (untouched + LEARNING)
        Topic t1 = topicDAO.findById(topic1.getTopicId());
        Topic t2 = topicDAO.findById(topic2.getTopicId());
        Topic t3 = topicDAO.findById(topic3.getTopicId());

        // Map difficulty 8 -> topic difficulty: ceil(8/2.0) = 4
        int expectedTopicDifficulty = com.learntrack.service.DifficultyMapper.toTopicDifficulty(8);
        assertEquals(4, expectedTopicDifficulty, "Mapped difficulty should be 4");

        assertEquals(expectedTopicDifficulty, t1.getDifficulty(), "Untouched LEARNING topic should be updated to 4");
        assertEquals(3, t2.getDifficulty(), "Touched topic should stay at 3");
        assertEquals(3, t3.getDifficulty(), "Non-LEARNING topic should stay at 3");
    }

    @Test
    void testDifficultyClamped1To10() throws SQLException {
        // Setup
        Semester sem = new Semester(null, "Fall 2024", "ACTIVE");
        semesterDAO.insert(sem);
        int semesterId = sem.getSemesterId();

        int unitId = syllabusDAO.insertUnit(semesterId, "MATH101", "Unit 1", 0);
        int subtopicId = syllabusDAO.insertSubtopic(unitId, "Subtopic A", 0);

        // Try to set difficulty below 1
        syllabusDAO.updateSubtopicDifficulty(subtopicId, -5);
        SyllabusSubtopic subtopic = syllabusDAO.findSubtopicsByUnit(unitId).get(0);
        assertEquals(1, subtopic.getDifficulty(), "Difficulty should be clamped to 1");

        // Try to set difficulty above 10
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 15);
        subtopic = syllabusDAO.findSubtopicsByUnit(unitId).get(0);
        assertEquals(10, subtopic.getDifficulty(), "Difficulty should be clamped to 10");

        // Valid value should work
        syllabusDAO.updateSubtopicDifficulty(subtopicId, 7);
        subtopic = syllabusDAO.findSubtopicsByUnit(unitId).get(0);
        assertEquals(7, subtopic.getDifficulty(), "Valid difficulty should be stored");
    }

    @Test
    void testMultipleSubtopicsCanHaveDifferentDifficulties() throws SQLException {
        // Setup
        Semester sem = new Semester(null, "Fall 2024", "ACTIVE");
        semesterDAO.insert(sem);
        int semesterId = sem.getSemesterId();

        int unitId = syllabusDAO.insertUnit(semesterId, "MATH101", "Unit 1", 0);
        int sub1 = syllabusDAO.insertSubtopic(unitId, "Easy topic", 0);
        int sub2 = syllabusDAO.insertSubtopic(unitId, "Hard topic", 1);

        // Set different difficulties
        syllabusDAO.updateSubtopicDifficulty(sub1, 2);
        syllabusDAO.updateSubtopicDifficulty(sub2, 9);

        // Verify they remain independent
        List<SyllabusSubtopic> subtopics = syllabusDAO.findSubtopicsByUnit(unitId);
        assertEquals(2, subtopics.get(0).getDifficulty());
        assertEquals(9, subtopics.get(1).getDifficulty());
    }
}
