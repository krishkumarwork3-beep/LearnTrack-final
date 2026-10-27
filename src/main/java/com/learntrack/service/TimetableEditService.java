package com.learntrack.service;

import com.learntrack.dao.DailyLogDAO;
import com.learntrack.dao.DateOverrideDAO;
import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamPeriodRoutineDAO;
import com.learntrack.dao.TopicDAO;
import com.learntrack.model.DailyLogEntry;
import com.learntrack.model.Exam;
import com.learntrack.model.FreeSlot;
import com.learntrack.model.Topic;
import com.learntrack.util.DurationFormat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Service handling safe, validated user adjustments to today's timetable:
 * - Swapping session items between two slots without corrupting slot durations.
 * - Resizing a session slot while enforcing non-overlap with other sessions,
 *   fixed commitments, and exam buffers.
 * Both actions preserve user intent via status = 'MANUAL'.
 * Plan Section 7, User Guide Section 2.2, Fix Pass 2 Item 3.
 */
public class TimetableEditService {

    private final DailyLogDAO dailyLogDAO;
    private final SemesterReplanService replanService;
    private final FreeSlotService freeSlotService;
    private final ExamPeriodFreeSlotService examPeriodFreeSlotService;
    private final ExamPeriodRoutineDAO routineDAO;
    private final ExamDAO examDAO;
    private final TopicDAO topicDAO;
    private final DateOverrideDAO dateOverrideDAO;
    private final ExamWindowService examWindowService;

    public TimetableEditService(DailyLogDAO dailyLogDAO,
                                SemesterReplanService replanService,
                                FreeSlotService freeSlotService,
                                ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                ExamPeriodRoutineDAO routineDAO,
                                ExamDAO examDAO) {
        this(dailyLogDAO, replanService, freeSlotService, examPeriodFreeSlotService, routineDAO, examDAO, new TopicDAO());
    }

    public TimetableEditService(DailyLogDAO dailyLogDAO,
                                SemesterReplanService replanService,
                                FreeSlotService freeSlotService,
                                ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                ExamPeriodRoutineDAO routineDAO,
                                ExamDAO examDAO,
                                TopicDAO topicDAO) {
        this(dailyLogDAO, replanService, freeSlotService, examPeriodFreeSlotService, routineDAO,
                examDAO, topicDAO, new DateOverrideDAO());
    }

    public TimetableEditService(DailyLogDAO dailyLogDAO,
                                SemesterReplanService replanService,
                                FreeSlotService freeSlotService,
                                ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                ExamPeriodRoutineDAO routineDAO,
                                ExamDAO examDAO,
                                TopicDAO topicDAO,
                                DateOverrideDAO dateOverrideDAO) {
        this.dailyLogDAO = Objects.requireNonNull(dailyLogDAO, "dailyLogDAO must not be null");
        this.replanService = Objects.requireNonNull(replanService, "replanService must not be null");
        this.freeSlotService = Objects.requireNonNull(freeSlotService, "freeSlotService must not be null");
        this.examPeriodFreeSlotService = Objects.requireNonNull(examPeriodFreeSlotService, "examPeriodFreeSlotService must not be null");
        this.routineDAO = Objects.requireNonNull(routineDAO, "routineDAO must not be null");
        this.examDAO = Objects.requireNonNull(examDAO, "examDAO must not be null");
        this.topicDAO = Objects.requireNonNull(topicDAO, "topicDAO must not be null");
        this.dateOverrideDAO = dateOverrideDAO != null ? dateOverrideDAO : new DateOverrideDAO();
        this.examWindowService = new ExamWindowService();
    }

