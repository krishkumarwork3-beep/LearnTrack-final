package com.learntrack.service;

import com.learntrack.model.Exam;
import com.learntrack.model.SyllabusSubtopic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of one {@link CoverageOptimizer} run: per exam, how much of the importance-weighted
 * syllabus is secured (already learned) or planned before the exam, and which topics do not fit.
 * Values are in "exam marks" units (credits × marks shared by importance), so percentages read as
 * "share of what this exam is worth".
 */
public class CoveragePlan {

    private final LocalDate fromDate;
    final List<ExamCoverage> exams = new ArrayList<>();

    CoveragePlan(LocalDate fromDate) {
        this.fromDate = fromDate;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public List<ExamCoverage> getExams() {
        return exams;
    }

    /** Value-weighted share of all open exams that is learned or planned (0..1). */
    public double overallCoverage() {
        double total = 0.0;
        double covered = 0.0;
        for (ExamCoverage e : exams) {
            total += e.totalValue;
            covered += e.securedValue + e.plannedValue;
        }
        return total <= 0 ? 1.0 : Math.min(1.0, covered / total);
    }

    /** Value-weighted share of all open exams that is already learned (0..1). */
    public double overallLearned() {
        double total = 0.0;
        double learned = 0.0;
        for (ExamCoverage e : exams) {
            total += e.totalValue;
            learned += e.securedValue;
        }
        return total <= 0 ? 0.0 : Math.min(1.0, learned / total);
    }

    public int atRiskCount() {
        int n = 0;
        for (ExamCoverage e : exams) n += e.atRisk.size();
        return n;
    }

    /** Topics rated High or Must-know that do not fit before their exam. */
    public int criticalAtRiskCount() {
        int n = 0;
        for (ExamCoverage e : exams) {
            for (AtRiskTopic t : e.atRisk) {
                if (t.importance >= 4) n++;
            }
        }
        return n;
    }

    public static final class ExamCoverage {
        private final Exam exam;
        private final LocalDate deadline;
        private final double capacityHours;
        double reservedHours;
        double neededHours;
        double plannedHours;
        double totalValue;
        double securedValue;
        double plannedValue;
        int totalTopics;
        int learnedTopics;
        int plannedTopics;
        final List<AtRiskTopic> atRisk = new ArrayList<>();

        ExamCoverage(Exam exam, LocalDate deadline, Double capacityHours) {
            this.exam = exam;
            this.deadline = deadline;
            this.capacityHours = capacityHours == null ? 0.0 : capacityHours;
        }

        public Exam getExam() { return exam; }
        public LocalDate getDeadline() { return deadline; }
        /** Usable free study hours before the exam (shared with earlier exams). */
        public double getCapacityHours() { return capacityHours; }
        public double getReservedHours() { return reservedHours; }
        /** Learning + one revision for every topic not yet learned. */
        public double getNeededHours() { return neededHours; }
        public double getPlannedHours() { return plannedHours; }
        public int getTotalTopics() { return totalTopics; }
        public int getLearnedTopics() { return learnedTopics; }
        public int getPlannedTopics() { return plannedTopics; }
        public List<AtRiskTopic> getAtRisk() { return atRisk; }

        /** Share of this exam's worth that is learned or planned (0..1). */
        public double coverage() {
            return totalValue <= 0 ? 1.0 : Math.min(1.0, (securedValue + plannedValue) / totalValue);
        }

        /** Share of this exam's worth already learned (0..1). */
        public double secured() {
            return totalValue <= 0 ? 0.0 : Math.min(1.0, securedValue / totalValue);
        }
    }

    public static final class AtRiskTopic {
        public final int topicId;
        public final String name;
        public final int importance;
        public final double hours;

        AtRiskTopic(int topicId, String name, int importance, double hours) {
            this.topicId = topicId;
            this.name = name;
            this.importance = importance;
            this.hours = hours;
        }

        public String importanceLabel() {
            return SyllabusSubtopic.importanceLabel(importance);
        }
    }
}
