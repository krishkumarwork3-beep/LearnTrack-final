package com.learntrack.service;

import com.learntrack.dao.DateOverrideDAO;
import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.DateOverride;
import com.learntrack.model.Exam;
import com.learntrack.model.FreeSlot;
import com.learntrack.model.WeekdayRoutine;
import com.learntrack.model.WeeklyTimetableEntry;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes available study slots on a normal teaching day.
 * Plan Section 4 & 8, extended with Wake/Sleep Routine model (User Guide §1).
 */
public class FreeSlotService {

    private static final List<String> DAYS_OF_WEEK = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    );

    private final TimetableDAO timetableDAO;
    private final DateOverrideDAO dateOverrideDAO;
    private final ExamDAO examDAO;

    public FreeSlotService(TimetableDAO timetableDAO, DateOverrideDAO dateOverrideDAO, ExamDAO examDAO) {
        this.timetableDAO = timetableDAO;
        this.dateOverrideDAO = dateOverrideDAO;
        this.examDAO = examDAO;
    }

    public static String getPreviousDayOfWeek(String dayOfWeek) {
        if (dayOfWeek == null) return "SUNDAY";
        int idx = DAYS_OF_WEEK.indexOf(dayOfWeek.toUpperCase());
        if (idx == -1) return dayOfWeek;
        return DAYS_OF_WEEK.get((idx + 6) % 7);
    }

    public List<FreeSlot> computeFreeSlots(int semesterId, LocalDate date) throws SQLException {
        String dayOfWeek = date.getDayOfWeek().name();
        String prevDayOfWeek = date.minusDays(1).getDayOfWeek().name();

        List<WeeklyTimetableEntry> entries = timetableDAO.findBySemesterAndDay(semesterId, dayOfWeek);
        List<WeeklyTimetableEntry> prevEntries = timetableDAO.findBySemesterAndDay(semesterId, prevDayOfWeek);
        List<WeekdayRoutine> routines = timetableDAO.findRoutinesBySemester(semesterId);

        List<DateOverride> overrides = dateOverrideDAO.findByDate(date);
        List<DateOverride> prevOverrides = dateOverrideDAO.findByDate(date.minusDays(1));

        Set<Integer> cancelledEntryIds = new HashSet<>();
        List<SlotMath.TimeInterval> blocked = new ArrayList<>();

        for (DateOverride ov : overrides) {
            if ("CANCEL".equalsIgnoreCase(ov.getAction()) && ov.getEntryId() != null) {
                cancelledEntryIds.add(ov.getEntryId());
            } else if ("ADD".equalsIgnoreCase(ov.getAction()) && ov.getStartTime() != null && ov.getEndTime() != null) {
                blocked.add(new SlotMath.TimeInterval(ov.getStartTime(), ov.getEndTime()));
            }
        }

        Set<Integer> prevCancelledEntryIds = new HashSet<>();
        for (DateOverride ov : prevOverrides) {
            if ("CANCEL".equalsIgnoreCase(ov.getAction()) && ov.getEntryId() != null) {
                prevCancelledEntryIds.add(ov.getEntryId());
            }
        }

        // 1. Wake / Sleep Routine
        if (routines != null && !routines.isEmpty()) {
            Map<String, WeekdayRoutine> routineMap = new HashMap<>();
            for (WeekdayRoutine r : routines) {
                routineMap.put(r.getDayOfWeek().toUpperCase(), r);
            }
            WeekdayRoutine currentRoutine = routineMap.get(dayOfWeek.toUpperCase());
            WeekdayRoutine prevRoutine = routineMap.get(prevDayOfWeek.toUpperCase());

            if (currentRoutine != null) {
                LocalTime currentWake = currentRoutine.getWakeTime();
                LocalTime currentSleep = currentRoutine.getSleepTime();

                // Morning sleep block on date
                if (prevRoutine != null && !prevRoutine.getSleepTime().isAfter(prevRoutine.getWakeTime())) {
                    // Previous day sleep was after midnight (e.g. 01:00)
                    if (prevRoutine.getSleepTime().isBefore(currentWake)) {
                        blocked.add(new SlotMath.TimeInterval(prevRoutine.getSleepTime(), currentWake));
                    }
                } else {
                    // Previous day sleep was before midnight (e.g. 23:00)
                    if (currentWake != null && currentWake.isAfter(LocalTime.MIN)) {
                        blocked.add(new SlotMath.TimeInterval(LocalTime.MIN, currentWake));
                    }
                }

                // Evening sleep block on date
                if (currentSleep != null && currentWake != null && currentSleep.isAfter(currentWake)) {
                    // Sleep is before midnight
                    blocked.add(new SlotMath.TimeInterval(currentSleep, LocalTime.MAX));
                }
                // If sleep <= wake: sleep is after midnight -> awake past midnight, no evening block on date D
            }
        }

        // 2. Commitments for date (dayOfWeek)
        for (WeeklyTimetableEntry entry : entries) {
            if (entry.getEntryId() != null && cancelledEntryIds.contains(entry.getEntryId())) {
                continue; // Cancelled
            }
            if (entry.getStartTime() != null && entry.getEndTime() != null) {
                if (entry.getEndTime().isAfter(entry.getStartTime())) {
                    blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), entry.getEndTime()));
                } else if (!entry.getStartTime().equals(entry.getEndTime())) {
                    // Crosses midnight (end <= start): blocks [start, 24:00) on day D
                    blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), LocalTime.MAX));
                }
            }
        }

        // 3. Commitments from prevDate (prevDayOfWeek) spilling into date
        for (WeeklyTimetableEntry prevEntry : prevEntries) {
            if (prevEntry.getEntryId() != null &&
                    (cancelledEntryIds.contains(prevEntry.getEntryId()) || prevCancelledEntryIds.contains(prevEntry.getEntryId()))) {
                continue; // Cancelled
            }
            if (prevEntry.getStartTime() != null && prevEntry.getEndTime() != null) {
                if (!prevEntry.getEndTime().isAfter(prevEntry.getStartTime()) && !prevEntry.getStartTime().equals(prevEntry.getEndTime())) {
                    // Crosses midnight (end <= start): spills [00:00, end) into date
                    if (prevEntry.getEndTime().isAfter(LocalTime.MIN)) {
                        blocked.add(new SlotMath.TimeInterval(LocalTime.MIN, prevEntry.getEndTime()));
                    }
                }
            }
        }

        // 4. Dated exams on date with asymmetric buffers
        List<Exam> examsToday = examDAO.findDatedOnDate(semesterId, date);
        for (Exam exam : examsToday) {
            if (exam.getExamStartTime() != null && exam.getExamEndTime() != null) {
                LocalTime start = exam.getExamStartTime();
                int travel = exam.getTravelTimeMinutes();
                LocalTime bufferedStart;
                if (start.toSecondOfDay() < travel * 60) {
                    bufferedStart = LocalTime.MIN;
                } else {
                    bufferedStart = start.minusMinutes(travel);
                }

                LocalTime end = exam.getExamEndTime();
                LocalTime bufferedEnd;
                if (end.toSecondOfDay() + 3600 >= 86400) {
                    bufferedEnd = LocalTime.MAX;
                } else {
                    bufferedEnd = end.plusHours(1);
                }

                blocked.add(new SlotMath.TimeInterval(bufferedStart, bufferedEnd));
            }
        }

        return SlotMath.computeComplement(blocked);
    }

    /**
     * Computes free slots for a weekday using the wake/sleep routine model and recurring timetable entries.
     * Takes into account previous day's wake/sleep and cross-midnight spill.
     */
    public List<FreeSlot> computeFreeSlotsForDay(String dayOfWeek, Map<String, WeekdayRoutine> routines, List<WeeklyTimetableEntry> allEntries) {
        String dayUpper = dayOfWeek != null ? dayOfWeek.toUpperCase() : "MONDAY";
        String prevDayUpper = getPreviousDayOfWeek(dayUpper);

        List<SlotMath.TimeInterval> blocked = new ArrayList<>();

        if (routines != null && !routines.isEmpty()) {
            WeekdayRoutine currentRoutine = routines.get(dayUpper);
            WeekdayRoutine prevRoutine = routines.get(prevDayUpper);

            if (currentRoutine != null) {
                LocalTime currentWake = currentRoutine.getWakeTime();
                LocalTime currentSleep = currentRoutine.getSleepTime();

                // Morning sleep block on day
                if (prevRoutine != null && !prevRoutine.getSleepTime().isAfter(prevRoutine.getWakeTime())) {
                    // Previous day sleep was after midnight (e.g. 01:00)
                    if (prevRoutine.getSleepTime().isBefore(currentWake)) {
                        blocked.add(new SlotMath.TimeInterval(prevRoutine.getSleepTime(), currentWake));
                    }
                } else {
                    // Previous day sleep was before midnight (e.g. 23:00)
                    if (currentWake != null && currentWake.isAfter(LocalTime.MIN)) {
                        blocked.add(new SlotMath.TimeInterval(LocalTime.MIN, currentWake));
                    }
                }

                // Evening sleep block on day
                if (currentSleep != null && currentWake != null && currentSleep.isAfter(currentWake)) {
                    // Sleep is before midnight
                    blocked.add(new SlotMath.TimeInterval(currentSleep, LocalTime.MAX));
                }
            }
        }

        if (allEntries != null) {
            for (WeeklyTimetableEntry entry : allEntries) {
                if (entry.getStartTime() == null || entry.getEndTime() == null) continue;
                if (dayUpper.equalsIgnoreCase(entry.getDayOfWeek())) {
                    if (entry.getEndTime().isAfter(entry.getStartTime())) {
                        blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), entry.getEndTime()));
                    } else if (!entry.getStartTime().equals(entry.getEndTime())) {
                        blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), LocalTime.MAX));
                    }
                } else if (prevDayUpper.equalsIgnoreCase(entry.getDayOfWeek())) {
                    if (!entry.getEndTime().isAfter(entry.getStartTime()) && !entry.getStartTime().equals(entry.getEndTime())) {
                        if (entry.getEndTime().isAfter(LocalTime.MIN)) {
                            blocked.add(new SlotMath.TimeInterval(LocalTime.MIN, entry.getEndTime()));
                        }
                    }
                }
            }
        }

        return SlotMath.computeComplement(blocked);
    }

    /**
     * Computes free hours for a weekday using the wake/sleep routine model and recurring timetable entries.
     */
    public double computeFreeHoursForDay(String dayOfWeek, Map<String, WeekdayRoutine> routines, List<WeeklyTimetableEntry> allEntries) {
        List<FreeSlot> slots = computeFreeSlotsForDay(dayOfWeek, routines, allEntries);
        double total = 0.0;
        for (FreeSlot slot : slots) {
            total += slot.getDuration();
        }
        return total;
    }

    /**
     * Computes free hours in a day given a list of recurring timetable entries for that day.
     * Preserved for backward compatibility (Plan §14 & Guide §1).
     */
    public double computeFreeHoursForEntries(List<WeeklyTimetableEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 24.0;
        }
        List<SlotMath.TimeInterval> blocked = new ArrayList<>();
        for (WeeklyTimetableEntry entry : entries) {
            if (entry.getStartTime() != null && entry.getEndTime() != null) {
                blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), entry.getEndTime()));
            }
        }
        List<FreeSlot> freeSlots = SlotMath.computeComplement(blocked);
        double totalFree = 0.0;
        for (FreeSlot slot : freeSlots) {
            totalFree += slot.getDuration();
        }
        return totalFree;
    }
}

