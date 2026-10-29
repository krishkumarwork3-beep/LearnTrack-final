package com.learntrack.model;

import java.time.LocalDate;

/**
 * Represents a skill tracked forever across semesters.
 * Plan Section 3 & 10.
 */
public class Skill {
    public static final LocalDate NEVER_PRACTICED = LocalDate.of(2000, 1, 1);

    private Integer skillId;
    private String name;
    private String category;
    private int proficiency;
    private LocalDate lastPracticed;
    private int priority = 3;          // 1 someday .. 5 top focus
    private LocalDate targetDate;      // optional goal date; null = open-ended

    public boolean isNeverPracticed() {
        return lastPracticed == null || !lastPracticed.isAfter(NEVER_PRACTICED);
    }

    public Skill() {
    }

    public Skill(Integer skillId, String name, String category, int proficiency, LocalDate lastPracticed) {
        this.skillId = skillId;
        this.name = name;
        this.category = category;
        this.proficiency = proficiency;
        this.lastPracticed = lastPracticed;
    }

    /**
     * Logging a session, of any length, updates last_practiced = today (Plan Section 10).
     */
    public void markPracticed(LocalDate today) {
        this.lastPracticed = today;
    }

    public Integer getSkillId() {
        return skillId;
    }

    public void setSkillId(Integer skillId) {
        this.skillId = skillId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getProficiency() {
        return proficiency;
    }

    public void setProficiency(int proficiency) {
        this.proficiency = proficiency;
    }

    public LocalDate getLastPracticed() {
        return lastPracticed;
    }

    public void setLastPracticed(LocalDate lastPracticed) {
        this.lastPracticed = lastPracticed;
    }

    public int getPriority() {
        return priority;
    }

    /** 0 (an unset column) reads as the neutral 3; everything else is clamped to 1..5. */
    public void setPriority(int priority) {
        this.priority = priority == 0 ? 3 : Math.max(1, Math.min(5, priority));
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    /** Short label for a priority level, shared by the skills screen and the planner. */
    public static String priorityLabel(int priority) {
        switch (Math.max(1, Math.min(5, priority))) {
            case 1: return "Someday";
            case 2: return "Low";
            case 4: return "High";
            case 5: return "Top focus";
            default: return "Normal";
        }
    }

}
