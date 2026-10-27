package com.learntrack.model;

import java.time.Duration;
import java.time.LocalTime;

/**
 * An available study time interval within a single day.
 * Not a database table. Plan Section 4 & 7.
 */
public class FreeSlot implements Comparable<FreeSlot> {
    private LocalTime start;
    private LocalTime end;

    public FreeSlot() {
    }

    public FreeSlot(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getStartTime() {
        return start;
    }

    public void setStart(LocalTime start) {
        this.start = start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public LocalTime getEndTime() {
        return end;
    }

    public void setEnd(LocalTime end) {
        this.end = end;
    }

    public double getDuration() {
        if (start == null || end == null) {
            return 0.0;
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (end.equals(LocalTime.MAX)) {
            minutes += 1;
        }
        return Math.max(0.0, minutes / 60.0);
    }

    @Override
    public int compareTo(FreeSlot other) {
        if (other == null) return 1;
        return this.start.compareTo(other.start);
    }

    @Override
    public String toString() {
        return "[" + start + " - " + end + " (" + getDuration() + "h)]";
    }
}
