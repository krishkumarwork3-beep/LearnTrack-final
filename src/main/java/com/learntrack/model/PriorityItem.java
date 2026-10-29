package com.learntrack.model;

import java.util.Comparator;

/**
 * Queue wrapper for topic / skill / PYQ scheduling.
 * Plan Section 5.
 */
public class PriorityItem implements Comparable<PriorityItem> {

    public enum ItemType {
        TOPIC_LEARN,
        TOPIC_REVISE,
        SKILL,
        PYQ_PRACTICE,
        SKILL_LEARN,
        SKILL_REVISE
    }

    private ItemType itemType;
    private int id; // topicId / skillId / subtopicId / examId (for PYQ)
    private String name;
    private double score;
    private double remainingHours;
    private Integer examId; // examId for topics/PYQ, skillId for skill subtopics

    // Planner fields for continuity & syllabus ordering
    private boolean inProgress;
    private long daysLeft = Long.MAX_VALUE;
    private long syllabusOrder = Long.MAX_VALUE;
    private int importance = 1;
    private double hoursNeeded = 0.0;
    /** False for topics the coverage optimizer could not fit before their exam. */
    private boolean planned = true;

    public PriorityItem() {
    }

    public PriorityItem(ItemType itemType, int id, String name, double score,
                        double remainingHours, Integer examId) {
        this(itemType, id, name, score, remainingHours, examId, false, Long.MAX_VALUE, Long.MAX_VALUE, 1, remainingHours);
    }

    public PriorityItem(ItemType itemType, int id, String name, double score,
                        double remainingHours, Integer examId,
                        boolean inProgress, long daysLeft, long syllabusOrder,
                        int importance, double hoursNeeded) {
        this.itemType = itemType;
        this.id = id;
        this.name = name;
        this.score = score;
        this.remainingHours = remainingHours;
        this.examId = examId;
        this.inProgress = inProgress;
        this.daysLeft = daysLeft;
        this.syllabusOrder = syllabusOrder;
        this.importance = importance;
        this.hoursNeeded = hoursNeeded;
    }

    public boolean isSkill() {
        return itemType == ItemType.SKILL || itemType == ItemType.SKILL_LEARN || itemType == ItemType.SKILL_REVISE;
    }

    public boolean isTopic() {
        return itemType == ItemType.TOPIC_LEARN || itemType == ItemType.TOPIC_REVISE;
    }

    public boolean isPyq() {
        return itemType == ItemType.PYQ_PRACTICE;
    }

    /**
     * Study priority: learning new syllabus comes first, then skills, then revision.
     * Tier 0 = learn a topic that fits before its exam.
     * Tier 1 = learn a topic that does not fit (still worth marks if time frees up).
     * Tier 2 = skills.
     * Tier 3 = revise a topic, or practise previous-year papers.
     */
    public int tier() {
        if (itemType == ItemType.TOPIC_LEARN) return planned ? 0 : 1;
        if (isSkill()) return 2;
        return 3;
    }

    public boolean isPlanned() {
        return planned;
    }

    public void setPlanned(boolean planned) {
        this.planned = planned;
    }

