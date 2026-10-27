package com.learntrack.dao;

import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.model.Topic;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TopicDAOTest {

    private TopicDAO topicDAO;
    private ExamDAO examDAO;
    private final String testDbPath = "target/test_topic.db";
    private int examId;
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        SemesterDAO semesterDAO = new SemesterDAO();
        examDAO = new ExamDAO();
        topicDAO = new TopicDAO();
        semesterId = semesterDAO.insert(new Semester(null, "Test Semester", "ACTIVE"));
        Exam exam = new Exam(null, semesterId, "Algorithms", "NORMAL", 4, 25.0,
                LocalDate.of(2026, 10, 20), null, null, 30, null, null, 2.0);
        examId = examDAO.insert(exam);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testTopicCRUDAndBatchFlag() throws SQLException {
        Topic t1 = new Topic(null, examId, "Sorting", 5, 3, 6.0, 6.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int id1 = topicDAO.insert(t1);
        assertTrue(id1 > 0);

        Topic t2 = new Topic(null, examId, "Dynamic Programming", 5, 5, 10.0, 10.0, "LEARNING", 3.0, 3.0, 7, null, 1);
        int id2 = topicDAO.insert(t2);
        assertTrue(id2 > 0);

        List<Topic> topics = topicDAO.findByExam(examId);
        assertEquals(2, topics.size());

        // Test updating important_flag for batch
        topicDAO.setImportantFlagForBatch(semesterId, "NORMAL", 0);
        Topic fetched = topicDAO.findById(id1);
        assertEquals(0, fetched.getImportantFlag());

        topicDAO.updateImportantFlag(id1, 1);
        assertEquals(1, topicDAO.findById(id1).getImportantFlag());
    }

    @Test
    void testDeleteRemovesOnlyTopicLogsNotSkillLogs() throws SQLException {
        DailyLogDAO dailyLogDAO = new DailyLogDAO();
        SkillDAO skillDAO = new SkillDAO();

        Topic topic = new Topic(null, examId, "Greedy", 4, 3, 4.0, 4.0, "LEARNING", 1.0, 1.0, 7, null, 1);
        int topicId = topicDAO.insert(topic);

        com.learntrack.model.Skill skill = new com.learntrack.model.Skill(null, "Typing", "General", 3, LocalDate.now());
        int skillId = skillDAO.insert(skill);

        com.learntrack.model.DailyLogEntry logLearn = new com.learntrack.model.DailyLogEntry(null, LocalDate.now(), "TOPIC_LEARN", topicId,
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 0), "PENDING");
        int logLearnId = dailyLogDAO.insert(logLearn);

        com.learntrack.model.DailyLogEntry logRevise = new com.learntrack.model.DailyLogEntry(null, LocalDate.now(), "TOPIC_REVISE", topicId,
                java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), "PENDING");
        int logReviseId = dailyLogDAO.insert(logRevise);

        com.learntrack.model.DailyLogEntry logSkill = new com.learntrack.model.DailyLogEntry(null, LocalDate.now(), "SKILL", skillId,
                java.time.LocalTime.of(11, 0), java.time.LocalTime.of(12, 0), "PENDING");
        int logSkillId = dailyLogDAO.insert(logSkill);

        // Delete topic
        topicDAO.delete(topicId);

        // Topic should be deleted
        assertNull(topicDAO.findById(topicId));

        // Topic logs should be deleted
        assertNull(dailyLogDAO.findById(logLearnId));
        assertNull(dailyLogDAO.findById(logReviseId));

        // Skill log must remain intact (Plan Section 12)
        assertNotNull(dailyLogDAO.findById(logSkillId));
    }
}
