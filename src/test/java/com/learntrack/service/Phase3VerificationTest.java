package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.DailyLogEntry;
import com.learntrack.model.WeeklyTimetableEntry;
import com.learntrack.util.DBConnection;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 verification: run replan against copy of real learntrack.db
 */
public class Phase3VerificationTest {
    
    @Test
    void verifyPhase3ReplanOutput() throws Exception {
        // Copy real DB to test DB
        File realDb = new File("learntrack.db");
        File testDb = new File("learntrack_verify_phase3_test.db");
        
        if (!realDb.exists()) {
            System.out.println("SKIP: learntrack.db not found!");
            return;
        }
        
        try {
            Files.copy(realDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("\nCopied learntrack.db -> learntrack_verify_phase3_test.db\n");
            
            // Point to test database
            DBConnection.setCustomUrl("jdbc:sqlite:learntrack_verify_phase3_test.db");
            
            // Initialize schema (runs migrations)
            try (Connection conn = DBConnection.getConnection()) {
                DBConnection.initSchema(conn);
                try (java.sql.Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM daily_log WHERE log_date >= '2026-10-22'");
                }
            }
            
            // Setup DAOs and services
            TimetableDAO timetableDAO = new TimetableDAO();
            ExamDAO examDAO = new ExamDAO();
            TopicDAO topicDAO = new TopicDAO();
            SkillDAO skillDAO = new SkillDAO();
            DailyLogDAO dailyLogDAO = new DailyLogDAO();
            SettingsDAO settingsDAO = new SettingsDAO();
            DateOverrideDAO dateOverrideDAO = new DateOverrideDAO();
            ExamPeriodRoutineDAO routineDAO = new ExamPeriodRoutineDAO();
            
            FreeSlotService freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
            ExamPeriodFreeSlotService examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);
            ScoringService scoringService = new ScoringService();
            PriorityQueueService priorityQueueService = new PriorityQueueService(scoringService);
            
            SemesterReplanService replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService
            );
            
            // Run replan for semester 2 from 2026-10-22
            LocalDate fromDate = LocalDate.of(2026, 10, 22);
            System.out.println("Running replan from " + fromDate + "...\n");
            replanService.regenerateSemesterPlan(2, fromDate);
            
            System.out.println("========================================");
            System.out.println("PHASE 3 REPLAN OUTPUT (2026-10-22 to 2026-10-26):");
            System.out.println("========================================\n");
            
            // Print study hours per day for 2026-10-22 to 2026-10-26
            printStudyHoursPerDay(dailyLogDAO, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 26));
            
            System.out.println("\n========================================");
            System.out.println("2026-10-22 (THURSDAY) TIMETABLE ENTRIES:");
            System.out.println("========================================\n");
            
            // Print timetable entries for Thursday
            List<WeeklyTimetableEntry> thursdayEntries = timetableDAO.findBySemesterAndDay(2, "THURSDAY");
            for (WeeklyTimetableEntry entry : thursdayEntries) {
                System.out.printf("%-10s %s - %s  %s%n",
                    entry.getActivityType(),
                    entry.getStartTime(),
                    entry.getEndTime(),
                    entry.getLabel() != null ? entry.getLabel() : "");
            }
            
            System.out.println("\n========================================");
            System.out.println("2026-10-22 (THURSDAY) ALL DAILY LOG ENTRIES:");
            System.out.println("========================================\n");
            
            // Print ALL entries for 2026-10-22 and verify no overlaps
            verifyNoOverlapsForDate(dailyLogDAO, timetableDAO, LocalDate.of(2026, 10, 22), 2);
            
        } finally {
            DBConnection.resetToDefaultUrl();
            testDb.delete();
        }
    }
    
    private void printStudyHoursPerDay(DailyLogDAO dailyLogDAO, LocalDate start, LocalDate end) throws Exception {
        LocalDate date = start;
        while (!date.isAfter(end)) {
            List<DailyLogEntry> entries = dailyLogDAO.findByDateRange(date, date);
            
            double totalHours = 0.0;
            for (DailyLogEntry entry : entries) {
                if (entry.getSlotStart() != null && entry.getSlotEnd() != null) {
                    long minutes = Duration.between(entry.getSlotStart(), entry.getSlotEnd()).toMinutes();
                    totalHours += minutes / 60.0;
                }
            }
            
            System.out.printf("%s (%9s): %5.2f hours, %2d sessions%n",
                date, date.getDayOfWeek(), totalHours, entries.size());
            
            date = date.plusDays(1);
        }
    }
    
    private void verifyNoOverlapsForDate(DailyLogDAO dailyLogDAO, TimetableDAO timetableDAO, 
                                         LocalDate date, int semesterId) throws Exception {
        List<DailyLogEntry> studyEntries = dailyLogDAO.findByDateRange(date, date);
        String dayOfWeek = date.getDayOfWeek().name();
        List<WeeklyTimetableEntry> timetableEntries = timetableDAO.findBySemesterAndDay(semesterId, dayOfWeek);
        
        System.out.printf("%-15s %-10s %-10s %-10s%n", "Type", "Item ID", "Start", "End");
        System.out.println("--------------------------------------------------");
        
        for (DailyLogEntry entry : studyEntries) {
            System.out.printf("%-15s %-10d %-10s %-10s%n",
                entry.getItemType(),
                entry.getItemId(),
                entry.getSlotStart(),
                entry.getSlotEnd());
        }
        
        System.out.println("\nVerifying no overlaps with timetable entries...");
        
        // Check each study entry against timetable entries
        for (DailyLogEntry study : studyEntries) {
            LocalTime studyStart = study.getSlotStart();
            LocalTime studyEnd = study.getSlotEnd();
            
            for (WeeklyTimetableEntry timetable : timetableEntries) {
                LocalTime ttStart = timetable.getStartTime();
                LocalTime ttEnd = timetable.getEndTime();
                
                // Check for overlap
                boolean overlaps = !(studyEnd.isBefore(ttStart) || studyEnd.equals(ttStart) ||
                                     studyStart.isAfter(ttEnd) || studyStart.equals(ttEnd));
                
                assertFalse(overlaps, 
                    String.format("Study session %s-%s overlaps with %s %s-%s",
                        studyStart, studyEnd, timetable.getActivityType(), ttStart, ttEnd));
            }
        }
        
        System.out.println("✓ No overlaps detected");
    }
}