    /**
     * Comparator enforcing:
     * 1. Tier ascending (Tier 0 topic/skill before Tier 1 PYQ).
     * 2. Continuity: In-progress topics (started, not finished) always first.
     * 3. For LEARN items: daysLeft asc (nearest exam first), syllabusOrder asc, importance desc, topicId.
     * 4. For REVISE / SKILL / mixed: score descending.
     */
    public static final Comparator<PriorityItem> TIER_COMPARATOR = (a, b) -> {
        if (a == b) return 0;
        if (a == null) return 1;
        if (b == null) return -1;

        // 1. Tier ascending
        int tierCmp = Integer.compare(a.tier(), b.tier());
        if (tierCmp != 0) return tierCmp;

        // 2. Continuity: In-progress items come FIRST
        boolean inProgA = a.isInProgress();
        boolean inProgB = b.isInProgress();
        if (inProgA != inProgB) {
            return inProgA ? -1 : 1;
        }

        // If both are in-progress:
        if (inProgA) {
            int dlCmp = Long.compare(a.getDaysLeft(), b.getDaysLeft());
            if (dlCmp != 0) return dlCmp;

            int sylCmp = Long.compare(a.getSyllabusOrder(), b.getSyllabusOrder());
            if (sylCmp != 0) return sylCmp;

            int impCmp = Integer.compare(b.getImportance(), a.getImportance());
            if (impCmp != 0) return impCmp;

            int idCmp = Integer.compare(a.getId(), b.getId());
            if (idCmp != 0) return idCmp;
        }

        // 3. Neither is in-progress (or tied in-progress):
        boolean learnA = a.getItemType() == ItemType.TOPIC_LEARN;
        boolean learnB = b.getItemType() == ItemType.TOPIC_LEARN;

        if (learnA && learnB) {
            // Both are LEARN:
            // Nearest exam first (daysLeft asc)
            int dlCmp = Long.compare(a.getDaysLeft(), b.getDaysLeft());
            if (dlCmp != 0) return dlCmp;

            // Inside exam / same daysLeft: syllabus order ascending
            int sylCmp = Long.compare(a.getSyllabusOrder(), b.getSyllabusOrder());
            if (sylCmp != 0) return sylCmp;

            // importance descending
            int impCmp = Integer.compare(b.getImportance(), a.getImportance());
            if (impCmp != 0) return impCmp;

            int idCmp = Integer.compare(a.getId(), b.getId());
            if (idCmp != 0) return idCmp;
        }

        // Both are SKILL_LEARN: position order within same skill
        boolean skillLearnA = a.getItemType() == ItemType.SKILL_LEARN;
        boolean skillLearnB = b.getItemType() == ItemType.SKILL_LEARN;
        if (skillLearnA && skillLearnB) {
            if (a.getExamId() != null && b.getExamId() != null && a.getExamId().equals(b.getExamId())) {
                int sylCmp = Long.compare(a.getSyllabusOrder(), b.getSyllabusOrder());
                if (sylCmp != 0) return sylCmp;
                int idCmp = Integer.compare(a.getId(), b.getId());
                if (idCmp != 0) return idCmp;
            }
        }

        // LEARN vs REVISE: earliest exam first, then new material before another revision pass
        // (one revision per planned topic is already reserved in the coverage budget).
        if (a.isTopic() && b.isTopic() && learnA != learnB) {
            int dlCmp = Long.compare(a.getDaysLeft(), b.getDaysLeft());
            if (dlCmp != 0) return dlCmp;
            return learnA ? -1 : 1;
        }

        // Both are REVISE, SKILL or PYQ:
        int scCmp = Double.compare(b.getScore(), a.getScore());
        if (scCmp != 0) return scCmp;

        // Tie-break: syllabus order asc, then id asc
        int sylCmp = Long.compare(a.getSyllabusOrder(), b.getSyllabusOrder());
        if (sylCmp != 0) return sylCmp;

        return Integer.compare(a.getId(), b.getId());
    };

    public ItemType getItemType() {
        return itemType;
    }

    public void setItemType(ItemType itemType) {
        this.itemType = itemType;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getRemainingHours() {
        return remainingHours;
    }

    public void setRemainingHours(double remainingHours) {
        this.remainingHours = remainingHours;
    }

    public Integer getExamId() {
        return examId;
    }

    public void setExamId(Integer examId) {
        this.examId = examId;
    }

    public boolean isInProgress() {
        return inProgress;
    }

    public void setInProgress(boolean inProgress) {
        this.inProgress = inProgress;
    }

    public long getDaysLeft() {
        return daysLeft;
    }

    public void setDaysLeft(long daysLeft) {
        this.daysLeft = daysLeft;
    }

    public long getSyllabusOrder() {
        return syllabusOrder;
    }

    public void setSyllabusOrder(long syllabusOrder) {
        this.syllabusOrder = syllabusOrder;
    }

    public int getImportance() {
        return importance;
    }

    public void setImportance(int importance) {
        this.importance = importance;
    }

    public double getHoursNeeded() {
        return hoursNeeded;
    }

    public void setHoursNeeded(double hoursNeeded) {
        this.hoursNeeded = hoursNeeded;
    }

    @Override
    public int compareTo(PriorityItem other) {
        return TIER_COMPARATOR.compare(this, other);
    }

    @Override
    public String toString() {
        return "[" + itemType + " id=" + id + " '" + name + "' score=" + score + " rem=" + remainingHours + "h]";
    }
}
