package com.learntrack.dao;

import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class KeptSubjectDAOTest {

    private SemesterDAO semesterDAO;
    private final String testDbPath = "target/test_kept_subject.db";

    @BeforeEach
    void setup() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        semesterDAO = new SemesterDAO();
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    private int createSemester(String name) throws SQLException {
        Semester sem = new Semester();
        sem.setName(name);
        sem.setStatus("ACTIVE");
        return semesterDAO.insert(sem);
    }

    @Test
    void testUpsertAndFindAll() throws SQLException {
        int semId = createSemester("Test Semester");

        KeptSubjectDAO.upsert(semId, "UCS503", "Database Systems");
        KeptSubjectDAO.upsert(semId, "UML501", "Machine Learning");

        List<KeptSubjectDAO.KeptSubject> kept = KeptSubjectDAO.findAll(semId);
        assertEquals(2, kept.size());
        assertEquals("UCS503", kept.get(0).getSubjectCode());
        assertEquals("Database Systems", kept.get(0).getDisplayName());
    }

    @Test
    void testUpsertUpdatesDisplayName() throws SQLException {
        int semId = createSemester("Test Semester");

        KeptSubjectDAO.upsert(semId, "UCS503", "Old Name");
        KeptSubjectDAO.upsert(semId, "UCS503", "New Name");

        List<KeptSubjectDAO.KeptSubject> kept = KeptSubjectDAO.findAll(semId);
        assertEquals(1, kept.size());
        assertEquals("New Name", kept.get(0).getDisplayName());
    }

    @Test
    void testDelete() throws SQLException {
        int semId = createSemester("Test Semester");

        KeptSubjectDAO.upsert(semId, "UCS503", "Database Systems");
        KeptSubjectDAO.upsert(semId, "UML501", "Machine Learning");
        assertTrue(KeptSubjectDAO.exists(semId, "UCS503"));

        KeptSubjectDAO.delete(semId, "UCS503");
        assertFalse(KeptSubjectDAO.exists(semId, "UCS503"));
        assertTrue(KeptSubjectDAO.exists(semId, "UML501"));
    }

    @Test
    void testFindAllAsMap() throws SQLException {
        int semId = createSemester("Test Semester");

        KeptSubjectDAO.upsert(semId, "UCS503", "Database Systems");
        KeptSubjectDAO.upsert(semId, "UML501", "Machine Learning");

        Map<String, String> map = KeptSubjectDAO.findAllAsMap(semId);
        assertEquals(2, map.size());
        assertEquals("Database Systems", map.get("UCS503"));
        assertEquals("Machine Learning", map.get("UML501"));
    }

    @Test
    void testDeleteAllForSemester() throws SQLException {
        int semId = createSemester("Test Semester");

        KeptSubjectDAO.upsert(semId, "UCS503", "Database Systems");
        KeptSubjectDAO.upsert(semId, "UML501", "Machine Learning");
        assertEquals(2, KeptSubjectDAO.findAll(semId).size());

        KeptSubjectDAO.deleteAllForSemester(semId);
        assertEquals(0, KeptSubjectDAO.findAll(semId).size());
    }
}
