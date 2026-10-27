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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class SubjectCleanupServiceTest {

    private static final String TEST_DB_PATH = "target/test_subject_cleanup.db";
    private TimetableDAO timetableDAO;
    private ExamDAO examDAO;
    private TopicDAO topicDAO;
    private DailyLogDAO dailyLogDAO;
    private SubjectCreditDAO subjectCreditDAO;
    private SubjectListService subjectListService;
    private SubjectCleanupService cleanupService;
    private int semesterId = 1;

    @BeforeEach
    void setUp() throws SQLException {
        new File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        timetableDAO = new TimetableDAO();
        examDAO = new ExamDAO();
        topicDAO = new TopicDAO();
        dailyLogDAO = new DailyLogDAO();
        subjectCreditDAO = new SubjectCreditDAO();
        subjectListService = new SubjectListService(timetableDAO, examDAO);
        cleanupService = new SubjectCleanupService();

        SemesterDAO semesterDAO = new SemesterDAO();
        semesterDAO.insert(new Semester(null, "Test Semester", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void testRemoveSubjectCompletelyRemovesAllLeftoversAndLeavesOthersUntouched() throws SQLException {
        // Setup Subject X (to be removed)
        String codeX = "UCS615";
        KeptSubjectDAO.upsert(semesterId, codeX, "Advanced Algorithms");
        SubjectNameDAO.upsert(semesterId, codeX, "Advanced Algorithms");
        subjectCreditDAO.setCredits(semesterId, codeX, 4.0);

        Exam examX = new Exam();
        examX.setSemesterId(semesterId);
        examX.setSubject(codeX);
        examX.setSubjectCode(codeX);
        examX.setExamType("NORMAL");
        examX.setEvalName("Quiz 1");
        examX.setCredits(4.0);
        int examIdX = examDAO.insert(examX);

        Topic topicX = new Topic();
        topicX.setExamId(examIdX);
        topicX.setName("Graph Theory");
        topicX.setRemainingHours(5.0);
        int topicIdX = topicDAO.insert(topicX);

        // Daily log rows for X: one PENDING (must be deleted) and one DONE (must be preserved)
        DailyLogEntry pendingLogX = new DailyLogEntry(
                null, LocalDate.now(), "TOPIC_LEARN", topicIdX,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "PENDING"
        );
        dailyLogDAO.insert(pendingLogX);

        DailyLogEntry doneLogX = new DailyLogEntry(
                null, LocalDate.now().minusDays(1), "TOPIC_LEARN", topicIdX,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "DONE"
        );
        dailyLogDAO.insert(doneLogX);

        // Setup Subject Y (to be kept untouched)
        String codeY = "UCS503";
        KeptSubjectDAO.upsert(semesterId, codeY, "Software Engineering");
        SubjectNameDAO.upsert(semesterId, codeY, "Software Engineering");
        subjectCreditDAO.setCredits(semesterId, codeY, 3.5);

        Exam examY = new Exam();
        examY.setSemesterId(semesterId);
        examY.setSubject(codeY);
        examY.setSubjectCode(codeY);
        examY.setExamType("NORMAL");
        examY.setEvalName("Midsem");
        examY.setCredits(3.5);
        int examIdY = examDAO.insert(examY);

        Topic topicY = new Topic();
        topicY.setExamId(examIdY);
        topicY.setName("Agile Processes");
        topicY.setRemainingHours(4.0);
        int topicIdY = topicDAO.insert(topicY);

        // Perform complete removal of X
        cleanupService.removeSubjectCompletely(semesterId, codeX);

        // Verify X is completely removed from all tables
        assertFalse(KeptSubjectDAO.exists(semesterId, codeX));
        assertFalse(SubjectNameDAO.findAllAsMap(semesterId).containsKey(codeX));
        assertEquals(0.0, subjectCreditDAO.getExplicitSubjectCredits(semesterId, codeX));
        assertTrue(examDAO.findBySubject(semesterId, codeX).isEmpty());
        assertTrue(topicDAO.findByExam(examIdX).isEmpty());

        // Verify pending daily_log for X was deleted, but DONE daily_log is preserved
        List<DailyLogEntry> allLogs = dailyLogDAO.findByDate(LocalDate.now());
        boolean hasPendingX = allLogs.stream().anyMatch(l -> "PENDING".equals(l.getStatus()) && l.getItemId() == topicIdX);
        assertFalse(hasPendingX, "Non-DONE daily log for removed subject should be deleted");

        List<DailyLogEntry> pastLogs = dailyLogDAO.findByDate(LocalDate.now().minusDays(1));
        boolean hasDoneX = pastLogs.stream().anyMatch(l -> "DONE".equals(l.getStatus()) && l.getItemId() == topicIdX);
        assertTrue(hasDoneX, "DONE daily log for removed subject must be preserved for history");

        // Verify Y is untouched
        assertTrue(KeptSubjectDAO.exists(semesterId, codeY));
        assertEquals("Software Engineering", SubjectNameDAO.findAllAsMap(semesterId).get(codeY));
        assertEquals(3.5, subjectCreditDAO.getExplicitSubjectCredits(semesterId, codeY));
        assertEquals(1, examDAO.findBySubject(semesterId, codeY).size());
        assertEquals(1, topicDAO.findByExam(examIdY).size());
    }

    @Test
    void testOrphanedSubjectLifecycleAndBuildSubjectList() throws SQLException {
        // Build a semester with timetable subjects A, B
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        WeeklyTimetableEntry eA = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "UCS553 lecture", "C101");
        eA.setSubjectCode("UCS553");
        entries.add(eA);

        WeeklyTimetableEntry eB = new WeeklyTimetableEntry(null, semesterId, "TUESDAY",
                LocalTime.of(10, 0), LocalTime.of(11, 0), "CLASS", "UCS503 lecture", "C102");
        eB.setSubjectCode("UCS503");
        entries.add(eB);
        timetableDAO.saveTimetableEntries(semesterId, entries);

        // Add old exam for subject X (UCS615) not in timetable and not kept
        String codeX = "UCS615";
        Exam examX = new Exam();
        examX.setSemesterId(semesterId);
        examX.setSubject(codeX);
        examX.setSubjectCode(codeX);
        examX.setExamType("NORMAL");
        examX.setEvalName("Old Quiz");
        examX.setCredits(4.0);
        examDAO.insert(examX);

        // Before cleanup, buildSubjectList returns X because it has an exam
        List<SubjectInfo> beforeList = subjectListService.buildSubjectList(semesterId);
        Set<String> beforeCodes = beforeList.stream().map(SubjectInfo::getSubjectCode).collect(Collectors.toSet());
        assertTrue(beforeCodes.contains("UCS553"));
        assertTrue(beforeCodes.contains("UCS503"));
        assertTrue(beforeCodes.contains(codeX), "Before cleanup buildSubjectList returns old exam subject X");

        // findOrphanedExamSubjects identifies X as stale
        List<String> orphaned = cleanupService.findOrphanedExamSubjects(semesterId);
        assertTrue(orphaned.contains(codeX));
        assertFalse(orphaned.contains("UCS553"));
        assertFalse(orphaned.contains("UCS503"));

        // Run cleanup
        cleanupService.removeSubjectCompletely(semesterId, codeX);

        // After cleanup, X is gone
        List<SubjectInfo> afterList = subjectListService.buildSubjectList(semesterId);
        Set<String> afterCodes = afterList.stream().map(SubjectInfo::getSubjectCode).collect(Collectors.toSet());
        assertTrue(afterCodes.contains("UCS553"));
        assertTrue(afterCodes.contains("UCS503"));
        assertFalse(afterCodes.contains(codeX), "After cleanup X is gone");
    }

    @Test
    void testRenameSetsNameAndBuildSubjectListReturnsDisplayName() throws SQLException {
        // Timetable has UML501
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        WeeklyTimetableEntry e = new WeeklyTimetableEntry(null, semesterId, "MONDAY",
                LocalTime.of(11, 0), LocalTime.of(12, 0), "CLASS", "UML501 lecture", "Room 1");
        e.setSubjectCode("UML501");
        entries.add(e);
        timetableDAO.saveTimetableEntries(semesterId, entries);

        // Rename sets the name in subject_names
        SubjectNameDAO.upsert(semesterId, "UML501", "Machine learning");

        // buildSubjectList returns the display name "Machine learning" for UML501
        List<SubjectInfo> list = subjectListService.buildSubjectList(semesterId);
        SubjectInfo info = list.stream().filter(s -> "UML501".equals(s.getSubjectCode())).findFirst().orElse(null);
        assertNotNull(info);
        assertEquals("Machine learning", info.getDisplayName());
    }

    @Test
    void testKeptSubjectStaysVisibleWithoutBeingInTimetable() throws SQLException {
        // UCS421 is NOT in timetable, but is in kept_subjects
        KeptSubjectDAO.upsert(semesterId, "UCS421", "Computer Graphics");

        List<SubjectInfo> list = subjectListService.buildSubjectList(semesterId);
        SubjectInfo info = list.stream().filter(s -> "UCS421".equals(s.getSubjectCode())).findFirst().orElse(null);
        assertNotNull(info, "Kept subject UCS421 should be visible in subject list");
        assertFalse(info.isInTimetable());
        assertTrue(info.isKeptForStudy());
        assertEquals("Kept for study", info.getBadge());
        assertEquals("Computer Graphics", info.getDisplayName());
    }
}