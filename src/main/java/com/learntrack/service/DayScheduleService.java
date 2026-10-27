package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Builds the complete merged daily schedule for a given date and semester:
 * fixed timetable commitments (classes, labs, tutorials, meals, sleep, exams),
 * date overrides (CANCEL and ADD), and scheduled study sessions (topics, skills, PYQ).
 */
public class DayScheduleService {

    private final TimetableDAO timetableDAO;
    private final DateOverrideDAO dateOverrideDAO;
    private final ExamDAO examDAO;
    private final DailyLogDAO dailyLogDAO;
    private final ExamPeriodRoutineDAO routineDAO;
    private final TopicDAO topicDAO;
    private final SkillDAO skillDAO;
    private final SkillSubtopicDAO skillSubtopicDAO;
    private final ExamScopeService examScopeService;
    private final ExamWindowService examWindowService;

    public DayScheduleService(TimetableDAO timetableDAO, DateOverrideDAO dateOverrideDAO,
                              ExamDAO examDAO, DailyLogDAO dailyLogDAO,
                              ExamPeriodRoutineDAO routineDAO, TopicDAO topicDAO,
                              SkillDAO skillDAO, SkillSubtopicDAO skillSubtopicDAO,
                              ExamScopeService examScopeService) {
        this.timetableDAO = timetableDAO;
        this.dateOverrideDAO = dateOverrideDAO;
        this.examDAO = examDAO;
        this.dailyLogDAO = dailyLogDAO;
        this.routineDAO = routineDAO;
        this.topicDAO = topicDAO;
        this.skillDAO = skillDAO;
        this.skillSubtopicDAO = skillSubtopicDAO != null ? skillSubtopicDAO : new SkillSubtopicDAO();
        this.examScopeService = examScopeService;
        this.examWindowService = new ExamWindowService();
    }

    public DayScheduleService(TimetableDAO timetableDAO, DateOverrideDAO dateOverrideDAO,
                              ExamDAO examDAO, DailyLogDAO dailyLogDAO,
                              ExamPeriodRoutineDAO routineDAO, TopicDAO topicDAO,
                              SkillDAO skillDAO, ExamScopeService examScopeService) {
        this(timetableDAO, dateOverrideDAO, examDAO, dailyLogDAO, routineDAO, topicDAO, skillDAO, new SkillSubtopicDAO(), examScopeService);
    }

    public DayScheduleService(TimetableDAO timetableDAO, DateOverrideDAO dateOverrideDAO,
                              ExamDAO examDAO, DailyLogDAO dailyLogDAO,
                              ExamPeriodRoutineDAO routineDAO, TopicDAO topicDAO,
                              SkillDAO skillDAO) {
        this(timetableDAO, dateOverrideDAO, examDAO, dailyLogDAO, routineDAO, topicDAO, skillDAO, new SkillSubtopicDAO(), new ExamScopeService());
    }

    public List<DayRow> build(int semesterId, LocalDate date) throws SQLException {
        return build(semesterId, date, false);
    }

    public List<DayRow> build(int semesterId, LocalDate date, boolean includeCancelled) throws SQLException {
        List<DayRow> rows = new ArrayList<>();

        boolean examPeriod = isExamPeriod(semesterId, date);

        if (examPeriod) {
            buildExamPeriodFixedRows(semesterId, date, rows);
        } else {
            buildNormalFixedRows(semesterId, date, rows, includeCancelled);
        }

        // Add study rows from daily_log
        buildStudyRows(date, rows);

        // Sort chronologically
        rows.sort((r1, r2) -> {
            int cmp = r1.getStart().compareTo(r2.getStart());
            if (cmp != 0) return cmp;
            return r1.getEnd().compareTo(r2.getEnd());
        });

        return rows;
    }

