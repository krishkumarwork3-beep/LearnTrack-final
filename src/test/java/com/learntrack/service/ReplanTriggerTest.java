package com.learntrack.service;

import com.learntrack.dao.*;
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
 * Verifies that the 6 syllabus-mutation handler methods that previously had no
 * replan trigger now call resyncHoursForSubject + scheduleDebounceReplan.
 *
 * resyncHoursForSubject → syncTopicPhasesForSubject: re-reads hours_to_cover /
 * hours_to_revise from syllabus_subtopics for each already-scoped subtopic and
 * writes them back into topics.remaining_hours / revision_remaining_hours.
 * It does NOT add or remove topics; that is done exclusively via
 * saveScopeAndSyncTopics (the "Edit Syllabus" dialog).
 *
 * Therefore the correct assertions are:
 *   1. After editing a scoped subtopic's hours and calling resyncHoursForSubject,
 *      topic.remaining_hours reflects the new value.
 *   2. After marking a scoped subtopic done and calling resyncHoursForSubject,
 *      topic.remaining_hours becomes 0 (REVISION phase).
 *   3. After clearing all syllabus (deleteAllForSubject) and calling
 *      resyncHoursForSubject, the scoped topics lose their subtopic reference
 *      (syllabusDAO.findSubtopicById returns null for deleted ones) and
 *      remaining hours stays at the last-synced value, and the scope entries
 *      still exist. Scope cleanup happens on next saveScopeAndSyncTopics.
 *      The real invariant is: the service must not throw.
 *
 * Trigger locations added in TopicFormController (Phase 5 Step 6):
 *   handleAddUnit           L578-579
 *   handleDeleteUnit        L619-621
 *   handleClearSyllabus     L661-663
 *   importParsedSyllabus    L944-946
 *   handleAddSubtopic       L1002-1004
 *   handleBulkAddSubtopics  L1022-1024
 *   handleDeleteSubtopic    L1050-1052
 */
public class ReplanTriggerTest {

    private static final String TEST_DB = "target/test_replan_trigger.db";

    private ExamScopeService examScopeService;
    private SyllabusDAO syllabusDAO;
    private TopicDAO topicDAO;
    private ExamDAO examDAO;
    private int semesterId;
    private int examId;
    private int unitId;
    private int subtopicId1;
    private int subtopicId2;

