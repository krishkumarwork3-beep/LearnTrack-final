package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.*;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FreeSlotServiceTest {

    private final String testDbPath = "target/test_free_slot.db";
    private TimetableDAO timetableDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private SemesterDAO semesterDAO;
    private FreeSlotService freeSlotService;
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        timetableDAO = new TimetableDAO();
        dateOverrideDAO = new DateOverrideDAO();
        examDAO = new ExamDAO();
        semesterDAO = new SemesterDAO();
        freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2026", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
    }

    @Test
    void testComputeFreeHoursForEntriesWithCrossMidnightSleep() {
        // Item 6: computeFreeHoursForEntries including a cross-midnight SLEEP entry
        List<WeeklyTimetableEntry> entries = List.of(
                new WeeklyTimetableEntry(null, semesterId, "MONDAY", LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Night Sleep"),
                new WeeklyTimetableEntry(null, semesterId, "MONDAY", LocalTime.of(9, 0), LocalTime.of(12, 0), "CLASS", "Morning Class"),
                new WeeklyTimetableEntry(null, semesterId, "MONDAY", LocalTime.of(14, 0), LocalTime.of(16, 0), "CLASS", "Lab")
        );

        // Sleep 23-7 = 8h, Class 9-12 = 3h, Lab 14-16 = 2h -> Blocked: 13h -> Free: 11h
        double freeHours = freeSlotService.computeFreeHoursForEntries(entries);
        assertEquals(11.0, freeHours, 0.05, "Free hours should be 11.0 after accounting for cross-midnight sleep and classes");
    }

    @Test
    void testComputeFreeHoursForEmptyEntries() {
        double freeEmpty = freeSlotService.computeFreeHoursForEntries(Collections.emptyList());
        assertEquals(24.0, freeEmpty, 0.01);

        double freeNull = freeSlotService.computeFreeHoursForEntries(null);
        assertEquals(24.0, freeNull, 0.01);
    }

    @Test
    void testComputeFreeSlotsWithExamBufferAndCancellations() throws SQLException {
        LocalDate date = LocalDate.of(2026, 10, 12); // Monday
        // Recurring class 9:00 - 12:00
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(12, 0), "CLASS", "Math");
        int entryId = timetableDAO.insertEntry(entry);

        // Dated exam sitting 14:00 - 16:00 with travel time 30 mins
        // Buffer: [14:00 - 30m, 16:00 + 1h] = [13:30, 17:00] (3.5h)
        Exam exam = new Exam(null, semesterId, "Midterm", "NORMAL", 3, 20.0,
                date, LocalTime.of(14, 0), LocalTime.of(16, 0), 30, null, null, 0.0);
        examDAO.insert(exam);

        List<FreeSlot> slots = freeSlotService.computeFreeSlots(semesterId, date);
        // Free slots before cancellation: [00:00-09:00], [12:00-13:30], [17:00-24:00]
        double totalFreeBefore = slots.stream().mapToDouble(FreeSlot::getDuration).sum();
        // 24h - 3h (class) - 3.5h (exam buffer) = 17.5h
        assertEquals(17.5, totalFreeBefore, 0.05);

        // Now cancel the class for this date
        dateOverrideDAO.insert(new DateOverride(null, date, entryId, "CANCEL", null, null, null, "Class cancelled"));

        List<FreeSlot> slotsAfter = freeSlotService.computeFreeSlots(semesterId, date);
        // After cancellation, class 9-12 is free! Total free: 17.5 + 3.0 = 20.5h
        double totalFreeAfter = slotsAfter.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(20.5, totalFreeAfter, 0.05, "Cancellation must restore class time to free slots");
    }

    @Test
    void testWakeSleepModelUniformAcrossAllDays() throws SQLException {
        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        // Check Monday 2026-10-12 through Sunday 2026-10-18
        LocalDate start = LocalDate.of(2026, 10, 12);
        for (int i = 0; i < 7; i++) {
            LocalDate date = start.plusDays(i);
            List<FreeSlot> slots = freeSlotService.computeFreeSlots(semesterId, date);
            double totalFree = slots.stream().mapToDouble(FreeSlot::getDuration).sum();
            assertEquals(16.0, totalFree, 0.001, "Every day without commitments should have 16.0h free");
            assertEquals(1, slots.size());
            assertEquals(LocalTime.of(7, 0), slots.get(0).getStartTime());
            assertEquals(LocalTime.of(23, 0), slots.get(0).getEndTime());
        }
    }

    @Test
    void testScreenshotScenarioMondayClassAndTuesdayFree() throws SQLException {
        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        // Monday class 09:00 - 10:00
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "CS101"));

        LocalDate monday = LocalDate.of(2026, 10, 12);
        LocalDate tuesday = LocalDate.of(2026, 10, 13);

        List<FreeSlot> mondaySlots = freeSlotService.computeFreeSlots(semesterId, monday);
        double mondayFree = mondaySlots.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(15.0, mondayFree, 0.001, "Monday should have 15.0h free");

        List<FreeSlot> tuesdaySlots = freeSlotService.computeFreeSlots(semesterId, tuesday);
        double tuesdayFree = tuesdaySlots.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(16.0, tuesdayFree, 0.001, "Tuesday must be 16.0h, NOT 24.0h");
    }

    @Test
    void testSleepAfterMidnightSpill() throws SQLException {
        // Wake 07:00, Sleep 01:00 (after midnight)
        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(1, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        LocalDate tuesday = LocalDate.of(2026, 10, 13);
        List<FreeSlot> slots = freeSlotService.computeFreeSlots(semesterId, tuesday);
        double totalFree = slots.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(18.0, totalFree, 0.001, "Total free time should be 18.0h when sleeping at 01:00");

        // Slots should include [00:00, 01:00) and [07:00, 24:00)
        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(0, 0), slots.get(0).getStartTime());
        assertEquals(LocalTime.of(1, 0), slots.get(0).getEndTime());
        assertEquals(LocalTime.of(7, 0), slots.get(1).getStartTime());
        assertEquals(LocalTime.MAX, slots.get(1).getEndTime());
    }

    @Test
    void testPerDayOverrideSaturday() throws SQLException {
        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        // Saturday wake 09:00, sleep 23:00 -> 14.0h
        routines.add(new WeekdayRoutine(semesterId, "SATURDAY", LocalTime.of(9, 0), LocalTime.of(23, 0)));
        timetableDAO.saveRoutines(semesterId, routines);

        LocalDate saturday = LocalDate.of(2026, 10, 17);
        List<FreeSlot> satSlots = freeSlotService.computeFreeSlots(semesterId, saturday);
        double satFree = satSlots.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(14.0, satFree, 0.001, "Saturday should have 14.0h free");

        LocalDate sunday = LocalDate.of(2026, 10, 18);
        List<FreeSlot> sunSlots = freeSlotService.computeFreeSlots(semesterId, sunday);
        double sunFree = sunSlots.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(16.0, sunFree, 0.001, "Sunday should remain 16.0h free");
    }

    @Test
    void testWeeklyCommitmentCrossingMidnight() throws SQLException {
        // Routine: Wake 07:00, Sleep 02:00 for all days (awake covers midnight)
        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(2, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        // Friday 23:00 - 01:00 OTHER
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "FRIDAY",
                LocalTime.of(23, 0), LocalTime.of(1, 0), "OTHER", "Late Movie"));

        LocalDate friday = LocalDate.of(2026, 10, 16);
        LocalDate saturday = LocalDate.of(2026, 10, 17);

        List<FreeSlot> friSlots = freeSlotService.computeFreeSlots(semesterId, friday);
        // Friday is awake [00:00-02:00] (from Thursday night) and [07:00-24:00]
        // Friday commitment blocks [23:00-24:00]
        // So Friday free time has [00:00-02:00] free (not blocked by Friday 23:00 commitment!)
        boolean friMidnightFree = friSlots.stream().anyMatch(s -> s.getStartTime().equals(LocalTime.of(0, 0)));
        assertTrue(friMidnightFree, "Friday 00:00-01:00 must NOT be blocked by Friday 23:00-01:00 commitment");

        // Saturday awake starts [00:00-02:00], but Friday commitment spills [00:00-01:00] as blocked!
        List<FreeSlot> satSlots = freeSlotService.computeFreeSlots(semesterId, saturday);
        // Saturday first free slot should start at 01:00 (since 00:00-01:00 is blocked by Friday commitment)
        assertEquals(LocalTime.of(1, 0), satSlots.get(0).getStartTime(),
                "Saturday 00:00-01:00 must be blocked by Friday cross-midnight commitment");
    }

    @Test
    void testLegacyPathWithoutRoutines() throws SQLException {
        // No weekday_routine rows in DB
        // Add a single class on Monday 09:00 - 12:00 (3h)
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(12, 0), "CLASS", "Legacy Math"));

        LocalDate monday = LocalDate.of(2026, 10, 12);
        List<FreeSlot> slots = freeSlotService.computeFreeSlots(semesterId, monday);
        double totalFree = slots.stream().mapToDouble(FreeSlot::getDuration).sum();
        // Legacy: entire 24h minus 3h = 21.0h
        assertEquals(21.0, totalFree, 0.001, "Legacy path without routines should treat unblocked day as 24h");
    }

    @Test
    void testComputeFreeHoursForDayInWizard() {
        Map<String, WeekdayRoutine> routines = new HashMap<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.put(day, new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }

        List<WeeklyTimetableEntry> entries = List.of(
                new WeeklyTimetableEntry(null, semesterId, "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0), "CLASS", "Math")
        );

        double mondayHours = freeSlotService.computeFreeHoursForDay("MONDAY", routines, entries);
        assertEquals(14.0, mondayHours, 0.001);

        double tuesdayHours = freeSlotService.computeFreeHoursForDay("TUESDAY", routines, entries);
        assertEquals(16.0, tuesdayHours, 0.001);
    }
}
