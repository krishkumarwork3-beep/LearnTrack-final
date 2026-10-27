package com.learntrack.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One-off modification to a specific date (CANCEL a timetable block or ADD an exam/other block).
 * Plan Section 3 & 11.
 */
public class DateOverride {
    private Integer overrideId;
    private LocalDate overrideDate;
    private Integer entryId;
    private String action; // 'CANCEL' | 'ADD'
    private LocalTime startTime;
    private LocalTime endTime;
    private String activityType; // 'EXAM' | 'OTHER'
    private String label;

    public DateOverride() {
    }

    public DateOverride(Integer overrideId, LocalDate overrideDate, Integer entryId,
                        String action, LocalTime startTime, LocalTime endTime,
                        String activityType, String label) {
        this.overrideId = overrideId;
        this.overrideDate = overrideDate;
        this.entryId = entryId;
        this.action = action;
        this.startTime = startTime;
        this.endTime = endTime;
        this.activityType = activityType;
        this.label = label;
    }

    public Integer getOverrideId() {
        return overrideId;
    }

    public void setOverrideId(Integer overrideId) {
        this.overrideId = overrideId;
    }

    public LocalDate getOverrideDate() {
        return overrideDate;
    }

    public void setOverrideDate(LocalDate overrideDate) {
        this.overrideDate = overrideDate;
    }

    public Integer getEntryId() {
        return entryId;
    }

    public void setEntryId(Integer entryId) {
        this.entryId = entryId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
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
