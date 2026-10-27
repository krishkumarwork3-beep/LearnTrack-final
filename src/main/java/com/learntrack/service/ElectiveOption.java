package com.learntrack.service;

import com.learntrack.model.WeeklyTimetableEntry;

import java.time.LocalTime;
import java.util.Map;

/**
 * Represents an individual subject option inside an elective slot cell.
 */
public class ElectiveOption {
    private final String rawText;
    private final String baseCode;
    private final char suffix;
    private final String activityType; // CLASS | TUTORIAL | LAB
    private final String kind; // lecture | tutorial | practical
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final String room;

    public ElectiveOption(String rawText, String baseCode, char suffix,
                          String activityType, String kind,
                          LocalTime startTime, LocalTime endTime, String room) {
        this.rawText = rawText;
        this.baseCode = baseCode;
        this.suffix = suffix;
        this.activityType = activityType;
        this.kind = kind;
        this.startTime = startTime;
        this.endTime = endTime;
        this.room = room;
    }

    public String getRawText() {
        return rawText;
    }

    public String getBaseCode() {
        return baseCode;
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

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getRoom() {
        return room;
    }

    public String getFormattedLabel(Map<String, String> renames) {
        String name = (renames != null && renames.containsKey(baseCode)) ? renames.get(baseCode) : baseCode;
        return name + " " + kind;
    }

    public WeeklyTimetableEntry toEntry(String day, Map<String, String> renames) {
        return new WeeklyTimetableEntry(
                null, 0, day, startTime, endTime, activityType, getFormattedLabel(renames), room
        );
    }

    public boolean matchesInput(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        String s = input.trim().toUpperCase();
        if (s.equalsIgnoreCase(baseCode)) return true;
        if (s.equalsIgnoreCase(baseCode + suffix)) return true;
        if (rawText != null && rawText.toUpperCase().startsWith(s)) return true;
        return false;
    }

    @Override
    public String toString() {
        return rawText;
    }
}
