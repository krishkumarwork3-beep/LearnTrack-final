package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.Exam;
import com.learntrack.model.SubjectInfo;
import com.learntrack.model.WeeklyTimetableEntry;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubjectListService - verifies subject list merging without duplicates.
 */
public class SubjectListServiceTest {

    private static final String TEST_DB_PATH = "target/test_subject_list.db";
    private TimetableDAO timetableDAO;
    private ExamDAO examDAO;
    private SubjectListService service;

    @BeforeEach
    void setUp() throws SQLException {
        new File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        timetableDAO = new TimetableDAO();
        examDAO = new ExamDAO();
        service = new SubjectListService(timetableDAO, examDAO);
        
        // Create test semester
        SemesterDAO semesterDAO = new SemesterDAO();
        semesterDAO.insert(new com.learntrack.model.Semester(null, "Test Semester", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void testMergeTimetableAndKeptSubjects() throws SQLException {
        int semesterId = 1;

        // Add timetable entries for UCS503 and UCS420
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        entries.add(createEntry(semesterId, "MONDAY", "CLASS", "UCS503 Lecture", "UCS503"));
        entries.add(createEntry(semesterId, "TUESDAY", "LAB", "UCS503 Lab", "UCS503"));
        entries.add(createEntry(semesterId, "WEDNESDAY", "CLASS", "UCS420 Lecture", "UCS420"));
        entries.add(createEntry(semesterId, "THURSDAY", "MEAL", "Lunch", null)); // Non-academic
        timetableDAO.saveTimetableEntries(semesterId, entries);

        // Add subject name for UCS503
        SubjectNameDAO.upsert(semesterId, "UCS503", "Software Engineering");

        // Add kept subject UCS401 (not in timetable)
        KeptSubjectDAO.upsert(semesterId, "UCS401", "Database Systems");

        // Build subject list
        List<SubjectInfo> subjects = service.buildSubjectList(semesterId);

        // Should have 3 subjects: UCS503, UCS420, UCS401
        assertEquals(3, subjects.size());

        Set<String> codes = subjects.stream().map(SubjectInfo::getSubjectCode).collect(Collectors.toSet());
        assertTrue(codes.contains("UCS503"));
        assertTrue(codes.contains("UCS420"));
        assertTrue(codes.contains("UCS401"));

        // Check UCS503 (in timetable, has name)
        SubjectInfo ucs503 = subjects.stream().filter(s -> s.getSubjectCode().equals("UCS503")).findFirst().orElse(null);
        assertNotNull(ucs503);
        assertEquals("Software Engineering", ucs503.getDisplayName());
        assertTrue(ucs503.isInTimetable());
        assertEquals("In timetable", ucs503.getBadge());

        // Check UCS420 (in timetable, no custom name)
        SubjectInfo ucs420 = subjects.stream().filter(s -> s.getSubjectCode().equals("UCS420")).findFirst().orElse(null);
        assertNotNull(ucs420);
        assertTrue(ucs420.isInTimetable());

        // Check UCS401 (kept for study, not in timetable)
        SubjectInfo ucs401 = subjects.stream().filter(s -> s.getSubjectCode().equals("UCS401")).findFirst().orElse(null);
        assertNotNull(ucs401);
        assertEquals("Database Systems", ucs401.getDisplayName());
        assertFalse(ucs401.isInTimetable());
        assertTrue(ucs401.isKeptForStudy());
        assertEquals("Kept for study", ucs401.getBadge());
    }

    @Test
    void testMergeWithExamSubjects() throws SQLException {
        int semesterId = 1;

        // Add timetable entry for UCS503
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        entries.add(createEntry(semesterId, "MONDAY", "CLASS", "UCS503 Lecture", "UCS503"));
        timetableDAO.saveTimetableEntries(semesterId, entries);

        // Add exams for UCS503 (already in timetable) and UCS420 (not in timetable)
        Exam exam1 = new Exam();
        exam1.setSemesterId(semesterId);
        exam1.setSubject("UCS503");
        exam1.setExamType("NORMAL");
        exam1.setCredits(4.0);
        examDAO.insert(exam1);

        Exam exam2 = new Exam();
        exam2.setSemesterId(semesterId);
        exam2.setSubject("UCS420");
        exam2.setExamType("NORMAL");
        exam2.setCredits(4.0);
        examDAO.insert(exam2);

        // Build subject list
        List<SubjectInfo> subjects = service.buildSubjectList(semesterId);

        // Should have 2 subjects: UCS503 (timetable + exam), UCS420 (exam only)
        assertEquals(2, subjects.size());

        SubjectInfo ucs503 = subjects.stream().filter(s -> s.getSubjectCode().equals("UCS503")).findFirst().orElse(null);
        assertNotNull(ucs503);
        assertTrue(ucs503.isInTimetable());
        assertTrue(ucs503.isHasExam());

        SubjectInfo ucs420 = subjects.stream().filter(s -> s.getSubjectCode().equals("UCS420")).findFirst().orElse(null);
        assertNotNull(ucs420);
        assertFalse(ucs420.isInTimetable());
        assertTrue(ucs420.isHasExam());
        assertEquals("Exam only", ucs420.getBadge());
    }

    @Test
    void testNoDuplicates() throws SQLException {
        int semesterId = 1;

        // Add same subject in timetable, kept subjects, and exams
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        entries.add(createEntry(semesterId, "MONDAY", "CLASS", "UCS503 Lecture", "UCS503"));
        timetableDAO.saveTimetableEntries(semesterId, entries);

        KeptSubjectDAO.upsert(semesterId, "UCS503", "Software Engineering");

        Exam exam = new Exam();
        exam.setSemesterId(semesterId);
        exam.setSubject("UCS503");
        exam.setExamType("NORMAL");
        exam.setCredits(4.0);
        examDAO.insert(exam);

        // Build subject list
        List<SubjectInfo> subjects = service.buildSubjectList(semesterId);

        // Should have exactly 1 subject (de-duplicated)
        assertEquals(1, subjects.size());
        
        SubjectInfo subject = subjects.get(0);
        assertEquals("UCS503", subject.getSubjectCode());
        assertTrue(subject.isInTimetable());
        assertTrue(subject.isKeptForStudy());
        assertTrue(subject.isHasExam());
    }

    @Test
    void testEmptyDatabase() throws SQLException {
        List<SubjectInfo> subjects = service.buildSubjectList(1);
        assertTrue(subjects.isEmpty());
    }

    @Test
    void testSubjectCodeExtraction() throws SQLException {
        int semesterId = 1;

        // Add entry without subject_code - should extract from label
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry();
        entry.setSemesterId(semesterId);
        entry.setDayOfWeek("MONDAY");
        entry.setStartTime(LocalTime.of(9, 0));
        entry.setEndTime(LocalTime.of(10, 0));
        entry.setActivityType("CLASS");
        entry.setLabel("UCS503 Lecture");
        entry.setSubjectCode(null); // Explicitly null
        entries.add(entry);
        timetableDAO.saveTimetableEntries(semesterId, entries);

        List<SubjectInfo> subjects = service.buildSubjectList(semesterId);

        assertEquals(1, subjects.size());
        assertEquals("UCS503", subjects.get(0).getSubjectCode());
    }

    private WeeklyTimetableEntry createEntry(int semesterId, String day, String type, String label, String subjectCode) {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry();
        entry.setSemesterId(semesterId);
        entry.setDayOfWeek(day);
        entry.setStartTime(LocalTime.of(9, 0));
        entry.setEndTime(LocalTime.of(10, 0));
        entry.setActivityType(type);
        entry.setLabel(label);
        entry.setSubjectCode(subjectCode);
        return entry;
    }
}
