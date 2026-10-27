package com.learntrack.model;

import java.time.LocalTime;

/**
 * Recurring weekly commitment for normal teaching days.
 * Plan Section 3.
 */
public class WeeklyTimetableEntry {
    private Integer entryId;
    private int semesterId;
    private String dayOfWeek; // MONDAY..SUNDAY
    private LocalTime startTime;
    private LocalTime endTime;
    private String activityType; // CLASS | TUTORIAL | LAB | MEAL | SLEEP | OTHER
    private String label;
    private String room;
    private String subjectCode; // Subject code for academic entries (CLASS/TUTORIAL/LAB)

    public WeeklyTimetableEntry() {
    }

    public WeeklyTimetableEntry(Integer entryId, int semesterId, String dayOfWeek,
                                LocalTime startTime, LocalTime endTime,
                                String activityType, String label) {
        this(entryId, semesterId, dayOfWeek, startTime, endTime, activityType, label, null, null);
    }

    public WeeklyTimetableEntry(Integer entryId, int semesterId, String dayOfWeek,
                                LocalTime startTime, LocalTime endTime,
                                String activityType, String label, String room) {
        this(entryId, semesterId, dayOfWeek, startTime, endTime, activityType, label, room, null);
    }

    public WeeklyTimetableEntry(Integer entryId, int semesterId, String dayOfWeek,
                                LocalTime startTime, LocalTime endTime,
                                String activityType, String label, String room, String subjectCode) {
        this.entryId = entryId;
        this.semesterId = semesterId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.activityType = activityType;
        this.label = label;
        this.room = room;
        this.subjectCode = subjectCode;
    }

    /** Copy this entry onto another day, preserving type, label, room and subjectCode. */
    public WeeklyTimetableEntry copyToDay(String day) {
        return new WeeklyTimetableEntry(null, 0, day, startTime, endTime, activityType, label, room, subjectCode);
    }

    /** Same metadata with a new time range (used when trimming/splitting). */
    public WeeklyTimetableEntry withTimes(String day, LocalTime start, LocalTime end) {
        return new WeeklyTimetableEntry(null, 0, day, start, end, activityType, label, room, subjectCode);
    }

    public Integer getEntryId() {
        return entryId;
    }

    public void setEntryId(Integer entryId) {
        this.entryId = entryId;
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

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
    }

    /** Empty string when room is absent, never the word "null". */
    public String getRoomDisplay() {
        return room == null ? "" : room;
    }
}
