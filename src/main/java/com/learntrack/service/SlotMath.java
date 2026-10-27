package com.learntrack.service;

import com.learntrack.model.FreeSlot;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Package-private helper for interval merging, splitting cross-midnight blocks,
 * and computing free slot complements within a 24-hour day.
 * Plan Section 2 & 4.
 */
class SlotMath {

    static class TimeInterval implements Comparable<TimeInterval> {
        LocalTime start;
        LocalTime end;

        TimeInterval(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public int compareTo(TimeInterval o) {
            return this.start.compareTo(o.start);
        }

        @Override
        public String toString() {
            return "[" + start + " - " + end + "]";
        }
    }

    /**
     * Splits intervals that cross midnight (start > end) into two intervals:
     * [start, 23:59:59.999999999] and [00:00, end].
     */
    static List<TimeInterval> normalizeIntervals(List<TimeInterval> raw) {
        List<TimeInterval> normalized = new ArrayList<>();
        for (TimeInterval ti : raw) {
            if (ti.start.equals(ti.end)) {
                continue;
            }
            if (ti.start.isAfter(ti.end)) {
                // Crosses midnight
                normalized.add(new TimeInterval(ti.start, LocalTime.MAX));
                normalized.add(new TimeInterval(LocalTime.MIN, ti.end));
            } else {
                normalized.add(ti);
            }
        }
        return normalized;
    }

    /**
     * Merges overlapping or contiguous intervals into disjoint, sorted intervals.
     * Plan Section 16: Two exams with overlapping buffer windows same day -> merge into one blocked interval.
     */
    static List<TimeInterval> mergeIntervals(List<TimeInterval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return new ArrayList<>();
        }
        List<TimeInterval> sorted = new ArrayList<>(normalizeIntervals(intervals));
        sorted.sort(Comparator.comparing((TimeInterval t) -> t.start).thenComparing(t -> t.end));

        List<TimeInterval> merged = new ArrayList<>();
        TimeInterval current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            TimeInterval next = sorted.get(i);
            // Overlapping or contiguous if next.start <= current.end
            if (!next.start.isAfter(current.end)) {
                if (next.end.isAfter(current.end)) {
                    current.end = next.end;
                }
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * Computes the free slots (complement) of the given blocked intervals within [00:00, 23:59:59.999999999].
     * Returns a chronological list of FreeSlot.
     */
    static List<FreeSlot> computeComplement(List<TimeInterval> blockedIntervals) {
        List<TimeInterval> mergedBlocked = mergeIntervals(blockedIntervals);
        List<FreeSlot> freeSlots = new ArrayList<>();

        LocalTime cursor = LocalTime.MIN;

        for (TimeInterval blocked : mergedBlocked) {
            if (blocked.start.isAfter(cursor)) {
                // Gap between cursor and blocked.start is free time
                // Filter out tiny slivers under 5 minutes
                if (Duration.between(cursor, blocked.start).toMinutes() >= 5) {
                    freeSlots.add(new FreeSlot(cursor, blocked.start));
                }
            }
            if (blocked.end.equals(LocalTime.MAX)) {
                cursor = LocalTime.MAX;
                break;
            }
            if (blocked.end.isAfter(cursor)) {
                cursor = blocked.end;
            }
        }

        if (cursor.isBefore(LocalTime.MAX)) {
            // Free time from cursor to end of day
            if (Duration.between(cursor, LocalTime.MAX).toMinutes() >= 5) {
                freeSlots.add(new FreeSlot(cursor, LocalTime.MAX));
            }
        }

        return freeSlots;
    }

    /**
     * Subtracts occupied/surviving intervals from existing free slots.
     */
    static List<FreeSlot> subtractIntervals(List<FreeSlot> freeSlots, List<TimeInterval> occupied) {
        if (freeSlots == null || freeSlots.isEmpty() || occupied == null || occupied.isEmpty()) {
            return freeSlots == null ? new ArrayList<>() : new ArrayList<>(freeSlots);
        }
        List<FreeSlot> result = new ArrayList<>();
        List<TimeInterval> mergedOccupied = mergeIntervals(occupied);

        for (FreeSlot slot : freeSlots) {
            LocalTime cursor = slot.getStart();
            for (TimeInterval occ : mergedOccupied) {
                if (occ.end.isBefore(cursor) || occ.end.equals(cursor) ||
                    occ.start.isAfter(slot.getEnd()) || occ.start.equals(slot.getEnd())) {
                    continue;
                }
                if (occ.start.isAfter(cursor)) {
                    LocalTime pieceEnd = occ.start.isBefore(slot.getEnd()) ? occ.start : slot.getEnd();
                    if (Duration.between(cursor, pieceEnd).toMinutes() >= 5) {
                        result.add(new FreeSlot(cursor, pieceEnd));
                    }
                }
                if (occ.end.isAfter(cursor)) {
                    cursor = occ.end.isBefore(slot.getEnd()) ? occ.end : slot.getEnd();
                }
            }
            if (cursor.isBefore(slot.getEnd())) {
                if (Duration.between(cursor, slot.getEnd()).toMinutes() >= 5) {
                    result.add(new FreeSlot(cursor, slot.getEnd()));
                }
            }
        }
        return result;
    }
}
