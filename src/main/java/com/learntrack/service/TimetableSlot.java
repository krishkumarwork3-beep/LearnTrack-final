package com.learntrack.service;

import com.learntrack.model.WeeklyTimetableEntry;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed slot from an Excel timetable sheet.
 */
public class TimetableSlot {
    private final String day; // MONDAY..SUNDAY
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final String subjectCode; // Base code, e.g. "UCS503"
    private final char suffix; // 'L', 'T', 'P'
    private final String activityType; // CLASS, TUTORIAL, LAB
    private final String kind; // lecture, tutorial, practical
    private final String rawCellText;
    private final String room;
    private final boolean isElective;
    private final List<ElectiveOption> electiveOptions;

    public TimetableSlot(String day, LocalTime startTime, LocalTime endTime,
                         String subjectCode, char suffix, String activityType, String kind,
                         String rawCellText, String room, boolean isElective,
                         List<ElectiveOption> electiveOptions) {
        this.day = day;
        this.startTime = startTime;
        this.endTime = endTime;
        this.subjectCode = subjectCode;
        this.suffix = suffix;
        this.activityType = activityType;
        this.kind = kind;
        this.rawCellText = rawCellText;
        this.room = room;
        this.isElective = isElective;
        this.electiveOptions = electiveOptions != null ? Collections.unmodifiableList(electiveOptions) : List.of();
    }

    public String getDay() {
        return day;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public char getSuffix() {
        return suffix;
    }

    public String getActivityType() {
        return activityType;
    }

    public String getKind() {
        return kind;
    }

    public String getRawCellText() {
        return rawCellText;
    }

    public String getRoom() {
        return room;
    }

    public boolean isElective() {
        return isElective;
    }

    public List<ElectiveOption> getElectiveOptions() {
        return electiveOptions;
    }

    public String getFormattedLabel(Map<String, String> renames) {
        String name = (renames != null && renames.containsKey(subjectCode)) ? renames.get(subjectCode) : subjectCode;
        return name + " " + kind;
    }

    public WeeklyTimetableEntry toEntry(Map<String, String> renames) {
        return new WeeklyTimetableEntry(
                null, 0, day, startTime, endTime, activityType, getFormattedLabel(renames), room, subjectCode
        );
    }

    @Override
    public String toString() {
        return day + " " + startTime + "-" + endTime + " " + (isElective ? "[ELECTIVE: " + rawCellText + "]" : subjectCode + suffix);
    }
}