    private void buildExamPeriodFixedRows(int semesterId, LocalDate date, List<DayRow> rows) throws SQLException {
        List<ExamPeriodRoutineEntry> routine = routineDAO.findBySemester(semesterId);
        List<DateOverride> overrides = dateOverrideDAO.findByDate(date);

        // 1. Fixed routine entries (meals, sleep, other)
        if (routine != null && !routine.isEmpty()) {
            // Use explicit exam_period_routine entries
            for (ExamPeriodRoutineEntry entry : routine) {
                if (entry.getStartTime() == null || entry.getEndTime() == null) continue;

                String type = entry.getActivityType() != null ? entry.getActivityType().toUpperCase() : "OTHER";
                String label = entry.getLabel() != null && !entry.getLabel().isBlank() ? entry.getLabel() : type;

                if (entry.getStartTime().isAfter(entry.getEndTime())) {
                    // Crosses midnight, e.g. sleep 23:00 - 07:00
                    rows.add(new DayRow(type, LocalTime.MIN, entry.getEndTime(), label, null, true));
                    rows.add(new DayRow(type, entry.getStartTime(), LocalTime.MAX, label, null, true));
                } else if (!entry.getStartTime().equals(entry.getEndTime())) {
                    rows.add(new DayRow(type, entry.getStartTime(), entry.getEndTime(), label, null, true));
                }
            }
        } else {
            // Fallback: derive default routine from weekly data (shared logic with ExamPeriodFreeSlotService)
            DefaultExamRoutineProvider provider = new DefaultExamRoutineProvider(timetableDAO);
            List<DefaultExamRoutineProvider.DefaultRoutineBlock> blocks = provider.deriveRoutineBlocks(semesterId, date);
            for (DefaultExamRoutineProvider.DefaultRoutineBlock block : blocks) {
                rows.add(new DayRow(block.getActivityType(), block.getStartTime(), block.getEndTime(), 
                                   block.getLabel(), null, true));
            }
        }

        // 2. Dated exams on date with asymmetric buffer: [start - travel, end + 1h]
        addDatedExamsOnDate(semesterId, date, rows);

        // 3. Date overrides: ADD overrides apply in exam window
        for (DateOverride ov : overrides) {
            if ("ADD".equalsIgnoreCase(ov.getAction()) && ov.getStartTime() != null && ov.getEndTime() != null) {
                String label = ov.getLabel() != null && !ov.getLabel().isBlank() ? ov.getLabel() : "Override";
                String type = ov.getActivityType() != null && !ov.getActivityType().isBlank()
                        ? ov.getActivityType().toUpperCase() : "OTHER";
                rows.add(new DayRow(type, ov.getStartTime(), ov.getEndTime(), label, null, true));
            }
        }
    }

