package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.DayRow;
import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

public class VerifyPhase4ManualTest {

    @Test
    void testVerifyPhase4OnCopyDb() throws Exception {
        File orig = new File("learntrack.db");
        org.junit.jupiter.api.Assumptions.assumeTrue(orig.exists(), "learntrack.db is missing; skipping manual verification test");
        File copy = new File("target/learntrack_copy_phase4.db");
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
        LocalDate today = LocalDate.of(2026, 10, 22); // Thursday

        System.out.println("=== Running regenerateSemesterPlan for Semester " + semId + " from " + today + " ===");
        replanService.regenerateSemesterPlan(semId, today);

        List<DayRow> rows = dayScheduleService.build(semId, today);

        System.out.println("================================================================================");
        System.out.println("MERGED DAY SCHEDULE FOR THURSDAY " + today + ":");
        System.out.println("================================================================================");
        for (DayRow r : rows) {
            String status = r.isFixed() ? "FIXED" : (r.getDailyLogEntry() != null ? r.getDailyLogEntry().getStatus() : "PLANNED");
            System.out.printf("%-5s - %-5s | %-12s | %-45s | %5.2f h | %s%n",
                    r.getStart(), (r.getEnd() != null && r.getEnd().equals(java.time.LocalTime.MAX) ? "24:00" : r.getEnd()),
                    r.getType(), r.getLabel(), r.getDurationHours(), status);
        }
        System.out.println("================================================================================");

        DBConnection.resetToDefaultUrl();
    }
}