    /**
     * Resizes one occurrence of a weekly commitment (class, lab or tutorial) on a single date.
     * The recurring timetable is never touched: the occurrence is cancelled for that date and a
     * matching one-off block is added with the new end time, which is the same day-override
     * mechanism the cancel/restore action uses. The caller replans once afterwards.
     */
    public void resizeFixedOccurrence(int semesterId, LocalDate date, int timetableEntryId,
                                      LocalTime newStart, LocalTime newEnd,
                                      String activityType, String label)
            throws SQLException, IllegalArgumentException {
        if (timetableEntryId <= 0) {
            throw new IllegalArgumentException("This commitment has no recurring entry to resize.");
        }
        if (newStart == null || newEnd == null || !newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException("The new end time must be after the start time.");
        }
        double durationHours = Duration.between(newStart, newEnd).toMinutes() / 60.0;
        if (durationHours < 0.25 || durationHours > 8.0) {
            throw new IllegalArgumentException("A commitment must last between 15 minutes and 8 hours.");
        }

        // Replace any earlier resize of this occurrence instead of stacking overrides.
        dateOverrideDAO.deleteByDateAndEntry(date, timetableEntryId, "CANCEL");
        dateOverrideDAO.deleteByDateAndEntry(date, timetableEntryId, "ADD");

        dateOverrideDAO.insert(new com.learntrack.model.DateOverride(
                null, date, timetableEntryId, "CANCEL", null, null, null, "Resized"));
        dateOverrideDAO.insert(new com.learntrack.model.DateOverride(
                null, date, timetableEntryId, "ADD", newStart, newEnd,
                activityType != null ? activityType : "OTHER", label));
    }

    /**
     * Swaps the study items (item_type and item_id) between two sessions on the given date.
     * Keeps each slot's start and end times intact, thus preserving exact durations without distortion.
     * Both sessions are updated with status = 'MANUAL' and the schedule is replanned from date.
     * Rejects sessions that are not in PENDING or MANUAL state (e.g. DONE, CONTINUING, MISSED).
     */
    public void swapSessions(int semesterId, LocalDate date, int logId1, int logId2) throws SQLException, IllegalArgumentException {
        if (logId1 == logId2) {
            throw new IllegalArgumentException("Cannot swap a session with itself.");
        }

        DailyLogEntry log1 = dailyLogDAO.findById(logId1);
        DailyLogEntry log2 = dailyLogDAO.findById(logId2);

        if (log1 == null || log2 == null) {
            throw new IllegalArgumentException("One or both sessions could not be found.");
        }

        if (!log1.getLogDate().equals(date) || !log2.getLogDate().equals(date)) {
            throw new IllegalArgumentException("Sessions must both be scheduled on date " + date + ".");
        }

        if (!isEditable(log1)) {
            throw new IllegalArgumentException("Cannot swap session #" + logId1 + " with status '" + log1.getStatus() +
                    "'. Only PENDING or MANUAL sessions can be swapped.");
        }
        if (!isEditable(log2)) {
            throw new IllegalArgumentException("Cannot swap session #" + logId2 + " with status '" + log2.getStatus() +
                    "'. Only PENDING or MANUAL sessions can be swapped.");
        }

        // Swap the assigned item payload while preserving each slot's start/end times
        String itemType1 = log1.getItemType();
        int itemId1 = log1.getItemId();

        log1.setItemType(log2.getItemType());
        log1.setItemId(log2.getItemId());
        log1.setStatus("MANUAL");

        log2.setItemType(itemType1);
        log2.setItemId(itemId1);
        log2.setStatus("MANUAL");

        dailyLogDAO.update(log1);
        dailyLogDAO.update(log2);

        replanService.regenerateSemesterPlan(semesterId, date);
    }

    /**
     * Resizes a session duration by setting a new end time.
     * Validates that:
     * - The session is PENDING or MANUAL.
     * - newEnd is strictly after slotStart and duration is between 0.25 and 8.0 hours.
     * - [slotStart, newEnd] does not overlap any other scheduled session on date.
     * - [slotStart, newEnd] fits entirely inside an available base free slot on date
     *   (i.e. does not overlap fixed commitments or exam buffers).
     * Sets status = 'MANUAL' and replans from date.
     */
    public String resizeSession(int semesterId, LocalDate date, int logId, LocalTime newEnd) throws SQLException, IllegalArgumentException {
        DailyLogEntry log = dailyLogDAO.findById(logId);
        if (log == null) {
            throw new IllegalArgumentException("Session #" + logId + " not found.");
        }
        return resizeSession(semesterId, date, logId, log.getSlotStart(), newEnd);
    }

