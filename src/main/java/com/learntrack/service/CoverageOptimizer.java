package com.learntrack.service;

import com.learntrack.model.Exam;
import com.learntrack.model.Topic;

import java.time.LocalDate;
import java.util.*;

/**
 * Decides WHICH exam topics to study before each exam, and in what order, so the most valuable
 * syllabus is covered in the time that is actually free.
 *
 * <h3>Model</h3>
 * Every exam is worth {@code examWeight = credits × marks}. That worth is shared between the
 * exam's in-scope topics equally, so the plan maximises how much of each syllabus gets learned
 * (weighted by what each exam is worth). A topic's cost is the learning hours it still needs plus one
 * revision cycle. Each exam also reserves time it will need anyway: one revision of the topics
 * that are already learned, and the remaining PYQ practice.
 *
 * <h3>Selection: deadline-constrained knapsack, solved greedily</h3>
 * Topics are considered in order of value per hour (best first). A topic is accepted only if the
 * plan stays feasible for every deadline: for each exam date D, the hours of everything accepted
 * whose exam falls on or before D must fit inside the free study hours available before D.
 * Because the day-by-day planner serves topics earliest-deadline-first, a set that passes this
 * test can actually be scheduled. When everything fits, everything is planned; when it does not,
 * the longest topics of the least valuable exams are the ones left out, so the most topics fit.
 *
 * <h3>Order</h3>
 * Inside an exam, planned topics are learned in syllabus order (importance only matters for
 * revision). Topics that do not fit come after every planned topic
 * of the same exam, so they never hold the important ones back.
 */
public class CoverageOptimizer {

    /** Order inside one exam. */
    public enum OrderMode { IMPORTANCE, SYLLABUS }

    /** Rank offset that pushes unplanned topics behind every planned one of the same exam. */
    static final long UNPLANNED_RANK_OFFSET = 1_000_000L;

    private final ScoringService scoringService;

