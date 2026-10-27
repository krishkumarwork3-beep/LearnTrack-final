package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.SettingsDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.dao.TopicDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.FreeSlot;
import com.learntrack.model.Topic;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Computes available and required study hours for dated MST/EST exam batches
 * and manages extra daily study hours for the negotiation loop.
 * Plan Section 9 & User Guide Section 6.
 */
public class ExamPeriodCapacityService {

    private final ExamDAO examDAO;
    private final TopicDAO topicDAO;
    private final TimetableDAO timetableDAO;
    private final SettingsDAO settingsDAO;
    private final ExamPeriodFreeSlotService examPeriodFreeSlotService;
    private final ExamWindowService examWindowService;

    public ExamPeriodCapacityService(ExamDAO examDAO, TopicDAO topicDAO,
                                     TimetableDAO timetableDAO, SettingsDAO settingsDAO,
                                     ExamPeriodFreeSlotService examPeriodFreeSlotService) {
        this(examDAO, topicDAO, timetableDAO, settingsDAO, examPeriodFreeSlotService, new ExamWindowService());
    }

    public ExamPeriodCapacityService(ExamDAO examDAO, TopicDAO topicDAO,
                                     TimetableDAO timetableDAO, SettingsDAO settingsDAO,
                                     ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                     ExamWindowService examWindowService) {
        this.examDAO = examDAO;
        this.topicDAO = topicDAO;
        this.timetableDAO = timetableDAO;
        this.settingsDAO = settingsDAO;
        this.examPeriodFreeSlotService = examPeriodFreeSlotService;
        this.examWindowService = examWindowService != null ? examWindowService : new ExamWindowService();
    }

    public double getExtraHours(String batchType) throws SQLException {
        String key = "exam_period_extra_hours_" + batchType.toUpperCase();
        return settingsDAO.getDouble(key, 0.0);
    }

    public void setExtraHours(String batchType, double hours) throws SQLException {
        String key = "exam_period_extra_hours_" + batchType.toUpperCase();
        settingsDAO.set(key, String.valueOf(hours));
    }

    /**
     * Required hours for a batch: remaining hours of all important_flag = 1 topics
     * plus PYQ remaining hours for exams in this batch.
     * Anticipated PYQ load is explicitly counted per Plan Section 9.
     */
    public double getRequiredHours(int semesterId, String batchType, LocalDate today) throws SQLException {
        List<Exam> exams = examDAO.findBatch(semesterId, batchType);
        double total = 0.0;
        for (Exam e : exams) {
            if (today != null && !e.isUnfinished(today)) {
                continue; // Skip already finished exams in the batch (Plan §5)
            }
            List<Topic> topics = topicDAO.findByExam(e.getExamId());
            for (Topic t : topics) {
                if (t.getImportantFlag() == 1) {
                    total += t.getRemainingHours();
                }
            }
            total += getRequiredPyqHoursForExam(e);
        }
        return total;
    }

    public double getRequiredHours(int semesterId, String batchType) throws SQLException {
        return getRequiredHours(semesterId, batchType, LocalDate.now());
    }

    /**
     * Explicit named method for counting anticipated PYQ load for an exam in the batch.
     */
    public double getRequiredPyqHoursForExam(Exam exam) {
        return exam != null ? exam.getPyqRemainingHours() : 0.0;
    }

    public boolean isBatchDated(int semesterId, String batchType) throws SQLException {
        List<Exam> exams = examDAO.findBatch(semesterId, batchType);
        for (Exam e : exams) {
            if (e.getExamDate() != null) {
                return true;
            }
        }
        return false;
    }

    public LocalDate getLatestExamDate(int semesterId, String batchType) throws SQLException {
        // Use ExamWindowService.windowFor for the datesheet range
        LocalDate[] window = examWindowService.windowFor(semesterId, batchType);
        if (window != null && window.length >= 2 && window[1] != null) {
            return window[1];
        }
        
        // Fallback: compute from individual exam dates
        List<Exam> exams = examDAO.findBatch(semesterId, batchType);
        LocalDate latestDated = null;
        for (Exam e : exams) {
            if (e.getExamDate() != null) {
                if (latestDated == null || e.getExamDate().isAfter(latestDated)) {
                    latestDated = e.getExamDate();
                }
            }
        }
        return latestDated;
    }

