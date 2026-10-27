package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamScopeService.getTopicCoverage(), the single source of truth for badge logic.
 *
 * Scenarios:
 *  1. Topic only in EST (no earlier exam) → NOT_STUDIED, no badge
 *  2. Topic in MST (past) and EST → COVERED_IN_PAST_EXAM, badge names MST
 *  3. Topic in upcoming MST and EST → SHARED_WITH_EARLIER_EXAM, badge "Learn for MST…"
 *  4. Topic marked "Already studied" (isDone=true) → DONE_BY_USER, green badge
 *  5. Topic in no exam scope → NOT_STUDIED, no badge
 */
public class TopicCoverageTest {

    private static final String TEST_DB_PATH = "test_topic_coverage.db";
    private ExamScopeService service;
    private ExamScopeDAO examScopeDAO;
    private SyllabusDAO syllabusDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;

    private int semesterId;
    private int mstId;  // past exam
    private int estId;  // future exam

    private int subOnlyEst;       // only in EST scope
    private int subMstAndEst;     // in both MST and EST; MST is past
    private int subFutureMstEst;  // in both future-MST and EST
    private int subDoneByUser;    // isDone=true
    private int subNoScope;       // not added to any exam scope

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @BeforeEach
    void setUp() throws SQLException {
        new File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        SemesterDAO semesterDAO = new SemesterDAO();
        Semester semester = new Semester();
        semester.setName("Test Sem");
        semester.setStatus("ACTIVE");
        semesterId = semesterDAO.insert(semester);

        examDAO = new ExamDAO();
        examScopeDAO = new ExamScopeDAO();
        syllabusDAO = new SyllabusDAO();
        topicDAO = new TopicDAO();
        service = new ExamScopeService(examScopeDAO, topicDAO, syllabusDAO, examDAO);

        // MST: past (13 days ago)
        Exam mst = new Exam();
        mst.setSemesterId(semesterId);
        mst.setSubjectCode("UCS101");
        mst.setSubject("UCS101");
        mst.setExamType("MST");
        mst.setEvalName(null);
        mst.setCredits(3.0);
        mst.setExamDate(TODAY.minusDays(13));
        mstId = examDAO.insert(mst);

        // EST: future (38 days from now)
        Exam est = new Exam();
        est.setSemesterId(semesterId);
        est.setSubjectCode("UCS101");
        est.setSubject("UCS101");
        est.setExamType("EST");
        est.setEvalName(null);
        est.setCredits(3.0);
        est.setExamDate(TODAY.plusDays(38));
        estId = examDAO.insert(est);

        // Syllabus units & subtopics
        int unitId = syllabusDAO.insertUnit(semesterId, "UCS101", "Unit 1", 0);
        subOnlyEst       = syllabusDAO.insertSubtopic(unitId, "Only in EST", 0);
        subMstAndEst     = syllabusDAO.insertSubtopic(unitId, "In MST and EST", 1);
        subFutureMstEst  = syllabusDAO.insertSubtopic(unitId, "Future Quiz and EST", 2);
        subDoneByUser    = syllabusDAO.insertSubtopic(unitId, "Done by user", 3);
        subNoScope       = syllabusDAO.insertSubtopic(unitId, "Not in any scope", 4);

        // Mark subDoneByUser as done
        syllabusDAO.updateSubtopicDone(subDoneByUser, true);

        // Future evaluation (Quiz 1) for subFutureMstEst test
        Exam futureQuiz = new Exam();
        futureQuiz.setSemesterId(semesterId);
        futureQuiz.setSubjectCode("UCS101");
        futureQuiz.setSubject("UCS101");
        futureQuiz.setExamType("QUIZ");
        futureQuiz.setEvalName("Quiz 1");
        futureQuiz.setCredits(3.0);
        futureQuiz.setExamDate(TODAY.plusDays(10)); // upcoming Quiz
        int futureQuizId = examDAO.insert(futureQuiz);

        // Scope assignments
        // subOnlyEst: only in EST
        addScope(estId, subOnlyEst);

        // subMstAndEst: in past MST and EST
        addScope(mstId, subMstAndEst);
        addScope(estId, subMstAndEst);

        // subFutureMstEst: in future Quiz 1 and EST
        addScope(futureQuizId, subFutureMstEst);
        addScope(estId, subFutureMstEst);

        // subDoneByUser: in EST only (but isDone=true)
        addScope(estId, subDoneByUser);

        // subNoScope: not added to any exam
    }

