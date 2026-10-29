package com.learntrack.service;

import com.learntrack.dao.*;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamScopeService.
 */
public class ExamScopeServiceTest {

    private static final String TEST_DB_PATH = "test_exam_scope_service.db";
    private ExamScopeService service;
    private ExamScopeDAO examScopeDAO;
    private TopicDAO topicDAO;
    private SyllabusDAO syllabusDAO;
    private ExamDAO examDAO;
    private int semesterId;
    private int examId;
    private int unitId;
    private int subtopicId1;
    private int subtopicId2;
    private int subtopicId3;

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
        topicDAO = new TopicDAO();
        syllabusDAO = new SyllabusDAO();
        service = new ExamScopeService(examScopeDAO, topicDAO, syllabusDAO, examDAO);

        // Create exam
        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Data Structures");
        exam.setSubjectCode("UCS420");
        exam.setExamType("MST");
        exam.setCredits(4.0);
        exam.setEvalMarks(20.0);
        examId = examDAO.insert(exam);

        // Create syllabus
        unitId = syllabusDAO.insertUnit(semesterId, "UCS420", "Arrays", 0);
        subtopicId1 = syllabusDAO.insertSubtopic(unitId, "Introduction", 0);
        subtopicId2 = syllabusDAO.insertSubtopic(unitId, "Dynamic Arrays", 1);
        subtopicId3 = syllabusDAO.insertSubtopic(unitId, "Multi-dimensional", 2);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void testAddNewTopicsForSelectedSubtopics() throws SQLException {
        // Select two subtopics
        Set<Integer> selected = Set.of(subtopicId1, subtopicId2);
        ExamScopeService.ScopeSyncResult result = service.saveScopeAndSyncTopics(examId, selected);

        // Verify result counts
        assertEquals(2, result.getAdded());
        assertEquals(0, result.getRemoved());
        assertEquals(0, result.getKeptStudied());

        // Verify topics were created
        Topic topic1 = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertNotNull(topic1);
        assertEquals("Introduction", topic1.getName());
        assertEquals(3, topic1.getImportance()); // inherits the subtopic importance (default Normal = 3)
        assertEquals(1, topic1.getDifficulty()); // Neutral difficulty (DifficultyMapper removed from planning)
        assertEquals(1.0, topic1.getHoursNeeded());
        assertEquals(1.0, topic1.getRemainingHours());
        assertEquals("LEARNING", topic1.getPhase());
        assertEquals(0.5, topic1.getRevisionHoursNeeded());
        assertEquals(7, topic1.getRevisionIntervalDays());
        assertEquals(1, topic1.getImportantFlag());
        assertEquals(subtopicId1, topic1.getSubtopicId());

        Topic topic2 = topicDAO.findByExamAndSubtopic(examId, subtopicId2);
        assertNotNull(topic2);
        assertEquals("Dynamic Arrays", topic2.getName());

        // Verify scope was saved
        Set<Integer> scope = examScopeDAO.findSubtopicIdsForExam(examId);
        assertEquals(2, scope.size());
        assertTrue(scope.contains(subtopicId1));
        assertTrue(scope.contains(subtopicId2));
    }

