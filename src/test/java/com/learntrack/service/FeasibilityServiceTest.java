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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FeasibilityServiceTest {

    private final String testDbPath = "target/test_feasibility.db";
    private TimetableDAO timetableDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SemesterDAO semesterDAO;
    private FreeSlotService freeSlotService;
    private FeasibilityService feasibilityService;

    private int semesterId;
    private final LocalDate today = LocalDate.of(2026, 10, 10);

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
        topicDAO = new TopicDAO();
        semesterDAO = new SemesterDAO();
        freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        feasibilityService = new FeasibilityService(topicDAO, examDAO, timetableDAO, freeSlotService);

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2026", "ACTIVE"));

        // Setup timetable: sleep 23-7 (8h), class 9-12 (3h) -> 13h free time/day
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(9, 0), LocalTime.of(12, 0), "CLASS", "Class"));
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Sleep"));
        }

        // Preference: 4h study per day
        timetableDAO.saveStudyPreferences(semesterId, List.of(
                new WeekdayStudyPreference(semesterId, "MONDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "TUESDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "WEDNESDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "THURSDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "FRIDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "SATURDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "SUNDAY", 4.0)
        ));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
    }

    @Test
    void testPastExamsExcludedFromNeededHours() throws SQLException {
        // Exam in the past (5 days ago)
        Exam pastExam = new Exam(null, semesterId, "Past Subject", "NORMAL", 3, 20.0,
                today.minusDays(5), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, null, null, 0.0);
        int pastExamId = examDAO.insert(pastExam);
        topicDAO.insert(new Topic(null, pastExamId, "Past Topic", 5, 4, 15.0, 15.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        // Upcoming exam (3 days ahead: today, today+1, today+2, today+3 -> 4 days * 4h = 16h capacity)
        Exam upcomingExam = new Exam(null, semesterId, "Upcoming Subject", "NORMAL", 3, 20.0,
                today.plusDays(3), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, null, null, 0.0);
        int upId = examDAO.insert(upcomingExam);
        topicDAO.insert(new Topic(null, upId, "Upcoming Topic", 5, 4, 10.0, 10.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        FeasibilityService.FeasibilityResult result = feasibilityService.calculateFeasibility(semesterId, today);

        // Needed hours should ONLY count the upcoming topic (10h), excluding past topic (15h)
        assertEquals(10.0, result.getNeededHours(), 0.01, "Past exams must not add to needed hours");
        assertTrue(result.isFeasible(), "10h needed should fit within ~16h capacity across 4 days");
    }

    @Test
    void testExamBuffersAndCancellationsImpactCapacity() throws SQLException {
        // Upcoming exam on today+1 (2 days of capacity: today and tomorrow)
        // With preference 4h/day, base capacity is 8h (4h today + 4h tomorrow).
        Exam exam = new Exam(null, semesterId, "Subject", "NORMAL", 3, 20.0,
                today.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, null, null, 0.0);
        int examId = examDAO.insert(exam);
        topicDAO.insert(new Topic(null, examId, "Topic", 5, 4, 6.0, 6.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        FeasibilityService.FeasibilityResult baseResult = feasibilityService.calculateFeasibility(semesterId, today);
        assertEquals(6.0, baseResult.getNeededHours(), 0.01);
        assertEquals(8.0, baseResult.getCapacityHours(), 0.01);

        // 1. Exam buffer sitting today reduces available free time below the 4h preference:
        // Sitting 10:00 - 21:00 with 4h travel (start minus 4h = 06:00, end plus 1h = 22:00).
        // Free time is [22:00-23:00] (1h). Daily cap today drops from 4h to 1h, total capacity drops to 5h.
        Exam sittingToday = new Exam(null, semesterId, "Sitting Today", "NORMAL", 2, 10.0,
                today, LocalTime.of(10, 0), LocalTime.of(21, 0), 240, null, null, 0.0);
        int sittingId = examDAO.insert(sittingToday);

        FeasibilityService.FeasibilityResult bufferedResult = feasibilityService.calculateFeasibility(semesterId, today);
        assertTrue(bufferedResult.getCapacityHours() < baseResult.getCapacityHours(),
                "Exam buffer sittings reduce available free time and daily capacity (was: " + bufferedResult.getCapacityHours() + " vs base: " + baseResult.getCapacityHours() + ")");
        assertEquals(5.0, bufferedResult.getCapacityHours(), 0.05);

        // 2. Cancellation increases free time and restores capacity:
        // Delete sitting exam to restore base capacity
        examDAO.delete(sittingId);
        // Set high study preference (15h/day). Base free time is 13h due to 3h class (9:00-12:00).
        timetableDAO.saveStudyPreferences(semesterId, List.of(
                new WeekdayStudyPreference(semesterId, today.getDayOfWeek().name(), 15.0),
                new WeekdayStudyPreference(semesterId, today.plusDays(1).getDayOfWeek().name(), 15.0)
        ));
        FeasibilityService.FeasibilityResult highPrefResult = feasibilityService.calculateFeasibility(semesterId, today);
        // Today free time is 13h (class 9-12 + sleep 23-7). Tomorrow free time is 12h (class 9-12 merged with exam sitting 10-13 + sleep 23-7).
        // Capacity with 15h pref is min(13h, 15h) + min(12h, 15h) = 25.0h
        assertEquals(25.0, highPrefResult.getCapacityHours(), 0.05);

        // Cancel today's class (entryId from weekly_timetable)
        WeeklyTimetableEntry classEntry = timetableDAO.findBySemesterAndDay(semesterId, today.getDayOfWeek().name()).stream()
                .filter(e -> "CLASS".equalsIgnoreCase(e.getActivityType()))
                .findFirst().orElseThrow();
        dateOverrideDAO.insert(new DateOverride(null, today, classEntry.getEntryId(), "CANCEL", null, null, null, "Cancelled class"));

        FeasibilityService.FeasibilityResult afterCancelResult = feasibilityService.calculateFeasibility(semesterId, today);
        // Today free time is now 16h (min(16h, 15h) = 15h). Total capacity = 15h + 12h = 27h > 25h!
        assertTrue(afterCancelResult.getCapacityHours() > highPrefResult.getCapacityHours(),
                "Class cancellation must increase free hours and improve study capacity");
        assertEquals(27.0, afterCancelResult.getCapacityHours(), 0.05);
    }

    @Test
    void testUndatedExamsDoNotBreakFeasibility() throws SQLException {
        // Exam with completely unset date and window
        Exam undatedExam = new Exam(null, semesterId, "Undated Subject", "EST", 3, 20.0,
                null, null, null, 30, null, null, 0.0);
        int examId = examDAO.insert(undatedExam);
        topicDAO.insert(new Topic(null, examId, "Undated Topic", 5, 4, 8.0, 8.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        // Feasibility check should not throw and should use the 60-day horizon
        FeasibilityService.FeasibilityResult result = feasibilityService.calculateFeasibility(semesterId, today);
        assertNotNull(result);
        assertEquals(8.0, result.getNeededHours(), 0.01, "Undated exam topics should be counted in needed hours");
        assertTrue(result.getCapacityHours() > 0, "Capacity should be computed over the undated horizon");
        assertTrue(result.isFeasible(), "Ample capacity over 60-day horizon should be feasible");
    }

    /**
     * Quote: PLANNER_FIX_PLAN.md line 21:
     * "- PYQ only after all learning for that exam is done, and only for exams that have syllabus scope."
     * and line 59:
     * "(+ PYQ only for exams with scope)"
     */
    @Test
    void testPyqOnlyForExamsWithScope() throws SQLException {
        // Exam 1 with scope (has a topic) and 3.0h PYQ
        Exam examWithScope = new Exam(null, semesterId, "Subject With Scope", "NORMAL", 3, 20.0,
                today.plusDays(3), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, null, null, 3.0);
        int e1Id = examDAO.insert(examWithScope);
        topicDAO.insert(new Topic(null, e1Id, "Topic 1", 5, 4, 2.0, 2.0, "LEARNING", 1.0, 1.0, 7, null, 1));

        // Exam 2 WITHOUT scope (no topics, no exam_syllabus_scope rows) and 4.0h PYQ
        Exam examWithoutScope = new Exam(null, semesterId, "Subject No Scope", "NORMAL", 3, 20.0,
                today.plusDays(3), LocalTime.of(14, 0), LocalTime.of(16, 0), 0, null, null, 4.0);
        examDAO.insert(examWithoutScope);

        FeasibilityService.FeasibilityResult result = feasibilityService.calculateFeasibility(semesterId, today);

        // Expected needed: 2.0h (topic) + 3.0h (PYQ for exam with scope).
        // Exam 2's 4.0h PYQ must NOT be counted because it has no syllabus scope.
        assertEquals(5.0, result.getNeededHours(), 0.01,
                "PYQ hours must only count for exams with syllabus scope; exam without scope must be excluded");
    }

    /**
     * Quote: PLANNER_FIX_PLAN.md line 59:
     * "It must not show 0.0 / 0.0 when topics exist."
     */
    @Test
    void testNeverZeroZeroWhenTopicsExist() throws SQLException {
        Exam upcomingExam = new Exam(null, semesterId, "Subject Active", "NORMAL", 3, 20.0,
                today.plusDays(4), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, null, null, 0.0);
        int examId = examDAO.insert(upcomingExam);
        topicDAO.insert(new Topic(null, examId, "Active Topic", 5, 4, 7.5, 7.5, "LEARNING", 2.0, 2.0, 7, null, 1));

        FeasibilityService.FeasibilityResult result = feasibilityService.calculateFeasibility(semesterId, today);

        assertNotNull(result);
        assertTrue(result.getNeededHours() > 0.0, "Needed hours must not be 0.0 when topics exist");
        assertTrue(result.getCapacityHours() > 0.0, "Capacity hours must not be 0.0 when study capacity exists");
        assertFalse(result.getNeededHours() == 0.0 && result.getCapacityHours() == 0.0,
                "Feasibility must never report 0.0 / 0.0 when topics exist");
    }

    /**
     * Quote: PLANNER_FIX_PLAN.md line 19:
     * "- If a subtopic was covered for an earlier exam (MST etc.), later exams use hours_to_revise only (already implemented in ExamScopeService)."
     * and line 59:
     * "needed = remaining learn + due revision"
     */
    @Test
    void testRevisionOnlyTopicsCountReviseHours() throws SQLException {
        Exam upcomingExam = new Exam(null, semesterId, "EST Exam", "EST", 4, 50.0,
                today.plusDays(5), LocalTime.of(9, 0), LocalTime.of(12, 0), 0, null, null, 0.0);
        int examId = examDAO.insert(upcomingExam);

        // Topic with 0 learning hours (e.g. covered in earlier MST), in REVISION phase with 3.5h revision remaining
        // lastRevised is null so revision is due immediately
        Topic revisionTopic = new Topic(null, examId, "Covered Earlier Topic", 4, 3,
                0.0, 0.0, "REVISION", 3.5, 3.5, 7, null, 1);
        topicDAO.insert(revisionTopic);

        FeasibilityService.FeasibilityResult result = feasibilityService.calculateFeasibility(semesterId, today);

        assertEquals(3.5, result.getNeededHours(), 0.01,
                "Revision-only topics with due revision must count their revision remaining hours into needed hours");
    }
    @Test
    void testClassHoursNotSubtractedInsideDatedWindow() throws SQLException {
        LocalDate wednesday = LocalDate.of(2026, 10, 14); // Wednesday

        // Dedicated semester so the shared setUp timetable is untouched.
        int windowSemesterId = semesterDAO.insert(new Semester(null, "Window Semester", "ACTIVE"));
        List<WeekdayRoutine> routines = new java.util.ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(windowSemesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(windowSemesterId, routines);

        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, windowSemesterId, "WEDNESDAY",
                LocalTime.of(9, 0), LocalTime.of(11, 0), "CLASS", "Morning Class"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, windowSemesterId, "WEDNESDAY",
                LocalTime.of(14, 0), LocalTime.of(15, 0), "CLASS", "Afternoon Class"));

        // No weekday study cap for this semester, so capacity == free time.
        timetableDAO.saveStudyPreferences(windowSemesterId, List.of(
                new WeekdayStudyPreference(windowSemesterId, "MONDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "TUESDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "WEDNESDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "THURSDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "FRIDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "SATURDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "SUNDAY", 24.0)
        ));

        // Stored MST window covering the Wednesday, datesheet announced.
        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setWindow(windowSemesterId, "MST", wednesday.minusDays(2), wednesday.plusDays(2));
        timelineDAO.setDatesheetAnnounced(windowSemesterId, "MST", true);

        // Deadline exam dated on the Wednesday (no sitting times, so no exam buffer).
        examDAO.insert(new Exam(null, windowSemesterId, "Deadline Subject", "NORMAL", 3, 20.0,
                wednesday, null, null, 0, null, null, 0.0));

        ExamPeriodFreeSlotService windowFreeSlotService = new ExamPeriodFreeSlotService(
                new ExamPeriodRoutineDAO(), dateOverrideDAO, examDAO, timetableDAO);
        FeasibilityService windowFeasibility = new FeasibilityService(
                topicDAO, examDAO, timetableDAO, freeSlotService,
                new ExamScopeDAO(), new ScoringService(),
                new ExamWindowService(), windowFreeSlotService);

        FeasibilityService.FeasibilityResult result =
                windowFeasibility.calculateFeasibility(windowSemesterId, wednesday);

        // One counted day (the Wednesday): 24h - 8h sleep = 16h. The two CLASS entries (3h)
        // must NOT be subtracted because ExamPeriodFreeSlotService is used inside the window.
        assertEquals(16.0, result.getCapacityHours(), 0.01,
                "Inside a dated window the class hours must not be subtracted from capacity");
    }

    @Test
    void testClassHoursSubtractedOutsideWindow() throws SQLException {
        LocalDate wednesday = LocalDate.of(2026, 10, 14); // Wednesday

        int windowSemesterId = semesterDAO.insert(new Semester(null, "Window Semester", "ACTIVE"));
        List<WeekdayRoutine> routines = new java.util.ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(windowSemesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(windowSemesterId, routines);

        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, windowSemesterId, "WEDNESDAY",
                LocalTime.of(9, 0), LocalTime.of(11, 0), "CLASS", "Morning Class"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, windowSemesterId, "WEDNESDAY",
                LocalTime.of(14, 0), LocalTime.of(15, 0), "CLASS", "Afternoon Class"));

        timetableDAO.saveStudyPreferences(windowSemesterId, List.of(
                new WeekdayStudyPreference(windowSemesterId, "MONDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "TUESDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "WEDNESDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "THURSDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "FRIDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "SATURDAY", 24.0),
                new WeekdayStudyPreference(windowSemesterId, "SUNDAY", 24.0)
        ));

        examDAO.insert(new Exam(null, windowSemesterId, "Deadline Subject", "NORMAL", 3, 20.0,
                wednesday, null, null, 0, null, null, 0.0));

        ExamPeriodFreeSlotService windowFreeSlotService = new ExamPeriodFreeSlotService(
                new ExamPeriodRoutineDAO(), dateOverrideDAO, examDAO, timetableDAO);
        FeasibilityService service = new FeasibilityService(
                topicDAO, examDAO, timetableDAO, freeSlotService,
                new ExamScopeDAO(), new ScoringService(),
                new ExamWindowService(), windowFreeSlotService);

        // No stored/announced window at this point: normal teaching day, classes are subtracted.
        FeasibilityService.FeasibilityResult outside = service.calculateFeasibility(windowSemesterId, wednesday);
        assertEquals(13.0, outside.getCapacityHours(), 0.01,
                "Outside a window the 3h of classes must be subtracted: 24h - 8h sleep - 3h class");

        // Same day, now with a stored window: classes stop being subtracted.
        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setWindow(windowSemesterId, "MST", wednesday.minusDays(2), wednesday.plusDays(2));
        timelineDAO.setDatesheetAnnounced(windowSemesterId, "MST", true);

        FeasibilityService.FeasibilityResult inside = service.calculateFeasibility(windowSemesterId, wednesday);
        assertEquals(16.0, inside.getCapacityHours(), 0.01);

        assertEquals(3.0, inside.getCapacityHours() - outside.getCapacityHours(), 0.01,
                "The only difference between the two capacities must be the 3.0h of class time");
    }
}