    /**
     * Resizes a session slot on the given date to explicit newStart and newEnd times.
     */
    public String resizeSession(int semesterId, LocalDate date, int logId, LocalTime newStart, LocalTime newEnd) throws SQLException, IllegalArgumentException {
        DailyLogEntry log = dailyLogDAO.findById(logId);
        if (log == null) {
            throw new IllegalArgumentException("Session #" + logId + " not found.");
        }

        if (!log.getLogDate().equals(date)) {
            throw new IllegalArgumentException("Session must be scheduled on date " + date + ".");
        }

        if (!isEditable(log)) {
            throw new IllegalArgumentException("Cannot resize session #" + logId + " with status '" + log.getStatus() +
                    "'. Only PENDING or MANUAL sessions can be resized.");
        }

        if (newStart == null) {
            throw new IllegalArgumentException("Session start time must not be null.");
        }
        if (newEnd == null || !newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException("New end time must be after slot start time (" + newStart + ").");
        }

        double durationHours = Duration.between(newStart, newEnd).toMinutes() / 60.0;
        if (durationHours < 0.25 || durationHours > 8.0) {
            throw new IllegalArgumentException("Session duration must be between 0.25 and 8.0 hours (requested: " +
                    String.format("%.2f", durationHours) + " h).");
        }

        // 1. Overlap check with other sessions today
        List<DailyLogEntry> todayLogs = dailyLogDAO.findByDate(date);
        for (DailyLogEntry other : todayLogs) {
            if (!other.getLogId().equals(logId) && other.getSlotStart() != null && other.getSlotEnd() != null) {
                if (newStart.isBefore(other.getSlotEnd()) && newEnd.isAfter(other.getSlotStart())) {
                    throw new IllegalArgumentException(String.format(
                            "Resized session [%s - %s] overlaps with existing scheduled session [%s - %s].",
                            newStart, newEnd, other.getSlotStart(), other.getSlotEnd()
                    ));
                }
            }
        }

        // 2. Base free slot containment check (cannot overlap fixed commitments or exam buffers)
        List<FreeSlot> freeSlots = getBaseFreeSlots(semesterId, date);
        boolean fitsInsideFreeSlot = false;
        for (FreeSlot fs : freeSlots) {
            if (!newStart.isBefore(fs.getStart()) && !newEnd.isAfter(fs.getEnd())) {
                fitsInsideFreeSlot = true;
                break;
            }
        }
        if (!fitsInsideFreeSlot) {
            throw new IllegalArgumentException(String.format(
                    "Resized session [%s - %s] extends into fixed commitments or exam buffer. Must fit inside available free time.",
                    newStart, newEnd
            ));
        }

        // Update the log after validation passes
        log.setSlotStart(newStart);
        log.setSlotEnd(newEnd);
        log.setStatus("MANUAL");
        dailyLogDAO.update(log);

        // Replan handles the freed hours through simulation - no need to update DB remaining_hours
        replanService.regenerateSemesterPlan(semesterId, date);

        // Check feasibility after shortening/resizing
        try {
            com.learntrack.dao.TopicDAO topicDAO = new com.learntrack.dao.TopicDAO();
            com.learntrack.dao.TimetableDAO timetableDAO = new com.learntrack.dao.TimetableDAO();
            FeasibilityService feasibilityService = new FeasibilityService(topicDAO, examDAO, timetableDAO, freeSlotService);
            FeasibilityService.FeasibilityResult feasResult = feasibilityService.calculateFeasibility(semesterId, date);
            if (!feasResult.isFeasible()) {
                return String.format("Warning: After resizing, required study hours (%s) exceed available capacity (%s) before exam deadlines.",
                        DurationFormat.format(feasResult.getNeededHours()), DurationFormat.format(feasResult.getCapacityHours()));
            }

            com.learntrack.dao.SettingsDAO settingsDAO = new com.learntrack.dao.SettingsDAO();
            ExamPeriodCapacityService capacityService = new ExamPeriodCapacityService(examDAO, topicDAO, timetableDAO, settingsDAO, examPeriodFreeSlotService);
            for (String batch : List.of("MST", "EST")) {
                if (capacityService.isBatchDated(semesterId, batch)) {
                    double required = capacityService.getRequiredHours(semesterId, batch, date);
                    double available = capacityService.getAvailableHours(semesterId, batch, date);
                    if (required > available) {
                        return String.format("Warning: After resizing, %s exam batch has a capacity deficit of %s (required: %s, available: %s).",
                                batch, DurationFormat.format(required - available), DurationFormat.format(required), DurationFormat.format(available));
                    }
                }
            }
        } catch (Exception ignored) {
            // Feasibility check should not block successful resize
        }

        return null;
    }

