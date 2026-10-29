package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.model.Topic;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class StudyProgressServiceTest {

    private static final String DB = "target/test_study_progress.db";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);
    private TopicDAO topicDAO;
    private SyllabusDAO syllabusDAO;
    private StudyProgressService service;
    private int topicId;
    private int subtopicId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(DB).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + DB);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        int semesterId = new SemesterDAO().insert(new Semester(null, "Odd 2026", "ACTIVE"));
        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Java");
        exam.setSubjectCode("UCS539");
        exam.setExamType("EST");
        int examId = new ExamDAO().insert(exam);

        syllabusDAO = new SyllabusDAO();
        topicDAO = new TopicDAO();
        int unitId = syllabusDAO.insertUnit(semesterId, "UCS539", "Java platform", 0);
        subtopicId = syllabusDAO.insertSubtopic(unitId, "JVM", 0, 5, 2.0, 0.5);
        topicId = topicDAO.insert(new Topic(null, examId, "JVM", 3, 1, 2.0, 2.0, "LEARNING", 0.5, 0.5, 7, null, 1, subtopicId));
        service = new StudyProgressService(topicDAO, syllabusDAO);
    }

    @Test
    void firstHalfOfASplitTopicDoesNotFinishIt() throws SQLException {
        service.completeLearnSession(topicId, 1.0, DAY);
        Topic t = topicDAO.findById(topicId);
        assertEquals("LEARNING", t.getPhase());
        assertEquals(1.0, t.getRemainingHours(), 1e-9);
        assertFalse(syllabusDAO.findSubtopicById(subtopicId).isDone());
    }

    @Test
    void lastSessionLearnsTheTopicAndTicksTheSyllabus() throws SQLException {
        service.completeLearnSession(topicId, 1.0, DAY);
        service.completeLearnSession(topicId, 1.0, DAY);
        assertEquals("REVISION", topicDAO.findById(topicId).getPhase());
        assertTrue(syllabusDAO.findSubtopicById(subtopicId).isDone(), "the syllabus box is ticked");
    }

    @Test
    void untickingGivesHoursBackAndUnticksTheSyllabus() throws SQLException {
        service.finishTopic(topicId, DAY);
        service.undoLearnSession(topicId, 1.0);
        Topic t = topicDAO.findById(topicId);
        assertEquals("LEARNING", t.getPhase());
        assertEquals(1.0, t.getRemainingHours(), 1e-9);
        assertFalse(syllabusDAO.findSubtopicById(subtopicId).isDone());
    }
}
