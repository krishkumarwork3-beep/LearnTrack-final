package com.learntrack.dao;

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

public class DailyLogDAOTest {

    private DailyLogDAO dailyLogDAO;
    private SemesterDAO semesterDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private SkillDAO skillDAO;
    private final String testDbPath = "target/test_dailylog.db";
    private int semesterId;
    private int examId;
    private int topicId;
    private int skillId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        semesterDAO = new SemesterDAO();
        examDAO = new ExamDAO();
        topicDAO = new TopicDAO();
        skillDAO = new SkillDAO();
        dailyLogDAO = new DailyLogDAO();

        semesterId = semesterDAO.insert(new Semester(null, "Test Term", "ACTIVE"));
        Exam exam = new Exam(null, semesterId, "Math", "NORMAL", 3, 10.0,
                LocalDate.of(2026, 10, 20), null, null, 30, null, null, 2.0);
        examId = examDAO.insert(exam);
        Topic topic = new Topic(null, examId, "Calculus", 4, 3, 5.0, 5.0, "LEARNING", 1.0, 1.0, 7, null, 1);
        topicId = topicDAO.insert(topic);
        Skill skill = new Skill(null, "Typing", "Tech", 4, LocalDate.of(2026, 10, 1));
        skillId = skillDAO.insert(skill);
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testClearFutureRowsLocksHistory() throws SQLException {
        LocalDate past = LocalDate.of(2026, 10, 8);
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate future = LocalDate.of(2026, 10, 12);

        // Past row (PENDING or DONE, should remain untouched because before today)
        dailyLogDAO.insert(new DailyLogEntry(null, past, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "DONE"));
        // Today DONE row (should be preserved because status is DONE)
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "DONE"));
        // Today PENDING row (should be deleted)
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(14, 0), LocalTime.of(15, 0), "PENDING"));
        // Future PENDING row (should be deleted)
        dailyLogDAO.insert(new DailyLogEntry(null, future, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"));

        dailyLogDAO.clearFutureRows(today);

        List<DailyLogEntry> pastRows = dailyLogDAO.findByDate(past);
        assertEquals(1, pastRows.size());

        List<DailyLogEntry> todayRows = dailyLogDAO.findByDate(today);
        assertEquals(1, todayRows.size());
        assertEquals("DONE", todayRows.get(0).getStatus());

        List<DailyLogEntry> futureRows = dailyLogDAO.findByDate(future);
        assertTrue(futureRows.isEmpty());
    }

    @Test
    void testDeleteNonSkillBySemesterLeavesSkillUntouched() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "PYQ_PRACTICE", examId, LocalTime.of(10, 0), LocalTime.of(11, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "SKILL", skillId, LocalTime.of(11, 0), LocalTime.of(12, 0), "DONE"));

        dailyLogDAO.deleteNonSkillBySemester(semesterId);

        List<DailyLogEntry> remaining = dailyLogDAO.findByDate(today);
        assertEquals(1, remaining.size());
        assertEquals("SKILL", remaining.get(0).getItemType());
        assertEquals(skillId, remaining.get(0).getItemId());
    }

    @Test
    void testMarkMissedBeforeDate() throws SQLException {
        LocalDate yesterday = LocalDate.of(2026, 10, 9);
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate tomorrow = LocalDate.of(2026, 10, 11);

        // Yesterday rows
        dailyLogDAO.insert(new DailyLogEntry(null, yesterday, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "PENDING"));
        dailyLogDAO.insert(new DailyLogEntry(null, yesterday, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "CONTINUING"));
        dailyLogDAO.insert(new DailyLogEntry(null, yesterday, "TOPIC_LEARN", topicId, LocalTime.of(11, 0), LocalTime.of(12, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, yesterday, "TOPIC_LEARN", topicId, LocalTime.of(13, 0), LocalTime.of(14, 0), "MANUAL"));

        // Today rows
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "PENDING"));

        // Tomorrow rows
        dailyLogDAO.insert(new DailyLogEntry(null, tomorrow, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "PENDING"));

        int changed = dailyLogDAO.markMissedBeforeDate(today);
        assertEquals(2, changed, "Exactly 2 past rows (PENDING and CONTINUING) should be changed to MISSED");

        List<DailyLogEntry> yesterdayRows = dailyLogDAO.findByDate(yesterday);
        assertEquals(4, yesterdayRows.size());
        assertEquals("MISSED", yesterdayRows.get(0).getStatus()); // was PENDING
        assertEquals("MISSED", yesterdayRows.get(1).getStatus()); // was CONTINUING
        assertEquals("DONE", yesterdayRows.get(2).getStatus());   // was DONE (untouched)
        assertEquals("MANUAL", yesterdayRows.get(3).getStatus()); // was MANUAL (untouched)

        List<DailyLogEntry> todayRows = dailyLogDAO.findByDate(today);
        assertEquals("PENDING", todayRows.get(0).getStatus(), "Today's rows must be untouched");

        List<DailyLogEntry> tomorrowRows = dailyLogDAO.findByDate(tomorrow);
        assertEquals("PENDING", tomorrowRows.get(0).getStatus(), "Future rows must be untouched");
    }

    @Test
    void testInsertBatchAtomicity() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        List<DailyLogEntry> batch = List.of(
                new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "PENDING"),
                // Invalid entry: logDate is null, violating NOT NULL constraint on log_date column
                new DailyLogEntry(null, null, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING")
        );

        assertThrows(SQLException.class, () -> dailyLogDAO.insertBatch(batch),
                "Batch with invalid row must throw SQLException");

        // Verify that no rows from the failing batch were committed
        List<DailyLogEntry> logs = dailyLogDAO.findByDate(today);
        assertTrue(logs.isEmpty(), "Failing batch must roll back completely (atomicity)");
    }

    @Test
    void testInsertBatchAssignsDistinctCorrectLogIds() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        List<DailyLogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId,
                    LocalTime.of(8 + i, 0), LocalTime.of(9 + i, 0), "PENDING"));
        }

        dailyLogDAO.insertBatch(entries);

        java.util.Set<Integer> assignedIds = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            DailyLogEntry entry = entries.get(i);
            assertNotNull(entry.getLogId(), "Each entry must receive a non-null logId");
            assertTrue(entry.getLogId() > 0, "Each entry must receive a positive logId");
            assertTrue(assignedIds.add(entry.getLogId()), "Each entry must receive a distinct logId");

            DailyLogEntry fromDb = dailyLogDAO.findById(entry.getLogId());
            assertNotNull(fromDb, "Row must be retrievable by assigned logId");
            assertEquals(entry.getLogDate(), fromDb.getLogDate());
            assertEquals(entry.getSlotStart(), fromDb.getSlotStart());
            assertEquals(entry.getSlotEnd(), fromDb.getSlotEnd());
            assertEquals(entry.getItemType(), fromDb.getItemType());
        }

        List<DailyLogEntry> fromDate = dailyLogDAO.findByDate(today);
        assertEquals(5, fromDate.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(entries.get(i).getLogId(), fromDate.get(i).getLogId());
        }
    }

    @Test
    void testReplaceFutureRowsHappyPath() throws SQLException {
        LocalDate past = LocalDate.of(2026, 10, 8);
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate future = LocalDate.of(2026, 10, 12);

        // Pre-existing rows:
        // Past rows: before today, must remain untouched
        dailyLogDAO.insert(new DailyLogEntry(null, past, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, past, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"));

        // Today rows:
        // DONE, MISSED, MANUAL: must remain untouched
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(8, 0), LocalTime.of(9, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "MISSED"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "MANUAL"));
        // PENDING, CONTINUING on today: must be deleted/replaced
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(11, 0), LocalTime.of(12, 0), "PENDING"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(12, 0), LocalTime.of(13, 0), "CONTINUING"));

        // Future rows: PENDING/CONTINUING/MANUAL after today: must be deleted/replaced
        dailyLogDAO.insert(new DailyLogEntry(null, future, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "PENDING"));
        dailyLogDAO.insert(new DailyLogEntry(null, future, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "CONTINUING"));
        dailyLogDAO.insert(new DailyLogEntry(null, future, "TOPIC_LEARN", topicId, LocalTime.of(11, 0), LocalTime.of(12, 0), "MANUAL"));

        // New replacement entries
        List<DailyLogEntry> newEntries = List.of(
                new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(14, 0), LocalTime.of(15, 0), "PENDING"),
                new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(15, 0), LocalTime.of(16, 0), "PENDING"),
                new DailyLogEntry(null, future, "TOPIC_LEARN", topicId, LocalTime.of(14, 0), LocalTime.of(15, 0), "PENDING")
        );

        dailyLogDAO.replaceFutureRows(today, newEntries);

        // Assert log ids are set, positive and distinct
        java.util.Set<Integer> assignedIds = new java.util.HashSet<>();
        for (DailyLogEntry entry : newEntries) {
            assertNotNull(entry.getLogId());
            assertTrue(entry.getLogId() > 0);
            assertTrue(assignedIds.add(entry.getLogId()), "Log IDs must be distinct");
        }

        // Past rows are untouched (both DONE and PENDING before today remain)
        List<DailyLogEntry> pastRows = dailyLogDAO.findByDate(past);
        assertEquals(2, pastRows.size());

        // Today rows: DONE, MISSED, MANUAL + 2 new PENDING = 5 rows
        List<DailyLogEntry> todayRows = dailyLogDAO.findByDate(today);
        assertEquals(5, todayRows.size());
        assertEquals("DONE", todayRows.get(0).getStatus());
        assertEquals("MISSED", todayRows.get(1).getStatus());
        assertEquals("MANUAL", todayRows.get(2).getStatus());
        assertEquals("PENDING", todayRows.get(3).getStatus());
        assertEquals(LocalTime.of(14, 0), todayRows.get(3).getSlotStart());
        assertEquals("PENDING", todayRows.get(4).getStatus());
        assertEquals(LocalTime.of(15, 0), todayRows.get(4).getSlotStart());

        // Future rows: exactly the 1 new PENDING row
        List<DailyLogEntry> futureRows = dailyLogDAO.findByDate(future);
        assertEquals(1, futureRows.size());
        assertEquals(newEntries.get(2).getLogId(), futureRows.get(0).getLogId());
    }

    @Test
    void testReplaceFutureRowsAtomicRollback() throws SQLException {
        LocalDate past = LocalDate.of(2026, 10, 8);
        LocalDate today = LocalDate.of(2026, 10, 10);
        LocalDate future = LocalDate.of(2026, 10, 12);

        // Pre-existing rows:
        dailyLogDAO.insert(new DailyLogEntry(null, past, "TOPIC_LEARN", topicId, LocalTime.of(9, 0), LocalTime.of(10, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(8, 0), LocalTime.of(9, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"));
        dailyLogDAO.insert(new DailyLogEntry(null, future, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"));

        List<DailyLogEntry> initialPast = dailyLogDAO.findByDate(past);
        List<DailyLogEntry> initialToday = dailyLogDAO.findByDate(today);
        List<DailyLogEntry> initialFuture = dailyLogDAO.findByDate(future);

        // Batch with an invalid entry midway: null itemType violates NOT NULL constraint
        List<DailyLogEntry> failingEntries = List.of(
                new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(14, 0), LocalTime.of(15, 0), "PENDING"),
                new DailyLogEntry(null, today, null, topicId, LocalTime.of(15, 0), LocalTime.of(16, 0), "PENDING")
        );

        assertThrows(SQLException.class, () -> dailyLogDAO.replaceFutureRows(today, failingEntries));

        // Assert all old rows are still present (the delete was rolled back)
        List<DailyLogEntry> afterPast = dailyLogDAO.findByDate(past);
        List<DailyLogEntry> afterToday = dailyLogDAO.findByDate(today);
        List<DailyLogEntry> afterFuture = dailyLogDAO.findByDate(future);

        assertEquals(initialPast.size(), afterPast.size());
        assertEquals(initialToday.size(), afterToday.size());
        assertEquals(initialFuture.size(), afterFuture.size());

        assertEquals(initialToday.get(0).getLogId(), afterToday.get(0).getLogId());
        assertEquals(initialToday.get(1).getLogId(), afterToday.get(1).getLogId());
        assertEquals(initialFuture.get(0).getLogId(), afterFuture.get(0).getLogId());
    }

    @Test
    void testReplaceFutureRowsToleratesEmptyOrNull() throws SQLException {
        LocalDate today = LocalDate.of(2026, 10, 10);
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(8, 0), LocalTime.of(9, 0), "DONE"));
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"));

        // Passing empty list should delete pending rows and commit successfully
        dailyLogDAO.replaceFutureRows(today, new ArrayList<>());
        List<DailyLogEntry> todayRows = dailyLogDAO.findByDate(today);
        assertEquals(1, todayRows.size());
        assertEquals("DONE", todayRows.get(0).getStatus());

        // Passing null should also be tolerated and commit delete
        dailyLogDAO.insert(new DailyLogEntry(null, today, "TOPIC_LEARN", topicId, LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"));
        assertEquals(2, dailyLogDAO.findByDate(today).size());
        dailyLogDAO.replaceFutureRows(today, null);
        assertEquals(1, dailyLogDAO.findByDate(today).size());
        assertEquals("DONE", dailyLogDAO.findByDate(today).get(0).getStatus());
    }
}

