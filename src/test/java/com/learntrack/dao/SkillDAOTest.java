package com.learntrack.dao;

import com.learntrack.model.Skill;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SkillDAOTest {

    private SkillDAO skillDAO;
    private final String testDbPath = "target/test_skill.db";

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        skillDAO = new SkillDAO();
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testSkillCRUDAndTracking() throws SQLException {
        Skill skill = new Skill(null, "Guitar", "Music", 3, LocalDate.of(2026, 10, 1));
        int id = skillDAO.insert(skill);
        assertTrue(id > 0);

        List<Skill> all = skillDAO.getAll();
        assertEquals(1, all.size());
        assertEquals("Guitar", all.get(0).getName());

        skillDAO.updateLastPracticed(id, LocalDate.of(2026, 10, 10));
        Skill updated = skillDAO.findById(id);
        assertEquals(LocalDate.of(2026, 10, 10), updated.getLastPracticed());
    }

    @Test
    void testInsertSkillWithNullLastPracticedOnFreshSchema() throws SQLException {
        // What "+ Add Skill" does: no practice date yet. The column is NOT NULL, so the sentinel
        // is stored and read back as "never practiced".
        int id = skillDAO.insert(new Skill(null, "Guitar", "Music", 3, null));
        assertTrue(id > 0);

        Skill stored = skillDAO.findById(id);
        assertNotNull(stored);
        assertEquals("Guitar", stored.getName());
        assertTrue(stored.isNeverPracticed(), "A new skill must read back as never practiced");
        assertNull(stored.getLastPracticed(), "The sentinel must be mapped back to null on read");
    }

    @Test
    void testInsertSkillWithNullLastPracticedOnLegacySchema() throws SQLException {
        // A database created by the old schema: last_practiced TEXT NOT NULL, no default.
        try (Connection conn = DBConnection.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE skills");
                stmt.execute("CREATE TABLE skills (" +
                        "skill_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "proficiency INTEGER NOT NULL, " +
                        "last_practiced TEXT NOT NULL)");
                stmt.execute("INSERT INTO skills (name, category, proficiency, last_practiced) " +
                        "VALUES ('web dev', 'General', 2, '2026-10-23')");
            }
            DBConnection.initSchema(conn);
        }

        int id = skillDAO.insert(new Skill(null, "Guitar", "Music", 3, null));
        assertTrue(id > 0, "A skill without a practice date must insert into a migrated database");

        Skill stored = skillDAO.findById(id);
        assertTrue(stored.isNeverPracticed());
        assertNull(stored.getLastPracticed());

        // The legacy row is untouched.
        Skill legacy = skillDAO.getAll().stream()
                .filter(s -> "web dev".equals(s.getName())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 23), legacy.getLastPracticed());
    }
}
