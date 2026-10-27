package com.learntrack.service;

import com.learntrack.dao.DateOverrideDAO;
import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamPeriodRoutineDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.model.DateOverride;
import com.learntrack.model.ExamPeriodRoutineEntry;
import com.learntrack.model.FreeSlot;
import com.learntrack.model.Semester;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExamPeriodFreeSlotServiceTest {

    private final String testDbPath = "target/test_exam_period_free_slot.db";
    private ExamPeriodRoutineDAO routineDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private SemesterDAO semesterDAO;
    private ExamPeriodFreeSlotService examPeriodFreeSlotService;
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        routineDAO = new ExamPeriodRoutineDAO();
        dateOverrideDAO = new DateOverrideDAO();
        examDAO = new ExamDAO();
        semesterDAO = new SemesterDAO();
        examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2026", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testCancellingWeeklyEntryDoesNotChangeExamPeriodFreeSlots() throws SQLException {
        LocalDate date = LocalDate.of(2026, 10, 15);

        // Fixed routine entries: Sleep 23:00 - 07:00 (8h), Lunch 12:00 - 13:00 (1h)
        routineDAO.insertEntry(new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Night Sleep"));
        routineDAO.insertEntry(new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(12, 0), LocalTime.of(13, 0), "MEAL", "Lunch"));

        List<FreeSlot> slotsBefore = examPeriodFreeSlotService.computeFreeSlots(semesterId, date);
        double freeBefore = slotsBefore.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(15.0, freeBefore, 0.05);

        // Add CANCEL override for weekly timetable entry id 1 (different ID space)
        dateOverrideDAO.insert(new DateOverride(null, date, 1, "CANCEL", null, null, null, "Class cancelled"));

        List<FreeSlot> slotsAfter = examPeriodFreeSlotService.computeFreeSlots(semesterId, date);
        double freeAfter = slotsAfter.stream().mapToDouble(FreeSlot::getDuration).sum();

        // Cancelling weekly timetable entry must NOT affect exam period free slots
        assertEquals(freeBefore, freeAfter, 0.001,
                "ExamPeriodFreeSlotService must ignore CANCEL overrides that reference weekly timetable entry IDs");

        // Verify that ADD overrides still apply in exam period
        dateOverrideDAO.insert(new DateOverride(null, date, null, "ADD",
                LocalTime.of(15, 0), LocalTime.of(16, 0), "Doctor", "Medical checkup"));

        List<FreeSlot> slotsWithAdd = examPeriodFreeSlotService.computeFreeSlots(semesterId, date);
        double freeWithAdd = slotsWithAdd.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(14.0, freeWithAdd, 0.05, "ADD overrides must still block time in ExamPeriodFreeSlotService");
    }

    @Test
    void testDailyRoutineWithSleepBlocksBothMidnightHalves() throws SQLException {
        LocalDate date = LocalDate.of(2026, 10, 16);
        // Daily routine with SLEEP 23:00 - 07:00 (cross midnight)
        routineDAO.insertEntry(new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Night Sleep"));

        List<FreeSlot> slots = examPeriodFreeSlotService.computeFreeSlots(semesterId, date);
        // Slots should be exactly [07:00 - 23:00], duration 16.0h
        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(7, 0), slots.get(0).getStartTime());
        assertEquals(LocalTime.of(23, 0), slots.get(0).getEndTime());
        assertEquals(16.0, slots.get(0).getDuration(), 0.001);
    }

    @Test
    void testEmptyRoutineWithClassHoursInWindowFreeClassHoursOutsideBlocked() throws SQLException {
        // Wednesday with CLASS 09:00-11:00 and 14:00-15:00, MEAL 13:00-14:00, wake 07:00, sleep 23:00
        // Empty exam_period_routine: ExamPeriodFreeSlotService derives default routine
        // Class hours should be FREE inside window, BLOCKED outside window (via FreeSlotService)
        
        com.learntrack.dao.TimetableDAO timetableDAO = new com.learntrack.dao.TimetableDAO();
        
        // Set up weekly routine for Wednesday
        timetableDAO.saveRoutines(semesterId, java.util.Collections.singletonList(
                new com.learntrack.model.WeekdayRoutine(
                        semesterId, "WEDNESDAY", LocalTime.of(7, 0), LocalTime.of(23, 0))));
        
        // Add CLASS entries
        int classId1 = timetableDAO.insertEntry(new com.learntrack.model.WeeklyTimetableEntry(
                null, semesterId, "WEDNESDAY", LocalTime.of(9, 0), LocalTime.of(11, 0), 
                "CLASS", "Math Lecture", null, null));
        int classId2 = timetableDAO.insertEntry(new com.learntrack.model.WeeklyTimetableEntry(
                null, semesterId, "WEDNESDAY", LocalTime.of(14, 0), LocalTime.of(15, 0), 
                "CLASS", "Physics Lab", null, null));
        
        // Add MEAL entry
        timetableDAO.insertEntry(new com.learntrack.model.WeeklyTimetableEntry(
                null, semesterId, "WEDNESDAY", LocalTime.of(13, 0), LocalTime.of(14, 0), 
                "MEAL", "Lunch", null, null));
        
        LocalDate wednesday = LocalDate.of(2026, 10, 14); // A Wednesday
        
        // Exam_period_routine is EMPTY - should derive default
        List<FreeSlot> slots = examPeriodFreeSlotService.computeFreeSlots(semesterId, wednesday);
        
        // Should block: 00:00-07:00 (sleep), 13:00-14:00 (meal), 23:00-24:00 (sleep)
        // Should NOT block: 09:00-11:00 and 14:00-15:00 (class hours are free in exam period)
        // Free time: 07:00-13:00 (6h) + 14:00-23:00 (9h) = 15h
        double totalFree = slots.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(15.0, totalFree, 0.05, "With empty exam_period_routine, class hours should be FREE");
        
        // Verify class hours are in free slots
        boolean hasClassSlot1 = slots.stream().anyMatch(s -> 
                !s.getStartTime().isAfter(LocalTime.of(9, 0)) && 
                !s.getEndTime().isBefore(LocalTime.of(11, 0)));
        boolean hasClassSlot2 = slots.stream().anyMatch(s -> 
                !s.getStartTime().isAfter(LocalTime.of(14, 0)) && 
                !s.getEndTime().isBefore(LocalTime.of(15, 0)));
        
        assertTrue(hasClassSlot1, "09:00-11:00 class hours should be free inside window");
        assertTrue(hasClassSlot2, "14:00-15:00 class hours should be free inside window");
    }

    @Test
    void testNoWakeSleepRoutineFallsBackTo0700And2300() throws SQLException {
        // No weekday routine for THURSDAY - should fallback to 07:00/23:00
        com.learntrack.dao.TimetableDAO timetableDAO = new com.learntrack.dao.TimetableDAO();
        
        // Add only a MEAL entry, no wake/sleep routine
        timetableDAO.insertEntry(new com.learntrack.model.WeeklyTimetableEntry(
                null, semesterId, "THURSDAY", LocalTime.of(12, 0), LocalTime.of(13, 0), 
                "MEAL", "Lunch", null, null));
        
        LocalDate thursday = LocalDate.of(2026, 10, 15);
        
        // Should not throw exception, should use 07:00/23:00 fallback
        List<FreeSlot> slots = examPeriodFreeSlotService.computeFreeSlots(semesterId, thursday);
        
        // Free time: 07:00-12:00 (5h) + 13:00-23:00 (10h) = 15h
        double totalFree = slots.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(15.0, totalFree, 0.05, "Should use 07:00/23:00 fallback when no routine exists");
        
        // Verify sleep blocks are at default times
        boolean hasMorningSleep = slots.stream().noneMatch(s -> 
                s.getStartTime().isBefore(LocalTime.of(7, 0)));
        boolean hasEveningSleep = slots.stream().noneMatch(s -> 
                s.getEndTime().isAfter(LocalTime.of(23, 0)));
        
        assertTrue(hasMorningSleep, "Should block time before 07:00 (fallback wake time)");
        assertTrue(hasEveningSleep, "Should block time after 23:00 (fallback sleep time)");
    }
}
