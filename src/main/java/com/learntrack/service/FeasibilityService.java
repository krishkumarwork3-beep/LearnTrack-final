package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamScopeDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.dao.TopicDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.FreeSlot;
import com.learntrack.model.Topic;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * Service to evaluate backlog feasibility across upcoming exams.
 * Needed = remaining learn + due revision (+ PYQ only for exams with scope).
 * Capacity = free study time from today to each deadline.
 */
public class FeasibilityService {

    private final TopicDAO topicDAO;
    private final ExamDAO examDAO;
    private final TimetableDAO timetableDAO;
    private final FreeSlotService freeSlotService;
    private final ExamScopeDAO examScopeDAO;
    private final ScoringService scoringService;
    private final ExamWindowService examWindowService;
    private final ExamPeriodFreeSlotService examPeriodFreeSlotService;

    public FeasibilityService(TopicDAO topicDAO, ExamDAO examDAO,
                              TimetableDAO timetableDAO, FreeSlotService freeSlotService) {
        this(topicDAO, examDAO, timetableDAO, freeSlotService, new ExamScopeDAO(), new ScoringService(),
             new ExamWindowService(), null);
    }

    public FeasibilityService(TopicDAO topicDAO, ExamDAO examDAO,
                              TimetableDAO timetableDAO, FreeSlotService freeSlotService,
                              ExamScopeDAO examScopeDAO, ScoringService scoringService) {
        this(topicDAO, examDAO, timetableDAO, freeSlotService, examScopeDAO, scoringService,
             new ExamWindowService(), null);
    }

    public FeasibilityService(TopicDAO topicDAO, ExamDAO examDAO,
                              TimetableDAO timetableDAO, FreeSlotService freeSlotService,
                              ExamScopeDAO examScopeDAO, ScoringService scoringService,
                              ExamWindowService examWindowService,
                              ExamPeriodFreeSlotService examPeriodFreeSlotService) {
        this.topicDAO = topicDAO;
        this.examDAO = examDAO;
        this.timetableDAO = timetableDAO;
        this.freeSlotService = freeSlotService;
        this.examScopeDAO = examScopeDAO != null ? examScopeDAO : new ExamScopeDAO();
        this.scoringService = scoringService != null ? scoringService : new ScoringService();
        this.examWindowService = examWindowService != null ? examWindowService : new ExamWindowService();
        this.examPeriodFreeSlotService = examPeriodFreeSlotService;
    }

    public static class FeasibilityResult {
        private final double neededHours;
        private final double capacityHours;
        private final boolean feasible;

        public FeasibilityResult(double neededHours, double capacityHours, boolean feasible) {
            this.neededHours = neededHours;
            this.capacityHours = capacityHours;
            this.feasible = feasible;
        }

        public double getNeededHours() {
            return neededHours;
        }

        public double getCapacityHours() {
            return capacityHours;
        }

        public boolean isFeasible() {
            return feasible;
        }

        public double getRatio() {
            return capacityHours > 0 ? Math.min(neededHours / capacityHours, 1.0) : (neededHours > 0 ? 1.0 : 0.0);
        }
    }

    public FeasibilityResult calculateFeasibility(int semesterId, LocalDate today) throws SQLException {
        List<Exam> exams = examDAO.findBySemester(semesterId);
        List<Exam> upcomingExams = new ArrayList<>();

        for (Exam e : exams) {
            LocalDate deadline = scoringService.effectiveDeadline(e, today);
            if (deadline != null && !deadline.isBefore(today)) {
                upcomingExams.add(e);
            }
        }

        upcomingExams.sort(Comparator.comparing(e -> scoringService.effectiveDeadline(e, today)));

        List<Topic> allTopics = topicDAO.findBySemester(semesterId);
        Map<Integer, List<Topic>> topicsByExam = new HashMap<>();
        for (Topic t : allTopics) {
            topicsByExam.computeIfAbsent(t.getExamId(), k -> new ArrayList<>()).add(t);
        }

        double cumulativeNeeded = 0.0;
        double cumulativeCapacity = 0.0;
        boolean infeasible = false;
        LocalDate lastDateCounted = today;

        for (Exam exam : upcomingExams) {
            LocalDate deadline = scoringService.effectiveDeadline(exam, today);
            List<Topic> topics = topicsByExam.getOrDefault(exam.getExamId(), Collections.emptyList());

            for (Topic t : topics) {
                // Remaining learn
                if (t.getRemainingHours() > 0) {
                    cumulativeNeeded += t.getRemainingHours();
                }
                // Due revision
                if (scoringService.isRevisionDue(t, exam, today) && t.getRevisionRemainingHours() > 0) {
                    cumulativeNeeded += t.getRevisionRemainingHours();
                }
            }

            // PYQ only for exams with syllabus scope
            boolean hasScope = !topics.isEmpty() || examScopeDAO.countForExam(exam.getExamId()) > 0;
            if (hasScope && exam.getPyqRemainingHours() > 0) {
                cumulativeNeeded += exam.getPyqRemainingHours();
            }

            // Free study time capacity from lastDateCounted to deadline
            LocalDate d = lastDateCounted;
            while (!d.isAfter(deadline)) {
                List<FreeSlot> slots;
                // Use ExamPeriodFreeSlotService inside dated exam windows
                boolean inWindow = examWindowService.isInDatedWindow(semesterId, d);
                if (inWindow && examPeriodFreeSlotService != null) {
                    slots = examPeriodFreeSlotService.computeFreeSlots(semesterId, d);
                } else {
                    slots = freeSlotService.computeFreeSlots(semesterId, d);
                }
                
                double dayFree = 0.0;
                for (FreeSlot s : slots) {
                    dayFree += s.getDuration();
                }
                double pref = timetableDAO.getStudyHours(semesterId, d.getDayOfWeek().name());
                double dayCap = (pref > 0) ? Math.min(dayFree, pref) : dayFree;
                cumulativeCapacity += dayCap;
                d = d.plusDays(1);
            }
            if (!deadline.isBefore(lastDateCounted)) {
                lastDateCounted = deadline.plusDays(1);
            }

            if (cumulativeNeeded > cumulativeCapacity) {
                infeasible = true;
            }
        }

        return new FeasibilityResult(cumulativeNeeded, cumulativeCapacity, !infeasible);
    }
}