    public List<FreeSlot> getBaseFreeSlots(int semesterId, LocalDate date) throws SQLException {
        boolean inDatedWindow = examWindowService.isInDatedWindow(semesterId, date);

        if (inDatedWindow) {
            return examPeriodFreeSlotService.computeFreeSlots(semesterId, date);
        }
        return freeSlotService.computeFreeSlots(semesterId, date);
    }

    /**
     * Resizes multiple sessions on a date to a new duration.
     * Enforces:
     * - Minimum 15 minutes (0.25h), maximum 8.0 hours.
     * - Resized sessions and following sessions are shifted in order without overlapping fixed commitments or sleep.
     * - If a session cannot fit before the next fixed commitment/sleep, it is pushed after it.
     * - If any session cannot fit, the entire operation is rejected (all-or-nothing).
     * - Recalculates topic remaining hours: shorter session -> leftover returned to topic;
     *   longer session -> remaining hours reduced, never below 0.
     * - Replans from today/date and saves updated sessions as 'MANUAL'.
     */
    public void resizeMultipleSessions(int semesterId, LocalDate date, List<Integer> logIdsToResize, double newDurationHours) throws SQLException, IllegalArgumentException {
        if (logIdsToResize == null || logIdsToResize.isEmpty()) {
            throw new IllegalArgumentException("No sessions selected to resize.");
        }
        if (newDurationHours < 0.25 || newDurationHours > 8.0) {
            throw new IllegalArgumentException("Session duration must be between 0.25 and 8.0 hours (requested: " +
                    String.format("%.2f", newDurationHours) + " h).");
        }

        List<DailyLogEntry> allDayLogs = dailyLogDAO.findByDate(date);
        List<DailyLogEntry> studyLogs = new ArrayList<>();
        List<SlotMath.TimeInterval> doneIntervals = new ArrayList<>();
        for (DailyLogEntry l : allDayLogs) {
            if (l.getSlotStart() != null && l.getSlotEnd() != null) {
                if ("DONE".equalsIgnoreCase(l.getStatus())) {
                    doneIntervals.add(new SlotMath.TimeInterval(l.getSlotStart(), l.getSlotEnd()));
                } else {
                    studyLogs.add(l);
                }
            }
        }
        studyLogs.sort(Comparator.comparing(DailyLogEntry::getSlotStart));

        Set<Integer> resizeSet = new HashSet<>(logIdsToResize);
        long targetMinutes = Math.round(newDurationHours * 60.0);

        List<FreeSlot> freeSlots = getBaseFreeSlots(semesterId, date);
        if (!doneIntervals.isEmpty()) {
            freeSlots = SlotMath.subtractIntervals(freeSlots, doneIntervals);
        }
        if (freeSlots.isEmpty()) {
            throw new IllegalArgumentException("No available study slots on " + date + " to accommodate sessions.");
        }

        class Proposed {
            final DailyLogEntry log;
            final LocalTime start;
            final LocalTime end;
            final boolean isResized;

            Proposed(DailyLogEntry log, LocalTime start, LocalTime end, boolean isResized) {
                this.log = log;
                this.start = start;
                this.end = end;
                this.isResized = isResized;
            }
        }

        List<Proposed> proposedList = new ArrayList<>();
        LocalTime cursor = LocalTime.MIN;
        long shiftMinutes = 0;

        for (DailyLogEntry log : studyLogs) {
            boolean isTarget = resizeSet.contains(log.getLogId());
            long origDur = Duration.between(log.getSlotStart(), log.getSlotEnd()).toMinutes();
            long durMin = isTarget ? targetMinutes : origDur;

            LocalTime desiredStart;
            if (isTarget) {
                desiredStart = log.getSlotStart();
                shiftMinutes += (targetMinutes - origDur);
            } else {
                long origStartMins = log.getSlotStart().toSecondOfDay() / 60;
                long shiftedMins = Math.max(0, origStartMins + shiftMinutes);
                desiredStart = LocalTime.of((int)(shiftedMins / 60), (int)(shiftedMins % 60));
            }

            LocalTime candidateStart = (cursor != null && cursor.isAfter(desiredStart)) ? cursor : desiredStart;

            boolean placed = false;
            for (FreeSlot fs : freeSlots) {
                if (!fs.getEnd().isAfter(candidateStart)) {
                    continue;
                }
                LocalTime slotStart = candidateStart.isBefore(fs.getStart()) ? fs.getStart() : candidateStart;
                LocalTime slotEnd = slotStart.plusMinutes(durMin);

                if (!slotEnd.isAfter(fs.getEnd())) {
                    proposedList.add(new Proposed(log, slotStart, slotEnd, isTarget));
                    cursor = slotEnd;
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                throw new IllegalArgumentException(String.format(
                        "Cannot fit session '%s' [%s - %s] before fixed commitments or end of day after resize. All changes cancelled.",
                        log.getItemType(), log.getSlotStart(), log.getSlotStart().plusMinutes(durMin)
                ));
            }
        }

        // All sessions fit! Update remaining hours for resized sessions
        for (Proposed p : proposedList) {
            if (p.isResized) {
                double oldDuration = p.log.getDurationHours();
                double delta = newDurationHours - oldDuration;
                if (Math.abs(delta) > 0.001) {
                    if ("TOPIC_LEARN".equalsIgnoreCase(p.log.getItemType())) {
                        Topic t = topicDAO.findById(p.log.getItemId());
                        if (t != null) {
                            double newRem = Math.max(0.0, Math.min(t.getHoursNeeded(), t.getRemainingHours() - delta));
                            t.setRemainingHours(newRem);
                            topicDAO.update(t);
                        }
                    } else if ("TOPIC_REVISE".equalsIgnoreCase(p.log.getItemType())) {
                        Topic t = topicDAO.findById(p.log.getItemId());
                        if (t != null) {
                            double newRevRem = Math.max(0.0, Math.min(t.getRevisionHoursNeeded(), t.getRevisionRemainingHours() - delta));
                            t.setRevisionRemainingHours(newRevRem);
                            topicDAO.update(t);
                        }
                    } else if ("PYQ_PRACTICE".equalsIgnoreCase(p.log.getItemType())) {
                        Exam e = examDAO.findById(p.log.getItemId());
                        if (e != null) {
                            double newPyq = Math.max(0.0, e.getPyqRemainingHours() - delta);
                            e.setPyqRemainingHours(newPyq);
                            examDAO.update(e);
                        }
                    }
                }
            }
            p.log.setSlotStart(p.start);
            p.log.setSlotEnd(p.end);
            p.log.setStatus("MANUAL");
            dailyLogDAO.update(p.log);
        }

        LocalDate replanFrom = date.isBefore(LocalDate.now()) ? date : LocalDate.now();
        replanService.regenerateSemesterPlan(semesterId, replanFrom);
    }

    private boolean isEditable(DailyLogEntry log) {
        if (log == null || log.getStatus() == null) {
            return false;
        }
        String s = log.getStatus().toUpperCase();
        return "PENDING".equals(s) || "MANUAL".equals(s);
    }
}
