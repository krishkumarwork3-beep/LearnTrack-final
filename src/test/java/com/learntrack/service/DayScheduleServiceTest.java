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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DayScheduleServiceTest {

    private final String testDbPath = "target/test_day_schedule.db";
    private SemesterDAO semesterDAO;
    private TimetableDAO timetableDAO;
    private ExamPeriodRoutineDAO routineDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SkillDAO skillDAO;
    private DailyLogDAO dailyLogDAO;
    private SettingsDAO settingsDAO;
    private SemesterReplanService replanService;
    private DayScheduleService dayScheduleService;

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
        routineDAO = new ExamPeriodRoutineDAO();
        dateOverrideDAO = new DateOverrideDAO();
        examDAO = new ExamDAO();
        topicDAO = new TopicDAO();
        skillDAO = new SkillDAO();
        dailyLogDAO = new DailyLogDAO();
        settingsDAO = new SettingsDAO();

        ScoringService scoringService = new ScoringService();
        PriorityQueueService priorityQueueService = new PriorityQueueService(scoringService);
        FreeSlotService freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        ExamPeriodFreeSlotService examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);

        replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService
        );

        dayScheduleService = new DayScheduleService(
                timetableDAO, dateOverrideDAO, examDAO, dailyLogDAO,
                routineDAO, topicDAO, skillDAO
        );

        semesterId = semesterDAO.insert(new Semester(null, "Test Semester", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testMergedDayWithClassesSleepAndStudyRowsOrderedNoOverlaps() throws SQLException {
        LocalDate thursday = LocalDate.of(2026, 10, 15); // Thursday

        // Set routine: wake 07:00, sleep 23:00 for all days
        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        // Add 2 classes on Thursday: 09:00-11:00 and 14:00-16:00
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "THURSDAY",
                LocalTime.of(9, 0), LocalTime.of(11, 0), "CLASS", "Morning Lecture"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "THURSDAY",
                LocalTime.of(14, 0), LocalTime.of(16, 0), "CLASS", "Afternoon Lab"));

        // Add an exam and a topic with sufficient hours to fill free slots
        Exam exam = new Exam(null, semesterId, "Software Engineering", "NORMAL", 4, 20.0,
                thursday.plusDays(7), null, null, 30, null, null, 0.0);
        int examId = examDAO.insert(exam);
        Topic topic = new Topic(null, examId, "Design Patterns", 5, 4, 30.0, 30.0, "LEARNING", 5.0, 5.0, 7, null, 1);
        topicDAO.insert(topic);

        // Replan to populate daily_log
        replanService.regenerateSemesterPlan(semesterId, thursday);

        List<DayRow> schedule = dayScheduleService.build(semesterId, thursday);
        assertFalse(schedule.isEmpty(), "Schedule should not be empty");

        // Verify sleep blocks cover overnight wrap
        DayRow morningSleep = schedule.stream()
                .filter(r -> "SLEEP".equalsIgnoreCase(r.getType()) && r.getStart().equals(LocalTime.MIN))
                .findFirst().orElse(null);
        assertNotNull(morningSleep, "Morning sleep block must exist starting at 00:00");
        assertEquals(LocalTime.of(7, 0), morningSleep.getEnd(), "Morning sleep block ends at wake time 07:00");

        DayRow eveningSleep = schedule.stream()
                .filter(r -> "SLEEP".equalsIgnoreCase(r.getType()) && r.getStart().equals(LocalTime.of(23, 0)))
                .findFirst().orElse(null);
        assertNotNull(eveningSleep, "Evening sleep block must exist starting at 23:00");
        assertTrue(eveningSleep.getEnd().equals(LocalTime.MAX) || eveningSleep.getEnd().equals(LocalTime.MIDNIGHT));

        // Verify study rows exist in the free gaps (07:00-09:00, 11:00-14:00, 16:00-23:00)
        boolean hasEarlyStudy = schedule.stream().anyMatch(r -> !r.isFixed() && !r.getStart().isBefore(LocalTime.of(7, 0)) && r.getEnd().isBefore(LocalTime.of(9, 1)));
        boolean hasMidStudy = schedule.stream().anyMatch(r -> !r.isFixed() && !r.getStart().isBefore(LocalTime.of(11, 0)) && r.getEnd().isBefore(LocalTime.of(14, 1)));
        boolean hasLateStudy = schedule.stream().anyMatch(r -> !r.isFixed() && !r.getStart().isBefore(LocalTime.of(16, 0)) && !r.getEnd().isAfter(LocalTime.of(23, 0)));

        assertTrue(hasEarlyStudy, "Free gap 07:00-09:00 should be filled by study rows");
        assertTrue(hasMidStudy, "Free gap 11:00-14:00 should be filled by study rows");
        assertTrue(hasLateStudy, "Free gap 16:00-23:00 should be filled by study rows");

        // Verify ordering and no overlaps
        for (int i = 0; i < schedule.size() - 1; i++) {
            DayRow curr = schedule.get(i);
            DayRow next = schedule.get(i + 1);

            // Chronological start order
            assertFalse(curr.getStart().isAfter(next.getStart()),
                    "Rows must be ordered by start time: " + curr + " vs " + next);

            // No overlap
            LocalTime currEnd = curr.getEnd();
            if (!currEnd.equals(LocalTime.MAX) && !(currEnd.equals(LocalTime.MIDNIGHT) && curr.getStart().isAfter(LocalTime.MIDNIGHT))) {
                assertFalse(currEnd.isAfter(next.getStart()),
                        "Row " + curr + " overlaps with next row " + next);
            }
        }
    }

    @Test
    void testCancelOverrideRemovesClassFromMergedSchedule() throws SQLException {
        LocalDate tuesday = LocalDate.of(2026, 10, 13); // Tuesday

        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        int entryId = timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "TUESDAY",
                LocalTime.of(10, 0), LocalTime.of(12, 0), "CLASS", "Canceled Class"));

        // Initially class is present
        List<DayRow> beforeCancel = dayScheduleService.build(semesterId, tuesday);
        assertTrue(beforeCancel.stream().anyMatch(r -> "Canceled Class".equals(r.getLabel())));

        // Record cancellation
        dateOverrideDAO.insert(new DateOverride(null, tuesday, entryId, "CANCEL", null, null, null, "Prof ill"));

        // After cancellation, class must not appear
        List<DayRow> afterCancel = dayScheduleService.build(semesterId, tuesday);
        assertFalse(afterCancel.stream().anyMatch(r -> "Canceled Class".equals(r.getLabel())),
                "Cancelled class must not appear in merged day schedule");
    }

    @Test
    void testAddOverrideAppearsInMergedSchedule() throws SQLException {
        LocalDate wednesday = LocalDate.of(2026, 10, 14);

        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        // Add override: Guest Seminar 13:00 - 15:00
        dateOverrideDAO.insert(new DateOverride(null, wednesday, null, "ADD",
                LocalTime.of(13, 0), LocalTime.of(15, 0), "OTHER", "Guest Seminar"));

        List<DayRow> schedule = dayScheduleService.build(semesterId, wednesday);
        DayRow overrideRow = schedule.stream()
                .filter(r -> "Guest Seminar".equals(r.getLabel()))
                .findFirst().orElse(null);

        assertNotNull(overrideRow, "ADD override must appear in schedule");
        assertEquals("OTHER", overrideRow.getType());
        assertEquals(LocalTime.of(13, 0), overrideRow.getStart());
        assertEquals(LocalTime.of(15, 0), overrideRow.getEnd());
        assertTrue(overrideRow.isFixed(), "ADD override row must be marked as fixed");
    }

    @Test
    void testExamPeriodRoutineUsedDuringExamWindow() throws SQLException {
        LocalDate examDate = LocalDate.of(2026, 10, 20);

        // Regular timetable class on Tuesday
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "TUESDAY",
                LocalTime.of(10, 0), LocalTime.of(11, 0), "CLASS", "Normal Class"));

        // Exam period routine: Breakfast 08:00-09:00, Lunch 13:00-14:00, Sleep 23:00-07:00
        routineDAO.saveRoutineEntries(semesterId, List.of(
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(8, 0), LocalTime.of(9, 0), "MEAL", "Breakfast"),
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(13, 0), LocalTime.of(14, 0), "MEAL", "Lunch"),
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Sleep")
        ));

        // Create dated MST exam on examDate
        Exam mst = new Exam(null, semesterId, "Math", "MST", 4, 20.0,
                examDate, LocalTime.of(9, 30), LocalTime.of(11, 30), 30, null, null, 0.0);
        examDAO.insert(mst);

        List<DayRow> schedule = dayScheduleService.build(semesterId, examDate);

        // Verify exam period routine items appear
        boolean hasBreakfast = schedule.stream().anyMatch(r -> "Breakfast".equals(r.getLabel()));
        boolean hasLunch = schedule.stream().anyMatch(r -> "Lunch".equals(r.getLabel()));
        boolean hasExam = schedule.stream().anyMatch(r -> "EXAM".equals(r.getType()));
        boolean hasNormalClass = schedule.stream().anyMatch(r -> "Normal Class".equals(r.getLabel()));

        assertTrue(hasBreakfast, "Exam routine Breakfast must appear");
        assertTrue(hasLunch, "Exam routine Lunch must appear");
        assertTrue(hasExam, "Buffered exam must appear");
        assertFalse(hasNormalClass, "Normal weekly timetable class must NOT appear during exam window");
    }
    @Test
    void testInsideWindowWithEmptyRoutineNoClassLabTutorialButDefaultSleepMeal() throws SQLException {
        LocalDate wednesday = LocalDate.of(2026, 10, 21); // Wednesday

        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "WEDNESDAY",
                LocalTime.of(9, 0), LocalTime.of(11, 0), "CLASS", "Weekly Wednesday Class"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "WEDNESDAY",
                LocalTime.of(12, 0), LocalTime.of(13, 0), "MEAL", "Lunch"));

        // Stored MST window covering the Wednesday, datesheet announced.
        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setWindow(semesterId, "MST", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 25));
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);

        // Dated MST exam inside the window, not on the inspected date.
        examDAO.insert(new Exam(null, semesterId, "Math", "MST", 4, 20.0,
                LocalDate.of(2026, 10, 22), LocalTime.of(9, 0), LocalTime.of(11, 0), 30, null, null, 0.0));

        // exam_period_routine is intentionally EMPTY -> DefaultExamRoutineProvider is used.
        List<DayRow> schedule = dayScheduleService.build(semesterId, wednesday);

        assertTrue(schedule.stream().noneMatch(r -> "CLASS".equalsIgnoreCase(r.getType())),
                "Weekly CLASS must not appear inside an exam window when exam_period_routine is empty");
        assertTrue(schedule.stream().noneMatch(r -> "LAB".equalsIgnoreCase(r.getType())),
                "LAB must not appear inside an exam window");
        assertTrue(schedule.stream().noneMatch(r -> "TUTORIAL".equalsIgnoreCase(r.getType())),
                "TUTORIAL must not appear inside an exam window");
        assertTrue(schedule.stream().noneMatch(r -> "Weekly Wednesday Class".equals(r.getLabel())),
                "The weekly class label must not leak into the exam-period schedule");

        DayRow meal = schedule.stream().filter(r -> "Lunch".equals(r.getLabel())).findFirst().orElse(null);
        assertNotNull(meal, "Weekly MEAL entry must be kept by the default exam routine");
        assertEquals("MEAL", meal.getType());
        assertEquals(LocalTime.of(12, 0), meal.getStart());
        assertEquals(LocalTime.of(13, 0), meal.getEnd());

        assertTrue(schedule.stream().anyMatch(r -> "SLEEP".equalsIgnoreCase(r.getType())
                        && LocalTime.MIN.equals(r.getStart()) && LocalTime.of(7, 0).equals(r.getEnd())),
                "Default exam routine must show the morning SLEEP block 00:00-07:00");
        assertTrue(schedule.stream().anyMatch(r -> "SLEEP".equalsIgnoreCase(r.getType())
                        && LocalTime.of(23, 0).equals(r.getStart())),
                "Default exam routine must show the evening SLEEP block starting at 23:00");
    }

    @Test
    void testOutsideWindowClassRowsAppear() throws SQLException {
        LocalDate outsideWednesday = LocalDate.of(2026, 11, 4); // Wednesday, after the window

        List<WeekdayRoutine> routines = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            routines.add(new WeekdayRoutine(semesterId, day, LocalTime.of(7, 0), LocalTime.of(23, 0)));
        }
        timetableDAO.saveRoutines(semesterId, routines);

        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "WEDNESDAY",
                LocalTime.of(9, 0), LocalTime.of(11, 0), "CLASS", "Weekly Wednesday Class"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "WEDNESDAY",
                LocalTime.of(12, 0), LocalTime.of(13, 0), "MEAL", "Lunch"));

        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setWindow(semesterId, "MST", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 25));
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);

        examDAO.insert(new Exam(null, semesterId, "Math", "MST", 4, 20.0,
                LocalDate.of(2026, 10, 22), LocalTime.of(9, 0), LocalTime.of(11, 0), 30, null, null, 0.0));

        List<DayRow> schedule = dayScheduleService.build(semesterId, outsideWednesday);

        DayRow classRow = schedule.stream()
                .filter(r -> "CLASS".equalsIgnoreCase(r.getType()) && "Weekly Wednesday Class".equals(r.getLabel()))
                .findFirst().orElse(null);
        assertNotNull(classRow, "Weekly CLASS must appear on a normal day outside the exam window");
        assertEquals(LocalTime.of(9, 0), classRow.getStart());
        assertEquals(LocalTime.of(11, 0), classRow.getEnd());
        assertTrue(classRow.isFixed(), "Weekly class row must be a fixed commitment");
    }
}
