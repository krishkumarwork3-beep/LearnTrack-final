package com.learntrack.service;

import com.learntrack.dao.DateOverrideDAO;
import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamPeriodRoutineDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Computes available study slots during a dated MST/EST exam window.
 * If exam_period_routine has rows, uses them.
 * If empty, derives default routine from weekly data:
 * wake/sleep from weekday routine plus weekly entries with activityType MEAL, SLEEP, OTHER.
 * Academic entries (CLASS, TUTORIAL, LAB) are excluded.
 */
public class ExamPeriodFreeSlotService {

    private final ExamPeriodRoutineDAO routineDAO;
    private final DateOverrideDAO dateOverrideDAO;
    private final ExamDAO examDAO;
    private final TimetableDAO timetableDAO;

    public ExamPeriodFreeSlotService(ExamPeriodRoutineDAO routineDAO, DateOverrideDAO dateOverrideDAO, ExamDAO examDAO) {
        this(routineDAO, dateOverrideDAO, examDAO, new TimetableDAO());
    }

    public ExamPeriodFreeSlotService(ExamPeriodRoutineDAO routineDAO, DateOverrideDAO dateOverrideDAO,
                                    ExamDAO examDAO, TimetableDAO timetableDAO) {
        this.routineDAO = routineDAO;
        this.dateOverrideDAO = dateOverrideDAO;
        this.examDAO = examDAO;
        this.timetableDAO = timetableDAO != null ? timetableDAO : new TimetableDAO();
    }

    public List<FreeSlot> computeFreeSlots(int semesterId, LocalDate date) throws SQLException {
        List<ExamPeriodRoutineEntry> routine = routineDAO.findBySemester(semesterId);
        List<DateOverride> overrides = dateOverrideDAO.findByDate(date);

        // CANCEL overrides reference weekly_timetable entry_ids (a different ID space).
        // They must NOT be applied here: doing so silently cancels the wrong exam_period_routine
        // block whenever both tables share the same numeric id. Only ADD overrides apply in the
        // exam-period context (Plan §4, Fix 2).
        List<SlotMath.TimeInterval> blocked = new ArrayList<>();

        for (DateOverride ov : overrides) {
            if ("ADD".equalsIgnoreCase(ov.getAction()) && ov.getStartTime() != null && ov.getEndTime() != null) {
                blocked.add(new SlotMath.TimeInterval(ov.getStartTime(), ov.getEndTime()));
            }
        }

        // Fixed routine (meals, sleep, other)
        if (routine != null && !routine.isEmpty()) {
            for (ExamPeriodRoutineEntry entry : routine) {
                if (entry.getStartTime() != null && entry.getEndTime() != null) {
                    if (entry.getStartTime().isAfter(entry.getEndTime())) {
                        // Crosses midnight
                        blocked.add(new SlotMath.TimeInterval(LocalTime.MIN, entry.getEndTime()));
                        blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), LocalTime.MAX));
                    } else if (!entry.getStartTime().equals(entry.getEndTime())) {
                        blocked.add(new SlotMath.TimeInterval(entry.getStartTime(), entry.getEndTime()));
                    }
                }
            }
        } else {
            // Empty exam_period_routine fallback: derive from weekly data using shared provider
            DefaultExamRoutineProvider provider = new DefaultExamRoutineProvider(timetableDAO);
            blocked.addAll(provider.deriveBlockedIntervals(semesterId, date));
        }

        // Each dated exam sitting on date with asymmetric buffer:
        // [start - travel_time_minutes, end + 1h] (Plan Section 4)
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
}
