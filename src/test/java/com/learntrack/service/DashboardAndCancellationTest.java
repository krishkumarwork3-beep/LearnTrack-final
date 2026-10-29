package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.ui.views.DashboardView;
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

public class DashboardAndCancellationTest {

    private final String testDbPath = "target/test_dashboard_cancellation.db";
    private SemesterDAO semesterDAO;
    private TimetableDAO timetableDAO;
    private ExamPeriodRoutineDAO routineDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SkillDAO skillDAO;
    private DailyLogDAO dailyLogDAO;
    private SettingsDAO settingsDAO;
    private SyllabusDAO syllabusDAO;
    private SemesterReplanService replanService;
    private SemesterService semesterService;
    private DayScheduleService dayScheduleService;
    private FreeSlotService freeSlotService;
    private TimetableEditService timetableEditService;

    private int semesterId;
    private int examId;

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
        syllabusDAO = new SyllabusDAO();

        ScoringService scoringService = new ScoringService();
        PriorityQueueService priorityQueueService = new PriorityQueueService(scoringService);
        freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        ExamPeriodFreeSlotService examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);

        replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService
        );

        dayScheduleService = new DayScheduleService(
                timetableDAO, dateOverrideDAO, examDAO, dailyLogDAO,
                routineDAO, topicDAO, skillDAO
        );

        semesterService = new SemesterService(
                semesterDAO, timetableDAO, routineDAO, examDAO, topicDAO,
                dailyLogDAO, dateOverrideDAO, settingsDAO, replanService
        );

        timetableEditService = new TimetableEditService(
                dailyLogDAO, replanService, freeSlotService,
                examPeriodFreeSlotService, routineDAO, examDAO, topicDAO
        );

        semesterId = semesterDAO.insert(new Semester(null, "Test Semester", "ACTIVE"));

        // Setup common exam
        LocalDate examDate = LocalDate.now().plusDays(30);
        examId = examDAO.insert(new Exam(null, semesterId, "UCS503", "MST", 4.0, 50.0,
                examDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 30, null, null, 0.0));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testCheckboxDoneUndoRoundTrip() throws SQLException {
        // Setup topic with 4 hours needed and remaining
        int topicId = topicDAO.insert(new Topic(null, examId, "Software Models", 3, 2,
                4.0, 4.0, "LEARNING", 2.0, 2.0, 3, null, 0));

        LocalDate today = LocalDate.now();
        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", topicId,
                LocalTime.of(10, 0), LocalTime.of(12, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);

        // 1. Tick Done
        Topic topic = topicDAO.findById(topicId);
        assertNotNull(topic);
        topic.markLearningDone(today);
        topicDAO.update(topic);
        dailyLogDAO.updateStatus(logId, "DONE");

        Topic doneTopic = topicDAO.findById(topicId);
        assertEquals("REVISION", doneTopic.getPhase());
        assertEquals(0.0, doneTopic.getRemainingHours(), 0.001);
        assertEquals("DONE", dailyLogDAO.findById(logId).getStatus());

        // 2. Untick Done (Undo)
        dailyLogDAO.updateStatus(logId, "PENDING");
        doneTopic.setPhase("LEARNING");
        double restored = Math.min(doneTopic.getHoursNeeded(), doneTopic.getRemainingHours() + log.getDurationHours());
        doneTopic.setRemainingHours(restored);
        topicDAO.update(doneTopic);

        Topic revertedTopic = topicDAO.findById(topicId);
        assertEquals("LEARNING", revertedTopic.getPhase());
        assertEquals(2.0, revertedTopic.getRemainingHours(), 0.001); // 0 + 2.0
        assertEquals("PENDING", dailyLogDAO.findById(logId).getStatus());
    }

    @Test
    void testResizeMultipleSessionsAllOrNothingAndRemainingHours() throws SQLException {
        LocalDate today = LocalDate.now();
        String dayOfWeek = today.getDayOfWeek().name();

        // Routines: wake 08:00, sleep 23:00
        timetableDAO.saveRoutines(semesterId, List.of(new WeekdayRoutine(semesterId, dayOfWeek, LocalTime.of(8, 0), LocalTime.of(23, 0))));

        int topic1Id = topicDAO.insert(new Topic(null, examId, "Architecture", 3, 2,
                4.0, 4.0, "LEARNING", 2.0, 2.0, 3, null, 0));
        int topic2Id = topicDAO.insert(new Topic(null, examId, "Design Patterns", 3, 2,
                4.0, 4.0, "LEARNING", 2.0, 2.0, 3, null, 0));

        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", topic1Id,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "PENDING");
        int log1Id = dailyLogDAO.insert(log1);

        DailyLogEntry log2 = new DailyLogEntry(null, today, "TOPIC_LEARN", topic2Id,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING");
        int log2Id = dailyLogDAO.insert(log2);

        // DONE session: 11:00 - 12:00
        DailyLogEntry doneLog = new DailyLogEntry(null, today, "TOPIC_LEARN", topic1Id,
                LocalTime.of(11, 0), LocalTime.of(12, 0), "DONE");
        dailyLogDAO.insert(doneLog);

        // Resize log1 from 1.0h to 1.5h (1h 30m).
        // Since log1 becomes 09:00 - 10:30, log2 desires to start at 10:30.
        // But with duration 1.0h, log2 would be 10:30 - 11:30, overlapping the DONE log (11:00-12:00).
        // So log2 must be pushed after the DONE log, starting at 12:00 (12:00 - 13:00)!
        timetableEditService.resizeMultipleSessions(semesterId, today, List.of(log1Id), 1.5);

        DailyLogEntry updated1 = dailyLogDAO.findById(log1Id);
        DailyLogEntry updated2 = dailyLogDAO.findById(log2Id);
        DailyLogEntry checkDone = dailyLogDAO.findById(doneLog.getLogId());

        assertEquals(LocalTime.of(9, 0), updated1.getSlotStart());
        assertEquals(LocalTime.of(10, 30), updated1.getSlotEnd());
        assertEquals("MANUAL", updated1.getStatus());

        assertEquals(LocalTime.of(12, 0), updated2.getSlotStart());
        assertEquals(LocalTime.of(13, 0), updated2.getSlotEnd());

        assertEquals(LocalTime.of(11, 0), checkDone.getSlotStart());
        assertEquals(LocalTime.of(12, 0), checkDone.getSlotEnd());
        assertEquals("DONE", checkDone.getStatus());

        // Topic 1 remaining hours: was 4.0, resized session increased by 0.5h -> remaining is 3.5h
        Topic updatedTopic1 = topicDAO.findById(topic1Id);
        assertEquals(3.5, updatedTopic1.getRemainingHours(), 0.001);

        // All-or-nothing check: try resizing to 15 hours (exceeds day) -> must throw exception and NOT change anything
        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeMultipleSessions(semesterId, today, List.of(log1Id), 15.0));

        // State remains unchanged
        assertEquals(LocalTime.of(10, 30), dailyLogDAO.findById(log1Id).getSlotEnd());
    }

    @Test
    void testCancelClassOnDateAndRestoreNextWeekUnchanged() throws SQLException {
        LocalDate nextMonday = LocalDate.now().plusDays(1);
        while (nextMonday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            nextMonday = nextMonday.plusDays(1);
        }

        // Add a recurring Monday class: UCS503 10:00 - 11:00
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(
                null, semesterId, "MONDAY", LocalTime.of(10, 0), LocalTime.of(11, 0),
                "CLASS", "UCS503", "Room 101"
        );
        int entryId = timetableDAO.insertEntry(entry);

        // Verify normal Monday has the class
        List<DayRow> normalRows = dayScheduleService.build(semesterId, nextMonday, false);
        assertTrue(normalRows.stream().anyMatch(r -> "UCS503".equals(r.getLabel()) && !r.isCancelled()));

        // Cancel on nextMonday only
        semesterService.recordCancellation(nextMonday, entryId);

        // On nextMonday: with includeCancelled=false, class is absent
        List<DayRow> cancelledDateRows = dayScheduleService.build(semesterId, nextMonday, false);
        assertFalse(cancelledDateRows.stream().anyMatch(r -> "UCS503".equals(r.getLabel())));

        // With includeCancelled=true, class is visible with isCancelled=true and status CANCELLED
        List<DayRow> visibleCancelledRows = dayScheduleService.build(semesterId, nextMonday, true);
        DayRow cancelledRow = visibleCancelledRows.stream()
                .filter(r -> "UCS503".equals(r.getLabel()))
                .findFirst()
                .orElse(null);
        assertNotNull(cancelledRow);
        assertTrue(cancelledRow.isCancelled());
        assertEquals(entryId, cancelledRow.getTimetableEntryId());

        // A week later (nextMonday + 7): normal timetable is completely unchanged!
        LocalDate followingMonday = nextMonday.plusDays(7);
        List<DayRow> followingWeekRows = dayScheduleService.build(semesterId, followingMonday, false);
        assertTrue(followingWeekRows.stream().anyMatch(r -> "UCS503".equals(r.getLabel()) && !r.isCancelled()));

        // Restore class on nextMonday
        semesterService.recordRemoveCancellation(nextMonday, entryId);
        List<DayRow> restoredRows = dayScheduleService.build(semesterId, nextMonday, false);
        assertTrue(restoredRows.stream().anyMatch(r -> "UCS503".equals(r.getLabel()) && !r.isCancelled()));
    }

    @Test
    void testShiftStudySessionsIntoFreedTime() throws SQLException {
        LocalDate monday = LocalDate.now().plusDays(1);
        while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            monday = monday.plusDays(1);
        }

        timetableDAO.saveRoutines(semesterId, List.of(new WeekdayRoutine(semesterId, "MONDAY", LocalTime.of(8, 0), LocalTime.of(22, 0))));

        // Timetable class: 10:30 - 11:20
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(
                null, semesterId, "MONDAY", LocalTime.of(10, 30), LocalTime.of(11, 20),
                "CLASS", "UCS503 Class", "Room 101"
        );
        int entryId = timetableDAO.insertEntry(entry);

        // Topics in syllabus order
        int tA = topicDAO.insert(new Topic(null, examId, "Topic A", 3, 2, 1.0, 1.0, "LEARNING", 1.0, 1.0, 3, null, 1));
        int tB = topicDAO.insert(new Topic(null, examId, "Topic B", 3, 2, 1.0, 1.0, "LEARNING", 1.0, 1.0, 3, null, 1));
        int tC = topicDAO.insert(new Topic(null, examId, "Topic C", 3, 2, 1.0, 1.0, "LEARNING", 1.0, 1.0, 3, null, 1));

        // Replan before cancellation
        replanService.regenerateSemesterPlan(semesterId, monday);

        List<DailyLogEntry> logsBefore = dailyLogDAO.findByDate(monday);
        assertFalse(logsBefore.isEmpty());

        // Cancel the class
        semesterService.recordCancellation(monday, entryId);
        replanService.regenerateSemesterPlan(semesterId, monday);

        List<DailyLogEntry> logsAfter = dailyLogDAO.findByDate(monday);

        // Assert study sessions are placed into the freed slots in order
        assertTrue(logsAfter.size() >= 3);
        assertEquals(tA, logsAfter.get(0).getItemId());
        assertEquals(tB, logsAfter.get(1).getItemId());
        assertEquals(tC, logsAfter.get(2).getItemId());

        // Topics are scheduled in proper sequence with no overlaps
        for (int i = 0; i < logsAfter.size() - 1; i++) {
            assertFalse(logsAfter.get(i).getSlotEnd().isAfter(logsAfter.get(i + 1).getSlotStart()));
        }
    }

    @Test
    void testHolidayCancelsEveryClassLabTutorialAndRestoreBringsThemBack() throws SQLException {
        LocalDate holiday = LocalDate.now().plusDays(1);
        while (holiday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            holiday = holiday.plusDays(1);
        }

        int classId = timetableDAO.insertEntry(new WeeklyTimetableEntry(
                null, semesterId, "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0),
                "CLASS", "UCS503", "Room 101"
        ));
        int labId = timetableDAO.insertEntry(new WeeklyTimetableEntry(
                null, semesterId, "MONDAY", LocalTime.of(10, 30), LocalTime.of(12, 30),
                "LAB", "UCS303 Lab", "Lab 2"
        ));
        int tutorialId = timetableDAO.insertEntry(new WeeklyTimetableEntry(
                null, semesterId, "MONDAY", LocalTime.of(14, 0), LocalTime.of(15, 0),
                "TUTORIAL", "UCT301 Tutorial", "Room 5"
        ));

        List<DayRow> beforeRows = dayScheduleService.build(semesterId, holiday, false);
        assertEquals(3, beforeRows.stream().filter(DashboardAndCancellationTest::isClassLike).count(),
                "All three cancellable classes must exist before the holiday: " + beforeRows);

        // The Holiday button cancels every CLASS / LAB / TUTORIAL row of the date via recordCancellation
        for (DayRow row : beforeRows) {
            if (isClassLike(row) && row.getTimetableEntryId() != null) {
                semesterService.recordCancellation(holiday, row.getTimetableEntryId());
            }
        }

        List<DayRow> cancelledRows = dayScheduleService.build(semesterId, holiday, false);
        assertFalse(cancelledRows.stream().anyMatch(DashboardAndCancellationTest::isClassLike),
                "No class may remain visible on a holiday: " + cancelledRows);

        // The rows are still there behind the flag, marked cancelled
        List<DayRow> visibleRows = dayScheduleService.build(semesterId, holiday, true);
        assertEquals(3, visibleRows.stream().filter(r -> isClassLike(r) && r.isCancelled()).count(),
                "Every class of the day must be flagged cancelled: " + visibleRows);

        // Other dates of the week are untouched
        List<DayRow> nextWeekRows = dayScheduleService.build(semesterId, holiday.plusDays(7), false);
        assertEquals(3, nextWeekRows.stream().filter(DashboardAndCancellationTest::isClassLike).count());

        // Removing the holiday restores all of them
        for (int entryId : List.of(classId, labId, tutorialId)) {
            semesterService.recordRemoveCancellation(holiday, entryId);
        }
        List<DayRow> restoredRows = dayScheduleService.build(semesterId, holiday, false);
        assertEquals(3, restoredRows.stream().filter(DashboardAndCancellationTest::isClassLike).count(),
                "Removing the holiday must restore every class: " + restoredRows);
        assertTrue(restoredRows.stream().noneMatch(DayRow::isCancelled));
    }

    private static boolean isClassLike(DayRow row) {
        String type = row.getType();
        return "CLASS".equalsIgnoreCase(type) || "LAB".equalsIgnoreCase(type) || "TUTORIAL".equalsIgnoreCase(type);
    }

    @Test
    void testCancelledSessionOverrideRemovesThatSlotFromFreeSlots() throws SQLException {
        LocalDate day = LocalDate.now().plusDays(1);
        while (day.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            day = day.plusDays(1);
        }

        // Awake 08:00 - 22:00 with no commitments: the whole window is free
        timetableDAO.saveRoutines(semesterId, List.of(
                new WeekdayRoutine(semesterId, "MONDAY", LocalTime.of(8, 0), LocalTime.of(22, 0))));

        LocalTime slotStart = LocalTime.of(16, 0);
        LocalTime slotEnd = LocalTime.of(17, 0);

        List<FreeSlot> before = freeSlotService.computeFreeSlots(semesterId, day);
        assertTrue(before.stream().anyMatch(fs -> !fs.getStart().isAfter(slotStart) && !fs.getEnd().isBefore(slotEnd)),
                "16:00 - 17:00 must start out free: " + before);

        // Exactly the override the Cancel session button writes
        dateOverrideDAO.insert(new DateOverride(null, day, null, "ADD",
                slotStart, slotEnd, "OTHER", "Cancelled session"));

        List<FreeSlot> after = freeSlotService.computeFreeSlots(semesterId, day);
        for (FreeSlot fs : after) {
            assertFalse(fs.getStart().isBefore(slotEnd) && fs.getEnd().isAfter(slotStart),
                    "The cancelled session slot must not stay in free time: " + after);
        }
        double freedBefore = before.stream().mapToDouble(FreeSlot::getDuration).sum();
        double freedAfter = after.stream().mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(1.0, freedBefore - freedAfter, 0.01,
                "Exactly the cancelled hour must be removed from free time");

        // Restoring the session gives the hour back
        for (DateOverride ov : dateOverrideDAO.findByDate(day)) {
            if ("ADD".equalsIgnoreCase(ov.getAction())
                    && "Cancelled session".equalsIgnoreCase(ov.getLabel())) {
                dateOverrideDAO.delete(ov.getOverrideId());
            }
        }
        double restored = freeSlotService.computeFreeSlots(semesterId, day).stream()
                .mapToDouble(FreeSlot::getDuration).sum();
        assertEquals(freedBefore, restored, 0.01, "Restoring the session must free the hour again");
    }

    @Test
    void testPastDateAndEndedClassGuard() {
        LocalDate pastDate = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();
        LocalDate futureDate = LocalDate.now().plusDays(1);

        assertFalse(DashboardView.canLogProgress(futureDate, today), "Future session cannot be logged as done");
        assertTrue(DashboardView.canLogProgress(today, today), "Today session can be logged as done");
        assertTrue(DashboardView.canLogProgress(pastDate, today), "Past session can be logged as done");
    }
}
