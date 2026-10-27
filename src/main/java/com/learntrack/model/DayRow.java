package com.learntrack.model;

import java.time.LocalTime;

/**
 * Represents a single merged row in the daily schedule view.
 * Sources include weekly timetable commitments, wake/sleep routines,
 * exams, date overrides, and scheduled study sessions.
 */
public class DayRow {

    private String type; // CLASS, LAB, TUTORIAL, MEAL, OTHER, EXAM, SLEEP, TOPIC_LEARN, TOPIC_REVISE, PYQ_PRACTICE, SKILL
    private LocalTime start;
    private LocalTime end;
    private String label;
    private DailyLogEntry dailyLogEntry; // nullable
    private boolean fixed;
    private Integer timetableEntryId; // nullable, set for CLASS, LAB, TUTORIAL rows
    private Integer dateOverrideId; // nullable, set for date_overrides rows
    private boolean cancelled; // true if this commitment was cancelled on this date

    public DayRow() {
    }

    public DayRow(String type, LocalTime start, LocalTime end, String label, DailyLogEntry dailyLogEntry, boolean fixed) {
        this.type = type;
        this.start = start;
        this.end = end;
        this.label = label;
        this.dailyLogEntry = dailyLogEntry;
        this.fixed = fixed;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public LocalTime getStart() {
        return start;
    }

    public void setStart(LocalTime start) {
        this.start = start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public void setEnd(LocalTime end) {
        this.end = end;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public DailyLogEntry getDailyLogEntry() {
        return dailyLogEntry;
    }

    public void setDailyLogEntry(DailyLogEntry dailyLogEntry) {
        this.dailyLogEntry = dailyLogEntry;
    }

    public boolean isFixed() {
        return fixed;
    }

    public void setFixed(boolean fixed) {
        this.fixed = fixed;
    }

    public Integer getTimetableEntryId() {
        return timetableEntryId;
    }

    public void setTimetableEntryId(Integer timetableEntryId) {
        this.timetableEntryId = timetableEntryId;
    }

    public Integer getDateOverrideId() {
        return dateOverrideId;
    }

    public void setDateOverrideId(Integer dateOverrideId) {
        this.dateOverrideId = dateOverrideId;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public double getDurationHours() {
        if (start == null || end == null) {
            return 0.0;
        }
        if (end.equals(LocalTime.MAX)) {
            return (86400 - start.toSecondOfDay()) / 3600.0;
        }
        if (end.equals(LocalTime.MIDNIGHT) && start.isAfter(LocalTime.MIDNIGHT)) {
            return (86400 - start.toSecondOfDay()) / 3600.0;
        }
        if (end.isBefore(start)) {
            long secs = (86400 - start.toSecondOfDay()) + end.toSecondOfDay();
            return secs / 3600.0;
        }
        return (end.toSecondOfDay() - start.toSecondOfDay()) / 3600.0;
    }

    @Override
    public String toString() {
        return String.format("%s %s-%s [%s] fixed=%b (%.2fh)",
                type, start, end, label, fixed, getDurationHours());
    }
}