    public int getDaysInWindow(int semesterId, String batchType, LocalDate today) throws SQLException {
        LocalDate[] window = examWindowService.windowFor(semesterId, batchType);
        if (window == null || window.length < 2) {
            return 0;
        }
        
        LocalDate start = window[0];
        LocalDate end = window[1];
        
        // Clamp start to max(today, window start)
        if (start == null || start.isBefore(today)) {
            start = today;
        }
        
        if (end == null || end.isBefore(start)) {
            return 0;
        }
        
        return (int) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
    }

    /**
     * Calculates suggested extra daily study hours needed to close the deficit.
     * Suggested hours rounded up to nearest 0.5 hour increment.
     */
    public double calculateSuggestedExtraHoursPerDay(int semesterId, String batchType, LocalDate today) throws SQLException {
        double required = getRequiredHours(semesterId, batchType, today);
        double available = getAvailableHours(semesterId, batchType, today);
        double deficit = required - available;
        if (deficit <= 0.05) {
            return 0.0;
        }
        int days = getDaysInWindow(semesterId, batchType, today);
        if (days <= 0) {
            return deficit;
        }
        double neededPerDay = deficit / days;
        double rounded = Math.ceil(neededPerDay * 2.0) / 2.0;
        return Math.max(0.5, rounded);
    }

    /**
     * Maximum possible study hours across the batch window if study hours equal total free time every day.
     */
    public double getMaxPossibleAvailableHours(int semesterId, String batchType, LocalDate today) throws SQLException {
        LocalDate[] window = examWindowService.windowFor(semesterId, batchType);
        if (window == null || window.length < 2) {
            return 0.0;
        }
        
        LocalDate start = window[0];
        LocalDate end = window[1];
        
        // Clamp start to max(today, window start)
        if (start == null || start.isBefore(today)) {
            start = today;
        }
        
        if (end == null || end.isBefore(start)) {
            return 0.0;
        }
        
        double maxPossible = 0.0;
        LocalDate cur = start;
        while (!cur.isAfter(end)) {
            List<FreeSlot> slots = examPeriodFreeSlotService.computeFreeSlots(semesterId, cur);
            double dayFree = 0.0;
            for (FreeSlot s : slots) {
                dayFree += s.getDuration();
            }
            maxPossible += dayFree;
            cur = cur.plusDays(1);
        }
        return maxPossible;
    }

    /**
     * Computes whether increasing extra daily study hours can resolve the deficit.
     * Returns false if the required hours exceed the maximum possible free time across the window,
     * or if current available hours are already at max possible free time.
     */
    public boolean canExtraHoursResolveDeficit(int semesterId, String batchType, LocalDate today) throws SQLException {
        double required = getRequiredHours(semesterId, batchType, today);
        double available = getAvailableHours(semesterId, batchType, today);
        if (required <= available + 0.05) {
            return true;
        }
        double maxPossible = getMaxPossibleAvailableHours(semesterId, batchType, today);
        if (required > maxPossible + 0.05) {
            return false;
        }
        if (available >= maxPossible - 0.05) {
            return false;
        }
        return true;
    }

    /**
     * Available hours across the window from today to the latest dated exam in the batch.
     * Daily budget = min(freeTime, weekday_preference + extraHours).
     */
    public double getAvailableHours(int semesterId, String batchType, LocalDate today) throws SQLException {
        LocalDate[] window = examWindowService.windowFor(semesterId, batchType);
        if (window == null || window.length < 2) {
            return 0.0;
        }
        
        LocalDate start = window[0];
        LocalDate end = window[1];
        
        // Clamp start to max(today, window start)
        if (start == null || start.isBefore(today)) {
            start = today;
        }
        
        if (end == null || end.isBefore(start)) {
            return 0.0;
        }

        double extraHours = getExtraHours(batchType);
        double totalAvailable = 0.0;
        LocalDate cur = start;

        while (!cur.isAfter(end)) {
            List<FreeSlot> slots = examPeriodFreeSlotService.computeFreeSlots(semesterId, cur);
            double dayFree = 0.0;
            for (FreeSlot s : slots) {
                dayFree += s.getDuration();
            }
            double pref = timetableDAO.getStudyHours(semesterId, cur.getDayOfWeek().name());
            double dayBudget = Math.min(dayFree, pref + extraHours);
            totalAvailable += dayBudget;
            cur = cur.plusDays(1);
        }

        return totalAvailable;
    }
}
