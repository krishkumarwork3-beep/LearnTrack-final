package com.learntrack.dao;

import com.learntrack.model.DateOverride;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DateOverrideDAOTest {

    private DateOverrideDAO dateOverrideDAO;
    private final String testDbPath = "target/test_date_override.db";

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        dateOverrideDAO = new DateOverrideDAO();
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testInsertAndFindByDate() throws SQLException {
        LocalDate date = LocalDate.of(2026, 10, 15);
        DateOverride override = new DateOverride(null, date, 12, "CANCEL", null, null, null, "Class cancelled");
        int id = dateOverrideDAO.insert(override);
        assertTrue(id > 0);

        List<DateOverride> list = dateOverrideDAO.findByDate(date);
        assertEquals(1, list.size());
        assertEquals("CANCEL", list.get(0).getAction());
        assertEquals(12, list.get(0).getEntryId());

        dateOverrideDAO.delete(id);
        assertTrue(dateOverrideDAO.findByDate(date).isEmpty());
    }
}