    public CoverageOptimizer(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    /**
     * Non-linear importance weight: a must-know topic is worth about twelve optional ones, so the
     * optimizer drops several minor topics before it drops one critical topic.
     */
    public static double importanceWeight(int importance) {
        switch (Math.max(1, Math.min(5, importance))) {
            case 1: return 1.0;
            case 2: return 2.5;
            case 4: return 8.0;
            case 5: return 12.0;
            default: return 5.0;
        }
    }

    /**
     * Runs the selection and writes the outcome onto the given (simulated) topics:
     * {@link Topic#setPlanned(boolean)} and a plan rank in {@link Topic#setSyllabusOrder(long)}.
     *
     * @param topics        simulated topic copies; syllabusOrder must hold the real syllabus order
     * @param exams         simulated exams
     * @param dailyCapacity free study hours per date from {@code fromDate} onward
     * @param fromDate      first day being planned
     * @param capacityFactor share of free time assumed usable (0..1], leaves room for slippage
     */
    public CoveragePlan optimize(List<Topic> topics, List<Exam> exams, Map<LocalDate, Double> dailyCapacity,
                                 LocalDate fromDate, double capacityFactor, OrderMode orderMode) {
        double factor = capacityFactor <= 0 || capacityFactor > 1 ? 1.0 : capacityFactor;

        Map<Integer, Exam> examById = new HashMap<>();
        List<Exam> openExams = new ArrayList<>();
        for (Exam e : exams) {
            if (e.getExamId() == null) continue;
            examById.put(e.getExamId(), e);
            if (e.isUnfinished(fromDate)) openExams.add(e);
        }
        openExams.sort(Comparator.comparing((Exam e) -> scoringService.effectiveDeadline(e, fromDate))
                .thenComparing(Exam::getExamId));

        // Capacity strictly before each exam's date (the exam day itself goes to the sitting).
        Map<Integer, Double> capacityBefore = new HashMap<>();
        for (Exam e : openExams) {
            LocalDate deadline = scoringService.effectiveDeadline(e, fromDate);
            double sum = 0.0;
            for (Map.Entry<LocalDate, Double> day : dailyCapacity.entrySet()) {
                if (day.getKey().isBefore(deadline)) sum += day.getValue();
            }
            // An exam today still gets today's remaining hours.
            if (!deadline.isAfter(fromDate)) sum = dailyCapacity.getOrDefault(fromDate, 0.0);
            capacityBefore.put(e.getExamId(), sum * factor);
        }

        // Per exam: number of topics in scope (learned or not). Every topic counts the same while
        // learning, so the plan covers as much of the syllabus as possible; importance only steers revision.
        Map<Integer, Double> weightSum = new HashMap<>();
        for (Topic t : topics) {
            if (t.getImportantFlag() != 1) continue;
            weightSum.merge(t.getExamId(), 1.0, Double::sum);
        }

        CoveragePlan plan = new CoveragePlan(fromDate);
        Map<Integer, CoveragePlan.ExamCoverage> coverageByExam = new LinkedHashMap<>();
        Map<Integer, Double> load = new HashMap<>();
        for (Exam e : openExams) {
            CoveragePlan.ExamCoverage cov = new CoveragePlan.ExamCoverage(e,
                    scoringService.effectiveDeadline(e, fromDate), capacityBefore.get(e.getExamId()));
            coverageByExam.put(e.getExamId(), cov);
            // Reserved: PYQ practice still to do.
            double reserved = Math.max(0.0, e.getPyqRemainingHours());
            cov.reservedHours += reserved;
            load.put(e.getExamId(), reserved);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Topic t : topics) {
            CoveragePlan.ExamCoverage cov = coverageByExam.get(t.getExamId());
            if (cov == null || t.getImportantFlag() != 1) {
                continue; // finished exam, or excluded during an MST/EST negotiation
            }
            Exam e = examById.get(t.getExamId());
            double examValue = Math.max(scoringService.examWeight(e), 1.0);
            double value = examValue
                    / Math.max(weightSum.getOrDefault(t.getExamId(), 1.0), 1e-9);
            cov.totalValue += value;
            cov.totalTopics++;

            boolean learning = "LEARNING".equalsIgnoreCase(t.getPhase()) && t.getRemainingHours() > 0.05;
            if (!learning) {
                // Already learned: its value is secured; keep one revision in reserve.
                cov.securedValue += value;
                cov.learnedTopics++;
                double rev = Math.max(0.0, t.getRevisionHoursNeeded());
                cov.reservedHours += rev;
                load.merge(t.getExamId(), rev, Double::sum);
                continue;
            }
            double cost = t.getRemainingHours() + Math.max(0.0, t.getRevisionHoursNeeded());
            cov.neededHours += cost;
            candidates.add(new Candidate(t, value, cost));
        }

        // Reserves that already overflow a deadline must not veto every later topic: trim them so
        // the starting point is feasible, and let only the topics compete for what is left.
        double cumulative = 0.0;
        for (Exam e : openExams) {
            double cap = capacityBefore.getOrDefault(e.getExamId(), 0.0);
            double reserved = load.getOrDefault(e.getExamId(), 0.0);
            double allowed = Math.max(0.0, Math.min(reserved, cap - cumulative));
            load.put(e.getExamId(), allowed);
            cumulative += allowed;
        }

        // Value per hour, best first (shorter topics first when time is short); ties keep syllabus order.
        candidates.sort(Comparator.comparingDouble((Candidate c) -> -c.value / Math.max(c.cost, 0.25))
                .thenComparingLong(c -> c.topic.getSyllabusOrder())
                .thenComparing(c -> c.topic.getTopicId()));

        for (Candidate c : candidates) {
            int examId = c.topic.getExamId();
            load.merge(examId, c.cost, Double::sum);
            if (fits(openExams, load, capacityBefore)) {
                c.planned = true;
                CoveragePlan.ExamCoverage cov = coverageByExam.get(examId);
                cov.plannedValue += c.value;
                cov.plannedHours += c.cost;
                cov.plannedTopics++;
            } else {
                load.merge(examId, -c.cost, Double::sum);
            }
        }

        // Write the decision and the study order back onto the simulated topics.
        Map<Integer, List<Candidate>> byExam = new HashMap<>();
        for (Candidate c : candidates) {
            byExam.computeIfAbsent(c.topic.getExamId(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<Integer, List<Candidate>> entry : byExam.entrySet()) {
            List<Candidate> list = entry.getValue();
            Comparator<Candidate> order = orderMode == OrderMode.SYLLABUS
                    ? Comparator.comparingLong((Candidate c) -> c.topic.getSyllabusOrder())
                    : Comparator.comparingInt((Candidate c) -> -c.topic.getImportance())
                            .thenComparingLong(c -> c.topic.getSyllabusOrder());
            // A topic that is already half done keeps its place at the front (continuity).
            Comparator<Candidate> full = Comparator.comparing((Candidate c) -> !c.planned)
                    .thenComparing(c -> !isStarted(c.topic))
                    .thenComparing(order)
                    .thenComparing(c -> c.topic.getTopicId());
            list.sort(full);
            long rank = 0;
            for (Candidate c : list) {
                c.topic.setPlanned(c.planned);
                c.topic.setSyllabusOrder(c.planned ? rank : UNPLANNED_RANK_OFFSET + rank);
                rank++;
                if (!c.planned) {
                    coverageByExam.get(c.topic.getExamId()).atRisk.add(new CoveragePlan.AtRiskTopic(
                            c.topic.getTopicId(), c.topic.getName(), c.topic.getImportance(), c.cost));
                }
            }
        }

        for (CoveragePlan.ExamCoverage cov : coverageByExam.values()) {
            cov.atRisk.sort(Comparator.comparingInt((CoveragePlan.AtRiskTopic a) -> -a.importance)
                    .thenComparing(a -> a.name == null ? "" : a.name));
            plan.exams.add(cov);
        }
        return plan;
    }

    private static boolean isStarted(Topic t) {
        return t.getRemainingHours() < t.getHoursNeeded() - 0.001 && t.getRemainingHours() > 0.001;
    }

    /** Nested deadline check: work due by each date must fit in the time before that date. */
    private static boolean fits(List<Exam> examsByDeadline, Map<Integer, Double> load,
                                Map<Integer, Double> capacityBefore) {
        double cumulative = 0.0;
        for (Exam e : examsByDeadline) {
            cumulative += load.getOrDefault(e.getExamId(), 0.0);
            if (cumulative > capacityBefore.getOrDefault(e.getExamId(), 0.0) + 1e-6) {
                return false;
            }
        }
        return true;
    }

    private static final class Candidate {
        final Topic topic;
        final double value;
        final double cost;
        boolean planned;

        Candidate(Topic topic, double value, double cost) {
            this.topic = topic;
            this.value = value;
            this.cost = cost;
        }
    }
}
