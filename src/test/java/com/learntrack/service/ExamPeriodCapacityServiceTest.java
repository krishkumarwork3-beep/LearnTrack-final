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

public class ExamPeriodCapacityServiceTest {

    private final String testDbPath = "target/test_capacity.db";
    private SemesterDAO semesterDAO;
    private TimetableDAO timetableDAO;
    private ExamPeriodRoutineDAO routineDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SettingsDAO settingsDAO;
    private ExamPeriodFreeSlotService examPeriodFreeSlotService;
    private ExamPeriodCapacityService capacityService;
    private SemesterReplanService replanService;

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
        settingsDAO = new SettingsDAO();

        examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);
        capacityService = new ExamPeriodCapacityService(examDAO, topicDAO, timetableDAO, settingsDAO, examPeriodFreeSlotService);

        DailyLogDAO dailyLogDAO = new DailyLogDAO();
        SkillDAO skillDAO = new SkillDAO();
        ScoringService scoringService = new ScoringService();
        PriorityQueueService pqService = new PriorityQueueService(scoringService);
        FreeSlotService freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, pqService, routineDAO
        );

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2026", "ACTIVE"));

        // Setup weekday study preferences (4h/day)
        timetableDAO.saveStudyPreferences(semesterId, List.of(
                new WeekdayStudyPreference(semesterId, "MONDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "TUESDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "WEDNESDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "THURSDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "FRIDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "SATURDAY", 4.0),
                new WeekdayStudyPreference(semesterId, "SUNDAY", 4.0)
        ));

        // Setup routine: sleep 23-7 (8h), meals 8-9 and 13-14 (2h) -> 14h free time/day
        routineDAO.saveRoutineEntries(semesterId, List.of(
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(23, 0), LocalTime.of(7, 0), "SLEEP", "Sleep"),
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(8, 0), LocalTime.of(9, 0), "MEAL", "Breakfast"),
                new ExamPeriodRoutineEntry(null, semesterId, LocalTime.of(13, 0), LocalTime.of(14, 0), "MEAL", "Lunch")
        ));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testDeficitDetection() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate examDate = LocalDate.of(2026, 10, 12); // 3 days: 10th, 11th, 12th -> 3 * 4h = 12h available (minus exam sitting on 12th)

        Exam mst = new Exam(null, semesterId, "Computer Networks", "MST", 4, 20.0,
                examDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 30,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), 4.0);
        int examId = examDAO.insert(mst);
        
        // Store window in exam_timeline for ExamWindowService.windowFor (today..examDate, not wider)
        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setWindow(semesterId, "MST", today, examDate);
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);

        // Topics needing 20h total learning + 4h PYQ = 24h required
        topicDAO.insert(new Topic(null, examId, "Routing", 5, 4, 12.0, 12.0, "LEARNING", 2.0, 2.0, 7, null, 1));
        topicDAO.insert(new Topic(null, examId, "TCP/IP", 4, 3, 8.0, 8.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        double required = capacityService.getRequiredHours(semesterId, "MST", today);
        assertEquals(24.0, required, 0.01);

        double available = capacityService.getAvailableHours(semesterId, "MST", today);
        // Available is <= 12 hours, which is less than 24 required
        assertTrue(available < required, "Available hours (" + available + ") should be less than required (24.0)");

        double suggestedExtra = capacityService.calculateSuggestedExtraHoursPerDay(semesterId, "MST", today);
        assertTrue(suggestedExtra > 0.0, "Suggested extra hours should be positive when deficit exists");
    }

    @Test
    void testExtraHoursResolvingDeficit() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate examDate = LocalDate.of(2026, 10, 12);

        Exam mst = new Exam(null, semesterId, "Algorithms", "MST", 3, 20.0,
                examDate, LocalTime.of(14, 0), LocalTime.of(16, 0), 30,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), 2.0);
        int examId = examDAO.insert(mst);
        
        // Store window in exam_timeline for ExamWindowService.windowFor (today..examDate, not wider)
        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setWindow(semesterId, "MST", today, examDate);
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);

        // Required: 12h topic + 2h PYQ = 14h
        topicDAO.insert(new Topic(null, examId, "Graphs", 5, 4, 12.0, 12.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        double initialAvailable = capacityService.getAvailableHours(semesterId, "MST", today);
        double required = capacityService.getRequiredHours(semesterId, "MST", today);
        assertTrue(initialAvailable < required);

        // Add 3.0 extra hours per day
        capacityService.setExtraHours("MST", 3.0);
        assertEquals(3.0, capacityService.getExtraHours("MST"), 0.01);

        double updatedAvailable = capacityService.getAvailableHours(semesterId, "MST", today);
        assertTrue(updatedAvailable > initialAvailable);
        assertTrue(updatedAvailable >= required, "Extra hours should resolve the deficit: available=" + updatedAvailable + ", required=" + required);
    }

    @Test
    void testTrimmingResolvingDeficit() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate examDate = LocalDate.of(2026, 10, 12);

        Exam mst = new Exam(null, semesterId, "Operating Systems", "MST", 4, 20.0,
                examDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 30,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), 0.0);
        int examId = examDAO.insert(mst);
        
        // Store window in exam_timeline for ExamWindowService.windowFor (today..examDate, not wider)
        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setWindow(semesterId, "MST", today, examDate);
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);

        Topic t1 = new Topic(null, examId, "Virtual Memory", 5, 4, 6.0, 6.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        Topic t2 = new Topic(null, examId, "File Systems", 3, 2, 10.0, 10.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        int t1Id = topicDAO.insert(t1);
        int t2Id = topicDAO.insert(t2);

        double available = capacityService.getAvailableHours(semesterId, "MST", today);
        double requiredBefore = capacityService.getRequiredHours(semesterId, "MST", today);
        assertEquals(16.0, requiredBefore, 0.01);
        assertTrue(available < requiredBefore);

        // Trim t2 (important_flag = 0)
        topicDAO.updateImportantFlag(t2Id, 0);

        double requiredAfter = capacityService.getRequiredHours(semesterId, "MST", today);
        assertEquals(6.0, requiredAfter, 0.01);
        assertTrue(available >= requiredAfter, "Trimming should resolve deficit: available=" + available + ", required=" + requiredAfter);
    }

    @Test
    void testWiderSheetWindowGivesMoreCapacity() throws SQLException {
        // Real datesheet windows can be wider than the user's exam dates.
        // Sheet window 10-15 Oct (6 days) vs exam on 12 Oct should give 6*4=24h, not 3*4=12h.
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate examDate = LocalDate.of(2026, 10, 12);

        Exam mst = new Exam(null, semesterId, "Data Structures", "MST", 4, 20.0,
                examDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 30,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15), 0.0);
        examDAO.insert(mst);

        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        
        // Narrow window: today..examDate (10-12 Oct = 3 days)
        timelineDAO.setWindow(semesterId, "MST", today, examDate);
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);
        double narrowAvailable = capacityService.getAvailableHours(semesterId, "MST", today);
        
        // Wider window: 10-15 Oct (6 days)
        timelineDAO.setWindow(semesterId, "MST", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15));
        double wideAvailable = capacityService.getAvailableHours(semesterId, "MST", today);
        
        assertTrue(wideAvailable > narrowAvailable, 
            "Wider sheet window (6 days) should give more capacity than narrow window (3 days): wide=" + wideAvailable + ", narrow=" + narrowAvailable);
        // 6 days * 4h pref = 24h (assuming free time >= pref)
        assertTrue(wideAvailable >= 20.0, "Wider window should have at least 20h available, got: " + wideAvailable);
    }

    @Test
    void testFlagsResetAfterBatchWindowCloses() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 20); // Window ended on 10-15
        LocalDate examDate = LocalDate.of(2026, 10, 15);

        Exam mst = new Exam(null, semesterId, "Databases", "MST", 4, 20.0,
                examDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 30,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15), 0.0);
        int examId = examDAO.insert(mst);

        Topic t1 = new Topic(null, examId, "Indexing", 4, 3, 6.0, 6.0, "LEARNING", 2.0, 2.0, 7, null, 0);
        int t1Id = topicDAO.insert(t1);
        assertEquals(0, topicDAO.findById(t1Id).getImportantFlag());

        capacityService.setExtraHours("MST", 2.0);
        assertEquals(2.0, capacityService.getExtraHours("MST"), 0.01);

        // Reset flags and extra hours after window closed via surviving SemesterReplanService path (Item 4)
        boolean reset = replanService.checkAndResetBatchIfWindowClosed(semesterId, "MST", today);
        assertTrue(reset);
        assertEquals(1, topicDAO.findById(t1Id).getImportantFlag(), "Topic important_flag should reset to 1 after batch window closes");
        assertEquals(0.0, capacityService.getExtraHours("MST"), 0.01, "Exam-period extra hours must reset to 0.0 after batch window closes");
    }

    @Test
    void testDeficitNotFixableByExtraHoursAndResolvedByTrimming() throws SQLException {
        // Item 5: When required hours exceed maximum possible free time across window,
        // increasing extra hours cannot fix it, and trimming resolves it.
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate examDate = LocalDate.of(2026, 10, 11);
        Exam mst = new Exam(null, semesterId, "Databases", "MST", 4, 20.0,
                examDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 30,
                today, examDate, 0.0);
        int examId = examDAO.insert(mst);

        double maxPossible = capacityService.getMaxPossibleAvailableHours(semesterId, "MST", today);
        assertTrue(maxPossible > 0, "Max possible available hours across window must be positive");

        Topic t1 = new Topic(null, examId, "Core Indexing", 5, 4, maxPossible * 0.6, maxPossible * 0.6, "LEARNING", 2.0, 2.0, 7, null, 1);
        int t1Id = topicDAO.insert(t1);
        Topic t2 = new Topic(null, examId, "Minor Advanced SQL", 2, 2, maxPossible * 0.8, maxPossible * 0.8, "LEARNING", 2.0, 2.0, 7, null, 1);
        int t2Id = topicDAO.insert(t2);

        // Required hours (1.4 * maxPossible) > maxPossible -> cannot be fixed by extra hours!
        assertFalse(capacityService.canExtraHoursResolveDeficit(semesterId, "MST", today),
                "Deficit exceeding max possible free hours must be detected as not fixable by extra hours");

        // User trims the minor topic (important_flag = 0)
        topicDAO.updateImportantFlag(t2Id, 0);

        // Now required hours is 0.6 * maxPossible, which fits within maximum possible capacity
        assertTrue(capacityService.canExtraHoursResolveDeficit(semesterId, "MST", today),
                "After trimming, deficit should be resolvable or resolved within available capacity");
    }

    @Test
    void testBatchWithPastExamReportsRequiredHoursForFutureExamOnly() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        // Past exam in MST batch (dated yesterday)
        Exam pastExam = new Exam(null, semesterId, "Past MST", "MST", 4, 20.0,
                today.minusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), 30, null, null, 2.0);
        int pastId = examDAO.insert(pastExam);
        topicDAO.insert(new Topic(null, pastId, "Past Topic", 5, 4, 5.0, 5.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        // Future exam in MST batch (dated in 5 days)
        Exam futureExam = new Exam(null, semesterId, "Future MST", "MST", 4, 20.0,
                today.plusDays(5), LocalTime.of(10, 0), LocalTime.of(12, 0), 30, null, null, 2.0);
        int futureId = examDAO.insert(futureExam);
        topicDAO.insert(new Topic(null, futureId, "Future Topic", 5, 4, 3.0, 3.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        // Required hours should count only the future exam (3.0h topic + 2.0h PYQ = 5.0h), not past exam (5.0h topic + 2.0h PYQ = 7.0h)
        double required = capacityService.getRequiredHours(semesterId, "MST", today);
        assertEquals(5.0, required, 0.01, "Required hours must exclude past exams in the batch");
    }
}
