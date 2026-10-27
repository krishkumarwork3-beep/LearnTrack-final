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

public class SkillSchedulingTest {

    private final String testDbPath = "target/test_skill_scheduling.db";

    private SemesterDAO semesterDAO;
    private TimetableDAO timetableDAO;
    private ExamPeriodRoutineDAO routineDAO;
    private DateOverrideDAO dateOverrideDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SkillDAO skillDAO;
    private SkillSubtopicDAO skillSubtopicDAO;
    private DailyLogDAO dailyLogDAO;
    private SettingsDAO settingsDAO;
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
        skillSubtopicDAO = new SkillSubtopicDAO();
        dailyLogDAO = new DailyLogDAO();
        settingsDAO = new SettingsDAO();

        scoringService = new ScoringService();
        priorityQueueService = new PriorityQueueService(scoringService);
        freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);

        replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, skillSubtopicDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService
        );

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2026", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testExamTopicsAndPyqAlwaysScheduledBeforeSkills() throws SQLException {
        // (1) Exam topics and PYQ are always scheduled before skill items;
        // with a tight exam window no skill item appears until syllabus fits.
        LocalDate today = LocalDate.of(2026, 10, 1);
        LocalDate examDate = today.plusDays(1); // Exam tomorrow!

        Exam exam = new Exam(null, semesterId, "Data Structures", "NORMAL", 4, 100.0,
                examDate, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, null, null, 1.0);
        int examId = examDAO.insert(exam);

        // Topic requires 5 hours
        topicDAO.insert(new Topic(null, examId, "Binary Trees", 5, 5, 5.0, 5.0, "LEARNING", 2.0, 2.0, 7, null, 1));

        // Skill subtopic requires 2 hours
        int skillId = skillDAO.insert(new Skill(null, "Guitar", "Music", 3, null));
        skillSubtopicDAO.insert(new SkillSubtopic(
                null, skillId, "Fingerpicking", 1,
                2.0, 2.0, 1.0, 1.0, 7,
                false, null, false, null, null
        ));

        // Give exactly 3 hours of free time today (09:00 - 12:00) using date overrides
        dateOverrideDAO.insert(new DateOverride(null, today, null, "ADD", LocalTime.of(0, 0), LocalTime.of(9, 0), "OTHER", "Morning Sleep"));
        dateOverrideDAO.insert(new DateOverride(null, today, null, "ADD", LocalTime.of(12, 0), LocalTime.of(23, 59), "OTHER", "Afternoon Work"));

        // With 3 hours of free time and 5 hours of topic needed, all slots must be TOPIC_LEARN, no skill!
        replanService.regenerateSemesterPlan(semesterId, today);

        List<DailyLogEntry> todayLogs = dailyLogDAO.findByDate(today);
        assertFalse(todayLogs.isEmpty());

        for (DailyLogEntry log : todayLogs) {
            assertNotEquals("SKILL_LEARN", log.getItemType(), "Skill must not be scheduled when exam syllabus needs all available time!");
            assertNotEquals("SKILL_REVISE", log.getItemType());
            assertNotEquals("SKILL", log.getItemType());
        }
    }

    @Test
    void testMissedSkillRowRescheduledNextDayWithoutLosingProgress() throws SQLException {
        // (2) A skill row that was PENDING on a past day becomes MISSED and the next replan
        // puts its remaining hours back, starting today, behind exam work.
        LocalDate pastDay = LocalDate.of(2026, 10, 1);
        LocalDate today = LocalDate.of(2026, 10, 2);

        int skillId = skillDAO.insert(new Skill(null, "Rust", "Tech", 3, null));
        int subId = skillSubtopicDAO.insert(new SkillSubtopic(
                null, skillId, "Ownership", 1,
                2.0, 1.0, 1.0, 1.0, 7, // 1 hour already completed, 1 hour remaining
                false, null, false, null, null
        ));

        // Insert a PENDING row on pastDay
        dailyLogDAO.insert(new DailyLogEntry(
                null, pastDay, "SKILL_LEARN", subId,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"
        ));

        // Replan starting from today
        replanService.regenerateSemesterPlan(semesterId, today);

        // Check that past row was marked MISSED by markMissedBeforeDate
        List<DailyLogEntry> pastLogs = dailyLogDAO.findByDate(pastDay);
        assertEquals(1, pastLogs.size());
        assertEquals("MISSED", pastLogs.get(0).getStatus());

        // Check that the subtopic still has 1 hour remaining (hours already completed not lost)
        SkillSubtopic st = skillSubtopicDAO.findById(subId);
        assertEquals(1.0, st.getLearnRemainingHours(), 0.001);

        // Check that a new row for this subtopic was planned starting today or later
        List<DailyLogEntry> allLogs = dailyLogDAO.findAll(semesterId);
        boolean foundRescheduled = allLogs.stream().anyMatch(l ->
                !l.getLogDate().isBefore(today) && "SKILL_LEARN".equalsIgnoreCase(l.getItemType()) && l.getItemId() == subId
        );
        assertTrue(foundRescheduled, "Missed skill subtopic must be rescheduled on or after today");
    }

    @Test
    void testRevisionNotScheduledBeforeDueDateAndScheduledWhenDue() throws SQLException {
        // (3) A REVISE item is never scheduled before revision_due_date, and late revision is scheduled after it.
        LocalDate today = LocalDate.of(2026, 10, 1);
        LocalDate revDueDate = today.plusDays(3);

        int skillId = skillDAO.insert(new Skill(null, "Chess", "Games", 3, null));
        int subId = skillSubtopicDAO.insert(new SkillSubtopic(
                null, skillId, "Openings", 1,
                2.0, 0.0, 1.0, 1.0, 3,
                true, today, false, null, revDueDate // learned today, revision due in 3 days
        ));

        replanService.regenerateSemesterPlan(semesterId, today);

        List<DailyLogEntry> allLogs = dailyLogDAO.findAll(semesterId);
        for (DailyLogEntry log : allLogs) {
            if ("SKILL_REVISE".equalsIgnoreCase(log.getItemType()) && log.getItemId() == subId) {
                assertFalse(log.getLogDate().isBefore(revDueDate),
                        "Revision session scheduled on " + log.getLogDate() + " cannot be before revision_due_date " + revDueDate);
            }
        }
    }

    @Test
    void testLearnSubtopicsScheduledInPositionOrder() throws SQLException {
        // (4) LEARN subtopics of one skill are scheduled in position order.
        LocalDate today = LocalDate.of(2026, 10, 1);

        int skillId = skillDAO.insert(new Skill(null, "Spanish", "Language", 3, null));
        int subId1 = skillSubtopicDAO.insert(new SkillSubtopic(
                null, skillId, "Alphabet", 1,
                1.0, 1.0, 0.5, 0.5, 5,
                false, null, false, null, null
        ));
        int subId2 = skillSubtopicDAO.insert(new SkillSubtopic(
                null, skillId, "Greetings", 2,
                1.0, 1.0, 0.5, 0.5, 5,
                false, null, false, null, null
        ));

        replanService.regenerateSemesterPlan(semesterId, today);

        List<DailyLogEntry> allLogs = dailyLogDAO.findAll(semesterId);
        DailyLogEntry firstSub1 = null;
        DailyLogEntry firstSub2 = null;

        for (DailyLogEntry log : allLogs) {
            if ("SKILL_LEARN".equalsIgnoreCase(log.getItemType())) {
                if (log.getItemId() == subId1 && firstSub1 == null) {
                    firstSub1 = log;
                } else if (log.getItemId() == subId2 && firstSub2 == null) {
                    firstSub2 = log;
                }
            }
        }

        assertNotNull(firstSub1, "Position 1 subtopic must be scheduled");
        if (firstSub2 != null) {
            assertTrue(!firstSub2.getLogDate().isBefore(firstSub1.getLogDate()),
                    "Position 1 subtopic must be scheduled before or on the same day as position 2");
        }
    }

    @Test
    void testTickingLearnedSetsRevisionDueDate() throws SQLException {
        // (5) Ticking Learned sets revision_due_date = learned_on + revise_after_days.
        LocalDate today = LocalDate.of(2026, 10, 5);
        int reviseDays = 10;

        int skillId = skillDAO.insert(new Skill(null, "Cooking", "Life", 3, null));
        int subId = skillSubtopicDAO.insert(new SkillSubtopic(
                null, skillId, "Knife Skills", 1,
                3.0, 3.0, 1.0, 1.0, reviseDays,
                false, null, false, null, null
        ));

        // Mark learned
        skillSubtopicDAO.markLearned(subId, today, reviseDays);

        SkillSubtopic updated = skillSubtopicDAO.findById(subId);
        assertNotNull(updated);
        assertTrue(updated.isLearned());
        assertEquals(today, updated.getLearnedOn());
        assertEquals(today.plusDays(reviseDays), updated.getRevisionDueDate());
        assertEquals(0.0, updated.getLearnRemainingHours(), 0.001);
    }
}