    private void buildNormalFixedRows(int semesterId, LocalDate date, List<DayRow> rows, boolean includeCancelled) throws SQLException {
        String dayOfWeek = date.getDayOfWeek().name();
        String prevDayOfWeek = date.minusDays(1).getDayOfWeek().name();

        List<WeeklyTimetableEntry> entries = timetableDAO.findBySemesterAndDay(semesterId, dayOfWeek);
        List<WeeklyTimetableEntry> prevEntries = timetableDAO.findBySemesterAndDay(semesterId, prevDayOfWeek);
        List<WeekdayRoutine> routines = timetableDAO.findRoutinesBySemester(semesterId);

        List<DateOverride> overrides = dateOverrideDAO.findByDate(date);
        List<DateOverride> prevOverrides = dateOverrideDAO.findByDate(date.minusDays(1));

        Set<Integer> cancelledEntryIds = new HashSet<>();
        for (DateOverride ov : overrides) {
            if ("CANCEL".equalsIgnoreCase(ov.getAction()) && ov.getEntryId() != null) {
                cancelledEntryIds.add(ov.getEntryId());
            }
        }

        Set<Integer> prevCancelledEntryIds = new HashSet<>();
        for (DateOverride ov : prevOverrides) {
            if ("CANCEL".equalsIgnoreCase(ov.getAction()) && ov.getEntryId() != null) {
                prevCancelledEntryIds.add(ov.getEntryId());
            }
        }

        boolean routineApplied = false;

        // 1. Wake / Sleep Routine
        if (routines != null && !routines.isEmpty()) {
            Map<String, WeekdayRoutine> routineMap = new HashMap<>();
            for (WeekdayRoutine r : routines) {
                routineMap.put(r.getDayOfWeek().toUpperCase(), r);
            }
            WeekdayRoutine currentRoutine = routineMap.get(dayOfWeek.toUpperCase());
            WeekdayRoutine prevRoutine = routineMap.get(prevDayOfWeek.toUpperCase());

            if (currentRoutine != null) {
                routineApplied = true;
                LocalTime currentWake = currentRoutine.getWakeTime();
                LocalTime currentSleep = currentRoutine.getSleepTime();

                // Morning sleep block on date
                if (prevRoutine != null && !prevRoutine.getSleepTime().isAfter(prevRoutine.getWakeTime())) {
                    if (prevRoutine.getSleepTime().isBefore(currentWake)) {
                        rows.add(new DayRow("SLEEP", prevRoutine.getSleepTime(), currentWake, "Sleep", null, true));
                    }
                } else {
                    if (currentWake != null && currentWake.isAfter(LocalTime.MIN)) {
                        rows.add(new DayRow("SLEEP", LocalTime.MIN, currentWake, "Sleep", null, true));
                    }
                }

                // Evening sleep block on date
                if (currentSleep != null && currentWake != null && currentSleep.isAfter(currentWake)) {
                    rows.add(new DayRow("SLEEP", currentSleep, LocalTime.MAX, "Sleep", null, true));
                }
            }
        }

        // 2. Commitments for date (dayOfWeek)
        for (WeeklyTimetableEntry entry : entries) {
            boolean isCancelled = entry.getEntryId() != null && cancelledEntryIds.contains(entry.getEntryId());
            if (isCancelled && !includeCancelled) {
                continue; // Cancelled
            }
            if (routineApplied && "SLEEP".equalsIgnoreCase(entry.getActivityType())) {
                continue; // Handled by weekday_routine
            }
            if (entry.getStartTime() != null && entry.getEndTime() != null) {
                String type = entry.getActivityType() != null ? entry.getActivityType().toUpperCase() : "CLASS";
                String label = entry.getLabel() != null && !entry.getLabel().isBlank() ? entry.getLabel() : type;

                DayRow row;
                if (entry.getEndTime().isAfter(entry.getStartTime())) {
                    row = new DayRow(type, entry.getStartTime(), entry.getEndTime(), label, null, true);
                } else if (!entry.getStartTime().equals(entry.getEndTime())) {
                    row = new DayRow(type, entry.getStartTime(), LocalTime.MAX, label, null, true);
                } else {
                    continue;
                }
                row.setTimetableEntryId(entry.getEntryId());
                row.setCancelled(isCancelled);
                rows.add(row);
            }
        }

        // 3. Commitments from prevDate spilling into date
        for (WeeklyTimetableEntry prevEntry : prevEntries) {
            boolean isCancelled = prevEntry.getEntryId() != null &&
                    (cancelledEntryIds.contains(prevEntry.getEntryId()) || prevCancelledEntryIds.contains(prevEntry.getEntryId()));
            if (isCancelled && !includeCancelled) {
                continue; // Cancelled
            }
            if (routineApplied && "SLEEP".equalsIgnoreCase(prevEntry.getActivityType())) {
                continue; // Handled by weekday_routine
            }
            if (prevEntry.getStartTime() != null && prevEntry.getEndTime() != null) {
                if (!prevEntry.getEndTime().isAfter(prevEntry.getStartTime()) && !prevEntry.getStartTime().equals(prevEntry.getEndTime())) {
                    if (prevEntry.getEndTime().isAfter(LocalTime.MIN)) {
                        String type = prevEntry.getActivityType() != null ? prevEntry.getActivityType().toUpperCase() : "CLASS";
                        String label = prevEntry.getLabel() != null && !prevEntry.getLabel().isBlank() ? prevEntry.getLabel() : type;
                        DayRow row = new DayRow(type, LocalTime.MIN, prevEntry.getEndTime(), label, null, true);
                        row.setTimetableEntryId(prevEntry.getEntryId());
                        row.setCancelled(isCancelled);
                        rows.add(row);
                    }
                }
            }
        }

        // 4. Dated exams on date with asymmetric buffers
        addDatedExamsOnDate(semesterId, date, rows);

        // 5. Date overrides: ADD overrides (a resized commitment keeps its own activity type)
        for (DateOverride ov : overrides) {
            if ("ADD".equalsIgnoreCase(ov.getAction()) && ov.getStartTime() != null && ov.getEndTime() != null) {
                String label = ov.getLabel() != null && !ov.getLabel().isBlank() ? ov.getLabel() : "Override";
                String type = ov.getActivityType() != null && !ov.getActivityType().isBlank()
                        ? ov.getActivityType().toUpperCase() : "OTHER";
                rows.add(new DayRow(type, ov.getStartTime(), ov.getEndTime(), label, null, true));
            }
        }
    }

