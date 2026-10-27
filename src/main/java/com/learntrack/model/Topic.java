package com.learntrack.model;

import java.time.LocalDate;

/**
 * Represents a study topic under an exam, with phase transitions.
 * Plan Section 3 & 10.
 */
public class Topic {
    private Integer topicId;
    private int examId;
    private String name;
    private int importance;
    private int difficulty;
    private double hoursNeeded;
    private double remainingHours;
    private String phase = "LEARNING"; // 'LEARNING' | 'REVISION'
    private double revisionHoursNeeded = 0.0;
    private double revisionRemainingHours = 0.0;
    private int revisionIntervalDays = 7;
    private LocalDate lastRevised;
    private int importantFlag = 1;
    private Integer subtopicId; // links to syllabus subtopic; NULL for manual topics
    private long syllabusOrder = Long.MAX_VALUE; // transient position in syllabus: unitPosition * 100000 + subtopicPosition

    public Topic() {
    }

    public Topic(Integer topicId, int examId, String name, int importance,
                 int difficulty, double hoursNeeded, double remainingHours,
                 String phase, double revisionHoursNeeded, double revisionRemainingHours,
                 int revisionIntervalDays, LocalDate lastRevised, int importantFlag) {
        this(topicId, examId, name, importance, difficulty, hoursNeeded, remainingHours,
             phase, revisionHoursNeeded, revisionRemainingHours, revisionIntervalDays, 
             lastRevised, importantFlag, null);
    }

    public Topic(Integer topicId, int examId, String name, int importance,
                 int difficulty, double hoursNeeded, double remainingHours,
                 String phase, double revisionHoursNeeded, double revisionRemainingHours,
                 int revisionIntervalDays, LocalDate lastRevised, int importantFlag, Integer subtopicId) {
        this.topicId = topicId;
        this.examId = examId;
        this.name = name;
        this.importance = importance;
        this.difficulty = difficulty;
        this.hoursNeeded = hoursNeeded;
        this.remainingHours = remainingHours;
        this.phase = phase != null ? phase : "LEARNING";
        this.revisionHoursNeeded = revisionHoursNeeded;
        this.revisionRemainingHours = revisionRemainingHours;
        this.revisionIntervalDays = revisionIntervalDays;
        this.lastRevised = lastRevised;
        this.importantFlag = importantFlag;
        this.subtopicId = subtopicId;
    }

    /**
     * Topic, LEARNING phase: Yes, done
     * remaining_hours = 0, phase = REVISION, revision_remaining_hours = revision_hours_needed, last_revised = today.
     * Plan Section 10.
     */
    public void markLearningDone(LocalDate today) {
        this.remainingHours = 0.0;
        this.phase = "REVISION";
        this.revisionRemainingHours = this.revisionHoursNeeded;
        this.lastRevised = today;
    }

    /**
     * Topic, LEARNING phase: Not yet
     * student enters a new estimate, overwriting remaining_hours.
     * Plan Section 10.
     */
    public void markLearningNotYet(double newRemainingHours) {
        this.remainingHours = Math.max(newRemainingHours, 0.0);
    }

    /**
     * Topic, REVISION phase: Yes, done
     * last_revised = today, revision_remaining_hours resets to revision_hours_needed.
     * Plan Section 10.
     */
    public void markRevisionDone(LocalDate today) {
        this.lastRevised = today;
        this.revisionRemainingHours = this.revisionHoursNeeded;
    }

    /**
     * Topic, REVISION phase: Not yet
     * student enters a new estimate, overwriting revision_remaining_hours; last_revised is NOT updated.
     * Plan Section 10.
     */
    public void markRevisionNotYet(double newRevisionRemainingHours) {
        this.revisionRemainingHours = Math.max(newRevisionRemainingHours, 0.0);
    }

    public Integer getTopicId() {
        return topicId;
    }

    public void setTopicId(Integer topicId) {
        this.topicId = topicId;
    }

    public int getExamId() {
        return examId;
    }

    public void setExamId(int examId) {
        this.examId = examId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getImportance() {
        return importance;
    }

    public void setImportance(int importance) {
        this.importance = importance;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(int difficulty) {
        this.difficulty = difficulty;
    }

    public double getHoursNeeded() {
        return hoursNeeded;
    }

    public void setHoursNeeded(double hoursNeeded) {
        this.hoursNeeded = hoursNeeded;
    }

    public double getRemainingHours() {
        return remainingHours;
    }

    public void setRemainingHours(double remainingHours) {
        this.remainingHours = remainingHours;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public double getRevisionHoursNeeded() {
        return revisionHoursNeeded;
    }

    public void setRevisionHoursNeeded(double revisionHoursNeeded) {
        this.revisionHoursNeeded = revisionHoursNeeded;
    }

    public double getRevisionRemainingHours() {
        return revisionRemainingHours;
    }

    public void setRevisionRemainingHours(double revisionRemainingHours) {
        this.revisionRemainingHours = revisionRemainingHours;
    }

    public int getRevisionIntervalDays() {
        return revisionIntervalDays;
    }

    public void setRevisionIntervalDays(int revisionIntervalDays) {
        this.revisionIntervalDays = revisionIntervalDays;
    }

    public LocalDate getLastRevised() {
        return lastRevised;
    }

    public void setLastRevised(LocalDate lastRevised) {
        this.lastRevised = lastRevised;
    }

    public int getImportantFlag() {
        return importantFlag;
    }

    public void setImportantFlag(int importantFlag) {
        this.importantFlag = importantFlag;
    }

    public Integer getSubtopicId() {
        return subtopicId;
    }

    public void setSubtopicId(Integer subtopicId) {
        this.subtopicId = subtopicId;
    }

    public long getSyllabusOrder() {
        return syllabusOrder;
    }

    public void setSyllabusOrder(long syllabusOrder) {
        this.syllabusOrder = syllabusOrder;
    }
}
