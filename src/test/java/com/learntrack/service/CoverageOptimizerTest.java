package com.learntrack.service;

import com.learntrack.model.Exam;
import com.learntrack.model.Topic;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CoverageOptimizerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private final CoverageOptimizer optimizer = new CoverageOptimizer(new ScoringService());

    private static Exam exam(int id, LocalDate date, double credits, double marks) {
        return new Exam(id, 1, "S" + id, "NORMAL", credits, marks, date, null, null, 30, null, null, 0.0);
    }

    private static Topic topic(int id, int examId, int importance, double hours, long syllabusOrder) {
        Topic t = new Topic(id, examId, "T" + id, importance, 1, hours, hours, "LEARNING",
                0.0, 0.0, 7, null, 1, null);
        t.setSyllabusOrder(syllabusOrder);
        return t;
    }

    /** Same free hours on every day from TODAY until (exclusive) the given end. */
    private static Map<LocalDate, Double> capacity(double hoursPerDay, LocalDate endExclusive) {
        Map<LocalDate, Double> map = new LinkedHashMap<>();
        for (LocalDate d = TODAY; d.isBefore(endExclusive); d = d.plusDays(1)) {
            map.put(d, hoursPerDay);
        }
        return map;
    }

    @Test
    void everythingIsPlannedWhenTimeIsEnough() {
        Exam e = exam(1, TODAY.plusDays(10), 4, 50);
        List<Topic> topics = List.of(topic(1, 1, 3, 2, 1), topic(2, 1, 1, 2, 2), topic(3, 1, 5, 2, 3));

        CoveragePlan plan = optimizer.optimize(topics, List.of(e), capacity(2, TODAY.plusDays(10)), TODAY, 1.0,
                CoverageOptimizer.OrderMode.IMPORTANCE);

        assertTrue(topics.stream().allMatch(Topic::isPlanned));
        assertEquals(1.0, plan.overallCoverage(), 1e-9);
        assertEquals(0, plan.atRiskCount());
    }

    @Test
    void mustKnowTopicsWinWhenTimeIsShort() {
        Exam e = exam(1, TODAY.plusDays(3), 4, 50); // 3 days x 2h = 6h before the exam
        Topic optionalEarly = topic(1, 1, 1, 3, 1);
        Topic mustKnowLate = topic(2, 1, 5, 3, 2);
        Topic highLate = topic(3, 1, 4, 3, 3);

        CoveragePlan plan = optimizer.optimize(List.of(optionalEarly, mustKnowLate, highLate), List.of(e),
                capacity(2, TODAY.plusDays(3)), TODAY, 1.0, CoverageOptimizer.OrderMode.IMPORTANCE);

        assertTrue(mustKnowLate.isPlanned());
        assertTrue(highLate.isPlanned());
        assertFalse(optionalEarly.isPlanned(), "the optional topic is the one that does not fit");
        assertEquals(1, plan.atRiskCount());
        assertEquals(0, plan.criticalAtRiskCount());
        // Must-know is studied first even though it is later in the syllabus.
        assertTrue(mustKnowLate.getSyllabusOrder() < highLate.getSyllabusOrder());
        assertTrue(optionalEarly.getSyllabusOrder() >= CoverageOptimizer.UNPLANNED_RANK_OFFSET);
    }

    @Test
    void syllabusModeKeepsSyllabusOrderAmongPlannedTopics() {
        Exam e = exam(1, TODAY.plusDays(10), 4, 50);
        Topic a = topic(1, 1, 1, 1, 1);
        Topic b = topic(2, 1, 5, 1, 2);

        optimizer.optimize(List.of(a, b), List.of(e), capacity(2, TODAY.plusDays(10)), TODAY, 1.0,
                CoverageOptimizer.OrderMode.SYLLABUS);

        assertTrue(a.getSyllabusOrder() < b.getSyllabusOrder());
    }

    @Test
    void earlierExamCannotBorrowTimeFromAfterItsDate() {
        // 2h/day. Exam 1 in 2 days (4h before it), exam 2 in 10 days (20h before it, shared).
        Exam early = exam(1, TODAY.plusDays(2), 4, 50);
        Exam late = exam(2, TODAY.plusDays(10), 4, 50);
        Topic earlyBig = topic(1, 1, 5, 6, 1);   // cannot fit in 4h whatever its value
        Topic earlySmall = topic(2, 1, 3, 3, 2);
        Topic lateTopic = topic(3, 2, 3, 10, 1);

        optimizer.optimize(List.of(earlyBig, earlySmall, lateTopic), List.of(early, late),
                capacity(2, TODAY.plusDays(10)), TODAY, 1.0, CoverageOptimizer.OrderMode.IMPORTANCE);

        assertFalse(earlyBig.isPlanned());
        assertTrue(earlySmall.isPlanned());
        assertTrue(lateTopic.isPlanned());
    }

    @Test
    void valueFollowsExamWorthAcrossSubjects() {
        // 4h total; each exam has one 4h topic. The exam worth more marks wins the time.
        Exam small = exam(1, TODAY.plusDays(2), 2, 20);
        Exam big = exam(2, TODAY.plusDays(2), 4, 50);
        Topic smallTopic = topic(1, 1, 3, 4, 1);
        Topic bigTopic = topic(2, 2, 3, 4, 1);

        CoveragePlan plan = optimizer.optimize(List.of(smallTopic, bigTopic), List.of(small, big),
                capacity(2, TODAY.plusDays(2)), TODAY, 1.0, CoverageOptimizer.OrderMode.IMPORTANCE);

        assertTrue(bigTopic.isPlanned());
        assertFalse(smallTopic.isPlanned());
        assertTrue(plan.overallCoverage() > 0.5);
    }

    @Test
    void learnedTopicsCountAsSecuredCoverage() {
        Exam e = exam(1, TODAY.plusDays(1), 4, 50); // only 2h left
        Topic learned = new Topic(1, 1, "Done", 5, 1, 2, 0, "REVISION", 0.5, 0.5, 7, null, 1, null);
        Topic pending = topic(2, 1, 5, 5, 2);

        CoveragePlan plan = optimizer.optimize(List.of(learned, pending), List.of(e),
                capacity(2, TODAY.plusDays(1)), TODAY, 1.0, CoverageOptimizer.OrderMode.IMPORTANCE);

        CoveragePlan.ExamCoverage cov = plan.getExams().get(0);
        assertEquals(0.5, cov.secured(), 1e-9);
        assertFalse(pending.isPlanned());
        assertEquals(1, plan.criticalAtRiskCount());
    }
}
