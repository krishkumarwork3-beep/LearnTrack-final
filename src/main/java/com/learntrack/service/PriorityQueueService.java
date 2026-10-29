package com.learntrack.service;

import com.learntrack.model.Exam;
import com.learntrack.model.PriorityItem;
import com.learntrack.model.Skill;
import com.learntrack.model.Topic;

import java.time.LocalDate;
import java.util.*;

/**
 * Builds the unified priority queue of topics, skills, and PYQ items.
 * Plan Section 5 & 5a.
 */
public class PriorityQueueService {

    private final ScoringService scoringService;

    public PriorityQueueService(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    public PriorityQueue<PriorityItem> build(List<Topic> topics, List<Skill> skills,
                                            List<Exam> exams, LocalDate today,
                                            double skillSessionLength) {
        return build(topics, skills, null, exams, today, skillSessionLength);
    }

    public PriorityQueue<PriorityItem> build(List<Topic> topics, List<Skill> skills,
                                            List<com.learntrack.model.SkillSubtopic> subtopics,
                                            List<Exam> exams, LocalDate today,
                                            double skillSessionLength) {
        PriorityQueue<PriorityItem> queue = new PriorityQueue<>(PriorityItem.TIER_COMPARATOR);

        // Map exams by ID for fast lookup
        Map<Integer, Exam> examMap = new HashMap<>();
        if (exams != null) {
            for (Exam e : exams) {
                if (e.getExamId() != null) {
                    examMap.put(e.getExamId(), e);
                }
            }
        }

        // Map skills by ID
        Map<Integer, Skill> skillMap = new HashMap<>();
        if (skills != null) {
            for (Skill s : skills) {
                if (s.getSkillId() != null) {
                    skillMap.put(s.getSkillId(), s);
                }
            }
        }

        // Group topics by exam
        Map<Integer, List<Topic>> topicsByExam = new HashMap<>();
        if (topics != null) {
            for (Topic t : topics) {
                topicsByExam.computeIfAbsent(t.getExamId(), k -> new ArrayList<>()).add(t);
            }
        }

        // 1. Topics: Filter out important_flag == 0 BEFORE scoring (Plan Section 5 & Step 5)
        if (topics != null) {
            for (Topic topic : topics) {
                if (topic.getImportantFlag() == 0) {
                    continue; // Skipped entirely, receives no score and no time
                }
                Exam exam = examMap.get(topic.getExamId());
                if (exam != null && !exam.isUnfinished(today)) {
                    continue; // Skip topics of already-finished exams (Plan §5)
                }

                long dl = exam != null ? scoringService.daysLeft(exam, today) : Long.MAX_VALUE;

                if ("LEARNING".equalsIgnoreCase(topic.getPhase())) {
                    if (topic.getRemainingHours() > 0) {
                        boolean inProg = (topic.getRemainingHours() < topic.getHoursNeeded() - 0.001)
                                && (topic.getRemainingHours() > 0.001);
                        double score = (topic.getImportance() * Math.max(topic.getHoursNeeded(), 1.0)) / (double) Math.max(dl, 1);
                        PriorityItem learnItem = new PriorityItem(
                                PriorityItem.ItemType.TOPIC_LEARN,
                                topic.getTopicId(),
                                topic.getName(),
                                score,
                                topic.getRemainingHours(),
                                topic.getExamId(),
                                inProg,
                                dl,
                                topic.getSyllabusOrder(),
                                topic.getImportance(),
                                topic.getHoursNeeded()
                        );
                        learnItem.setPlanned(topic.isPlanned());
                        queue.offer(learnItem);
                    }
                } else if ("REVISION".equalsIgnoreCase(topic.getPhase())) {
                    if (topic.getRevisionRemainingHours() > 0 && scoringService.isRevisionDue(topic, exam, today)) {
                        boolean inProg = (topic.getRevisionRemainingHours() < topic.getRevisionHoursNeeded() - 0.001)
                                && (topic.getRevisionRemainingHours() > 0.001);
                        double score = scoringService.revisionScore(topic, today);
                        queue.offer(new PriorityItem(
                                PriorityItem.ItemType.TOPIC_REVISE,
                                topic.getTopicId(),
                                topic.getName() + " (Revise)",
                                score,
                                topic.getRevisionRemainingHours(),
                                topic.getExamId(),
                                inProg,
                                dl,
                                topic.getSyllabusOrder(),
                                topic.getImportance(),
                                topic.getRevisionHoursNeeded()
                        ));
                    }
                }
            }
        }

        // 2. Skills / Subtopics: scored by the skill's priority, raised when a target date makes the
        // remaining learning urgent, and for revisions that are overdue (spaced repetition slipping).
        Map<Integer, Double> skillLearnRemaining = new HashMap<>();
        if (subtopics != null) {
            for (com.learntrack.model.SkillSubtopic sub : subtopics) {
                if (!sub.isLearned()) {
                    skillLearnRemaining.merge(sub.getSkillId(), Math.max(0.0, sub.getLearnRemainingHours()), Double::sum);
                }
            }
        }
        Set<Integer> skillsWithSubtopics = new HashSet<>();
        if (subtopics != null) {
            for (com.learntrack.model.SkillSubtopic sub : subtopics) {
                skillsWithSubtopics.add(sub.getSkillId());
                Skill skill = skillMap.get(sub.getSkillId());
                String skillName = (skill != null && skill.getName() != null) ? skill.getName() : "Skill";
                double learnScore = skillScore(skill, skillLearnRemaining.getOrDefault(sub.getSkillId(), 0.0), today);

                // (a) LEARN item: available immediately if not learned and hours remain
                if (!sub.isLearned() && sub.getLearnRemainingHours() > 0.001) {
                    boolean inProg = (sub.getLearnRemainingHours() < sub.getLearnHours() - 0.001)
                            && (sub.getLearnRemainingHours() > 0.001);
                    queue.offer(new PriorityItem(
                            PriorityItem.ItemType.SKILL_LEARN,
                            sub.getSubtopicId(),
                            "Skill: " + skillName + " › " + sub.getName() + " (Learn)",
                            learnScore,
                            sub.getLearnRemainingHours(),
                            sub.getSkillId(),
                            inProg,
                            Long.MAX_VALUE,
                            sub.getPosition(),
                            1,
                            sub.getLearnHours()
                    ));
                }
                // (b) REVISE item: available only on/after revision_due_date and only if LEARN is done
                else if (sub.isLearned() && !sub.isRevised() && sub.getReviseRemainingHours() > 0.001) {
                    LocalDate dueDate = sub.getRevisionDueDate();
                    if (dueDate == null || !today.isBefore(dueDate)) {
                        boolean inProg = (sub.getReviseRemainingHours() < sub.getReviseHours() - 0.001)
                                && (sub.getReviseRemainingHours() > 0.001);
                        queue.offer(new PriorityItem(
                                PriorityItem.ItemType.SKILL_REVISE,
                                sub.getSubtopicId(),
                                "Skill: " + skillName + " › " + sub.getName() + " (Revise)",
                                reviseScore(skill, dueDate, today),
                                sub.getReviseRemainingHours(),
                                sub.getSkillId(),
                                inProg,
                                Long.MAX_VALUE,
                                sub.getPosition(),
                                1,
                                sub.getReviseHours()
                        ));
                    }
                }
            }
        }
        if (skills != null) {
            // Legacy skill fallback for skills with no subtopics defined
            for (Skill skill : skills) {
                if (skillsWithSubtopics.contains(skill.getSkillId())) {
                    continue;
                }
                double score = skillScore(skill, 0.0, today);
                queue.offer(new PriorityItem(
                        PriorityItem.ItemType.SKILL,
                        skill.getSkillId(),
                        "Skill: " + skill.getName(),
                        score,
                        skillSessionLength,
                        skill.getSkillId(),
                        false,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        1,
                        skillSessionLength
                ));
            }
        }

        // 3. PYQ items: Eligible only if all important topics covered and exam deadline not passed
        if (exams != null) {
            for (Exam exam : exams) {
                if (!exam.isUnfinished(today)) {
                    continue; // Skip PYQ of already-finished exams
                }
                List<Topic> examTopics = topicsByExam.getOrDefault(exam.getExamId(), Collections.emptyList());
                if (scoringService.pyqEligible(exam, examTopics, today)) {
                    double score = scoringService.pyqScore(exam, today);
                    long dl = scoringService.daysLeft(exam, today);
                    queue.offer(new PriorityItem(
                            PriorityItem.ItemType.PYQ_PRACTICE,
                            exam.getExamId(),
                            "PYQ: " + exam.getSubject(),
                            score,
                            exam.getPyqRemainingHours(),
                            exam.getExamId(),
                            false,
                            dl,
                            Long.MAX_VALUE,
                            1,
                            exam.getPyqRemainingHours()
                    ));
                }
            }
        }

        return queue;
    }

    /**
     * Skill learning score: priority weight (1..5 mapped like topic importance), multiplied by how
     * many hours a day the remaining learning needs to hit the skill's target date. Open-ended skills
     * keep their plain priority weight, so a "Top focus" skill always leads a "Someday" one.
     */
    static double skillScore(Skill skill, double learnHoursRemaining, LocalDate today) {
        int priority = skill == null ? 3 : skill.getPriority();
        double score = CoverageOptimizer.importanceWeight(priority);
        LocalDate target = skill == null ? null : skill.getTargetDate();
        if (target != null && learnHoursRemaining > 0) {
            long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(today, target));
            double hoursPerDay = learnHoursRemaining / days;
            score *= 1.0 + Math.min(hoursPerDay, 4.0);
            if (today.isAfter(target)) {
                score *= 1.5; // overdue goal: catch up first
            }
        }
        return score;
    }

    /** Revision score: priority weight, raised by one step for every week the review is overdue. */
    static double reviseScore(Skill skill, LocalDate dueDate, LocalDate today) {
        double score = CoverageOptimizer.importanceWeight(skill == null ? 3 : skill.getPriority());
        if (dueDate != null && today.isAfter(dueDate)) {
            long overdue = java.time.temporal.ChronoUnit.DAYS.between(dueDate, today);
            score *= 1.0 + Math.min(overdue / 7.0, 3.0);
        }
        return score;
    }
}
