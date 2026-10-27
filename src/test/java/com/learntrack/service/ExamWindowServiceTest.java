package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ExamWindowServiceTest {

    private final String testDbPath = "target/test_exam_window.db";
    private SemesterDAO semesterDAO;
    private ExamDAO examDAO;
    private ExamTimelineDAO timelineDAO;
    private ExamWindowService examWindowService;
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(testDbPath).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + testDbPath);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        semesterDAO = new SemesterDAO();
        examDAO = new ExamDAO();
        timelineDAO = new ExamTimelineDAO();
        examWindowService = new ExamWindowService();

        semesterId = semesterDAO.insert(new Semester(null, "Fall 2024", "ACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(testDbPath).delete();
    }

    @Test
    void testWindowFromStoredSheetDates() throws SQLException {
        // Store a sheet window via ExamTimelineDAO
        LocalDate start = LocalDate.of(2024, 12, 2);
        LocalDate end = LocalDate.of(2024, 12, 28);
        
        timelineDAO.setWindow(semesterId, "EST", start, end);
        timelineDAO.setDatesheetAnnounced(semesterId, "EST", true);

        LocalDate[] window = examWindowService.windowFor(semesterId, "EST");
        
        assertNotNull(window, "Window should not be null");
        assertEquals(2, window.length);
        assertEquals(start, window[0], "Window start should match stored start");
        assertEquals(end, window[1], "Window end should match stored end");
    }

    @Test
    void testFallbackToMinMaxOfUserDatedExams() throws SQLException {
        // No stored sheet window, but we have dated exams
        LocalDate exam1Date = LocalDate.of(2024, 10, 5);
        LocalDate exam2Date = LocalDate.of(2024, 10, 12);
        LocalDate exam3Date = LocalDate.of(2024, 10, 8);

        examDAO.insert(new Exam(null, semesterId, "Math", "MST", 3, 20.0,
                exam1Date, LocalTime.of(10, 0), LocalTime.of(12, 0), 30, null, null, 2.0));
        examDAO.insert(new Exam(null, semesterId, "Physics", "MST", 3, 20.0,
                exam2Date, LocalTime.of(14, 0), LocalTime.of(16, 0), 30, null, null, 2.0));
        examDAO.insert(new Exam(null, semesterId, "Chemistry", "MST", 3, 20.0,
                exam3Date, LocalTime.of(9, 0), LocalTime.of(11, 0), 30, null, null, 2.0));

        LocalDate[] window = examWindowService.windowFor(semesterId, "MST");
        
        assertNotNull(window, "Window should not be null when dated exams exist");
        assertEquals(2, window.length);
        assertEquals(exam1Date, window[0], "Window start should be first exam date (not today)");
        assertEquals(exam2Date, window[1], "Window end should be last exam date");
    }

    @Test
    void testNullWhenNothingDated() throws SQLException {
        // No stored window, no dated exams - should return null
        examDAO.insert(new Exam(null, semesterId, "Math", "MST", 3, 20.0,
                null, null, null, 30, null, null, 2.0));

        LocalDate[] window = examWindowService.windowFor(semesterId, "MST");
        
        assertNull(window, "Window should be null when no dated exams and no stored window");
    }

    @Test
    void testMstEstOverlapDayReturnsExamType() throws SQLException {
        // Set up overlapping MST and EST windows
        timelineDAO.setWindow(semesterId, "MST", LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 10));
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);
        
        timelineDAO.setWindow(semesterId, "EST", LocalDate.of(2024, 10, 8), LocalDate.of(2024, 10, 15));
        timelineDAO.setDatesheetAnnounced(semesterId, "EST", true);

        LocalDate overlapDate = LocalDate.of(2024, 10, 9);
        
        Optional<String> examType = examWindowService.examTypeOnDate(semesterId, overlapDate);
        
        assertTrue(examType.isPresent(), "Should return an exam type for overlap day");
        assertTrue(examType.get().equals("MST") || examType.get().equals("EST"), 
                "Should return either MST or EST for overlap day");
    }

    @Test
    void testClearWindowRemovesStoredWindow() throws SQLException {
        // Store a window
        timelineDAO.setWindow(semesterId, "MST", LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 10));
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", true);

        LocalDate[] windowBefore = examWindowService.windowFor(semesterId, "MST");
        assertNotNull(windowBefore, "Window should exist before clearing");

        // Clear the window
        timelineDAO.clearWindow(semesterId, "MST");
        timelineDAO.setDatesheetAnnounced(semesterId, "MST", false);

        LocalDate[] windowAfter = examWindowService.windowFor(semesterId, "MST");
        assertNull(windowAfter, "Window should be null after clearing");
    }

    @Test
    void testReSettingWindowReplacesOldOne() throws SQLException {
        // Set initial window
        LocalDate oldStart = LocalDate.of(2024, 9, 1);
        LocalDate oldEnd = LocalDate.of(2024, 9, 15);
        timelineDAO.setWindow(semesterId, "EST", oldStart, oldEnd);
        timelineDAO.setDatesheetAnnounced(semesterId, "EST", true);

        LocalDate[] oldWindow = examWindowService.windowFor(semesterId, "EST");
        assertEquals(oldStart, oldWindow[0]);
        assertEquals(oldEnd, oldWindow[1]);

        // Re-set with new dates (should replace, not merge)
        LocalDate newStart = LocalDate.of(2024, 12, 1);
        LocalDate newEnd = LocalDate.of(2024, 12, 20);
        timelineDAO.setWindow(semesterId, "EST", newStart, newEnd);

        LocalDate[] newWindow = examWindowService.windowFor(semesterId, "EST");
        assertEquals(newStart, newWindow[0], "Window start should be replaced with new start");
        assertEquals(newEnd, newWindow[1], "Window end should be replaced with new end");
        assertNotEquals(oldStart, newWindow[0], "Old start should not be present");
    }
}
