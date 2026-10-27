import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.service.*;
import com.learntrack.util.DBConnection;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;

/**
 * Verify Phase 3: run replan against a copy of learntrack.db and print study hours per day
 */
public class VerifyPhase3 {
    public static void main(String[] args) throws Exception {
        // Copy real DB to test DB
        File realDb = new File("learntrack.db");
        File testDb = new File("learntrack_verify_phase3.db");
        
        if (!realDb.exists()) {
            System.out.println("ERROR: learntrack.db not found!");
            return;
        }
        
        Files.copy(realDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Copied learntrack.db -> learntrack_verify_phase3.db\n");
        
        try {
            // Point to test database
            DBConnection.setCustomUrl("jdbc:sqlite:learntrack_verify_phase3.db");
            
            // Initialize schema (runs migrations)
            try (Connection conn = DBConnection.getConnection()) {
                DBConnection.initSchema(conn);
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
            System.out.println("PHASE 3 REPLAN OUTPUT:");
            System.out.println("========================================\n");
            
            // Print study hours per day for 2026-10-22 to 2026-10-25
            printStudyHoursPerDay(dailyLogDAO, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 25));
            
            System.out.println("\n========================================");
            System.out.println("2026-10-22 (THURSDAY) TIME SLOTS:");
            System.out.println("========================================\n");
            
            // Print time slots for 2026-10-22
            printTimeSlotsForDate(dailyLogDAO, LocalDate.of(2026, 10, 22));
            
        } finally {
            DBConnection.resetToDefaultUrl();
            testDb.delete();
        }
    }
    
    private static void printStudyHoursPerDay(DailyLogDAO dailyLogDAO, LocalDate start, LocalDate end) throws Exception {
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
    
    private static void printTimeSlotsForDate(DailyLogDAO dailyLogDAO, LocalDate date) throws Exception {
        List<DailyLogEntry> entries = dailyLogDAO.findByDateRange(date, date);
        
        if (entries.isEmpty()) {
            System.out.println("No planned sessions for this date.");
            return;
        }
        
        System.out.printf("%-15s %-10s %-10s %-10s %-15s%n", 
            "Type", "Item ID", "Start", "End", "Status");
        System.out.println("----------------------------------------------------------------");
        
        for (DailyLogEntry entry : entries) {
            System.out.printf("%-15s %-10d %-10s %-10s %-15s%n",
                entry.getItemType(),
                entry.getItemId(),
                entry.getSlotStart(),
                entry.getSlotEnd(),
                entry.getStatus());
        }
        
        System.out.println("\nExpected: Study sessions in all free gaps:");
        System.out.println("  07:00-08:50 (before class 1)");
        System.out.println("  11:20-15:30 (between classes)");
        System.out.println("  17:10-23:00 (after class 2 until sleep)");
    }
}
