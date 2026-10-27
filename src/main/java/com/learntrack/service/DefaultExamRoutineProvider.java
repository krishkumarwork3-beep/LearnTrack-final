package com.learntrack.service;

import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.WeekdayRoutine;
import com.learntrack.model.WeeklyTimetableEntry;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper to derive default exam-period blocked slots when exam_period_routine is empty.
 * Used by both ExamPeriodFreeSlotService and DayScheduleService to ensure consistent behavior.
 * 
 * Default routine includes:
 * - Wake/sleep times from weekday routine (or 07:00/23:00 fallback)
 * - Weekly timetable entries with activityType MEAL, SLEEP, OTHER
 * - Excludes CLASS, TUTORIAL, LAB (these are not followed during exam periods)
 */
public class DefaultExamRoutineProvider {

    private final TimetableDAO timetableDAO;

    public DefaultExamRoutineProvider(TimetableDAO timetableDAO) {
        this.timetableDAO = timetableDAO;
    }

    /**
     * Derive default blocked time intervals for a given date during an exam period.
     * Returns intervals that should be blocked from study (sleep, meals, other commitments).
     */
    public List<SlotMath.TimeInterval> deriveBlockedIntervals(int semesterId, LocalDate date) throws SQLException {
        List<SlotMath.TimeInterval> blocked = new ArrayList<>();
        
        String dayOfWeek = date.getDayOfWeek().name();
        String prevDayOfWeek = date.minusDays(1).getDayOfWeek().name();

        List<WeekdayRoutine> routines = timetableDAO.findRoutinesBySemester(semesterId);
        Map<String, WeekdayRoutine> routineMap = new HashMap<>();
        if (routines != null) {
            for (WeekdayRoutine r : routines) {
                routineMap.put(r.getDayOfWeek().toUpperCase(), r);
            }
        }

        WeekdayRoutine currentRoutine = routineMap.get(dayOfWeek.toUpperCase());
        WeekdayRoutine prevRoutine = routineMap.get(prevDayOfWeek.toUpperCase());

        LocalTime currentWake = currentRoutine != null && currentRoutine.getWakeTime() != null
                ? currentRoutine.getWakeTime() : LocalTime.of(7, 0);
        LocalTime currentSleep = currentRoutine != null && currentRoutine.getSleepTime() != null
                ? currentRoutine.getSleepTime() : LocalTime.of(23, 0);

        // 1. Morning sleep block
        if (prevRoutine != null && prevRoutine.getSleepTime() != null
                && !prevRoutine.getSleepTime().isAfter(prevRoutine.getWakeTime())) {
            // Previous day sleep was after midnight (e.g. 01:00)
            if (prevRoutine.getSleepTime().isBefore(currentWake)) {
                blocked.add(new SlotMath.TimeInterval(prevRoutine.getSleepTime(), currentWake));
            }
        } else {
            // Previous day sleep was before midnight (e.g. 23:00)
            if (currentWake.isAfter(LocalTime.MIN)) {
                blocked.add(new SlotMath.TimeInterval(LocalTime.MIN, currentWake));
            }
        }

        // 2. Evening sleep block
        if (currentSleep.isAfter(currentWake)) {
            blocked.add(new SlotMath.TimeInterval(currentSleep, LocalTime.MAX));
        }

        // 3. Weekly timetable entries for day: include MEAL, SLEEP, OTHER. Exclude CLASS, TUTORIAL, LAB.
        List<WeeklyTimetableEntry> entries = timetableDAO.findBySemesterAndDay(semesterId, dayOfWeek);
        for (WeeklyTimetableEntry entry : entries) {
            String type = entry.getActivityType() != null ? entry.getActivityType().toUpperCase() : "";
            if ("CLASS".equals(type) || "TUTORIAL".equals(type) || "LAB".equals(type)) {
                continue; // Academic classes are excluded during exam period!
            }
            if (entry.getStartTime() != null && entry.getEndTime() != null) {
                if (entry.getEndTime().isAfter(entry.getStartTime())) {
                    blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), entry.getEndTime()));
                } else if (!entry.getStartTime().equals(entry.getEndTime())) {
                    // Crosses midnight
                    blocked.add(new SlotMath.TimeInterval(LocalTime.MIN, entry.getEndTime()));
                    blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), LocalTime.MAX));
                }
            }
        }

        return blocked;
    }

    /**
     * Simple data class representing a default routine block for display in DayScheduleService.
     */
    public static class DefaultRoutineBlock {
        private final String activityType;
        private final LocalTime startTime;
        private final LocalTime endTime;
        private final String label;

        public DefaultRoutineBlock(String activityType, LocalTime startTime, LocalTime endTime, String label) {
            this.activityType = activityType;
            this.startTime = startTime;
            this.endTime = endTime;
            this.label = label;
        }

        public String getActivityType() {
            return activityType;
        }

        public LocalTime getStartTime() {
            return startTime;
        }

        public LocalTime getEndTime() {
            return endTime;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * Derive default routine blocks for DayScheduleService display.
     * Returns blocks that can be shown as FIXED rows in the day schedule.
     */
    public List<DefaultRoutineBlock> deriveRoutineBlocks(int semesterId, LocalDate date) throws SQLException {
        List<DefaultRoutineBlock> blocks = new ArrayList<>();
        
        String dayOfWeek = date.getDayOfWeek().name();
        String prevDayOfWeek = date.minusDays(1).getDayOfWeek().name();

        List<WeekdayRoutine> routines = timetableDAO.findRoutinesBySemester(semesterId);
        Map<String, WeekdayRoutine> routineMap = new HashMap<>();
        if (routines != null) {
            for (WeekdayRoutine r : routines) {
                routineMap.put(r.getDayOfWeek().toUpperCase(), r);
            }
        }

        WeekdayRoutine currentRoutine = routineMap.get(dayOfWeek.toUpperCase());
        WeekdayRoutine prevRoutine = routineMap.get(prevDayOfWeek.toUpperCase());

        LocalTime currentWake = currentRoutine != null && currentRoutine.getWakeTime() != null
                ? currentRoutine.getWakeTime() : LocalTime.of(7, 0);
        LocalTime currentSleep = currentRoutine != null && currentRoutine.getSleepTime() != null
                ? currentRoutine.getSleepTime() : LocalTime.of(23, 0);

        // 1. Morning sleep block
        if (prevRoutine != null && prevRoutine.getSleepTime() != null
                && !prevRoutine.getSleepTime().isAfter(prevRoutine.getWakeTime())) {
            // Previous day sleep was after midnight (e.g. 01:00)
            if (prevRoutine.getSleepTime().isBefore(currentWake)) {
                blocks.add(new DefaultRoutineBlock("SLEEP", prevRoutine.getSleepTime(), currentWake, "Sleep"));
            }
        } else {
            // Previous day sleep was before midnight (e.g. 23:00)
            if (currentWake.isAfter(LocalTime.MIN)) {
                blocks.add(new DefaultRoutineBlock("SLEEP", LocalTime.MIN, currentWake, "Sleep"));
            }
        }

        // 2. Evening sleep block
        if (currentSleep.isAfter(currentWake)) {
            blocks.add(new DefaultRoutineBlock("SLEEP", currentSleep, LocalTime.MAX, "Sleep"));
        }

        // 3. Weekly timetable entries for day: include MEAL, SLEEP, OTHER. Exclude CLASS, TUTORIAL, LAB.
        List<WeeklyTimetableEntry> entries = timetableDAO.findBySemesterAndDay(semesterId, dayOfWeek);
        for (WeeklyTimetableEntry entry : entries) {
            String type = entry.getActivityType() != null ? entry.getActivityType().toUpperCase() : "";
            if ("CLASS".equals(type) || "TUTORIAL".equals(type) || "LAB".equals(type)) {
                continue; // Academic classes are excluded during exam period!
            }
            if (entry.getStartTime() != null && entry.getEndTime() != null) {
                String label = entry.getLabel() != null && !entry.getLabel().isBlank() 
                    ? entry.getLabel() 
                    : type;
                
                if (entry.getEndTime().isAfter(entry.getStartTime())) {
                    blocks.add(new DefaultRoutineBlock(type, entry.getStartTime(), entry.getEndTime(), label));
                } else if (!entry.getStartTime().equals(entry.getEndTime())) {
                    // Crosses midnight
                    blocks.add(new DefaultRoutineBlock(type, LocalTime.MIN, entry.getEndTime(), label));
                    blocks.add(new DefaultRoutineBlock(type, entry.getStartTime(), LocalTime.MAX, label));
                }
            }
        }

        return blocks;
    }
}
