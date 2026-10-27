package com.learntrack.model;

/**
 * Represents a subject in the active semester with metadata.
 * Used for building the unified subject list.
 */
public class SubjectInfo {
    private String subjectCode;
    private String displayName;
    private boolean inTimetable;
    private boolean keptForStudy;
    private boolean hasExam;
    private double credits = 4.0;

    public SubjectInfo(String subjectCode, String displayName, boolean inTimetable, boolean keptForStudy, boolean hasExam) {
        this(subjectCode, displayName, inTimetable, keptForStudy, hasExam, 4.0);
    }

    public SubjectInfo(String subjectCode, String displayName, boolean inTimetable, boolean keptForStudy, boolean hasExam, double credits) {
        this.subjectCode = subjectCode;
        this.displayName = displayName;
        this.inTimetable = inTimetable;
        this.keptForStudy = keptForStudy;
        this.hasExam = hasExam;
        this.credits = credits > 0 ? credits : 4.0;
    }

    public double getCredits() {
        return credits;
    }

    public void setCredits(double credits) {
        this.credits = credits;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isInTimetable() {
        return inTimetable;
    }

    public void setInTimetable(boolean inTimetable) {
        this.inTimetable = inTimetable;
    }

    public boolean isKeptForStudy() {
        return keptForStudy;
    }

    public void setKeptForStudy(boolean keptForStudy) {
        this.keptForStudy = keptForStudy;
    }

    public boolean isHasExam() {
        return hasExam;
    }

    public void setHasExam(boolean hasExam) {
        this.hasExam = hasExam;
    }

    /**
     * Get the badge text for this subject.
     */
    public String getBadge() {
        if (inTimetable) {
            return "In timetable";
        } else if (keptForStudy) {
            return "Kept for study";
        }
        return "Exam only";
    }
}
