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

public class TimetableEditServiceTest {

    private final String testDbPath = "target/test_timetable_edit.db";
    private DailyLogDAO dailyLogDAO;
    private TimetableDAO timetableDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SkillDAO skillDAO;
    private SettingsDAO settingsDAO;
    private SemesterDAO semesterDAO;
    private ExamPeriodRoutineDAO routineDAO;

    private TimetableEditService timetableEditService;
    private SemesterReplanService replanService;
    private int semesterId;
    private final LocalDate today = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }

        dailyLogDAO = new DailyLogDAO();
        timetableDAO = new TimetableDAO();
        dateOverrideDAO = new DateOverrideDAO();
        examDAO = new ExamDAO();
        topicDAO = new TopicDAO();
        skillDAO = new SkillDAO();
        settingsDAO = new SettingsDAO();
        semesterDAO = new SemesterDAO();
        routineDAO = new ExamPeriodRoutineDAO();

        FreeSlotService freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        ExamPeriodFreeSlotService examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);
        ScoringService scoringService = new ScoringService();
        PriorityQueueService priorityQueueService = new PriorityQueueService(scoringService);

        replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService, routineDAO
        );

        timetableEditService = new TimetableEditService(
                dailyLogDAO, replanService, freeSlotService, examPeriodFreeSlotService, routineDAO, examDAO
        );

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2026", "ACTIVE"));

        // Setup base timetable: class 13:00-15:00, sleep 23:00-07:00
        // Free slots: [07:00-13:00] (6h) and [15:00-23:00] (8h)
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(13, 0), LocalTime.of(15, 0), "CLASS", "Class"));
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Sleep"));
        }
        timetableDAO.saveStudyPreferences(semesterId, List.of(
                new WeekdayStudyPreference(semesterId, today.getDayOfWeek().name(), 4.0)
        ));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
    }

    @Test
    void testSwapSessionsSuccessful() throws SQLException {
        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int log1Id = dailyLogDAO.insert(log1);

        DailyLogEntry log2 = new DailyLogEntry(null, today, "TOPIC_LEARN", 202,
                LocalTime.of(10, 30), LocalTime.of(11, 30), "PENDING");
        int log2Id = dailyLogDAO.insert(log2);

        timetableEditService.swapSessions(semesterId, today, log1Id, log2Id);

        DailyLogEntry updated1 = dailyLogDAO.findById(log1Id);
        DailyLogEntry updated2 = dailyLogDAO.findById(log2Id);

        // Slot times must remain identical (duration preserved)
        assertEquals(LocalTime.of(8, 0), updated1.getSlotStart());
        assertEquals(LocalTime.of(10, 0), updated1.getSlotEnd());
        assertEquals(2.0, updated1.getDurationHours(), 0.01);
        assertEquals(202, updated1.getItemId(), "Log 1 must now hold item 202");
        assertEquals("MANUAL", updated1.getStatus());

        assertEquals(LocalTime.of(10, 30), updated2.getSlotStart());
        assertEquals(LocalTime.of(11, 30), updated2.getSlotEnd());
        assertEquals(1.0, updated2.getDurationHours(), 0.01);
        assertEquals(101, updated2.getItemId(), "Log 2 must now hold item 101");
        assertEquals("MANUAL", updated2.getStatus());
    }

    @Test
    void testSwapSessionsRejectsNonPendingOrManual() throws SQLException {
        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "DONE");
        int log1Id = dailyLogDAO.insert(log1);

        DailyLogEntry log2 = new DailyLogEntry(null, today, "TOPIC_LEARN", 202,
                LocalTime.of(10, 30), LocalTime.of(11, 30), "PENDING");
        int log2Id = dailyLogDAO.insert(log2);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.swapSessions(semesterId, today, log1Id, log2Id));
        assertTrue(ex.getMessage().contains("Cannot swap session"));
    }

    @Test
    void testResizeSessionSuccessful() throws SQLException {
        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(9, 30), "PENDING");
        int log1Id = dailyLogDAO.insert(log1);

        timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(11, 0));

        DailyLogEntry updated = dailyLogDAO.findById(log1Id);
        assertEquals(LocalTime.of(8, 0), updated.getSlotStart());
        assertEquals(LocalTime.of(11, 0), updated.getSlotEnd());
        assertEquals(3.0, updated.getDurationHours(), 0.01);
        assertEquals("MANUAL", updated.getStatus());
    }

    @Test
    void testResizeSessionRejectsNonPendingOrManual() throws SQLException {
        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(9, 30), "DONE");
        int log1Id = dailyLogDAO.insert(log1);

        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(11, 0)));
    }

    @Test
    void testResizeSessionRejectsOverlapWithAnotherSession() throws SQLException {
        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(9, 30), "PENDING");
        int log1Id = dailyLogDAO.insert(log1);

        DailyLogEntry log2 = new DailyLogEntry(null, today, "TOPIC_LEARN", 202,
                LocalTime.of(10, 0), LocalTime.of(12, 0), "PENDING");
        dailyLogDAO.insert(log2);

        // Resizing log1 to 10:30 overlaps with log2 [10:00-12:00]
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(10, 30)));
        assertTrue(ex.getMessage().contains("overlaps with existing scheduled session"));
    }

    @Test
    void testResizeSessionRejectsExceedingFreeSlotOrBuffer() throws SQLException {
        // Free slot is [07:00 - 13:00]. Class starts at 13:00.
        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(11, 0), LocalTime.of(12, 30), "PENDING");
        int log1Id = dailyLogDAO.insert(log1);

        // Resizing to 14:00 extends into class time (13:00-15:00)
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(14, 0)));
        assertTrue(ex.getMessage().contains("extends into fixed commitments"));
    }

    @Test
    void testShortenTopicLearnTurnsIntoManualWithNewDuration() throws SQLException {
        Exam exam = new Exam(null, semesterId, "TestSubject", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 0.0);
        int examId = examDAO.insert(exam);
        Topic topic = new Topic(null, examId, "TestTopic", 5, 4, 5.0, 3.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int topicId = topicDAO.insert(topic);

        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", topicId,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);

        timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(9, 0));

        DailyLogEntry updated = dailyLogDAO.findById(logId);
        assertNotNull(updated);
        assertEquals("MANUAL", updated.getStatus());
        assertEquals(LocalTime.of(8, 0), updated.getSlotStart());
        assertEquals(LocalTime.of(9, 0), updated.getSlotEnd());
        assertEquals(1.0, updated.getDurationHours(), 0.01);
    }

    @Test
    void testShortenSessionLeavesRemainingHoursUnchangedForLearnReviseAndPyq() throws SQLException {
        // 1. TOPIC_LEARN
        Exam exam1 = new Exam(null, semesterId, "SubjLearn", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 0.0);
        int exam1Id = examDAO.insert(exam1);
        Topic topicLearn = new Topic(null, exam1Id, "TopicLearn", 5, 4, 5.0, 3.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int topicLearnId = topicDAO.insert(topicLearn);

        DailyLogEntry logLearn = new DailyLogEntry(null, today, "TOPIC_LEARN", topicLearnId,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int logLearnId = dailyLogDAO.insert(logLearn);

        timetableEditService.resizeSession(semesterId, today, logLearnId, LocalTime.of(9, 0));
        assertEquals(3.0, topicDAO.findById(topicLearnId).getRemainingHours(), 0.001,
                "Shortening learn session must not change topic remaining_hours");

        dailyLogDAO.clearFutureRows(today);

        // 2. TOPIC_REVISE (in free slot [15:00-23:00])
        Topic topicRevise = new Topic(null, exam1Id, "TopicRevise", 5, 4, 5.0, 0.0, "REVISION", 2.0, 1.5, 7, null, 1);
        int topicReviseId = topicDAO.insert(topicRevise);

        DailyLogEntry logRevise = new DailyLogEntry(null, today, "TOPIC_REVISE", topicReviseId,
                LocalTime.of(15, 0), LocalTime.of(17, 0), "PENDING");
        int logReviseId = dailyLogDAO.insert(logRevise);

        timetableEditService.resizeSession(semesterId, today, logReviseId, LocalTime.of(16, 30));
        assertEquals(1.5, topicDAO.findById(topicReviseId).getRevisionRemainingHours(), 0.001,
                "Shortening revise session must not change topic revision_remaining_hours");

        dailyLogDAO.clearFutureRows(today);

        // 3. PYQ_PRACTICE
        Exam examPyq = new Exam(null, semesterId, "SubjPyq", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 4.0);
        int examPyqId = examDAO.insert(examPyq);

        DailyLogEntry logPyq = new DailyLogEntry(null, today, "PYQ_PRACTICE", examPyqId,
                LocalTime.of(18, 0), LocalTime.of(20, 0), "PENDING");
        int logPyqId = dailyLogDAO.insert(logPyq);

        timetableEditService.resizeSession(semesterId, today, logPyqId, LocalTime.of(19, 0));
        assertEquals(4.0, examDAO.findById(examPyqId).getPyqRemainingHours(), 0.001,
                "Shortening PYQ session must not change exam pyq_remaining_hours");
    }

    @Test
    void testAfterReplanTotalScheduledHoursEqualsRemainingHoursOrCapacity() throws SQLException {
        Exam exam = new Exam(null, semesterId, "TestSubject", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 0.0);
        int examId = examDAO.insert(exam);

        Topic topic = new Topic(null, examId, "TestTopic", 5, 4, 5.0, 3.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int topicId = topicDAO.insert(topic);

        // Pre-insert a 2h PENDING session on today
        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", topicId,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);

        // Resize from 2h to 1h
        timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(9, 0));

        // After replan: check all scheduled sessions for this topic
        List<DailyLogEntry> allLogs = dailyLogDAO.findByDateRange(today, today.plusDays(10));
        double totalScheduled = allLogs.stream()
                .filter(l -> l.getItemId() == topicId && "TOPIC_LEARN".equals(l.getItemType()))
                .mapToDouble(DailyLogEntry::getDurationHours)
                .sum();

        // 1h manual session survives, and replan schedules remaining hours (up to 3.0h total)
        assertEquals(3.0, totalScheduled, 0.01,
                "Total scheduled hours (manual + replanned) must equal topic remaining_hours (3.0h)");
    }

    @Test
    void testFreedHourIsCoveredByLaterSessionsNotLost() throws SQLException {
        Exam exam = new Exam(null, semesterId, "TestSubject", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 0.0);
        int examId = examDAO.insert(exam);

        Topic topic = new Topic(null, examId, "TestTopic", 5, 4, 5.0, 3.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int topicId = topicDAO.insert(topic);

        // Pre-insert a 2h PENDING session on today: 8:00 to 10:00
        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", topicId,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);

        // Shorten by 1h: new end is 9:00
        timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(9, 0));

        // Verify the manual session is exactly 1h
        DailyLogEntry manualSession = dailyLogDAO.findById(logId);
        assertEquals("MANUAL", manualSession.getStatus());
        assertEquals(1.0, manualSession.getDurationHours(), 0.01);

        // Look at all scheduled sessions for the topic from today through deadline
        List<DailyLogEntry> topicSessions = dailyLogDAO.findByDateRange(today, today.plusDays(10)).stream()
                .filter(l -> l.getItemId() == topicId && "TOPIC_LEARN".equals(l.getItemType()))
                .collect(java.util.stream.Collectors.toList());

        // The freed 1h plus the unscheduled 1h (total 2h outstanding) must be scheduled in other sessions
        double otherSessionsHours = topicSessions.stream()
                .filter(l -> !l.getLogId().equals(logId))
                .mapToDouble(DailyLogEntry::getDurationHours)
                .sum();

        assertEquals(2.0, otherSessionsHours, 0.01,
                "The freed hour and outstanding work must be covered by other sessions (2.0h total)");
        assertEquals(3.0, manualSession.getDurationHours() + otherSessionsHours, 0.01,
                "All 3.0h must remain scheduled across the plan without any hour being lost");
    }

    @Test
    void testExtendSessionLeavesRemainingUnchangedAndNoHoursDuplicated() throws SQLException {
        Exam exam = new Exam(null, semesterId, "TestSubject", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 0.0);
        int examId = examDAO.insert(exam);

        Topic topic = new Topic(null, examId, "TestTopic", 5, 4, 5.0, 5.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int topicId = topicDAO.insert(topic);

        // Pre-insert a 1h PENDING session: 8:00 to 9:00
        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", topicId,
                LocalTime.of(8, 0), LocalTime.of(9, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);

        // Extend to 2.5h: 8:00 to 10:30
        timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(10, 30));

        // DB remaining_hours must remain unchanged at 5.0
        assertEquals(5.0, topicDAO.findById(topicId).getRemainingHours(), 0.001,
                "Extending session must not change topic DB remaining_hours");

        // Verify the manual session is 2.5h
        DailyLogEntry manualSession = dailyLogDAO.findById(logId);
        assertEquals("MANUAL", manualSession.getStatus());
        assertEquals(2.5, manualSession.getDurationHours(), 0.01);

        // Across all days up to deadline, total scheduled hours must equal exactly 5.0h (no duplication)
        List<DailyLogEntry> topicSessions = dailyLogDAO.findByDateRange(today, today.plusDays(10)).stream()
                .filter(l -> l.getItemId() == topicId && "TOPIC_LEARN".equals(l.getItemType()))
                .collect(java.util.stream.Collectors.toList());

        double totalScheduled = topicSessions.stream()
                .mapToDouble(DailyLogEntry::getDurationHours)
                .sum();

        assertEquals(5.0, totalScheduled, 0.01,
                "Total scheduled hours must not duplicate work (manual 2.5h + planned 2.5h = 5.0h)");
    }

    @Test
    void testResizeSessionRejectedLeavesStateUnchanged() throws SQLException {
        // Create exam and topic
        Exam exam = new Exam(null, semesterId, "TestSubject", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 2.0);
        int examId = examDAO.insert(exam);
        
        Topic topic = new Topic(null, examId, "TestTopic", 5, 4, 5.0, 3.0, "LEARNING", 2.0, 1.5, 7, null, 1);
        int topicId = topicDAO.insert(topic);
        
        // Insert a 2-hour PENDING session
        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", topicId,
                LocalTime.of(10, 0), LocalTime.of(12, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);
        
        double beforeRemaining = topicDAO.findById(topicId).getRemainingHours();
        double beforeRevRemaining = topicDAO.findById(topicId).getRevisionRemainingHours();
        double beforePyqRemaining = examDAO.findById(examId).getPyqRemainingHours();
        
        // Try to resize into the CLASS slot [13:00-15:00] - should be rejected
        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(14, 0)));
        
        // Verify all buckets unchanged
        Topic afterTopic = topicDAO.findById(topicId);
        Exam afterExam = examDAO.findById(examId);
        assertEquals(beforeRemaining, afterTopic.getRemainingHours(), 0.01,
                "Rejected resize must not change topic remaining_hours");
        assertEquals(beforeRevRemaining, afterTopic.getRevisionRemainingHours(), 0.01,
                "Rejected resize must not change topic revision_remaining_hours");
        assertEquals(beforePyqRemaining, afterExam.getPyqRemainingHours(), 0.01,
                "Rejected resize must not change exam pyq_remaining_hours");
        
        // Verify session was not modified
        DailyLogEntry unchanged = dailyLogDAO.findById(logId);
        assertEquals(LocalTime.of(12, 0), unchanged.getSlotEnd());
        assertEquals("PENDING", unchanged.getStatus());
    }

    @Test
    void testReplanDoesNotPersistSimulatedRemainingHours() throws SQLException {
        // Create exam and topic
        Exam exam = new Exam(null, semesterId, "TestSubject", "NORMAL", 3, 20.0,
                today.plusDays(10), null, null, 30, null, null, 0.0);
        int examId = examDAO.insert(exam);
        
        // Topic with 5h remaining
        Topic topic = new Topic(null, examId, "TestTopic", 5, 4, 5.0, 5.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int topicId = topicDAO.insert(topic);
        
        double beforeReplan = topicDAO.findById(topicId).getRemainingHours();
        
        // Run replan first time
        replanService.regenerateSemesterPlan(semesterId, today);
        double afterReplan1 = topicDAO.findById(topicId).getRemainingHours();
        List<DailyLogEntry> scheduled1 = dailyLogDAO.findByDateRange(today, today.plusDays(30)).stream()
                .filter(e -> "TOPIC_LEARN".equals(e.getItemType()) && e.getItemId() == topicId)
                .collect(java.util.stream.Collectors.toList());
        double totalScheduled1 = scheduled1.stream().mapToDouble(DailyLogEntry::getDurationHours).sum();
        
        // Run replan second time
        replanService.regenerateSemesterPlan(semesterId, today);
        double afterReplan2 = topicDAO.findById(topicId).getRemainingHours();
        List<DailyLogEntry> scheduled2 = dailyLogDAO.findByDateRange(today, today.plusDays(30)).stream()
                .filter(e -> "TOPIC_LEARN".equals(e.getItemType()) && e.getItemId() == topicId)
                .collect(java.util.stream.Collectors.toList());
        double totalScheduled2 = scheduled2.stream().mapToDouble(DailyLogEntry::getDurationHours).sum();
        
        // DB remaining_hours should be unchanged across both replans
        assertEquals(beforeReplan, afterReplan1, 0.01, "Replan 1 must not change DB remaining_hours");
        assertEquals(beforeReplan, afterReplan2, 0.01, "Replan 2 must not change DB remaining_hours");
        assertEquals(5.0, afterReplan2, 0.01, "DB remaining_hours must stay at 5.0");
        
        // Scheduled hours should be identical after both runs
        assertEquals(totalScheduled1, totalScheduled2, 0.01, "Scheduled hours must be identical after both replans");
    }

    @Test
    void testResizeSessionWithStartAndEnd_Success() throws SQLException {
        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);

        timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(8, 30), LocalTime.of(10, 30));

        DailyLogEntry updated = dailyLogDAO.findById(logId);
        assertNotNull(updated);
        assertEquals(LocalTime.of(8, 30), updated.getSlotStart());
        assertEquals(LocalTime.of(10, 30), updated.getSlotEnd());
        assertEquals(2.0, updated.getDurationHours(), 0.01);
    }

    @Test
    void testResizeSessionWithStartAndEnd_Validations() throws SQLException {
        DailyLogEntry log1 = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int log1Id = dailyLogDAO.insert(log1);

        DailyLogEntry log2 = new DailyLogEntry(null, today, "TOPIC_LEARN", 102,
                LocalTime.of(11, 0), LocalTime.of(12, 0), "PENDING");
        dailyLogDAO.insert(log2);

        // End <= start
        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(10, 0), LocalTime.of(9, 0)));

        // Duration < 15 min
        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(8, 0), LocalTime.of(8, 10)));

        // Duration > 8 hours
        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(8, 0), LocalTime.of(16, 30)));

        // Overlapping with log2 (11:00-12:00)
        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, log1Id, LocalTime.of(10, 0), LocalTime.of(11, 30)));
    }

    @Test
    void testResizeSessionWithStartAndEnd_RejectsRangeOutsideFreeTime() throws SQLException {
        // Free slots are [07:00-13:00] and [15:00-23:00]; the class owns [13:00-15:00].
        DailyLogEntry log = new DailyLogEntry(null, today, "TOPIC_LEARN", 101,
                LocalTime.of(8, 0), LocalTime.of(10, 0), "PENDING");
        int logId = dailyLogDAO.insert(log);

        // 12:00 - 14:00 spills into the 13:00 - 15:00 class: not inside any free slot
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(12, 0), LocalTime.of(14, 0)));
        assertTrue(ex.getMessage().toLowerCase().contains("free time"),
                "The rejection must explain that the range is outside free time: " + ex.getMessage());

        // And a range entirely inside the class is rejected too
        assertThrows(IllegalArgumentException.class, () ->
                timetableEditService.resizeSession(semesterId, today, logId, LocalTime.of(13, 30), LocalTime.of(14, 30)));

        // The session is untouched after both rejections
        DailyLogEntry unchanged = dailyLogDAO.findById(logId);
        assertEquals(LocalTime.of(8, 0), unchanged.getSlotStart());
        assertEquals(LocalTime.of(10, 0), unchanged.getSlotEnd());
        assertEquals("PENDING", unchanged.getStatus());
    }
}
