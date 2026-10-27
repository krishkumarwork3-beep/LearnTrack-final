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

import static org.junit.jupiter.api.Assertions.*;

public class SemesterDAOTest {

    private SemesterDAO semesterDAO;
    private final String testDbPath = "target/test_semester.db";

    @BeforeEach
    void setUp() throws SQLException {
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

    @Test
    void testInsertAndGetActiveSemester() throws SQLException {
        assertNull(semesterDAO.getActiveSemester());

        Semester sem = new Semester(null, "Fall 2026", "ACTIVE");
        int id = semesterDAO.insert(sem);
        assertTrue(id > 0);

        Semester active = semesterDAO.getActiveSemester();
        assertNotNull(active);
        assertEquals("Fall 2026", active.getName());
        assertEquals("ACTIVE", active.getStatus());
    }

    @Test
    void testUpdateAndDelete() throws SQLException {
        Semester sem = new Semester(null, "Spring 2027", "ACTIVE");
        int id = semesterDAO.insert(sem);

        sem.setName("Spring 2027 Updated");
        sem.setStatus("COMPLETED");
        semesterDAO.update(sem);

        Semester found = semesterDAO.findById(id);
        assertEquals("Spring 2027 Updated", found.getName());
        assertEquals("COMPLETED", found.getStatus());

        semesterDAO.delete(id);
        assertNull(semesterDAO.findById(id));
    }
}
