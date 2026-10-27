package com.learntrack.dao;

import com.learntrack.model.ExamPeriodRoutineEntry;
import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExamPeriodRoutineDAOTest {

    private ExamPeriodRoutineDAO routineDAO;
    private final String testDbPath = "target/test_routine.db";
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        SemesterDAO semesterDAO = new SemesterDAO();
        routineDAO = new ExamPeriodRoutineDAO();
        semesterId = semesterDAO.insert(new Semester(null, "Test Semester", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testSaveAndFindRoutineEntries() throws SQLException {
        List<ExamPeriodRoutineEntry> entries = List.of(
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(8, 0), LocalTime.of(9, 0), "MEAL", "Breakfast"),
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Night Sleep")
        );
        routineDAO.saveRoutineEntries(semesterId, entries);

        List<ExamPeriodRoutineEntry> found = routineDAO.findBySemester(semesterId);
        assertEquals(2, found.size());

        routineDAO.deleteBySemester(semesterId);
        assertTrue(routineDAO.findBySemester(semesterId).isEmpty());
    }
}
