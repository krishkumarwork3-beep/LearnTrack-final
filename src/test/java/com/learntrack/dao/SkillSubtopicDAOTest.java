package com.learntrack.dao;

import com.learntrack.model.Skill;
import com.learntrack.model.SkillSubtopic;
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

public class SkillSubtopicDAOTest {

    private SkillDAO skillDAO;
    private SkillSubtopicDAO subtopicDAO;
    private final String testDbPath = "target/test_skill_subtopics.db";

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        skillDAO = new SkillDAO();
        subtopicDAO = new SkillSubtopicDAO();
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testCRUD() throws SQLException {
        int skillId = skillDAO.insert(new Skill(null, "Machine Learning", "Tech", 3, null));
        assertTrue(skillId > 0);

        SkillSubtopic st1 = new SkillSubtopic(
                null, skillId, "Linear Regression", 1,
                2.0, 2.0, 1.0, 1.0, 7,
                false, null, false, null, null
        );
        int subId1 = subtopicDAO.insert(st1);
        assertTrue(subId1 > 0);

        SkillSubtopic st2 = new SkillSubtopic(
                null, skillId, "Logistic Regression", 2,
                3.0, 3.0, 1.5, 1.5, 5,
                false, null, false, null, null
        );
        int subId2 = subtopicDAO.insert(st2);
        assertTrue(subId2 > 0);

        List<SkillSubtopic> bySkill = subtopicDAO.findBySkill(skillId);
        assertEquals(2, bySkill.size());
        assertEquals("Linear Regression", bySkill.get(0).getName());
        assertEquals("Logistic Regression", bySkill.get(1).getName());

        // Update
        SkillSubtopic fetched = subtopicDAO.findById(subId1);
        assertNotNull(fetched);
        fetched.setName("Simple Linear Regression");
        fetched.setLearnRemainingHours(1.5);
        subtopicDAO.update(fetched);

        SkillSubtopic updated = subtopicDAO.findById(subId1);
        assertEquals("Simple Linear Regression", updated.getName());
        assertEquals(1.5, updated.getLearnRemainingHours(), 0.001);

        // Delete
        subtopicDAO.delete(subId2);
        assertEquals(1, subtopicDAO.findBySkill(skillId).size());
    }

    @Test
    void testMarkAndUnmarkLearnedAndRevised() throws SQLException {
        int skillId = skillDAO.insert(new Skill(null, "Guitar", "Music", 3, null));
        SkillSubtopic st = new SkillSubtopic(
                null, skillId, "Chords", 1,
                4.0, 4.0, 2.0, 2.0, 7,
                false, null, false, null, null
        );
        int subId = subtopicDAO.insert(st);

        LocalDate today = LocalDate.of(2026, 10, 15);
        subtopicDAO.markLearned(subId, today, 7);

        SkillSubtopic learned = subtopicDAO.findById(subId);
        assertTrue(learned.isLearned());
        assertEquals(today, learned.getLearnedOn());
        assertEquals(0.0, learned.getLearnRemainingHours(), 0.001);
        assertEquals(today.plusDays(7), learned.getRevisionDueDate());
        assertFalse(learned.isRevised());

        // Mark revised
        LocalDate revDate = today.plusDays(7);
        subtopicDAO.markRevised(subId, revDate);

        SkillSubtopic revised = subtopicDAO.findById(subId);
        assertTrue(revised.isRevised());
        assertEquals(revDate, revised.getRevisedOn());
        assertEquals(0.0, revised.getReviseRemainingHours(), 0.001);
        assertTrue(revised.isCovered());

        // Unmark revised
        subtopicDAO.unmarkRevised(subId);
        SkillSubtopic unrevised = subtopicDAO.findById(subId);
        assertFalse(unrevised.isRevised());
        assertNull(unrevised.getRevisedOn());
        assertEquals(2.0, unrevised.getReviseRemainingHours(), 0.001);
        assertTrue(unrevised.isLearned());

        // Unmark learned
        subtopicDAO.unmarkLearned(subId);
        SkillSubtopic unlearned = subtopicDAO.findById(subId);
        assertFalse(unlearned.isLearned());
        assertNull(unlearned.getLearnedOn());
        assertNull(unlearned.getRevisionDueDate());
        assertEquals(4.0, unlearned.getLearnRemainingHours(), 0.001);
    }

    @Test
    void testCascadeDeleteWhenSkillDeleted() throws SQLException {
        int skillId = skillDAO.insert(new Skill(null, "Piano", "Music", 3, null));
        subtopicDAO.insert(new SkillSubtopic(null, skillId, "Scales", 1, 1.0, 1.0, 0.5, 0.5, 3, false, null, false, null, null));
        subtopicDAO.insert(new SkillSubtopic(null, skillId, "Arpeggios", 2, 1.5, 1.5, 0.5, 0.5, 3, false, null, false, null, null));

        assertEquals(2, subtopicDAO.findBySkill(skillId).size());

        // Deleting the parent skill must cascade delete subtopics
        skillDAO.delete(skillId);
        assertEquals(0, subtopicDAO.findBySkill(skillId).size());
    }
}
