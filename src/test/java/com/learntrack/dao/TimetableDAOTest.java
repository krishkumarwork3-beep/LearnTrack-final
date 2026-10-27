package com.learntrack.dao;

import com.learntrack.model.DateOverride;
import com.learntrack.model.Semester;
import com.learntrack.model.WeekdayStudyPreference;
import com.learntrack.model.WeeklyTimetableEntry;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TimetableDAOTest {

    private TimetableDAO timetableDAO;
    private SemesterDAO semesterDAO;
    private final String testDbPath = "target/test_timetable.db";
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        semesterDAO = new SemesterDAO();
        timetableDAO = new TimetableDAO();
        Semester sem = new Semester(null, "Test Semester", "ACTIVE");
        semesterId = semesterDAO.insert(sem);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testSaveTimetableEntriesAndPreferences() throws SQLException {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        entries.add(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "CS101"));
        entries.add(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(12, 0), LocalTime.of(13, 0), "MEAL", "Lunch"));

        timetableDAO.saveTimetableEntries(semesterId, entries);

        List<WeeklyTimetableEntry> found = timetableDAO.findBySemesterAndDay(semesterId, "MONDAY");
        assertEquals(2, found.size());
        assertEquals("CS101", found.get(0).getLabel());
        assertNull(found.get(0).getRoom());

        List<WeekdayStudyPreference> prefs = List.of(
                new WeekdayStudyPreference(semesterId, "MONDAY", 3.5),
                new WeekdayStudyPreference(semesterId, "TUESDAY", 4.0)
        );
        timetableDAO.saveStudyPreferences(semesterId, prefs);

        Map<String, Double> map = timetableDAO.findStudyPreferencesBySemester(semesterId);
        assertEquals(3.5, map.get("MONDAY"));
        assertEquals(4.0, map.get("TUESDAY"));
        assertEquals(3.5, timetableDAO.getStudyHours(semesterId, "MONDAY"));
    }

    @Test
    void testPreserveCancelledOverridesOnTimetableResave() throws SQLException {
        DateOverrideDAO dateOverrideDAO = new DateOverrideDAO();

        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "CS101");
        int entryId = timetableDAO.insertEntry(entry);

        LocalDate cancelDate = LocalDate.of(2026, 10, 12);
        dateOverrideDAO.insert(new DateOverride(null, cancelDate, entryId, "CANCEL", null, null, null, "Teacher sick"));

        // Re-save timetable unchanged, preserving entryId
        WeeklyTimetableEntry existing = new WeeklyTimetableEntry(entryId, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "CS101");
        java.util.Set<Integer> removed = timetableDAO.saveTimetableEntries(semesterId, List.of(existing));

        assertTrue(removed.isEmpty(), "No entries should be reported as removed");
        List<DateOverride> overrides = dateOverrideDAO.findByDate(cancelDate);
        assertEquals(1, overrides.size(), "Cancellation override must still apply after unchanged timetable save");
        assertEquals(entryId, overrides.get(0).getEntryId());
    }

    @Test
    void testRoutineSaveFindAndDelete() throws SQLException {
        com.learntrack.model.WeekdayRoutine mon = new com.learntrack.model.WeekdayRoutine(
                semesterId, "MONDAY", LocalTime.of(7, 0), LocalTime.of(23, 0));
        com.learntrack.model.WeekdayRoutine fri = new com.learntrack.model.WeekdayRoutine(
                semesterId, "FRIDAY", LocalTime.of(8, 0), LocalTime.of(1, 0));

        timetableDAO.saveRoutines(semesterId, List.of(mon, fri));

        List<com.learntrack.model.WeekdayRoutine> loaded = timetableDAO.findRoutinesBySemester(semesterId);
        assertEquals(2, loaded.size());

        Map<String, com.learntrack.model.WeekdayRoutine> map = timetableDAO.findRoutinesMapBySemester(semesterId);
        assertEquals(LocalTime.of(7, 0), map.get("MONDAY").getWakeTime());
        assertEquals(LocalTime.of(23, 0), map.get("MONDAY").getSleepTime());
        assertEquals(LocalTime.of(8, 0), map.get("FRIDAY").getWakeTime());
        assertEquals(LocalTime.of(1, 0), map.get("FRIDAY").getSleepTime());
        assertTrue(map.get("FRIDAY").isSleepAfterMidnight());

        // Test deleteBySemester removes routines
        timetableDAO.deleteBySemester(semesterId);
        assertTrue(timetableDAO.findRoutinesBySemester(semesterId).isEmpty());
    }

    @Test
    void testStartNewSemesterDeletesRoutines() throws SQLException {
        com.learntrack.model.WeekdayRoutine mon = new com.learntrack.model.WeekdayRoutine(
                semesterId, "MONDAY", LocalTime.of(7, 0), LocalTime.of(23, 0));
        timetableDAO.saveRoutines(semesterId, List.of(mon));
        assertFalse(timetableDAO.findRoutinesBySemester(semesterId).isEmpty());

        ExamPeriodRoutineDAO routineDAO = new ExamPeriodRoutineDAO();
        ExamDAO examDAO = new ExamDAO();
        TopicDAO topicDAO = new TopicDAO();
        DailyLogDAO dailyLogDAO = new DailyLogDAO();
        DateOverrideDAO dateOverrideDAO = new DateOverrideDAO();
        SettingsDAO settingsDAO = new SettingsDAO();
        com.learntrack.service.ScoringService scoringService = new com.learntrack.service.ScoringService();
        com.learntrack.service.FreeSlotService freeSlotService = new com.learntrack.service.FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        com.learntrack.service.PriorityQueueService pqService = new com.learntrack.service.PriorityQueueService(scoringService);
        com.learntrack.service.SemesterReplanService replanService = new com.learntrack.service.SemesterReplanService(
                timetableDAO, examDAO, topicDAO, new SkillDAO(), dailyLogDAO, settingsDAO,
                freeSlotService, new com.learntrack.service.ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO),
                scoringService, pqService);

        com.learntrack.service.SemesterService semesterService = new com.learntrack.service.SemesterService(
                semesterDAO, timetableDAO, routineDAO, examDAO, topicDAO, dailyLogDAO,
                dateOverrideDAO, settingsDAO, replanService);

        int newId = semesterService.startNewSemester("Spring 2027");
        assertTrue(timetableDAO.findRoutinesBySemester(semesterId).isEmpty(),
                "Old semester's routines must be deleted on startNewSemester");
    }

    @Test
    void testRoomRoundTrip() throws SQLException {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        entries.add(new WeeklyTimetableEntry(null, semesterId, "WEDNESDAY",
                LocalTime.of(15, 30), LocalTime.of(17, 10), "LAB", "URA521 practical", "F108"));
        timetableDAO.saveTimetableEntries(semesterId, entries);
        List<WeeklyTimetableEntry> found = timetableDAO.findBySemester(semesterId);
        assertEquals(1, found.size());
        assertEquals("F108", found.get(0).getRoom());

        found.get(0).setRoom("H107");
        timetableDAO.saveTimetableEntries(semesterId, found);
        List<WeeklyTimetableEntry> updated = timetableDAO.findBySemester(semesterId);
        assertEquals("H107", updated.get(0).getRoom());
    }
}
