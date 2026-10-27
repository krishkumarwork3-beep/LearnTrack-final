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

/**
 * Tests for LearnTrack planner continuity, syllabus ordering, and hours conservation:
 * 1. Continuity: leftover topic time scheduled first on the next day.
 * 2. Syllabus order: topics learned in ascending (unit.position, subtopic.position).
 * 3. Continuity first: an in-progress topic of later position outranks unstarted earlier topic.
 * 4. Multi-exam urgency + syllabus order: nearest exam served first, syllabus order within each.
 * 5. Done / revision-only topic does not block later syllabus topics.
 * 6. Hours conservation: sum of scheduled LEARN minutes == hoursNeeded across multi-day plan with fragments.
 */
public class PlannerContinuityAndSyllabusTest {

    private final String testDbPath = "target/test_planner_continuity.db";
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
    private FreeSlotService freeSlotService;
    private ExamPeriodFreeSlotService examPeriodFreeSlotService;
    private ScoringService scoringService;
    private PriorityQueueService priorityQueueService;
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
        skillDAO = new SkillDAO();
        dailyLogDAO = new DailyLogDAO();
        settingsDAO = new SettingsDAO();
        syllabusDAO = new SyllabusDAO();

        scoringService = new ScoringService();
        priorityQueueService = new PriorityQueueService(scoringService);
        freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);

        replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService,
                routineDAO, syllabusDAO
        );

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2026", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    /**
     * 1. Topic A needs 1h, day 1 has only 30m free at the end, day 2 has 3h free:
     * assert day 1 has a 30m A row, and day 2's FIRST study row is A for 30m (not another topic), total A = 60m.
     */
    @Test
    void testTopicCarryOverContinuity() throws SQLException {
        LocalDate day1 = LocalDate.of(2026, 11, 2); // Monday
        LocalDate day2 = LocalDate.of(2026, 11, 3); // Tuesday

        // Day 1 (Monday): 00:00-21:30 fixed, 21:30-22:00 free (30m), 22:00-24:00 fixed
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(0, 0), LocalTime.of(21, 30), "CLASS", "Fixed"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(22, 0), LocalTime.of(0, 0), "SLEEP", "Sleep"));

        // Day 2 (Tuesday): 00:00-07:00 fixed, 07:00-10:00 free (3h), 10:00-24:00 fixed
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "TUESDAY",
                LocalTime.of(0, 0), LocalTime.of(7, 0), "SLEEP", "Sleep"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "TUESDAY",
                LocalTime.of(10, 0), LocalTime.of(0, 0), "CLASS", "Fixed"));

        // Other days fixed
        for (String day : List.of("WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(0, 0), LocalTime.of(0, 0), "SLEEP", "Sleep"));
        }

        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Database Systems");
        exam.setSubjectCode("UCS553");
        exam.setExamType("EST");
        exam.setExamDate(day2.plusDays(5));
        exam.setCredits(4.0);
        exam.setEvalMarks(50.0);
        int examId = examDAO.insert(exam);

        int uId = syllabusDAO.insertUnit(semesterId, "UCS553", "Unit 1", 0);
        int subA = syllabusDAO.insertSubtopic(uId, "JDBC architecture", 0, 5, 1.0, 0.5);
        int subB = syllabusDAO.insertSubtopic(uId, "Transactions", 1, 5, 1.0, 0.5);

        int topicAId = topicDAO.insert(new Topic(null, examId, "JDBC architecture", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, subA));
        int topicBId = topicDAO.insert(new Topic(null, examId, "Transactions", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, subB));

        replanService.regenerateSemesterPlan(semesterId, day1);

        List<DailyLogEntry> day1Entries = dailyLogDAO.findByDate(day1);
        List<DailyLogEntry> day2Entries = dailyLogDAO.findByDate(day2);

        // Day 1 must have a 30m Topic A row (21:30 to 22:00)
        assertEquals(1, day1Entries.size(), "Day 1 should have exactly one study row");
        DailyLogEntry d1Entry = day1Entries.get(0);
        assertEquals(topicAId, d1Entry.getItemId(), "Day 1 should schedule Topic A");
        assertEquals(LocalTime.of(21, 30), d1Entry.getSlotStart());
        assertEquals(LocalTime.of(22, 0), d1Entry.getSlotEnd());
        assertEquals(30, java.time.Duration.between(d1Entry.getSlotStart(), d1Entry.getSlotEnd()).toMinutes());

        // Day 2's FIRST study row must be Topic A for 30m
        assertFalse(day2Entries.isEmpty(), "Day 2 should have study rows");
        DailyLogEntry d2First = day2Entries.get(0);
        assertEquals(topicAId, d2First.getItemId(), "Day 2's FIRST study row must be Topic A (leftover 30m)");
        assertEquals(30, java.time.Duration.between(d2First.getSlotStart(), d2First.getSlotEnd()).toMinutes(),
                "Topic A on Day 2 should be exactly the remaining 30m");

        // Sum of Topic A across Day 1 and Day 2 == 60m
        long totalLearnMinA = 0;
        for (DailyLogEntry e : day1Entries) {
            if ("TOPIC_LEARN".equalsIgnoreCase(e.getItemType()) && e.getItemId() == topicAId) {
                totalLearnMinA += java.time.Duration.between(e.getSlotStart(), e.getSlotEnd()).toMinutes();
            }
        }
        for (DailyLogEntry e : day2Entries) {
            if ("TOPIC_LEARN".equalsIgnoreCase(e.getItemType()) && e.getItemId() == topicAId) {
                totalLearnMinA += java.time.Duration.between(e.getSlotStart(), e.getSlotEnd()).toMinutes();
            }
        }
        assertEquals(60, totalLearnMinA, "Total scheduled LEARN minutes for Topic A must be exactly 60m (1.0h)");
    }

    /**
     * 2. Exam with topics in syllabus order T1 (unit1,pos1), T2 (unit1,pos2), T3 (unit2,pos1), all unstarted, enough days:
     * assert they are scheduled in the order T1, T2, T3 and T3 never before T1/T2.
     */
    @Test
    void testSyllabusOrderScheduling() throws SQLException {
        LocalDate start = LocalDate.of(2026, 11, 2);

        // Timetable with 4 hours free every day (14:00 to 18:00)
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(0, 0), LocalTime.of(14, 0), "CLASS", "Fixed"));
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(18, 0), LocalTime.of(0, 0), "SLEEP", "Fixed"));
        }

        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Algorithms");
        exam.setSubjectCode("UCS421");
        exam.setExamType("EST");
        exam.setExamDate(start.plusDays(6));
        exam.setCredits(4.0);
        exam.setEvalMarks(50.0);
        int examId = examDAO.insert(exam);

        int u1 = syllabusDAO.insertUnit(semesterId, "UCS421", "Unit 1", 0);
        int u2 = syllabusDAO.insertUnit(semesterId, "UCS421", "Unit 2", 1);

        int subT1 = syllabusDAO.insertSubtopic(u1, "T1", 0, 5, 1.0, 0.5);
        int subT2 = syllabusDAO.insertSubtopic(u1, "T2", 1, 5, 1.0, 0.5);
        int subT3 = syllabusDAO.insertSubtopic(u2, "T3", 0, 5, 1.0, 0.5);

        int t1Id = topicDAO.insert(new Topic(null, examId, "T1", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, subT1));
        int t2Id = topicDAO.insert(new Topic(null, examId, "T2", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, subT2));
        int t3Id = topicDAO.insert(new Topic(null, examId, "T3", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, subT3));

        replanService.regenerateSemesterPlan(semesterId, start);

        List<DailyLogEntry> allEntries = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            allEntries.addAll(dailyLogDAO.findByDate(start.plusDays(i)));
        }

        List<Integer> scheduledTopics = new ArrayList<>();
        for (DailyLogEntry e : allEntries) {
            if ("TOPIC_LEARN".equalsIgnoreCase(e.getItemType())) {
                if (scheduledTopics.isEmpty() || scheduledTopics.get(scheduledTopics.size() - 1) != e.getItemId()) {
                    scheduledTopics.add(e.getItemId());
                }
            }
        }

        assertTrue(scheduledTopics.contains(t1Id));
        assertTrue(scheduledTopics.contains(t2Id));
        assertTrue(scheduledTopics.contains(t3Id));

        int idx1 = scheduledTopics.indexOf(t1Id);
        int idx2 = scheduledTopics.indexOf(t2Id);
        int idx3 = scheduledTopics.indexOf(t3Id);

        assertTrue(idx1 < idx2, "T1 must be scheduled before T2");
        assertTrue(idx2 < idx3, "T2 must be scheduled before T3");
    }

    /**
     * 3. A started topic of a later syllabus position outranks an unstarted earlier-position topic (continuity first).
     */
    @Test
    void testStartedTopicOutranksEarlierUnstarted() throws SQLException {
        LocalDate start = LocalDate.of(2026, 11, 2);

        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(0, 0), LocalTime.of(14, 0), "CLASS", "Fixed"));
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(18, 0), LocalTime.of(0, 0), "SLEEP", "Fixed"));
        }

        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Algorithms");
        exam.setSubjectCode("UCS421");
        exam.setExamType("EST");
        exam.setExamDate(start.plusDays(5));
        exam.setCredits(4.0);
        exam.setEvalMarks(50.0);
        int examId = examDAO.insert(exam);

        int u1 = syllabusDAO.insertUnit(semesterId, "UCS421", "Unit 1", 0);
        int subT1 = syllabusDAO.insertSubtopic(u1, "T1", 0, 5, 1.0, 0.5);
        int subT2 = syllabusDAO.insertSubtopic(u1, "T2", 1, 5, 1.0, 0.5);

        // T1: unstarted (rem=1.0, needed=1.0)
        int t1Id = topicDAO.insert(new Topic(null, examId, "T1", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, subT1));
        // T2: started! (rem=0.5, needed=1.0)
        int t2Id = topicDAO.insert(new Topic(null, examId, "T2", 5, 5, 1.0, 0.5, "LEARNING", 0.5, 0.5, 7, null, 1, subT2));

        replanService.regenerateSemesterPlan(semesterId, start);

        List<DailyLogEntry> day1Entries = dailyLogDAO.findByDate(start);
        assertFalse(day1Entries.isEmpty());
        // The first TOPIC_LEARN row on Day 1 must be T2 because T2 is in-progress (continuity first)
        DailyLogEntry firstLearn = null;
        for (DailyLogEntry e : day1Entries) {
            if ("TOPIC_LEARN".equalsIgnoreCase(e.getItemType())) {
                firstLearn = e;
                break;
            }
        }
        assertNotNull(firstLearn);
        assertEquals(t2Id, firstLearn.getItemId(), "Started topic T2 must be scheduled before unstarted T1");
    }

    /**
     * 4. Two exams: nearest-deadline exam is served first, and within each exam syllabus order holds.
     */
    @Test
    void testTwoExamsUrgencyAndSyllabusOrder() throws SQLException {
        LocalDate start = LocalDate.of(2026, 11, 2);

        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(0, 0), LocalTime.of(14, 0), "CLASS", "Fixed"));
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(18, 0), LocalTime.of(0, 0), "SLEEP", "Fixed"));
        }

        // Exam 1: near deadline (in 2 days)
        Exam exam1 = new Exam();
        exam1.setSemesterId(semesterId);
        exam1.setSubject("Exam Near");
        exam1.setSubjectCode("NEAR101");
        exam1.setExamType("EST");
        exam1.setExamDate(start.plusDays(2));
        exam1.setCredits(4.0);
        exam1.setEvalMarks(50.0);
        int e1Id = examDAO.insert(exam1);

        // Exam 2: far deadline (in 10 days)
        Exam exam2 = new Exam();
        exam2.setSemesterId(semesterId);
        exam2.setSubject("Exam Far");
        exam2.setSubjectCode("FAR201");
        exam2.setExamType("EST");
        exam2.setExamDate(start.plusDays(10));
        exam2.setCredits(4.0);
        exam2.setEvalMarks(50.0);
        int e2Id = examDAO.insert(exam2);

        int uNear = syllabusDAO.insertUnit(semesterId, "NEAR101", "Unit 1", 0);
        int sNear1 = syllabusDAO.insertSubtopic(uNear, "Near_T1", 0, 5, 1.0, 0.5);
        int sNear2 = syllabusDAO.insertSubtopic(uNear, "Near_T2", 1, 5, 1.0, 0.5);

        int uFar = syllabusDAO.insertUnit(semesterId, "FAR201", "Unit 1", 0);
        int sFar1 = syllabusDAO.insertSubtopic(uFar, "Far_T1", 0, 5, 1.0, 0.5);
        int sFar2 = syllabusDAO.insertSubtopic(uFar, "Far_T2", 1, 5, 1.0, 0.5);

        int n1 = topicDAO.insert(new Topic(null, e1Id, "Near_T1", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, sNear1));
        int n2 = topicDAO.insert(new Topic(null, e1Id, "Near_T2", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, sNear2));

        int f1 = topicDAO.insert(new Topic(null, e2Id, "Far_T1", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, sFar1));
        int f2 = topicDAO.insert(new Topic(null, e2Id, "Far_T2", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, sFar2));

        replanService.regenerateSemesterPlan(semesterId, start);

        List<DailyLogEntry> d1Entries = dailyLogDAO.findByDate(start);
        assertFalse(d1Entries.isEmpty());

        // Nearest exam served first: first scheduled topic must be from Exam Near (n1)
        assertEquals(n1, d1Entries.get(0).getItemId(), "First scheduled topic must be Near_T1 from nearest exam");

        // Within Exam 1, n1 is before n2; within Exam 2, f1 is before f2
        List<Integer> order = new ArrayList<>();
        for (DailyLogEntry e : d1Entries) {
            if ("TOPIC_LEARN".equalsIgnoreCase(e.getItemType())) {
                order.add(e.getItemId());
            }
        }
        assertTrue(order.indexOf(n1) < order.indexOf(n2), "Near_T1 must be scheduled before Near_T2");
        assertTrue(order.indexOf(f1) < order.indexOf(f2), "Far_T1 must be scheduled before Far_T2");
    }

    /**
     * 5. A topic marked done / revision-only is not blocking later topics.
     */
    @Test
    void testDoneTopicDoesNotBlockLaterTopics() throws SQLException {
        LocalDate start = LocalDate.of(2026, 11, 2);

        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(0, 0), LocalTime.of(14, 0), "CLASS", "Fixed"));
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(18, 0), LocalTime.of(0, 0), "SLEEP", "Fixed"));
        }

        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Software Engineering");
        exam.setSubjectCode("UCS503");
        exam.setExamType("EST");
        exam.setExamDate(start.plusDays(5));
        exam.setCredits(4.0);
        exam.setEvalMarks(50.0);
        int examId = examDAO.insert(exam);

        int u = syllabusDAO.insertUnit(semesterId, "UCS503", "Unit 1", 0);
        int subDone = syllabusDAO.insertSubtopic(u, "DoneTopic", 0, 5, 1.0, 0.5);
        int subNext = syllabusDAO.insertSubtopic(u, "NextTopic", 1, 5, 1.0, 0.5);

        // Topic 1: marked done (phase REVISION, rem = 0)
        topicDAO.insert(new Topic(null, examId, "DoneTopic", 5, 5, 1.0, 0.0, "REVISION", 0.5, 0.5, 7, start, 1, subDone));
        // Topic 2: unstarted (phase LEARNING, rem = 1.0)
        int tNextId = topicDAO.insert(new Topic(null, examId, "NextTopic", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, subNext));

        replanService.regenerateSemesterPlan(semesterId, start);

        List<DailyLogEntry> d1 = dailyLogDAO.findByDate(start);
        assertFalse(d1.isEmpty());
        // NextTopic should be scheduled as TOPIC_LEARN without being blocked by DoneTopic
        boolean foundNext = false;
        for (DailyLogEntry e : d1) {
            if ("TOPIC_LEARN".equalsIgnoreCase(e.getItemType()) && e.getItemId() == tNextId) {
                foundNext = true;
                break;
            }
        }
        assertTrue(foundNext, "NextTopic must be scheduled for learning; not blocked by DoneTopic");
    }

    /**
     * 6. Hours conservation: sum of scheduled LEARN minutes per topic == hoursNeeded (no lost fractions)
     * across a multi-day plan, including days with fragments < min_session_minutes.
     */
    @Test
    void testHoursConservationAcrossFragments() throws SQLException {
        LocalDate day1 = LocalDate.of(2026, 11, 2); // Monday
        LocalDate day2 = LocalDate.of(2026, 11, 3); // Tuesday
        LocalDate day3 = LocalDate.of(2026, 11, 4); // Wednesday

        // Day 1: has a small 10m fragment (13:50 to 14:00) < min_session_minutes (15m), plus 45m slot (17:00-17:45)
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(0, 0), LocalTime.of(13, 50), "CLASS", "Fixed"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(14, 0), LocalTime.of(17, 0), "CLASS", "Fixed"));
        timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(17, 45), LocalTime.of(0, 0), "SLEEP", "Fixed"));

        // Day 2 & 3: standard 3h free
        for (String day : List.of("TUESDAY", "WEDNESDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(0, 0), LocalTime.of(14, 0), "CLASS", "Fixed"));
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(17, 0), LocalTime.of(0, 0), "SLEEP", "Fixed"));
        }
        for (String day : List.of("THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            timetableDAO.insertEntry(new WeeklyTimetableEntry(null, semesterId, day,
                    LocalTime.of(0, 0), LocalTime.of(0, 0), "SLEEP", "Fixed"));
        }

        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("Physics");
        exam.setSubjectCode("UPH013");
        exam.setExamType("EST");
        exam.setExamDate(day3.plusDays(3));
        exam.setCredits(4.0);
        exam.setEvalMarks(50.0);
        int examId = examDAO.insert(exam);

        int u = syllabusDAO.insertUnit(semesterId, "UPH013", "Unit 1", 0);
        int s1 = syllabusDAO.insertSubtopic(u, "Topic 1", 0, 5, 1.0, 0.5);
        int s2 = syllabusDAO.insertSubtopic(u, "Topic 2", 1, 5, 1.5, 0.5);

        int t1 = topicDAO.insert(new Topic(null, examId, "Topic 1", 5, 5, 1.0, 1.0, "LEARNING", 0.5, 0.5, 7, null, 1, s1));
        int t2 = topicDAO.insert(new Topic(null, examId, "Topic 2", 5, 5, 1.5, 1.5, "LEARNING", 0.5, 0.5, 7, null, 1, s2));

        replanService.regenerateSemesterPlan(semesterId, day1);

        List<DailyLogEntry> allEntries = new ArrayList<>();
        allEntries.addAll(dailyLogDAO.findByDate(day1));
        allEntries.addAll(dailyLogDAO.findByDate(day2));
        allEntries.addAll(dailyLogDAO.findByDate(day3));

        long minutesT1 = 0;
        long minutesT2 = 0;
        for (DailyLogEntry e : allEntries) {
            if ("TOPIC_LEARN".equalsIgnoreCase(e.getItemType())) {
                long m = java.time.Duration.between(e.getSlotStart(), e.getSlotEnd()).toMinutes();
                if (e.getItemId() == t1) {
                    minutesT1 += m;
                } else if (e.getItemId() == t2) {
                    minutesT2 += m;
                }
            }
        }

        // Exact hours conservation: T1 needs 1.0h = 60m, T2 needs 1.5h = 90m
        assertEquals(60, minutesT1, "Scheduled minutes for Topic 1 must equal 60 minutes exactly");
        assertEquals(90, minutesT2, "Scheduled minutes for Topic 2 must equal 90 minutes exactly");
    }
}
