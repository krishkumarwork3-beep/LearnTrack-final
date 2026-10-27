package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Manual verification test for Phase 5 requirements on a copy of the real database.
 */
public class VerifyPhase5ManualTest {

    @Test
    void testVerifyPhase5OnCopyDb() throws Exception {
        File orig = new File("learntrack.db");
        org.junit.jupiter.api.Assumptions.assumeTrue(orig.exists(), "learntrack.db is missing; skipping manual verification test");
        File copy = new File("target/learntrack_copy_phase5.db");
        Files.copy(orig.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);

        DBConnection.setCustomUrl("jdbc:sqlite:" + copy.getAbsolutePath());

        SemesterDAO semesterDAO = new SemesterDAO();
        TimetableDAO timetableDAO = new TimetableDAO();
        ExamPeriodRoutineDAO routineDAO = new ExamPeriodRoutineDAO();
        DateOverrideDAO dateOverrideDAO = new DateOverrideDAO();
        ExamDAO examDAO = new ExamDAO();
        TopicDAO topicDAO = new TopicDAO();
        SkillDAO skillDAO = new SkillDAO();
        DailyLogDAO dailyLogDAO = new DailyLogDAO();
        SettingsDAO settingsDAO = new SettingsDAO();

        ScoringService scoringService = new ScoringService();
        PriorityQueueService priorityQueueService = new PriorityQueueService(scoringService);
        FreeSlotService freeSlotService = new FreeSlotService(timetableDAO, dateOverrideDAO, examDAO);
        ExamPeriodFreeSlotService examPeriodFreeSlotService = new ExamPeriodFreeSlotService(routineDAO, dateOverrideDAO, examDAO);

        SemesterReplanService replanService = new SemesterReplanService(
                timetableDAO, examDAO, topicDAO, skillDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService
        );

        DayScheduleService dayScheduleService = new DayScheduleService(
                timetableDAO, dateOverrideDAO, examDAO, dailyLogDAO,
                routineDAO, topicDAO, skillDAO
        );

        Semester active = semesterDAO.getActiveSemester();
        assert active != null;
        int semId = active.getSemesterId();
        LocalDate startDate = LocalDate.of(2026, 10, 22);

        System.out.println("================================================================================");
        System.out.println("PHASE 5 VERIFICATION - Week schedule from " + startDate);
        System.out.println("================================================================================\n");

        System.out.println("=== Regenerating plan from " + startDate + " ===");
        replanService.regenerateSemesterPlan(semId, startDate);

        // Print daily schedules for one week (2026-10-22 to 2026-10-28)
        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            List<DayRow> rows = dayScheduleService.build(semId, date);

            double studyHours = rows.stream()
                    .filter(r -> !r.isFixed() && !r.getType().equals("SLEEP"))
                    .mapToDouble(DayRow::getDurationHours)
                    .sum();

            System.out.printf("%s %s - %.2f hours planned study%n",
                    date, date.getDayOfWeek(), studyHours);
        }

        System.out.println("\n================================================================================");
        System.out.println("DETAILED SCHEDULE FOR 2026-10-29 (Wednesday):");
        System.out.println("================================================================================");
        LocalDate oct29 = LocalDate.of(2026, 10, 29);
        List<DayRow> oct29Rows = dayScheduleService.build(semId, oct29);

        for (DayRow r : oct29Rows) {
            String status = r.isFixed() ? "FIXED" : (r.getDailyLogEntry() != null ? r.getDailyLogEntry().getStatus() : "PLANNED");
            System.out.printf("%-5s - %-5s | %-12s | %-50s | %5.2f h | %s%n",
                    r.getStart(),
                    (r.getEnd() != null && r.getEnd().equals(LocalTime.MAX) ? "24:00" : r.getEnd()),
                    r.getType(),
                    r.getLabel().length() > 50 ? r.getLabel().substring(0, 47) + "..." : r.getLabel(),
                    r.getDurationHours(),
                    status);
        }

        System.out.println("\n================================================================================");
        System.out.println("EXAMS WITHOUT SYLLABUS SCOPE (should show warnings):");
        System.out.println("================================================================================");
        List<Exam> allExams = examDAO.findBySemester(semId);
        ExamScopeDAO examScopeDAO = new ExamScopeDAO();
        for (Exam exam : allExams) {
            int scopeCount = 0;
            try {
                scopeCount = examScopeDAO.countForExam(exam.getExamId());
            } catch (Exception ex) {
                // Ignore
            }
            boolean hasScope = scopeCount > 0;
            if (!hasScope) {
                System.out.printf("⚠ %s %s has no scope. Add scope to get study sessions.%n",
                        exam.getSubjectCode(), exam.getExamType());
            }
        }

        System.out.println("\n================================================================================");
        System.out.println("UNDATED EXAMS (should be marked as tentative):");
        System.out.println("================================================================================");
        for (Exam exam : allExams) {
            if (exam.getExamDate() == null) {
                System.out.printf("📅 %s %s is TENTATIVE (undated, window: %s to %s)%n",
                        exam.getSubjectCode(),
                        exam.getExamType(),
                        exam.getWindowStartDate() != null ? exam.getWindowStartDate() : "not set",
                        exam.getWindowEndDate() != null ? exam.getWindowEndDate() : "not set");
            }
        }

        System.out.println("\n================================================================================");
        System.out.println("FEASIBILITY CHECK:");
        System.out.println("================================================================================");
        FeasibilityService feasibilityService = new FeasibilityService(topicDAO, examDAO, timetableDAO, freeSlotService);
        FeasibilityService.FeasibilityResult feas = feasibilityService.calculateFeasibility(semId, startDate);
        System.out.printf("Needed: %.2f h, Capacity: %.2f h, Feasible: %s%n",
                feas.getNeededHours(), feas.getCapacityHours(), feas.isFeasible() ? "YES" : "NO");

        DBConnection.resetToDefaultUrl();
    }
}