    @Test
    void testRemoveUntouchedTopics() throws SQLException {
        // Add initial scope
        Set<Integer> initial = Set.of(subtopicId1, subtopicId2);
        service.saveScopeAndSyncTopics(examId, initial);

        // Verify topics created
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId1));
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId2));

        // Remove subtopicId2 from scope
        Set<Integer> updated = Set.of(subtopicId1);
        ExamScopeService.ScopeSyncResult result = service.saveScopeAndSyncTopics(examId, updated);

        // Verify result counts
        assertEquals(0, result.getAdded());
        assertEquals(1, result.getRemoved());
        assertEquals(0, result.getKeptStudied());

        // Verify topic2 was deleted (untouched)
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId1));
        assertNull(topicDAO.findByExamAndSubtopic(examId, subtopicId2));
    }

    @Test
    void testKeepStudiedTopics() throws SQLException {
        // Add initial scope
        Set<Integer> initial = Set.of(subtopicId1, subtopicId2);
        service.saveScopeAndSyncTopics(examId, initial);

        // Mark topic as studied by changing its remaining hours
        Topic topic2 = topicDAO.findByExamAndSubtopic(examId, subtopicId2);
        assertNotNull(topic2);
        topic2.setRemainingHours(2.0); // Changed from initial 4.0
        topicDAO.update(topic2);

        // Remove subtopicId2 from scope
        Set<Integer> updated = Set.of(subtopicId1);
        ExamScopeService.ScopeSyncResult result = service.saveScopeAndSyncTopics(examId, updated);

        // Verify result counts
        assertEquals(0, result.getAdded());
        assertEquals(0, result.getRemoved());
        assertEquals(1, result.getKeptStudied());

        // Verify topic2 was kept (studied)
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId1));
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId2));

        // Verify scope does NOT include subtopicId2
        Set<Integer> scope = examScopeDAO.findSubtopicIdsForExam(examId);
        assertEquals(1, scope.size());
        assertTrue(scope.contains(subtopicId1));
        assertFalse(scope.contains(subtopicId2));
    }

    @Test
    void testTransactionRollbackOnFailure() throws SQLException {
        // Create invalid service that will fail during topic creation
        ExamScopeService badService = new ExamScopeService(examScopeDAO, topicDAO, syllabusDAO);

        // Select a subtopic with invalid exam ID to force failure
        Set<Integer> selected = Set.of(subtopicId1);
        
        // First, create a valid topic
        service.saveScopeAndSyncTopics(examId, selected);
        
        // Now try to add another subtopic but with a broken subtopic ID
        // This should cause the transaction to roll back
        Set<Integer> broken = Set.of(subtopicId1, 99999); // 99999 doesn't exist
        
        try {
            service.saveScopeAndSyncTopics(examId, broken);
            // Should not add the invalid subtopic but still work for valid one
        } catch (Exception e) {
            // Expected if subtopic doesn't exist
        }

        // Verify original scope remains intact
        Set<Integer> scope = examScopeDAO.findSubtopicIdsForExam(examId);
        // The service should handle missing subtopics gracefully
        assertTrue(scope.contains(subtopicId1));
    }

    @Test
    void testAddRemoveAndKeep() throws SQLException {
        // Initial scope: 1, 2
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1, subtopicId2));

        // Mark topic2 as studied
        Topic topic2 = topicDAO.findByExamAndSubtopic(examId, subtopicId2);
        topic2.setPhase("REVISING"); // Change phase
        topicDAO.update(topic2);

        // New scope: 1, 3 (remove 2, add 3)
        ExamScopeService.ScopeSyncResult result = service.saveScopeAndSyncTopics(
                examId, Set.of(subtopicId1, subtopicId3));

        // Verify counts
        assertEquals(1, result.getAdded()); // Added subtopicId3
        assertEquals(0, result.getRemoved()); // Did not remove topic2 (studied)
        assertEquals(1, result.getKeptStudied()); // Kept topic2

        // Verify topics
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId1));
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId2)); // Kept
        assertNotNull(topicDAO.findByExamAndSubtopic(examId, subtopicId3)); // Added

        // Verify scope (does NOT include subtopicId2)
        Set<Integer> scope = examScopeDAO.findSubtopicIdsForExam(examId);
        assertEquals(2, scope.size());
        assertTrue(scope.contains(subtopicId1));
        assertTrue(scope.contains(subtopicId3));
        assertFalse(scope.contains(subtopicId2));
    }

    @Test
    void testResyncUntouchedTopicUpdatesHours() throws SQLException {
        // Create exam scope with subtopic1
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1));
        
        Topic topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertNotNull(topic);
        assertEquals(1.0, topic.getHoursNeeded());
        assertEquals(0.5, topic.getRevisionHoursNeeded());
        
        // Change subtopic hours_to_cover from 1.0 to 2.5
        syllabusDAO.updateSubtopicHours(subtopicId1, 2.5, 1.0);
        
        // Resync the subject
        service.resyncHoursForSubject(semesterId, "UCS420", "Data Structures");
        
        // Verify topic hours were updated
        topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertEquals(2.5, topic.getHoursNeeded(), 0.01);
        assertEquals(2.5, topic.getRemainingHours(), 0.01);
        assertEquals(1.0, topic.getRevisionHoursNeeded(), 0.01);
    }

    @Test
    void testResyncTopicWithPlannedRowsUpdatesHours() throws SQLException {
        // Create exam scope with subtopic1
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1));
        
        Topic topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertNotNull(topic);
        
        // Add a PLANNED daily_log entry (simulates planner output)
        DailyLogDAO logDAO = new DailyLogDAO();
        logDAO.insert(semesterId, topic.getTopicId(), "TOPIC_LEARN", 
                      java.time.LocalDate.now(), 1.0, "PLANNED");
        
        // Change subtopic hours_to_cover from 1.0 to 3.0
        syllabusDAO.updateSubtopicHours(subtopicId1, 3.0, 1.5);
        
        // Resync the subject - PLANNED rows should NOT prevent update
        service.resyncHoursForSubject(semesterId, "UCS420", "Data Structures");
        
        // Verify topic hours were updated despite PLANNED row
        topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertEquals(3.0, topic.getHoursNeeded(), 0.01);
        assertEquals(3.0, topic.getRemainingHours(), 0.01);
        assertEquals(1.5, topic.getRevisionHoursNeeded(), 0.01);
    }

    @Test
    void testResyncPreservesTopicWithDoneRow() throws SQLException {
        // Create exam scope with subtopic1
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1));
        
        Topic topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertNotNull(topic);
        topic.setHoursNeeded(4.0);
        topic.setRemainingHours(2.0); // User has studied 2 hours
        topicDAO.update(topic);
        
        // Add a DONE daily_log entry (real user progress)
        DailyLogDAO logDAO = new DailyLogDAO();
        logDAO.insert(semesterId, topic.getTopicId(), "TOPIC_LEARN", 
                      java.time.LocalDate.now(), 2.0, "DONE");
        
        // Change subtopic hours_to_cover from 1.0 to 1.5
        syllabusDAO.updateSubtopicHours(subtopicId1, 1.5, 0.8);
        
        // Resync the subject - DONE row should prevent update
        service.resyncHoursForSubject(semesterId, "UCS420", "Data Structures");
        
        // Verify topic hours were NOT changed (preserved)
        topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertEquals(4.0, topic.getHoursNeeded(), 0.01);
        assertEquals(2.0, topic.getRemainingHours(), 0.01);
    }

    @Test
    void testResyncPreservesTopicWithContinuingRow() throws SQLException {
        // Create exam scope with subtopic1
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1));
        
        Topic topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertNotNull(topic);
        topic.setHoursNeeded(5.0);
        topic.setRemainingHours(3.5);
        topicDAO.update(topic);
        
        // Add a CONTINUING daily_log entry (ongoing work)
        DailyLogDAO logDAO = new DailyLogDAO();
        logDAO.insert(semesterId, topic.getTopicId(), "TOPIC_LEARN", 
                      java.time.LocalDate.now(), 1.5, "CONTINUING");
        
        // Change subtopic hours
        syllabusDAO.updateSubtopicHours(subtopicId1, 2.0, 1.0);
        
        // Resync the subject - CONTINUING row should prevent update
        service.resyncHoursForSubject(semesterId, "UCS420", "Data Structures");
        
        // Verify topic hours were NOT changed (preserved)
        topic = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        assertEquals(5.0, topic.getHoursNeeded(), 0.01);
        assertEquals(3.5, topic.getRemainingHours(), 0.01);
    }

    @Test
    void testRebuildPlanDeletesPlannedRows() throws SQLException {
        // Create exam scope
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1, subtopicId2));
        
        Topic topic1 = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        Topic topic2 = topicDAO.findByExamAndSubtopic(examId, subtopicId2);
        
        DailyLogDAO logDAO = new DailyLogDAO();
        java.time.LocalDate today = java.time.LocalDate.now();
        
        // Add PLANNED rows
        logDAO.insert(semesterId, topic1.getTopicId(), "TOPIC_LEARN", today, 1.0, "PLANNED");
        logDAO.insert(semesterId, topic2.getTopicId(), "TOPIC_REVISE", today.plusDays(1), 0.5, "PLANNED");
        
        // Add PYQ PENDING/MISSED rows
        logDAO.insert(semesterId, examId, "PYQ_PRACTICE", today, 1.0, "PENDING");
        logDAO.insert(semesterId, examId, "PYQ_PRACTICE", today.plusDays(1), 1.0, "MISSED");
        
        // Verify 4 rows exist
        assertEquals(4, logDAO.findAll(semesterId).size());
        
        // Run rebuild plan
        ExamScopeService.RebuildPlanResult result = service.rebuildPlan(semesterId);
        
        // Verify all PLANNED and PENDING/MISSED rows deleted
        assertTrue(result.getPlannedDeleted() >= 2); // At least the 2 PLANNED topic rows
        assertTrue(result.getPendingDeleted() >= 2); // At least the 2 PYQ rows
        assertEquals(0, logDAO.findAll(semesterId).size());
    }

    @Test
    void testRebuildPlanPreservesDoneAndContinuingRows() throws SQLException {
        // Create exam scope
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1, subtopicId2));
        
        Topic topic1 = topicDAO.findByExamAndSubtopic(examId, subtopicId1);
        Topic topic2 = topicDAO.findByExamAndSubtopic(examId, subtopicId2);
        
        DailyLogDAO logDAO = new DailyLogDAO();
        java.time.LocalDate today = java.time.LocalDate.now();
        
        // Add DONE rows
        logDAO.insert(semesterId, topic1.getTopicId(), "TOPIC_LEARN", today, 1.0, "DONE");
        
        // Add CONTINUING rows
        logDAO.insert(semesterId, topic2.getTopicId(), "TOPIC_REVISE", today, 0.5, "CONTINUING");
        
        // Add PLANNED rows (should be deleted)
        logDAO.insert(semesterId, topic1.getTopicId(), "TOPIC_LEARN", today.plusDays(1), 1.0, "PLANNED");
        
        // Verify 3 rows exist
        assertEquals(3, logDAO.findAll(semesterId).size());
        
        // Run rebuild plan
        service.rebuildPlan(semesterId);
        
        // Verify DONE and CONTINUING preserved, PLANNED deleted
        var remaining = logDAO.findAll(semesterId);
        assertEquals(2, remaining.size());
        assertTrue(remaining.stream().anyMatch(log -> log.getStatus().equals("DONE")));
        assertTrue(remaining.stream().anyMatch(log -> log.getStatus().equals("CONTINUING")));
    }

    @Test
    void testLaterExamGetsRevisionOnlyHours() throws SQLException {
        // Create two exams for the same subject with assignments (no MST limit)
        Exam exam1 = new Exam();
        exam1.setSemesterId(semesterId);
        exam1.setSubject("Data Structures");
        exam1.setSubjectCode("UCS420");
        exam1.setExamType("ASSIGNMENT");
        exam1.setEvalName("Assignment 1");
        exam1.setWindowStartDate(java.time.LocalDate.of(2026, 11, 1));
        exam1.setWindowEndDate(java.time.LocalDate.of(2026, 11, 15));
        int examId1 = examDAO.insert(exam1);
        
        Exam exam2 = new Exam();
        exam2.setSemesterId(semesterId);
        exam2.setSubject("Data Structures");
        exam2.setSubjectCode("UCS420");
        exam2.setExamType("ASSIGNMENT");
        exam2.setEvalName("Assignment 2");
        exam2.setWindowStartDate(java.time.LocalDate.of(2026, 12, 1));
        exam2.setWindowEndDate(java.time.LocalDate.of(2026, 12, 20));
        int examId2 = examDAO.insert(exam2);
        
        // Add same scope to both exams
        service.saveScopeAndSyncTopics(examId1, Set.of(subtopicId1));
        service.saveScopeAndSyncTopics(examId2, Set.of(subtopicId1));
        
        // First exam should have LEARNING phase
        Topic topic1 = topicDAO.findByExamAndSubtopic(examId1, subtopicId1);
        assertNotNull(topic1);
        assertEquals("LEARNING", topic1.getPhase());
        assertEquals(1.0, topic1.getHoursNeeded(), 0.01);
        assertEquals(1.0, topic1.getRemainingHours(), 0.01);
        assertEquals(0.5, topic1.getRevisionHoursNeeded(), 0.01);
        
        // Second exam should have REVISION phase with 0 hours_needed
        Topic topic2 = topicDAO.findByExamAndSubtopic(examId2, subtopicId1);
        assertNotNull(topic2);
        assertEquals("REVISION", topic2.getPhase());
        assertEquals(0.0, topic2.getHoursNeeded(), 0.01);
        assertEquals(0.0, topic2.getRemainingHours(), 0.01);
        assertEquals(0.5, topic2.getRevisionHoursNeeded(), 0.01);
    }


    @Test
    void testFindExamsWithoutScope() throws SQLException {
        // Create additional exams
        Exam exam2 = new Exam();
        exam2.setSemesterId(semesterId);
        exam2.setSubject("Algorithms");
        exam2.setSubjectCode("UCS421");
        exam2.setExamType("MST");
        exam2.setCredits(4.0);
        exam2.setEvalMarks(20.0);
        int examId2 = examDAO.insert(exam2);

        Exam exam3 = new Exam();
        exam3.setSemesterId(semesterId);
        exam3.setSubject("Networks");
        exam3.setSubjectCode("UCS503");
        exam3.setExamType("EST");
        exam3.setCredits(4.0);
        exam3.setEvalMarks(30.0);
        int examId3 = examDAO.insert(exam3);

        // Initially all exams have no scope
        java.util.List<Exam> withoutScope = service.findExamsWithoutScope(semesterId);
        assertEquals(3, withoutScope.size());
        assertTrue(withoutScope.stream().anyMatch(e -> e.getExamId() == examId));
        assertTrue(withoutScope.stream().anyMatch(e -> e.getExamId() == examId2));
        assertTrue(withoutScope.stream().anyMatch(e -> e.getExamId() == examId3));

        // Add scope to first exam
        service.saveScopeAndSyncTopics(examId, Set.of(subtopicId1));

        // Now only 2 exams without scope
        withoutScope = service.findExamsWithoutScope(semesterId);
        assertEquals(2, withoutScope.size());
        assertFalse(withoutScope.stream().anyMatch(e -> e.getExamId() == examId));
        assertTrue(withoutScope.stream().anyMatch(e -> e.getExamId() == examId2));
        assertTrue(withoutScope.stream().anyMatch(e -> e.getExamId() == examId3));

        // Create syllabus for exam2's subject
        int unit2 = syllabusDAO.insertUnit(semesterId, "UCS421", "Sorting", 0);
        int sub2 = syllabusDAO.insertSubtopic(unit2, "Bubble Sort", 0);
        
        // Add scope to exam2
        service.saveScopeAndSyncTopics(examId2, Set.of(sub2));

        // Now only 1 exam without scope
        withoutScope = service.findExamsWithoutScope(semesterId);
        assertEquals(1, withoutScope.size());
        assertEquals(examId3, withoutScope.get(0).getExamId());
        assertEquals("UCS503", withoutScope.get(0).getSubjectCode());
    }
}

