package com.learntrack.model;

import java.time.LocalDate;

/**
 * Represents a subtopic of a skill with learning, revision, and scheduling state.
 */
public class SkillSubtopic {

    private Integer subtopicId;
    private Integer skillId;
    private String name;
    private int position;
    private double learnHours = 1.0;
    private double learnRemainingHours = 1.0;
    private double reviseHours = 0.5;
    private double reviseRemainingHours = 0.5;
    private int reviseAfterDays = 7;
    private boolean learned = false;
    private LocalDate learnedOn;
    private boolean revised = false;
    private LocalDate revisedOn;
    private LocalDate revisionDueDate;

    public SkillSubtopic() {
    }

    public SkillSubtopic(Integer subtopicId, Integer skillId, String name, int position,
                         double learnHours, double learnRemainingHours,
                         double reviseHours, double reviseRemainingHours,
                         int reviseAfterDays, boolean learned, LocalDate learnedOn,
                         boolean revised, LocalDate revisedOn, LocalDate revisionDueDate) {
        this.subtopicId = subtopicId;
        this.skillId = skillId;
        this.name = name;
        this.position = position;
        this.learnHours = learnHours;
        this.learnRemainingHours = learnRemainingHours;
        this.reviseHours = reviseHours;
        this.reviseRemainingHours = reviseRemainingHours;
        this.reviseAfterDays = reviseAfterDays;
        this.learned = learned;
        this.learnedOn = learnedOn;
        this.revised = revised;
        this.revisedOn = revisedOn;
        this.revisionDueDate = revisionDueDate;
    }

    public SkillSubtopic(Integer skillId, String name, int position,
                         double learnHours, double reviseHours, int reviseAfterDays) {
        this.skillId = skillId;
        this.name = name;
        this.position = position;
        this.learnHours = learnHours;
        this.learnRemainingHours = learnHours;
        this.reviseHours = reviseHours;
        this.reviseRemainingHours = reviseHours;
        this.reviseAfterDays = reviseAfterDays;
    }

    public void markLearned(LocalDate date) {
        this.learned = true;
        this.learnedOn = date;
        this.learnRemainingHours = 0.0;
        this.revisionDueDate = date != null ? date.plusDays(reviseAfterDays) : null;
    }

    public void unmarkLearned() {
        this.learned = false;
        this.learnedOn = null;
        this.revisionDueDate = null;
        this.learnRemainingHours = this.learnHours;
        this.revised = false;
        this.revisedOn = null;
        this.reviseRemainingHours = this.reviseHours;
    }

    public void markRevised(LocalDate date) {
        this.revised = true;
        this.revisedOn = date;
        this.reviseRemainingHours = 0.0;
    }

    public void unmarkRevised() {
        this.revised = false;
        this.revisedOn = null;
        this.reviseRemainingHours = this.reviseHours;
    }

    public boolean isCovered() {
        return learned && revised;
    }

    public Integer getSubtopicId() {
        return subtopicId;
    }

    public void setSubtopicId(Integer subtopicId) {
        this.subtopicId = subtopicId;
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

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public double getLearnHours() {
        return learnHours;
    }

    public void setLearnHours(double learnHours) {
        this.learnHours = learnHours;
    }

    public double getLearnRemainingHours() {
        return learnRemainingHours;
    }

    public void setLearnRemainingHours(double learnRemainingHours) {
        this.learnRemainingHours = learnRemainingHours;
    }

    public double getReviseHours() {
        return reviseHours;
    }

    public void setReviseHours(double reviseHours) {
        this.reviseHours = reviseHours;
    }

    public double getReviseRemainingHours() {
        return reviseRemainingHours;
    }

    public void setReviseRemainingHours(double reviseRemainingHours) {
        this.reviseRemainingHours = reviseRemainingHours;
    }

    public int getReviseAfterDays() {
        return reviseAfterDays;
    }

    public void setReviseAfterDays(int reviseAfterDays) {
        this.reviseAfterDays = reviseAfterDays;
    }

    public boolean isLearned() {
        return learned;
    }

    public void setLearned(boolean learned) {
        this.learned = learned;
    }

    public LocalDate getLearnedOn() {
        return learnedOn;
    }

    public void setLearnedOn(LocalDate learnedOn) {
        this.learnedOn = learnedOn;
    }

    public boolean isRevised() {
        return revised;
    }

    public void setRevised(boolean revised) {
        this.revised = revised;
    }

    public LocalDate getRevisedOn() {
        return revisedOn;
    }

    public void setRevisedOn(LocalDate revisedOn) {
        this.revisedOn = revisedOn;
    }

    public LocalDate getRevisionDueDate() {
        return revisionDueDate;
    }

    public void setRevisionDueDate(LocalDate revisionDueDate) {
        this.revisionDueDate = revisionDueDate;
    }
}
