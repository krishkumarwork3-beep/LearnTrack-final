package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamScopeDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.dao.SyllabusDAO;
import com.learntrack.dao.TopicDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.Topic;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hours edited on a syllabus subtopic are the source of truth for the planner. Rebuilding
 * or re-syncing the study plan copies them onto the topic; it must never write back over
 * what the user typed.
 */
public class SyllabusHoursSurviveSyncTest {

    private static final String TEST_DB_PATH = "target/test_hours_survive_sync.db";

    private ExamScopeService scopeService;
    private SyllabusDAO syllabusDAO;
    private TopicDAO topicDAO;
    private int semesterId;
    private int examId;
    private int subtopicId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        SemesterDAO semesterDAO = new SemesterDAO();
        Semester semester = new Semester();
        semester.setName("Hours semester");
        semester.setStatus("ACTIVE");
        semesterId = semesterDAO.insert(semester);

        ExamDAO examDAO = new ExamDAO();
        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Operating Systems");
        exam.setSubjectCode("UCS501");
        exam.setExamType("MST");
        exam.setCredits(4.0);
        exam.setEvalMarks(20.0);
        examId = examDAO.insert(exam);

        syllabusDAO = new SyllabusDAO();
        topicDAO = new TopicDAO();
        scopeService = new ExamScopeService(new ExamScopeDAO(), topicDAO, syllabusDAO, examDAO);

        int unitId = syllabusDAO.insertUnit(semesterId, "UCS501", "Processes", 0);
        subtopicId = syllabusDAO.insertSubtopic(unitId, "Scheduling", 0);
        scopeService.saveScopeAndSyncTopics(examId, Set.of(subtopicId));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void editingSubtopicHoursSurvivesTopicResync() throws SQLException {
        syllabusDAO.updateSubtopicHoursToCover(subtopicId, 3.5);
        syllabusDAO.updateSubtopicHoursToRevise(subtopicId, 1.25);

        scopeService.syncTopicPhasesForSubject(semesterId, "UCS501");

        SyllabusSubtopic afterSync = syllabusDAO.findSubtopicById(subtopicId);
        assertEquals(3.5, afterSync.getHoursToCover(), 1e-9);
        assertEquals(1.25, afterSync.getHoursToRevise(), 1e-9);

        List<Topic> topics = topicDAO.findByExam(examId);
        assertEquals(1, topics.size());
        assertEquals(3.5, topics.get(0).getHoursNeeded(), 1e-9,
                "the sync copies the edited hours onto the topic");
    }

    @Test
    void rebuildStudyPlanDoesNotResetSubtopicHours() throws SQLException {
        syllabusDAO.updateSubtopicHoursToCover(subtopicId, 2.25);
        syllabusDAO.updateSubtopicHoursToRevise(subtopicId, 0.5);

        scopeService.rebuildPlan(semesterId);

        SyllabusSubtopic afterRebuild = syllabusDAO.findSubtopicById(subtopicId);
        assertEquals(2.25, afterRebuild.getHoursToCover(), 1e-9);
        assertEquals(0.5, afterRebuild.getHoursToRevise(), 1e-9);
    }
}
