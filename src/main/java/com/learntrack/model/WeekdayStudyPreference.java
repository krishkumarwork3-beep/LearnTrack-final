package com.learntrack.model;

/**
 * Target study hours preference per weekday.
 * Plan Section 3.
 */
public class WeekdayStudyPreference {
    private int semesterId;
    private String dayOfWeek; // MONDAY..SUNDAY
    private double studyHours;

    public WeekdayStudyPreference() {
    }

    public WeekdayStudyPreference(int semesterId, String dayOfWeek, double studyHours) {
        this.semesterId = semesterId;
        this.dayOfWeek = dayOfWeek;
        this.studyHours = studyHours;
    }

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public String getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(String dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public double getStudyHours() {
        return studyHours;
    }

    public void setStudyHours(double studyHours) {
        this.studyHours = studyHours;
    }
}