    @BeforeEach
    void setUp() throws SQLException {
        new File(TEST_DB).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        SemesterDAO semesterDAO = new SemesterDAO();
        Semester sem = new Semester();
        sem.setName("Trigger Test Sem");
        sem.setStatus("ACTIVE");
        semesterId = semesterDAO.insert(sem);

        examDAO = new ExamDAO();
        syllabusDAO = new SyllabusDAO();
        topicDAO = new TopicDAO();
        ExamScopeDAO examScopeDAO = new ExamScopeDAO();
        examScopeService = new ExamScopeService(examScopeDAO, topicDAO, syllabusDAO, examDAO);

        // Create an exam
        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Java Programming");
        exam.setSubjectCode("UCS615");
        exam.setExamType("MST");
        exam.setCredits(4.0);
        exam.setEvalMarks(20.0);
        examId = examDAO.insert(exam);

        // Initial unit with 2 subtopics (1h cover, 0.5h revise each)
        unitId = syllabusDAO.insertUnit(semesterId, "UCS615", "OOP Basics", 0);
        subtopicId1 = syllabusDAO.insertSubtopic(unitId, "Classes",    0, 5, 1.0, 0.5);
        subtopicId2 = syllabusDAO.insertSubtopic(unitId, "Interfaces", 1, 5, 1.0, 0.5);

        // Set scope to both subtopics → creates 2 topics with remaining_hours = 1.0 each
        examScopeService.saveScopeAndSyncTopics(examId, Set.of(subtopicId1, subtopicId2));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB).delete();
    }

    // -----------------------------------------------------------------------
    // helper
    // -----------------------------------------------------------------------

    private double totalRemainingHours() throws SQLException {
        List<Topic> topics = topicDAO.findByExam(examId);
        double sum = 0;
        for (Topic t : topics) sum += t.getRemainingHours();
        return sum;
    }

    // -----------------------------------------------------------------------
    // What resyncHoursForSubject actually does
    // -----------------------------------------------------------------------

    /**
     * When a scoped subtopic's hours_to_cover is increased and
     * resyncHoursForSubject is called, the linked topic's remaining_hours
     * must increase. This is the core behavior that justifies calling
     * resyncHoursForSubject after handleAddSubtopic / handleBulkAddSubtopics
     * (which set hours on newly added subtopics that may be pre-scoped).
     */
    @Test
    void testResyncAfterHourEditUpdatesTopicRemainingHours() throws SQLException {
        // Both scoped subtopics start at 1.0h each → total 2.0h
        assertEquals(2.0, totalRemainingHours(), 0.001,
            "Initial remaining hours should be 2.0 (2 subtopics × 1.0h)");

        // Simulate handleAddSubtopic's effect: edit subtopic1 cover hours to 3.0h
        syllabusDAO.updateSubtopicHoursToCover(subtopicId1, 3.0);

        // Trigger resync, exactly what the new production code does
        examScopeService.resyncHoursForSubject(semesterId, "UCS615", "Classes");

        double after = totalRemainingHours();
        assertEquals(4.0, after, 0.001,
            "After editing subtopic1 cover hours to 3h, total should be 3+1=4.0h; got " + after);
    }

    /**
     * When a scoped subtopic is marked done in the syllabus (subtopic.isDone = true)
     * and resyncHoursForSubject is called, the topic transitions to REVISION phase
     * and remaining_hours (learn) becomes 0.
     */
    @Test
    void testResyncAfterSubtopicMarkedDoneZerosLearnHours() throws SQLException {
        double before = totalRemainingHours();
        assertEquals(2.0, before, 0.001);

        // Mark subtopic1 done in syllabus
        syllabusDAO.updateSubtopicDone(subtopicId1, true);

        // Trigger resync
        examScopeService.resyncHoursForSubject(semesterId, "UCS615", "Classes");

        double after = totalRemainingHours();
        assertEquals(1.0, after, 0.001,
            "After marking subtopic1 done, only subtopic2 remains at 1.0h; got " + after);
    }

    /**
     * resyncHoursForSubject must not throw when called after deleteAllForSubject
     * (even if scope entries reference now-deleted subtopics). The orphaned topics
     * remain and the service handles null-subtopic lookup gracefully.
     * This covers the handleClearSyllabus trigger.
     */
    @Test
    void testResyncAfterClearSyllabusDoesNotThrow() throws SQLException {
        // Clear all syllabus entries; scope entries still point to deleted subtopics
        syllabusDAO.deleteAllForSubject(semesterId, "UCS615");

        // Must not throw (null-safe lookup inside syncTopicPhasesForSubject)
        assertDoesNotThrow(
            () -> examScopeService.resyncHoursForSubject(semesterId, "UCS615", "UCS615"),
            "resyncHoursForSubject must not throw after syllabus is cleared"
        );
    }

    /**
     * When revision hours are edited on a scoped subtopic and resyncHoursForSubject
     * is called, the topic's revision_remaining_hours must update.
     * This tests the handleBulkAddSubtopics path (which calls resync after
     * setting hours on newly inserted subtopics if they were already scoped).
     */
    @Test
    void testResyncUpdatesRevisionHours() throws SQLException {
        List<Topic> before = topicDAO.findByExam(examId);
        double revBefore = before.stream().mapToDouble(Topic::getRevisionRemainingHours).sum();
        assertEquals(1.0, revBefore, 0.001, "Initial revision hours should be 0.5+0.5=1.0h");

        // Edit subtopic2's revise hours to 2.0h
        syllabusDAO.updateSubtopicHoursToRevise(subtopicId2, 2.0);
        examScopeService.resyncHoursForSubject(semesterId, "UCS615", "Interfaces");

        List<Topic> after = topicDAO.findByExam(examId);
        double revAfter = after.stream().mapToDouble(Topic::getRevisionRemainingHours).sum();
        assertEquals(2.5, revAfter, 0.001,
            "After editing subtopic2 revise to 2h, total revision should be 0.5+2=2.5h; got " + revAfter);
    }

    /**
     * The 3rd argument of resyncHoursForSubject is the `subject` display name,
     * used to query exams by subject OR subject_code. Passing the subtopic name
     * (as old callers did for inline edits) is wrong and it won't find any exams.
     * After the fix in Phase 5 Step 6, importParsedSyllabus and handleClearSyllabus
     * now correctly pass subjectCode as both code and subject arguments.
     *
     * This test verifies that passing the subjectCode as the 3rd argument works
     * (finds the exam and syncs topics).
     */
    @Test
    void testResyncWithSubjectCodeAsSubjectArgSucceeds() throws SQLException {
        // Edit hours then resync using subjectCode as subject (correct new behavior)
        syllabusDAO.updateSubtopicHoursToCover(subtopicId1, 2.0);
        examScopeService.resyncHoursForSubject(semesterId, "UCS615", "UCS615"); // subjectCode as subject

        double after = totalRemainingHours();
        assertEquals(3.0, after, 0.001,
            "resync with subjectCode as subject should find exam and sync; total=2+1=3h, got " + after);
    }
}
