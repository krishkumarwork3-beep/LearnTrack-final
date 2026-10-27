package com.learntrack.util;

import com.learntrack.dao.SkillDAO;
import com.learntrack.model.Skill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Databases created by the old schema declare skills.last_practiced TEXT NOT NULL with no default,
 * so a skill that was never practiced could not be inserted. The migration rebuilds the table in
 * place, keeping ids, rows and the referring skill_subtopics rows.
 */
public class SkillsTableMigrationTest {

    private final String testDbPath = "target/test_skills_migration.db";

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            // Start from the strict declaration and rows an existing database has.
            stmt.execute("DROP TABLE skills");
            stmt.execute("CREATE TABLE skills (" +
                    "skill_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, " +
                    "category TEXT NOT NULL, " +
                    "proficiency INTEGER NOT NULL, " +
                    "last_practiced TEXT NOT NULL)");
            stmt.execute("INSERT INTO skills (name, category, proficiency, last_practiced) " +
                    "VALUES ('web dev', 'General', 2, '2026-10-23')");
            // dtd belongs to web dev (skill 1) and must survive the rebuild.
            stmt.execute("INSERT INTO skill_subtopics (skill_id, name, position) VALUES (1, 'dtd', 1)");
        }
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testMigrationRebuildsLegacyTableAndKeepsRows() throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            assertNull(defltValue(conn), "The legacy column must have no default before migrating");

            DBConnection.initSchema(conn);

            assertEquals("'2000-01-01'", defltValue(conn), "Migrated column must default to the sentinel");
            assertEquals(0, notNullFlag(conn), "Migrated column must accept the default");

            // Existing row and its id survive.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT skill_id, name, last_practiced FROM skills")) {
                assertTrue(rs.next(), "The existing skill must survive the migration");
                assertEquals(1, rs.getInt("skill_id"));
                assertEquals("web dev", rs.getString("name"));
                assertEquals("2026-10-23", rs.getString("last_practiced"));
                assertFalse(rs.next());
            }

            // Its subtopic survived and still points at the skill.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) FROM skill_subtopics WHERE skill_id = 1 AND name = 'dtd'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "dtd must survive the table rebuild");
            }

            // Foreign keys are enforced again and nothing dangles.
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Foreign keys must be re-enabled after the migration");
                }
                try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_check")) {
                    assertFalse(rs.next(), "The rebuild must not leave a dangling reference");
                }
            }

            // The bug itself: a skill with no practice date can now be inserted.
            SkillDAO skillDAO = new SkillDAO();
            int newId = skillDAO.insert(new Skill(null, "guitar", "Music", 3, null));
            assertTrue(newId > 1, "Ids must keep growing from the existing AUTOINCREMENT sequence");

            Skill stored = skillDAO.findById(newId);
            assertTrue(stored.isNeverPracticed());
            assertNull(stored.getLastPracticed());
            assertEquals(2, skillDAO.getAll().size(), "Both skills must be readable");
        }
    }

    @Test
    void testMigrationIsIdempotent() throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
            String ddlAfterFirstRun = tableSql(conn);

            DBConnection.initSchema(conn);

            assertEquals(ddlAfterFirstRun, tableSql(conn), "A second run must not rebuild the table");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM skills")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "A second run must not duplicate or drop rows");
            }

            SkillDAO skillDAO = new SkillDAO();
            assertEquals(2, skillDAO.insert(new Skill(null, "guitar", "Music", 3, null)));
            assertNull(skillDAO.findById(2).getLastPracticed());
        }
    }

    private String tableSql(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'skills'")) {
            assertTrue(rs.next(), "The skills table must exist");
            return rs.getString(1);
        }
    }

    private String defltValue(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(skills)")) {
            while (rs.next()) {
                if ("last_practiced".equalsIgnoreCase(rs.getString("name"))) {
                    return rs.getString("dflt_value");
                }
            }
        }
        fail("skills.last_practiced must exist");
        return null;
    }

    private int notNullFlag(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(skills)")) {
            while (rs.next()) {
                if ("last_practiced".equalsIgnoreCase(rs.getString("name"))) {
                    return rs.getInt("notnull");
                }
            }
        }
        fail("skills.last_practiced must exist");
        return -1;
    }
}