    private void addDatedExamsOnDate(int semesterId, LocalDate date, List<DayRow> rows) throws SQLException {
        List<Exam> examsToday = examDAO.findDatedOnDate(semesterId, date);
        for (Exam exam : examsToday) {
            if (exam.getExamStartTime() != null && exam.getExamEndTime() != null) {
                LocalTime start = exam.getExamStartTime();
                int travel = exam.getTravelTimeMinutes();
                LocalTime bufferedStart = (start.toSecondOfDay() < travel * 60) ? LocalTime.MIN : start.minusMinutes(travel);

                LocalTime end = exam.getExamEndTime();
                LocalTime bufferedEnd = (end.toSecondOfDay() + 3600 >= 86400) ? LocalTime.MAX : end.plusHours(1);

                String label = exam.getSubject() + (exam.getExamType() != null ? " " + exam.getExamType() : "") + " Exam";
                rows.add(new DayRow("EXAM", bufferedStart, bufferedEnd, label, null, true));
            }
        }
    }

    private void buildStudyRows(LocalDate date, List<DayRow> rows) throws SQLException {
        List<DailyLogEntry> logs = dailyLogDAO.findByDate(date);
        for (DailyLogEntry entry : logs) {
            String label = formatStudyLabel(entry);
            rows.add(new DayRow(entry.getItemType(), entry.getSlotStart(), entry.getSlotEnd(), label, entry, false));
        }
    }

    public String formatStudyLabel(DailyLogEntry entry) {
        String itemType = entry.getItemType();
        int itemId = entry.getItemId();
        try {
            if ("TOPIC_LEARN".equalsIgnoreCase(itemType)) {
                Topic t = topicDAO.findById(itemId);
                if (t != null) {
                    Exam ex = examDAO.findById(t.getExamId());
                    String subj = ex != null && ex.getSubject() != null ? ex.getSubject() : "";
                    return (subj.isEmpty() ? "" : subj + ": ") + t.getName() + " (Learn)";
                }
                return "Topic #" + itemId + " (Learn)";
            } else if ("TOPIC_REVISE".equalsIgnoreCase(itemType)) {
                Topic t = topicDAO.findById(itemId);
                if (t != null) {
                    Exam ex = examDAO.findById(t.getExamId());
                    String subj = ex != null && ex.getSubject() != null ? ex.getSubject() : "";
                    String badge = (examScopeService != null) ? examScopeService.getCoveredBeforeBadgeForTopic(t) : null;
                    if (badge != null && !badge.isBlank()) {
                        return (subj.isEmpty() ? "" : subj + ": ") + t.getName() + " (Revise): " + badge;
                    }
                    return (subj.isEmpty() ? "" : subj + ": ") + t.getName() + " (Revise)";
                }
                return "Topic #" + itemId + " (Revise)";
            } else if ("PYQ_PRACTICE".equalsIgnoreCase(itemType)) {
                Exam ex = examDAO.findById(itemId);
                if (ex != null && ex.getSubject() != null) {
                    return "PYQs: " + ex.getSubject();
                }
                return "PYQs: Exam #" + itemId;
            } else if ("SKILL_LEARN".equalsIgnoreCase(itemType) || "SKILL_REVISE".equalsIgnoreCase(itemType)) {
                if (skillSubtopicDAO != null) {
                    SkillSubtopic st = skillSubtopicDAO.findById(itemId);
                    if (st != null) {
                        Skill s = skillDAO.findById(st.getSkillId());
                        String sName = (s != null && s.getName() != null) ? s.getName() : "Skill";
                        String mode = "SKILL_LEARN".equalsIgnoreCase(itemType) ? "Learn" : "Revise";
                        return "Skill: " + sName + " › " + st.getName() + " (" + mode + ")";
                    }
                }
                return itemType + " #" + itemId;
            } else if ("SKILL".equalsIgnoreCase(itemType)) {
                Skill s = skillDAO.findById(itemId);
                if (s != null && s.getName() != null) {
                    return "Skill: " + s.getName();
                }
                return "Skill #" + itemId;
            }
        } catch (SQLException e) {
            // fallback
        }
        return itemType + " #" + itemId;
    }

    private boolean isExamPeriod(int semesterId, LocalDate date) throws SQLException {
        return examWindowService.isInDatedWindow(semesterId, date);
    }
}
