package com.learntrack.service;

import com.learntrack.dao.SkillDAO;
import com.learntrack.dao.SkillSubtopicDAO;
import com.learntrack.model.Skill;
import com.learntrack.model.SkillSubtopic;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dashboard-level sync test for skill progress logging.
 * Tests that logging the full remaining hours (and marking DONE) on a SKILL_LEARN
 * daily_log row through the same code path the Dashboard uses sets:
 * - learned = 1
 * - learned_on = sessionDate
 * - revision_due_date = sessionDate + revise_after_days
 * - learn_remaining_hours = 0
 * 
 * And for SKILL_REVISE:
 * - revised = 1
 * - revised_on = sessionDate
 * - revise_remaining_hours = 0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SkillProgressServiceTest {

    private static SkillDAO skillDAO;
    private static SkillSubtopicDAO skillSubtopicDAO;
    private static SkillProgressService skillProgressService;
    private static int testSkillId;
    private static int testSubtopicId;
    private static final String testDbPath = "target/test_skill_progress.db";

    @BeforeAll
    static void setUp() throws SQLException {
        new java.io.File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (java.sql.Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        skillDAO = new SkillDAO();
        skillSubtopicDAO = new SkillSubtopicDAO();
        skillProgressService = new SkillProgressService(skillSubtopicDAO);

        // Create a test skill
        Skill skill = new Skill(null, "Test Skill for Progress Service", "Tech", 5, null);
        testSkillId = skillDAO.insert(skill);

        // Create a test subtopic with learn and revise hours
        SkillSubtopic subtopic = new SkillSubtopic(
                null, testSkillId, "Test Subtopic", 1,
                5.0, 5.0, 2.0, 2.0, 7,
                false, null, false, null, null
        );
        testSubtopicId = skillSubtopicDAO.insert(subtopic);
    }

    @Test
    @Order(1)
    void testLogLearningProgressPartial() throws SQLException {
        LocalDate sessionDate = LocalDate.of(2026, 10, 15);

        // Log 2 hours, should reduce remaining but not mark as learned
        skillProgressService.logLearningProgress(testSubtopicId, 2.0, sessionDate);

        SkillSubtopic after = skillSubtopicDAO.findById(testSubtopicId);
        assertNotNull(after);
        assertEquals(3.0, after.getLearnRemainingHours(), 0.01, "Should have 3h remaining");
        assertFalse(after.isLearned(), "Should not be marked as learned yet");
        assertNull(after.getLearnedOn(), "learned_on should be null");
        assertNull(after.getRevisionDueDate(), "revision_due_date should be null");
    }

    @Test
    @Order(2)
    void testLogLearningProgressComplete() throws SQLException {
        LocalDate sessionDate = LocalDate.of(2026, 10, 16);

        // Log the full remaining 3 hours, should mark as learned
        skillProgressService.logLearningProgress(testSubtopicId, 3.0, sessionDate);

        SkillSubtopic after = skillSubtopicDAO.findById(testSubtopicId);
        assertNotNull(after);
        assertEquals(0.0, after.getLearnRemainingHours(), 0.01, "learn_remaining_hours should be 0");
        assertTrue(after.isLearned(), "learned should be 1");
        assertEquals(sessionDate, after.getLearnedOn(), "learned_on should be session date");
        assertEquals(sessionDate.plusDays(7), after.getRevisionDueDate(),
                "revision_due_date should be sessionDate + revise_after_days");
    }

    @Test
    @Order(3)
    void testLogRevisionProgressPartial() throws SQLException {
        LocalDate sessionDate = LocalDate.of(2026, 10, 23);

        // Log 1 hour of revision, should reduce remaining but not mark as revised
        skillProgressService.logRevisionProgress(testSubtopicId, 1.0, sessionDate);

        SkillSubtopic after = skillSubtopicDAO.findById(testSubtopicId);
        assertNotNull(after);
        assertEquals(1.0, after.getReviseRemainingHours(), 0.01, "Should have 1h revision remaining");
        assertFalse(after.isRevised(), "Should not be marked as revised yet");
        assertNull(after.getRevisedOn(), "revised_on should be null");
    }

    @Test
    @Order(4)
    void testLogRevisionProgressComplete() throws SQLException {
        LocalDate sessionDate = LocalDate.of(2026, 10, 24);

        // Log the full remaining 1 hour, should mark as revised
        skillProgressService.logRevisionProgress(testSubtopicId, 1.0, sessionDate);

        SkillSubtopic after = skillSubtopicDAO.findById(testSubtopicId);
        assertNotNull(after);
        assertEquals(0.0, after.getReviseRemainingHours(), 0.01, "revise_remaining_hours should be 0");
        assertTrue(after.isRevised(), "revised should be 1");
        assertEquals(sessionDate, after.getRevisedOn(), "revised_on should be session date");
    }

    @Test
    @Order(5)
    void testLogLearningProgressOverflow() throws SQLException {
        // Create another subtopic for overflow test
        SkillSubtopic overflow = new SkillSubtopic(
                null, testSkillId, "Overflow Subtopic", 2,
                3.0, 2.0, 1.0, 1.0, 10,
                false, null, false, null, null
        );
        int overflowId = skillSubtopicDAO.insert(overflow);

        LocalDate sessionDate = LocalDate.of(2026, 10, 25);

        // Log 5 hours (more than remaining 2h), should mark as learned with 0 remaining
        skillProgressService.logLearningProgress(overflowId, 5.0, sessionDate);

        SkillSubtopic after = skillSubtopicDAO.findById(overflowId);
        assertNotNull(after);
        assertEquals(0.0, after.getLearnRemainingHours(), 0.01,
                "learn_remaining_hours should be 0 even with overflow");
        assertTrue(after.isLearned(), "Should be marked as learned");
        assertEquals(sessionDate, after.getLearnedOn());
        assertEquals(sessionDate.plusDays(10), after.getRevisionDueDate());
    }

    @AfterAll
    static void tearDown() throws SQLException {
        // Clean up
        if (testSubtopicId > 0) {
            skillSubtopicDAO.delete(testSubtopicId);
        }
        if (testSkillId > 0) {
            skillDAO.delete(testSkillId);
        }
        DBConnection.resetToDefaultUrl();
        new java.io.File(testDbPath).delete();
    }
}
