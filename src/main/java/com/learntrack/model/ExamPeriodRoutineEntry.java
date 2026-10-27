package com.learntrack.model;

import java.time.LocalTime;

/**
 * Simplified fixed routine for dated exam periods (meals, sleep, other).
 * Plan Section 3 & 4.
 */
public class ExamPeriodRoutineEntry {
    private Integer routineId;
    private int semesterId;
    private LocalTime startTime;
    private LocalTime endTime;
    private String activityType; // MEAL | SLEEP | OTHER
    private String label;

    public ExamPeriodRoutineEntry() {
    }

    public ExamPeriodRoutineEntry(Integer routineId, int semesterId, LocalTime startTime,
                                  LocalTime endTime, String activityType, String label) {
        this.routineId = routineId;
        this.semesterId = semesterId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.activityType = activityType;
        this.label = label;
    }

    public Integer getRoutineId() {
        return routineId;
    }

    public void setRoutineId(Integer routineId) {
        this.routineId = routineId;
    }

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
