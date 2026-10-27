package com.learntrack.dao;

import com.learntrack.model.WeeklyTimetableEntry;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TimetableDAO subject_code column round-trip.
 */
class TimetableDAOSubjectCodeTest {

    private TimetableDAO dao;
    private Connection testConn;
    private final String testDbPath = "target/test_timetable_subject_code.db";

    @BeforeEach
    void setUp() throws SQLException {
        new java.io.File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        dao = new TimetableDAO();
        testConn = DBConnection.getConnection();
        DBConnection.initSchema(testConn);
        testConn.createStatement().execute("INSERT OR IGNORE INTO semesters (semester_id, name, status) VALUES (1, 'Test Semester', 'ACTIVE')");
        testConn.createStatement().execute("DELETE FROM weekly_timetable");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (testConn != null && !testConn.isClosed()) {
            testConn.close();
        }
        DBConnection.resetToDefaultUrl();
    }

    @Test
    void testInsertAndFindWithSubjectCode() throws SQLException {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry();
        entry.setSemesterId(1);
        entry.setDayOfWeek("MONDAY");
        entry.setStartTime(LocalTime.of(9, 0));
        entry.setEndTime(LocalTime.of(10, 30));
        entry.setActivityType("CLASS");
        entry.setLabel("Data Structures lecture");
        entry.setRoom("CR101");
        entry.setSubjectCode("UCS503");

        int id = dao.insertEntry(entry);
        assertTrue(id > 0);

        List<WeeklyTimetableEntry> found = dao.findBySemester(1);
        assertEquals(1, found.size());
        
        WeeklyTimetableEntry retrieved = found.get(0);
        assertEquals("UCS503", retrieved.getSubjectCode());
        assertEquals("CR101", retrieved.getRoom());
        assertEquals("Data Structures lecture", retrieved.getLabel());
    }

    @Test
    void testInsertWithoutSubjectCode() throws SQLException {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry();
        entry.setSemesterId(1);
        entry.setDayOfWeek("TUESDAY");
        entry.setStartTime(LocalTime.of(11, 0));
        entry.setEndTime(LocalTime.of(12, 0));
        entry.setActivityType("OTHER");
        entry.setLabel("Lunch");
        entry.setRoom(null);
        entry.setSubjectCode(null);

        int id = dao.insertEntry(entry);
        assertTrue(id > 0);

        List<WeeklyTimetableEntry> found = dao.findBySemester(1);
        assertEquals(1, found.size());
        
        WeeklyTimetableEntry retrieved = found.get(0);
        assertNull(retrieved.getSubjectCode());
        assertNull(retrieved.getRoom());
        assertEquals("Lunch", retrieved.getLabel());
    }

    @Test
    void testSaveTimetableEntriesWithSubjectCode() throws SQLException {
        WeeklyTimetableEntry entry1 = new WeeklyTimetableEntry(null, 1, "WEDNESDAY",
                LocalTime.of(14, 0), LocalTime.of(15, 0), "TUTORIAL", "Algorithms tutorial", "Lab2", "UCS502");
        WeeklyTimetableEntry entry2 = new WeeklyTimetableEntry(null, 1, "THURSDAY",
                LocalTime.of(10, 0), LocalTime.of(11, 30), "LAB", "UCS503 practical", "LR3", "UCS503");

        dao.saveTimetableEntries(1, List.of(entry1, entry2));

        List<WeeklyTimetableEntry> found = dao.findBySemester(1);
        assertEquals(2, found.size());
        
        // Verify both codes are present (order may vary)
        assertTrue(found.stream().anyMatch(e -> "UCS502".equals(e.getSubjectCode())));
        assertTrue(found.stream().anyMatch(e -> "UCS503".equals(e.getSubjectCode())));
    }

    @Test
    void testUpdatePreservesSubjectCode() throws SQLException {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, 1, "FRIDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "Networking lecture", "CR202", "UCS601");
        
        dao.saveTimetableEntries(1, List.of(entry));
        List<WeeklyTimetableEntry> found = dao.findBySemester(1);
        assertEquals(1, found.size());
        
        WeeklyTimetableEntry toUpdate = found.get(0);
        toUpdate.setRoom("CR203");
        toUpdate.setLabel("Computer Networks lecture");
        
        dao.saveTimetableEntries(1, List.of(toUpdate));
        found = dao.findBySemester(1);
        assertEquals(1, found.size());
        
        assertEquals("UCS601", found.get(0).getSubjectCode());
        assertEquals("CR203", found.get(0).getRoom());
        assertEquals("Computer Networks lecture", found.get(0).getLabel());
    }
}
