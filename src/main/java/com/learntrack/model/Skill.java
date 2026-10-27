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
}
