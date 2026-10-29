package com.learntrack.service;

import com.learntrack.model.Exam;
import com.learntrack.model.Skill;
import com.learntrack.model.Topic;
import com.learntrack.util.DateUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * All scoring and weighting formulas.
 * Plan Section 5, 5a, 8.
 */
public class ScoringService {

    /**
     * Single unified examWeight formula used everywhere in the application.
     * examWeight(E) = credits(E) * max(eval_marks(E), 1)
     * Plan Section 5a & 8.
     */
    public double examWeight(Exam exam) {
        if (exam == null) {
            return 1.0;
        }
        double credits = exam.getCredits() <= 0.0 ? 1.0 : exam.getCredits();
        double marks = Math.max(exam.getEvalMarks(), 1.0);
        return credits * marks;
    }

    /**
     * Determines effective deadline via the fallback chain.
     * Plan Section 5.
     */
    public LocalDate effectiveDeadline(Exam exam, LocalDate today) {
        if (exam == null) {
            return today;
        }
        return exam.getEffectiveDeadline(today);
    }

    /**
     * Days left to deadline, floored at 1.
     * Plan Section 5 & 16.
     */
    public long daysLeft(Exam exam, LocalDate today) {
        LocalDate deadline = effectiveDeadline(exam, today);
        return Math.max(DateUtils.daysBetween(today, deadline), 1);
    }

    /**
     * debtScore = (importance * remaining_hours) / max(daysLeft, 1)
     * Note: Difficulty dropped/replaced with neutral factor 1.0.
     */
    public double debtScore(Topic topic, Exam exam, LocalDate today) {
        if (topic == null || topic.getRemainingHours() <= 0) {
            return 0.0;
        }
        long dl = daysLeft(exam, today);
        return (topic.getImportance() * topic.getRemainingHours()) / (double) dl;
    }

    /**
     * revisionScore = (importance * daysSinceRevision * revision_remaining_hours) / revision_interval_days
     * Plan Section 5.
     */
    public double revisionScore(Topic topic, LocalDate today) {
        if (topic == null || topic.getRevisionRemainingHours() <= 0) {
            return 0.0;
        }
        long daysSince = topic.getLastRevised() != null
                ? Math.max(0, DateUtils.daysBetween(topic.getLastRevised(), today))
                : topic.getRevisionIntervalDays();
        int interval = Math.max(topic.getRevisionIntervalDays(), 1);
        return (topic.getImportance() * daysSince * topic.getRevisionRemainingHours()) / (double) interval;
    }

    /**
     * A topic's revisionScore only enters the queue once daysSinceRevision >= revision_interval_days,
     * and only while the exam is still unfinished.
     * Plan Section 5.
     */
    public boolean isRevisionDue(Topic topic, Exam exam, LocalDate today) {
        if (topic == null || !"REVISION".equalsIgnoreCase(topic.getPhase()) || topic.getRevisionRemainingHours() <= 0) {
            return false;
        }
        if (exam != null && !exam.isUnfinished(today)) {
            return false;
        }
        if (topic.getLastRevised() == null) {
            return true;
        }
        long daysSince = DateUtils.daysBetween(topic.getLastRevised(), today);
        return daysSince >= topic.getRevisionIntervalDays();
    }

    /**
     * pyqEligible(E) = there is at least one topic with important_flag == 1
     *                  AND every such topic has remaining_hours == 0
     *                  AND no REVISION topic with important_flag == 1 has revisionRemainingHours > 0 and is due
     *                  AND E.pyq_remaining_hours > 0
     *                  AND today <= effectiveDeadline(E)
     * Plan Section 5a.
     * Returns false when topicsForExam is null or empty (no vacuous truth).
     */
    public boolean pyqEligible(Exam exam, List<Topic> topicsForExam, LocalDate today) {
        if (exam == null || exam.getPyqRemainingHours() <= 0) {
            return false;
        }
        // No topics at all → PYQ is not yet eligible (syllabus not loaded)
        if (topicsForExam == null || topicsForExam.isEmpty()) {
            return false;
        }
        LocalDate deadline = effectiveDeadline(exam, today);
        if (today.isAfter(deadline)) {
            return false;
        }
        boolean hasImportantTopic = false;
        for (Topic t : topicsForExam) {
            if (t.getImportantFlag() != 1 || !t.isPlanned()) { // topics that do not fit never block PYQ
                continue;
            }
            hasImportantTopic = true;
            if (t.getRemainingHours() > 0) {
                return false; // Important topic not yet learned
            }
            // If topic is in REVISION and revision is due and not complete, block PYQ
            if (isRevisionDue(t, exam, today) && t.getRevisionRemainingHours() > 0) {
                return false;
            }
        }
        // Must have at least one important topic that is fully learned
        return hasImportantTopic;
    }

    /**
     * pyqScore(E) = (examWeight(E) * E.pyq_remaining_hours) / max(daysLeft, 1)
     * Plan Section 5a.
     */
    public double pyqScore(Exam exam, LocalDate today) {
        if (exam == null || exam.getPyqRemainingHours() <= 0) {
            return 0.0;
        }
        long dl = daysLeft(exam, today);
        return (examWeight(exam) * exam.getPyqRemainingHours()) / (double) dl;
    }
}