    private void addScope(int examId, int subtopicId) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            String sql = "INSERT OR IGNORE INTO exam_syllabus_scope (exam_id, subtopic_id) VALUES (?, ?)";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setInt(1, examId);
                ps.setInt(2, subtopicId);
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    /**
     * Topic only in EST scope (no earlier exam) → NOT_STUDIED, no badge.
     */
    @Test
    void topicOnlyInEst_returnsNotStudied() {
        ExamScopeService.TopicCoverage cov = service.getTopicCoverage(semesterId, "UCS101", subOnlyEst, estId, TODAY);
        assertEquals(ExamScopeService.CoverageType.NOT_STUDIED, cov.type,
                "Topic only in EST must be NOT_STUDIED");
        assertNull(cov.badgeText, "NOT_STUDIED must have no badge text");
    }

    /**
     * Topic in MST (past) and EST → COVERED_IN_PAST_EXAM for EST; badge must name MST, never say EST.
     */
    @Test
    void topicInPastMstAndEst_coveredInPastExam() {
        ExamScopeService.TopicCoverage cov = service.getTopicCoverage(semesterId, "UCS101", subMstAndEst, estId, TODAY);
        assertEquals(ExamScopeService.CoverageType.COVERED_IN_PAST_EXAM, cov.type,
                "Topic in past MST and EST must be COVERED_IN_PAST_EXAM");
        assertNotNull(cov.badgeText);
        assertTrue(cov.badgeText.contains("MST"),
                "Badge must name the earlier exam (MST), got: " + cov.badgeText);
        assertFalse(cov.badgeText.contains("EST"),
                "Badge must NOT name EST: " + cov.badgeText);
        assertEquals("past", cov.styleHint);
    }

    /**
     * Topic only in MST (past scope lookup) → NOT_STUDIED from MST context (it IS the first exam).
     */
    @Test
    void topicInPastMst_fromMstContext_notStudied() {
        ExamScopeService.TopicCoverage cov = service.getTopicCoverage(semesterId, "UCS101", subMstAndEst, mstId, TODAY);
        // MST is the first exam that contains it; no earlier exam → NOT_STUDIED
        assertEquals(ExamScopeService.CoverageType.NOT_STUDIED, cov.type,
                "From MST context the subtopic has no earlier exam, so NOT_STUDIED");
        assertNull(cov.badgeText);
    }

    /**
     * Topic in upcoming MST and EST → SHARED_WITH_EARLIER_EXAM, badge says "Learn for MST2, revise for EST".
     * Must NOT say "covered" or "done".
     */
    @Test
    void topicInFutureMstAndEst_sharedWithEarlierExam() {
        ExamScopeService.TopicCoverage cov = service.getTopicCoverage(semesterId, "UCS101", subFutureMstEst, estId, TODAY);
        assertEquals(ExamScopeService.CoverageType.SHARED_WITH_EARLIER_EXAM, cov.type,
                "Future MST + EST must be SHARED");
        assertNotNull(cov.badgeText);
        assertTrue(cov.badgeText.contains("Learn for"), "SHARED badge must say 'Learn for': " + cov.badgeText);
        assertFalse(cov.badgeText.contains("covered"), "SHARED badge must not say 'covered': " + cov.badgeText);
        assertFalse(cov.badgeText.contains("done"), "SHARED badge must not say 'done': " + cov.badgeText);
        assertEquals("shared", cov.styleHint);
    }

    /**
     * Topic with isDone=true → DONE_BY_USER regardless of exam scope.
     */
    @Test
    void topicMarkedDoneByUser_returnsDoneByUser() {
        ExamScopeService.TopicCoverage cov = service.getTopicCoverage(semesterId, "UCS101", subDoneByUser, estId, TODAY);
        assertEquals(ExamScopeService.CoverageType.DONE_BY_USER, cov.type,
                "isDone=true subtopic must be DONE_BY_USER");
        assertNotNull(cov.badgeText);
        assertTrue(cov.badgeText.contains("revise only") || cov.badgeText.contains("Studied"),
                "DONE badge should mention revise/studied: " + cov.badgeText);
        assertEquals("done", cov.styleHint);
    }

    /**
     * Topic in no exam scope → NOT_STUDIED, no badge.
     */
    @Test
    void topicInNoScope_returnsNotStudied() {
        ExamScopeService.TopicCoverage cov = service.getTopicCoverage(semesterId, "UCS101", subNoScope, null, TODAY);
        assertEquals(ExamScopeService.CoverageType.NOT_STUDIED, cov.type,
                "Topic not in any scope must be NOT_STUDIED");
        assertNull(cov.badgeText);
    }
}
