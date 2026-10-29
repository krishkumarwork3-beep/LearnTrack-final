package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.*;
import com.learntrack.util.DateUtils;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Regenerates the entire semester plan from fromDate through the horizon
 * using day-by-day simulated state progression.
 * Plan Section 6, 7, 8, 9 & User Guide Section 2, 4, 5, 6.
 */
public class SemesterReplanService {

    private final TimetableDAO timetableDAO;
    private final ExamDAO examDAO;
    private final TopicDAO topicDAO;
    private final SkillDAO skillDAO;
    private final SkillSubtopicDAO skillSubtopicDAO;
    private final DailyLogDAO dailyLogDAO;
    private final SettingsDAO settingsDAO;
    private final FreeSlotService freeSlotService;
    private final ExamPeriodFreeSlotService examPeriodFreeSlotService;
    private final ScoringService scoringService;
    private final PriorityQueueService priorityQueueService;
    private final ExamPeriodRoutineDAO routineDAO;
    private final SyllabusDAO syllabusDAO;
    private final ExamWindowService examWindowService;
    private java.util.function.Supplier<LocalTime> currentTimeSupplier = null;
    /** A session that splits an item is at least this long (30 min)... */
    static final double MIN_SPLIT_SESSION = 0.5;
    /** ...and leaves at least this much (30 min) for the next session, never a 10-minute scrap. */
    static final double MIN_REMAINDER = 0.5;
    /**
     * How long to study an item that still needs {@code need} hours in a gap of {@code room} hours,
     * or 0 when it should not start in this gap. An item is done in one sitting when it fits;
     * otherwise it is split into real pieces (30 min or more now, 30 min or more left), so a 1-hour
     * topic is never cut into 50 + 10 minutes and never gets more time than it needs.
     */
    static double sessionLength(double need, double room, double cap, double minSession) {
        return sessionLength(need, room, cap, minSession, false);
    }

    /**
     * As above; a {@code flexible} item (a revision pass, PYQ practice) can use any gap of at least
     * the minimum session, so short gaps between classes are still used for study.
     */
    static double sessionLength(double need, double room, double cap, double minSession, boolean flexible) {
        double alloc = Math.min(need, Math.min(cap, room));
        if (flexible) {
            return alloc >= minSession - 1e-9 ? alloc : 0;
        }
        if (alloc < minSession - 1e-9 || alloc <= 0) return 0;
        if (need - alloc > 0.01) {                         // the item would be split
            if (need - alloc < MIN_REMAINDER) alloc = need - MIN_REMAINDER;
            if (alloc < MIN_SPLIT_SESSION - 1e-9) return 0;
        }
        return alloc;
    }

    /** Outcome of the coverage optimizer from the most recent replan; read by the dashboard. */
    private volatile CoveragePlan lastCoveragePlan;

    public CoveragePlan getLastCoveragePlan() {
        return lastCoveragePlan;
    }

    /**
     * Free study slots of one day: exam-period routine inside a dated MST/EST window, weekly
     * timetable otherwise; on the first planned day, rows that survive the replan and (for today)
     * the time already gone are removed.
     */
    private List<FreeSlot> computeDayFreeSlots(int semesterId, LocalDate date, LocalDate fromDate,
                                               String datedBatch, List<SlotMath.TimeInterval> occupiedToday)
            throws SQLException {
        // Inside any dated exam window: always use exam-period free slots
        // (ExamPeriodFreeSlotService derives a default from the weekly routine when
        // the user's exam_period_routine table is empty, so no class entries are included)
        List<FreeSlot> freeSlots = datedBatch != null
                ? examPeriodFreeSlotService.computeFreeSlots(semesterId, date)
                : freeSlotService.computeFreeSlots(semesterId, date);

        // Item 2: On fromDate, subtract surviving rows' occupied intervals from free slots
        if (date.equals(fromDate) && !occupiedToday.isEmpty()) {
            freeSlots = SlotMath.subtractIntervals(freeSlots, occupiedToday);
        }

        // On today's date, only slots at or after current time may receive new study sessions
        if (currentTimeSupplier != null && date.equals(LocalDate.now())) {
            LocalTime now = currentTimeSupplier.get();
            if (now != null && now.isAfter(LocalTime.MIN)) {
                freeSlots = SlotMath.subtractIntervals(freeSlots, List.of(new SlotMath.TimeInterval(LocalTime.MIN, now)));
            }
        }
        return freeSlots;
    }

    public void setCurrentTimeSupplier(java.util.function.Supplier<LocalTime> supplier) {
        this.currentTimeSupplier = supplier;
    }

    public SemesterReplanService(TimetableDAO timetableDAO, ExamDAO examDAO, TopicDAO topicDAO,
                                 SkillDAO skillDAO, DailyLogDAO dailyLogDAO, SettingsDAO settingsDAO,
                                 FreeSlotService freeSlotService, ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                 ScoringService scoringService, PriorityQueueService priorityQueueService) {
        this(timetableDAO, examDAO, topicDAO, skillDAO, new SkillSubtopicDAO(), dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService,
                new ExamPeriodRoutineDAO(), new SyllabusDAO());
    }

    public SemesterReplanService(TimetableDAO timetableDAO, ExamDAO examDAO, TopicDAO topicDAO,
                                 SkillDAO skillDAO, SkillSubtopicDAO skillSubtopicDAO, DailyLogDAO dailyLogDAO, SettingsDAO settingsDAO,
                                 FreeSlotService freeSlotService, ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                 ScoringService scoringService, PriorityQueueService priorityQueueService) {
        this(timetableDAO, examDAO, topicDAO, skillDAO, skillSubtopicDAO, dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService,
                new ExamPeriodRoutineDAO(), new SyllabusDAO());
    }

    public SemesterReplanService(TimetableDAO timetableDAO, ExamDAO examDAO, TopicDAO topicDAO,
                                 SkillDAO skillDAO, DailyLogDAO dailyLogDAO, SettingsDAO settingsDAO,
                                 FreeSlotService freeSlotService, ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                 ScoringService scoringService, PriorityQueueService priorityQueueService,
                                 ExamPeriodRoutineDAO routineDAO) {
        this(timetableDAO, examDAO, topicDAO, skillDAO, new SkillSubtopicDAO(), dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService,
                routineDAO, new SyllabusDAO());
    }

    public SemesterReplanService(TimetableDAO timetableDAO, ExamDAO examDAO, TopicDAO topicDAO,
                                 SkillDAO skillDAO, DailyLogDAO dailyLogDAO, SettingsDAO settingsDAO,
                                 FreeSlotService freeSlotService, ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                 ScoringService scoringService, PriorityQueueService priorityQueueService,
                                 ExamPeriodRoutineDAO routineDAO, SyllabusDAO syllabusDAO) {
        this(timetableDAO, examDAO, topicDAO, skillDAO, new SkillSubtopicDAO(), dailyLogDAO, settingsDAO,
                freeSlotService, examPeriodFreeSlotService, scoringService, priorityQueueService,
                routineDAO, syllabusDAO);
    }

    public SemesterReplanService(TimetableDAO timetableDAO, ExamDAO examDAO, TopicDAO topicDAO,
                                 SkillDAO skillDAO, SkillSubtopicDAO skillSubtopicDAO, DailyLogDAO dailyLogDAO, SettingsDAO settingsDAO,
                                 FreeSlotService freeSlotService, ExamPeriodFreeSlotService examPeriodFreeSlotService,
                                 ScoringService scoringService, PriorityQueueService priorityQueueService,
                                 ExamPeriodRoutineDAO routineDAO, SyllabusDAO syllabusDAO) {
        this.timetableDAO = timetableDAO;
        this.examDAO = examDAO;
        this.topicDAO = topicDAO;
        this.skillDAO = skillDAO;
        this.skillSubtopicDAO = skillSubtopicDAO != null ? skillSubtopicDAO : new SkillSubtopicDAO();
        this.dailyLogDAO = dailyLogDAO;
        this.settingsDAO = settingsDAO;
        this.freeSlotService = freeSlotService;
        this.examPeriodFreeSlotService = examPeriodFreeSlotService;
        this.scoringService = scoringService;
        this.priorityQueueService = priorityQueueService;
        this.routineDAO = routineDAO;
        this.syllabusDAO = syllabusDAO != null ? syllabusDAO : new SyllabusDAO();
        this.examWindowService = new ExamWindowService();
    }

    /** Convenience: replan from today's date. */
    public void replanFromToday(int semesterId) throws SQLException {
        regenerateSemesterPlan(semesterId, LocalDate.now());
    }

    public void regenerateSemesterPlan(int semesterId, LocalDate fromDate) throws SQLException {
        // Mark past uncompleted study rows as MISSED
        dailyLogDAO.markMissedBeforeDate(fromDate);

        // Purge legacy 'SKILL' rows (pre-subtopic format) that have no subtopic_id,
        // leaving DONE history intact; only pending/continuing rows are removed.
        dailyLogDAO.purgeLegacySkillRows();

        // Sync covered-before phases across all exams of the semester
        new ExamScopeService().syncAllTopicPhases(semesterId);

        // Fix 5: Reset important_flag BEFORE loading topics so the current run benefits from the reset
        List<Exam> allExamsForReset = examDAO.findBySemester(semesterId);
        checkAndResetBatchImportantFlags(semesterId, allExamsForReset, fromDate);

        // 1. Lock history before fromDate
        // Deletion and re-insertion will happen atomically at the end in replaceFutureRows.

        // Fetch current active semester state
        List<Exam> allExams = examDAO.findBySemester(semesterId);
        List<Topic> allTopics = topicDAO.findBySemester(semesterId);
        List<Skill> allSkills = skillDAO.getAll();
        List<SkillSubtopic> allSubtopics = skillSubtopicDAO.findAll();

        double skillSessionLength = settingsDAO.getDouble("skill_session_length", 1.0);
        double revisionMajorityRatio = settingsDAO.getDouble("revision_majority_ratio", 0.8);
        int skillLookaheadDays = settingsDAO.getInt("skill_lookahead_days", 14);
        double maxSessionTopic = 2.0;

        // Create lightweight simulated working copies (Plan §6 & Fix Pass Item 3)
        Map<Integer, Long> orderMap = syllabusDAO.getSyllabusOrderMap(semesterId);
        Map<Integer, Integer> importanceMap = syllabusDAO.getImportanceMap(semesterId);
        List<Topic> simTopics = new ArrayList<>();
        Map<Integer, Topic> simTopicMap = new HashMap<>();
        for (Topic t : allTopics) {
            // The syllabus subtopic is the single source of truth for importance.
            int importance = t.getSubtopicId() != null && importanceMap.containsKey(t.getSubtopicId())
                    ? importanceMap.get(t.getSubtopicId())
                    : Math.max(1, Math.min(5, t.getImportance()));
            Topic copy = new Topic(
                    t.getTopicId(), t.getExamId(), t.getName(), importance, t.getDifficulty(),
                    t.getHoursNeeded(), t.getRemainingHours(), t.getPhase(), t.getRevisionHoursNeeded(),
                    t.getRevisionRemainingHours(), t.getRevisionIntervalDays(), t.getLastRevised(),
                    t.getImportantFlag(), t.getSubtopicId()
            );
            if (t.getSubtopicId() != null && orderMap.containsKey(t.getSubtopicId())) {
                copy.setSyllabusOrder(orderMap.get(t.getSubtopicId()));
            } else {
                copy.setSyllabusOrder(Long.MAX_VALUE);
            }
            simTopics.add(copy);
            simTopicMap.put(copy.getTopicId(), copy);
        }
        // The optimizer overwrites syllabusOrder with a plan rank; keep the real positions and each
        // exam's subject so learning can follow the syllabus across a subject's exams.
        syllabusPositionByTopic = new HashMap<>();
        for (Topic t : simTopics) {
            if (t.getSyllabusOrder() != Long.MAX_VALUE) syllabusPositionByTopic.put(t.getTopicId(), t.getSyllabusOrder());
        }
        subjectByExam = new HashMap<>();
        for (Exam e : allExams) {
            String key = e.getSubjectCode() != null && !e.getSubjectCode().isBlank() ? e.getSubjectCode() : e.getSubject();
            if (key != null) subjectByExam.put(e.getExamId(), key.trim());
        }

        List<Exam> simExams = new ArrayList<>();
        Map<Integer, Exam> simExamMap = new HashMap<>();
        for (Exam e : allExams) {
            Exam copy = new Exam(
                    e.getExamId(), e.getSemesterId(), e.getSubject(), e.getExamType(),
                    e.getCredits(), e.getEvalMarks(), e.getExamDate(), e.getExamStartTime(),
                    e.getExamEndTime(), e.getTravelTimeMinutes(), e.getWindowStartDate(),
                    e.getWindowEndDate(), e.getPyqRemainingHours()
            );
            simExams.add(copy);
            simExamMap.put(copy.getExamId(), copy);
        }

        List<Skill> simSkills = new ArrayList<>();
        Map<Integer, Skill> simSkillMap = new HashMap<>();
        for (Skill s : allSkills) {
            Skill copy = new Skill(
                    s.getSkillId(), s.getName(), s.getCategory(), s.getProficiency(), s.getLastPracticed()
            );
            copy.setPriority(s.getPriority());
            copy.setTargetDate(s.getTargetDate());
            simSkills.add(copy);
            simSkillMap.put(copy.getSkillId(), copy);
        }

        List<SkillSubtopic> simSubtopics = new ArrayList<>();
        Map<Integer, SkillSubtopic> simSubtopicMap = new HashMap<>();
        for (SkillSubtopic st : allSubtopics) {
            SkillSubtopic copy = new SkillSubtopic(
                    st.getSubtopicId(), st.getSkillId(), st.getName(), st.getPosition(),
                    st.getLearnHours(), st.getLearnRemainingHours(),
                    st.getReviseHours(), st.getReviseRemainingHours(),
                    st.getReviseAfterDays(), st.isLearned(), st.getLearnedOn(),
                    st.isRevised(), st.getRevisedOn(), st.getRevisionDueDate()
            );
            simSubtopics.add(copy);
            simSubtopicMap.put(copy.getSubtopicId(), copy);
        }

        // Account for surviving rows on fromDate (DONE, MISSED, MANUAL)
        // Since future rows are not deleted early, findByDate returns all rows on fromDate.
        // Filter out PENDING, CONTINUING, and PLANNED rows (which will be replaced), matching exactly
        // what clearFutureRows deletes on fromDate so today's budget is not wrongly shrunken.
        List<DailyLogEntry> allToday = dailyLogDAO.findByDate(fromDate);
        List<DailyLogEntry> survivingToday = new ArrayList<>();
        for (DailyLogEntry row : allToday) {
            String st = row.getStatus();
            if (st != null && (st.equalsIgnoreCase("PENDING") || st.equalsIgnoreCase("CONTINUING") || st.equalsIgnoreCase("PLANNED"))) {
                continue;
            }
            survivingToday.add(row);
        }
        double survivingTodayHours = 0.0;
        List<SlotMath.TimeInterval> occupiedToday = new ArrayList<>();

        for (DailyLogEntry row : survivingToday) {
            survivingTodayHours += row.getDurationHours();
            if (row.getSlotStart() != null && row.getSlotEnd() != null) {
                occupiedToday.add(new SlotMath.TimeInterval(row.getSlotStart(), row.getSlotEnd()));
            }

            // MANUAL rows consume simulated topic/pyq/skill hours (Item 9)
            if ("MANUAL".equalsIgnoreCase(row.getStatus())) {
                consumeSessionHours(row.getItemType(), row.getItemId(), row.getDurationHours(),
                        fromDate, simTopicMap, simExamMap, simSkillMap, simSubtopicMap);
            }
        }

        // 4. Determine horizon
        List<Exam> unfinishedExams = new ArrayList<>();
        LocalDate latestDeadline = null;
        for (Exam e : simExams) {
            if (e.isUnfinished(fromDate)) {
                unfinishedExams.add(e);
                LocalDate d = scoringService.effectiveDeadline(e, fromDate);
                if (latestDeadline == null || d.isAfter(latestDeadline)) {
                    latestDeadline = d;
                }
            }
        }

        LocalDate horizon;
        if (!unfinishedExams.isEmpty() && latestDeadline != null) {
            horizon = latestDeadline.isBefore(fromDate) ? fromDate : latestDeadline;
        } else {
            horizon = fromDate.plusDays(skillLookaheadDays);
        }

        // Determine dated MST/EST windows via ExamWindowService (uses stored sheet dates)

        // Fix 6: Cache settings once per replan (not once per day)
        double extraHoursMST = settingsDAO.getDouble("exam_period_extra_hours_MST", 0.0);
        double extraHoursEST = settingsDAO.getDouble("exam_period_extra_hours_EST", 0.0);
        int breakMinutes = (int) settingsDAO.getDouble("break_minutes", 0.0); // Default 0 (off)
        double minSessionHours = settingsDAO.getDouble("min_session_minutes", 15.0) / 60.0; // Default 15 min

        List<DailyLogEntry> allEntries = new ArrayList<>();

        // 5a. Free time for every day of the horizon, computed once: the coverage optimizer needs
        // the whole picture up front, and the day walk below reuses the same slots.
        Map<LocalDate, List<FreeSlot>> freeSlotsByDate = new HashMap<>();
        Map<LocalDate, String> datedBatchByDate = new HashMap<>();
        Map<LocalDate, Double> studyBudgetByDate = new LinkedHashMap<>();
        for (LocalDate d = fromDate; !d.isAfter(horizon); d = d.plusDays(1)) {
            // Use ExamWindowService: whole-sheet window, start = first exam date (not today)
            String datedBatch;
            try {
                datedBatch = examWindowService.examTypeOnDate(semesterId, d).orElse(null);
            } catch (SQLException ex) {
                datedBatch = null;
            }
            List<FreeSlot> freeSlots = computeDayFreeSlots(semesterId, d, fromDate, datedBatch, occupiedToday);
            double totalFree = 0.0;
            for (FreeSlot fs : freeSlots) {
                totalFree += fs.getDuration();
            }
            // NO daily study limit - always use ALL free time (+ the exam-period extra hours)
            double budget = totalFree;
            if (datedBatch != null) {
                budget += "MST".equalsIgnoreCase(datedBatch) ? extraHoursMST : extraHoursEST;
            }
            if (d.equals(fromDate)) {
                budget = Math.max(0.0, budget - survivingTodayHours);
            }
            freeSlotsByDate.put(d, freeSlots);
            datedBatchByDate.put(d, datedBatch);
            studyBudgetByDate.put(d, budget);
        }

        // 5b. Decide which topics fit before their exams, weighted by importance (see CoverageOptimizer).
        // Learning always follows the syllabus order and uses all free time; importance only
        // orders revision.
        lastCoveragePlan = new CoverageOptimizer(scoringService)
                .optimize(simTopics, simExams, studyBudgetByDate, fromDate, 1.0, CoverageOptimizer.OrderMode.SYLLABUS);

        // 5c. Walk each day from fromDate to horizon
        LocalDate date = fromDate;
        while (!date.isAfter(horizon)) {
            List<FreeSlot> freeSlots = freeSlotsByDate.get(date);

            double totalFreeTime = 0.0;
            for (FreeSlot fs : freeSlots) {
                totalFreeTime += fs.getDuration();
            }
            double studyBudget = studyBudgetByDate.get(date);

            // Item 3: Rebuild queue for THIS specific date from current simulated state
            PriorityQueue<PriorityItem> queue = priorityQueueService.build(
                    simTopics, simSkills, simSubtopics, simExams, date, skillSessionLength
            );

            List<DailyLogEntry> dailySchedule = new ArrayList<>();

            // Identify eve and clash exams for date
            List<Exam> normalEveExams = getNormalExamsWithEveOn(simExams, date);
            List<Exam> clashExams = getClashExamsOn(simExams, date);

            if (!normalEveExams.isEmpty() && totalFreeTime > 0.05) {
                // Item 4: Eve of exam majority revision rule (uncapped by weekday preference)
                dailySchedule = fillEveDayWithSimulation(
                        freeSlots, totalFreeTime, normalEveExams, clashExams,
                        revisionMajorityRatio, simTopics, simExams, queue,
                        skillSessionLength, maxSessionTopic, date, simTopicMap, simExamMap, simSkillMap, simSubtopicMap,
                        simSubtopics, breakMinutes, minSessionHours
                );
            } else if (clashExams.size() >= 2 && totalFreeTime > 0.05) {
                // Item 4: Same-day exam clash split (uncapped by weekday preference)
                dailySchedule = fillClashDayWithSimulation(
                        freeSlots, totalFreeTime, clashExams,
                        simTopics, simExams, queue, skillSessionLength, maxSessionTopic, date,
                        simTopicMap, simExamMap, simSkillMap, simSubtopicMap,
                        simSubtopics, breakMinutes, minSessionHours
                );
            } else {
                // Normal day
                dailySchedule = fillDayWithSimulation(
                        freeSlots, studyBudget, queue, skillSessionLength, maxSessionTopic, date,
                        simTopicMap, simExamMap, simSkillMap, simSubtopicMap, simSubtopics, breakMinutes, minSessionHours
                );
            }

            if (!dailySchedule.isEmpty()) {
                allEntries.addAll(dailySchedule);
            }

            date = date.plusDays(1);
        }

        // Single atomic database write: clear future rows and insert all new entries
        dailyLogDAO.replaceFutureRows(fromDate, allEntries);

        // Fix 5: important_flag reset already ran at the top of this method, so no repeat is needed here.
    }


    /**
     * Normal single-day filling, advancing simulated state as sessions are allocated.
     * Interleaves subjects across the day to prevent dedicating the whole day to one subject.
     */
    private List<DailyLogEntry> fillDayWithSimulation(List<FreeSlot> freeSlots, double studyBudget,
                                                      PriorityQueue<PriorityItem> queue,
                                                      double skillSessionLength, double maxSessionTopic,
                                                      LocalDate date,
                                                      Map<Integer, Topic> simTopicMap,
                                                      Map<Integer, Exam> simExamMap,
                                                      Map<Integer, Skill> simSkillMap,
                                                      Map<Integer, SkillSubtopic> simSubtopicMap,
                                                      List<SkillSubtopic> simSubtopics,
                                                      int breakMinutes,
                                                      double minSessionHours) {
        List<DailyLogEntry> schedule = new ArrayList<>();
        if (freeSlots == null || freeSlots.isEmpty() || studyBudget <= 0.05) {
            return schedule;
        }

        Set<Integer> scheduledSkillIdsToday = new HashSet<>();
        String lastScheduledSubject = null;
        String defaultStatus = date.isAfter(LocalDate.now()) ? "PLANNED" : "PENDING";
        List<SlotMath.TimeInterval> breaks = new ArrayList<>();

        if (queue != null && !queue.isEmpty()) {
            for (FreeSlot slot : freeSlots) {
                LocalTime slotStart = slot.getStart();
                double remainingSlot = slot.getDuration();
                List<PriorityItem> deferredInSlot = new ArrayList<>();

                while (remainingSlot >= minSessionHours && studyBudget >= minSessionHours && !queue.isEmpty()) {
                    PriorityItem item = pollInterleavedItem(queue, lastScheduledSubject, simExamMap, scheduledSkillIdsToday, simTopicMap, simSubtopicMap);
                    if (item == null) {
                        break;
                    }
                    double simRemainingBeforeAlloc = item.getRemainingHours();
                    double cap = item.isSkill() ? skillSessionLength : maxSessionTopic;
                    double alloc = sessionLength(simRemainingBeforeAlloc, Math.min(remainingSlot, studyBudget), cap, minSessionHours,
                            isFlexible(item.getItemType().name()));

                    if (alloc <= 0) {
                        // Fragment too small, defer this item so it is not lost
                        deferredInSlot.add(item);
                        continue;
                    }

                    long allocMinutes = Math.round(alloc * 60.0);
                    LocalTime slotEnd = slotStart.plusMinutes(allocMinutes);

                    schedule.add(new DailyLogEntry(
                            null, date, item.getItemType().name(), item.getId(),
                            slotStart, slotEnd, defaultStatus
                    ));

                    consumeSessionHours(item.getItemType().name(), item.getId(), alloc, date,
                            simTopicMap, simExamMap, simSkillMap, simSubtopicMap);

                    lastScheduledSubject = getItemSubject(item, simExamMap);
                    remainingSlot -= alloc;
                    studyBudget -= alloc;
                    
                    // Update slotStart for next session: current session end + optional break
                    slotStart = slotEnd;
                    if (alloc > 1.5 && breakMinutes > 0) {
                        breaks.add(new SlotMath.TimeInterval(slotEnd, slotEnd.plusMinutes(breakMinutes)));
                        slotStart = slotStart.plusMinutes(breakMinutes);
                        remainingSlot -= (breakMinutes / 60.0);
                    }

                    if (item.isSkill()) {
                        scheduledSkillIdsToday.add(item.getExamId() != null ? item.getExamId() : item.getId());
                    } else {
                        double remainingAfter = simRemainingBeforeAlloc - alloc;
                        if (remainingAfter >= 0.05) {
                            item.setRemainingHours(remainingAfter);
                            item.setInProgress(true);
                            queue.offer(item);
                        }
                    }
                }

                for (PriorityItem d : deferredInSlot) {
                    queue.offer(d);
                }
            }
        }

        // PHASE 2: Filler pass to use ALL remaining free time
        fillRemainingWithFiller(
                freeSlots, schedule, breaks,
                new ArrayList<>(simExamMap.values()), new ArrayList<>(simTopicMap.values()), new ArrayList<>(simSkillMap.values()),
                simSubtopics,
                date, simTopicMap, simExamMap, simSkillMap, simSubtopicMap,
                breakMinutes, minSessionHours, maxSessionTopic, skillSessionLength, lastScheduledSubject
        );

        return schedule;
    }

    private PriorityItem pollInterleavedItem(PriorityQueue<PriorityItem> queue,
                                             String lastScheduledSubject,
                                             Map<Integer, Exam> simExamMap,
                                             Set<Integer> scheduledSkillIdsToday,
                                             Map<Integer, Topic> simTopicMap,
                                             Map<Integer, SkillSubtopic> simSubtopicMap) {
        List<PriorityItem> deferred = new ArrayList<>();
        PriorityItem selected = null;
        PriorityItem firstValidSameSubject = null;

        while (!queue.isEmpty()) {
            PriorityItem cand = queue.poll();
            Integer trackSkillId = cand.isSkill() ? (cand.getExamId() != null ? cand.getExamId() : cand.getId()) : null;
            if (trackSkillId != null && scheduledSkillIdsToday.contains(trackSkillId)) {
                deferred.add(cand);
                continue;
            }
            double simRem = getSimulatedRemainingHours(cand, simTopicMap, simExamMap, simSubtopicMap);
            if (simRem < 0.05) {
                continue;
            }
            cand.setRemainingHours(Math.min(cand.getRemainingHours(), simRem));

            // Rule B check: A topic must never be scheduled while an earlier-position topic of the same exam is still unfinished
            if (hasEarlierUnfinishedLearnTopic(cand, simTopicMap)) {
                deferred.add(cand);
                continue;
            }

            // Skill subtopics: position order within skill
            if (hasEarlierUnfinishedSkillLearnSubtopic(cand, simSubtopicMap)) {
                deferred.add(cand);
                continue;
            }

            String subj = getItemSubject(cand, simExamMap);
            // Alternate subjects only within the same priority: never pick a skill or a revision
            // over a learning topic just because the subject differs.
            if (firstValidSameSubject != null && cand.tier() > firstValidSameSubject.tier()) {
                deferred.add(cand);
                break;
            }
            if (lastScheduledSubject == null || subj == null || !subj.equalsIgnoreCase(lastScheduledSubject)) {
                selected = cand;
                break;
            } else {
                if (firstValidSameSubject == null) {
                    firstValidSameSubject = cand;
                } else {
                    deferred.add(cand);
                }
            }
        }

        if (selected == null && firstValidSameSubject != null) {
            selected = firstValidSameSubject;
        } else if (firstValidSameSubject != null) {
            deferred.add(firstValidSameSubject);
        }

        for (PriorityItem d : deferred) {
            queue.offer(d);
        }

        return selected;
    }

    private boolean hasEarlierUnfinishedSkillLearnSubtopic(PriorityItem cand, Map<Integer, SkillSubtopic> simSubtopicMap) {
        if (cand == null || cand.getItemType() != PriorityItem.ItemType.SKILL_LEARN || simSubtopicMap == null) {
            return false;
        }
        if (cand.isInProgress()) {
            return false;
        }
        Integer skillId = cand.getExamId();
        if (skillId == null) {
            return false;
        }
        long candPos = cand.getSyllabusOrder();
        int candSubtopicId = cand.getId();

        for (SkillSubtopic st : simSubtopicMap.values()) {
            if (st.getSkillId().equals(skillId)
                    && !st.isLearned()
                    && st.getLearnRemainingHours() > 0.05
                    && st.getSubtopicId() != candSubtopicId
                    && st.getPosition() < candPos) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEarlierUnfinishedLearnTopic(PriorityItem cand, Map<Integer, Topic> simTopicMap) {
        if (cand == null || cand.getItemType() != PriorityItem.ItemType.TOPIC_LEARN) {
            return false;
        }
        if (cand.isInProgress()) {
            return false; // Continuity rule A allows started topic
        }
        Integer examId = cand.getExamId();
        if (examId == null) {
            return false;
        }
        long candSylOrder = cand.getSyllabusOrder();
        int candTopicId = cand.getId();

        for (Topic t : simTopicMap.values()) {
            if (t.getExamId() == examId
                    && "LEARNING".equalsIgnoreCase(t.getPhase())
                    && t.getImportantFlag() == 1
                    && t.getRemainingHours() > 0.05
                    && t.getTopicId() != candTopicId) {
                long tSylOrder = t.getSyllabusOrder();
                if (tSylOrder < candSylOrder) {
                    return true;
                } else if (tSylOrder == candSylOrder && tSylOrder == Long.MAX_VALUE) {
                    if (t.getTopicId() < candTopicId) {
                        return true;
                    }
                }
            }
        }
        return hasEarlierTopicInSubject(candTopicId, examId, simTopicMap);
    }

    /** Real syllabus position (unit, then subtopic) of each simulated topic; filled per replan. */
    private Map<Integer, Long> syllabusPositionByTopic = new HashMap<>();
    /** Subject of each exam (code, else name); filled per replan. */
    private Map<Integer, String> subjectByExam = new HashMap<>();

    /**
     * Learning follows the syllabus across the whole subject: a topic waits while an earlier
     * syllabus topic of the same subject is still unlearned, even when that topic belongs to the
     * subject's other exam (MST units 3-4 do not jump ahead of EST units 1-2, and vice versa).
     * A topic that does not fit before its exam never holds back one that does.
     */
    private boolean hasEarlierTopicInSubject(int candTopicId, int candExamId, Map<Integer, Topic> simTopicMap) {
        String subject = subjectByExam.get(candExamId);
        Long candPos = syllabusPositionByTopic.get(candTopicId);
        Topic cand = simTopicMap.get(candTopicId);
        if (subject == null || candPos == null || cand == null) {
            return false;
        }
        for (Topic t : simTopicMap.values()) {
            if (t.getTopicId() == candTopicId || t.getExamId() == candExamId) continue;
            if (!subject.equalsIgnoreCase(subjectByExam.get(t.getExamId()))) continue;
            if (!"LEARNING".equalsIgnoreCase(t.getPhase()) || t.getImportantFlag() != 1
                    || t.getRemainingHours() <= 0.05) continue;
            if (cand.isPlanned() && !t.isPlanned()) continue;
            Long pos = syllabusPositionByTopic.get(t.getTopicId());
            if (pos != null && pos < candPos) {
                return true;
            }
        }
        return false;
    }

    private String getItemSubject(PriorityItem item, Map<Integer, Exam> simExamMap) {
        if (item == null) return null;
        if (item.isSkill()) {
            return "SKILL_" + (item.getExamId() != null ? item.getExamId() : "GEN");
        }
        if (item.getExamId() != null) {
            Exam e = simExamMap.get(item.getExamId());
            if (e != null) {
                return e.getSubjectCode() != null ? e.getSubjectCode() : e.getSubject();
            }
        }
        return null;
    }

    /**
     * Eve day split: 80% of free time split across eve exams by weight.
     * Leftover 20% split between clash exams if 2+ exist, otherwise general queue.
     * Plan Section 8 & Fix Pass Item 4.
     */
    private List<DailyLogEntry> fillEveDayWithSimulation(List<FreeSlot> freeSlots, double totalFreeTime,
                                                        List<Exam> eveExams, List<Exam> clashExams,
                                                        double revisionMajorityRatio,
                                                        List<Topic> simTopics, List<Exam> simExams,
                                                        PriorityQueue<PriorityItem> queue,
                                                        double skillSessionLength, double maxSessionTopic,
                                                        LocalDate date,
                                                        Map<Integer, Topic> simTopicMap,
                                                        Map<Integer, Exam> simExamMap,
                                                        Map<Integer, Skill> simSkillMap,
                                                        Map<Integer, SkillSubtopic> simSubtopicMap,
                                                        List<SkillSubtopic> simSubtopics,
                                                        int breakMinutes, double minSessionHours) {
        List<DailyLogEntry> schedule = new ArrayList<>();
        double totalEveWeight = 0.0;
        for (Exam e : eveExams) {
            totalEveWeight += scoringService.examWeight(e);
        }
        if (totalEveWeight <= 0) totalEveWeight = 1.0;

        // Plan §8 & Guide §5: revision budget = totalFreeTime * revisionMajorityRatio (uncapped by weekday preference)
        double revisionBudgetTotal = totalFreeTime * revisionMajorityRatio;
        double leftoverBudget = Math.max(0.0, totalFreeTime - revisionBudgetTotal);

        Map<Integer, Double> eveExamBudgets = new HashMap<>();
        Map<Integer, PriorityQueue<PriorityItem>> eveExamQueues = new HashMap<>();

        for (Exam e : eveExams) {
            double share = scoringService.examWeight(e) / totalEveWeight;
            eveExamBudgets.put(e.getExamId(), revisionBudgetTotal * share);

            PriorityQueue<PriorityItem> examQ = new PriorityQueue<>(PriorityItem.TIER_COMPARATOR);
            for (Topic t : simTopics) {
                if (t.getExamId() == e.getExamId() && t.getImportantFlag() == 1) {
                    double rem = "REVISION".equalsIgnoreCase(t.getPhase()) ? t.getRevisionRemainingHours() : t.getRemainingHours();
                    if (rem > 0.05) {
                        double sc = "REVISION".equalsIgnoreCase(t.getPhase())
                                ? scoringService.revisionScore(t, date)
                                : ((t.getImportance() * Math.max(t.getHoursNeeded(), 1.0)) / (double) Math.max(scoringService.daysLeft(e, date), 1));
                        boolean inProg = "LEARNING".equalsIgnoreCase(t.getPhase())
                                ? (t.getRemainingHours() < t.getHoursNeeded() - 0.001 && t.getRemainingHours() > 0.001)
                                : (t.getRevisionRemainingHours() < t.getRevisionHoursNeeded() - 0.001 && t.getRevisionRemainingHours() > 0.001);
                        long dl = scoringService.daysLeft(e, date);
                        examQ.offer(new PriorityItem(
                                "REVISION".equalsIgnoreCase(t.getPhase()) ? PriorityItem.ItemType.TOPIC_REVISE : PriorityItem.ItemType.TOPIC_LEARN,
                                t.getTopicId(), t.getName() + ("REVISION".equalsIgnoreCase(t.getPhase()) ? " (Revise)" : ""),
                                sc, rem, e.getExamId(),
                                inProg, dl, t.getSyllabusOrder(), t.getImportance(),
                                "REVISION".equalsIgnoreCase(t.getPhase()) ? t.getRevisionHoursNeeded() : t.getHoursNeeded()
                        ));
                    }
                }
            }
            if (scoringService.pyqEligible(e, getTopicsForExam(simTopics, e.getExamId()), date)) {
                double sc = scoringService.pyqScore(e, date);
                long dl = scoringService.daysLeft(e, date);
                examQ.offer(new PriorityItem(
                        PriorityItem.ItemType.PYQ_PRACTICE, e.getExamId(), "PYQ: " + e.getSubject(),
                        sc, e.getPyqRemainingHours(), e.getExamId(),
                        false, dl, Long.MAX_VALUE, 1, e.getPyqRemainingHours()
                ));
            }
            eveExamQueues.put(e.getExamId(), examQ);
        }

        PriorityQueue<PriorityItem> fallbackQueue = null;
        Set<Integer> scheduledSkillIdsToday = new HashSet<>();
        String lastScheduledSubject = null;
        List<SlotMath.TimeInterval> breaks = new ArrayList<>();
        String defaultStatus = date.isAfter(LocalDate.now()) ? "PLANNED" : "PENDING";

        // Fill slots
        for (FreeSlot slot : freeSlots) {
            LocalTime slotStart = slot.getStart();
            double remainingSlot = slot.getDuration();

            // 1. Allocate revision budget for eve exams
            for (Exam e : eveExams) {
                double eBudget = eveExamBudgets.getOrDefault(e.getExamId(), 0.0);
                PriorityQueue<PriorityItem> eq = eveExamQueues.get(e.getExamId());

                while (remainingSlot >= 0.05 && eBudget >= 0.05 && eq != null && !eq.isEmpty()) {
                    PriorityItem item = eq.poll();
                    double alloc = sessionLength(item.getRemainingHours(), Math.min(remainingSlot, eBudget), maxSessionTopic, minSessionHours);
                    if (alloc <= 0) { eq.offer(item); break; }

                    long allocMinutes = Math.round(alloc * 60.0);
                    LocalTime slotEnd = slotStart.plusMinutes(allocMinutes);

                    schedule.add(new DailyLogEntry(
                            null, date, item.getItemType().name(), item.getId(),
                            slotStart, slotEnd, defaultStatus
                    ));

                    consumeSessionHours(item.getItemType().name(), item.getId(), alloc, date,
                            simTopicMap, simExamMap, simSkillMap, simSubtopicMap);

                    lastScheduledSubject = getItemSubject(item, simExamMap);
                    remainingSlot -= alloc;
                    eBudget -= alloc;
                    eveExamBudgets.put(e.getExamId(), eBudget);
                    slotStart = slotEnd;

                    if (alloc > 1.5 && breakMinutes > 0) {
                        breaks.add(new SlotMath.TimeInterval(slotEnd, slotEnd.plusMinutes(breakMinutes)));
                        slotStart = slotStart.plusMinutes(breakMinutes);
                        remainingSlot -= (breakMinutes / 60.0);
                    }

                    double newRem = item.getRemainingHours() - alloc;
                    if (newRem >= 0.05) {
                        item.setRemainingHours(newRem);
                        item.setInProgress(true);
                        eq.offer(item);
                    }
                }
            }

            // 2. Fill leftover time: if 2+ clash exams exist on date, split leftover 20% by weight, else general queue
            if (clashExams.size() >= 2 && leftoverBudget >= 0.05 && remainingSlot >= 0.05) {
                double totalClashWeight = 0.0;
                for (Exam ce : clashExams) totalClashWeight += scoringService.examWeight(ce);
                if (totalClashWeight <= 0) totalClashWeight = 1.0;

                for (Exam ce : clashExams) {
                    double ceShare = scoringService.examWeight(ce) / totalClashWeight;
                    double ceBudget = leftoverBudget * ceShare;
                    List<Topic> ceTopics = getTopicsForExam(simTopics, ce.getExamId());

                    for (Topic ct : ceTopics) {
                        double rem = "REVISION".equalsIgnoreCase(ct.getPhase()) ? ct.getRevisionRemainingHours() : ct.getRemainingHours();
                        if (rem >= 0.05 && remainingSlot >= 0.05 && ceBudget >= 0.05) {
                            double alloc = sessionLength(rem, Math.min(remainingSlot, ceBudget), maxSessionTopic, minSessionHours);
                            if (alloc <= 0) continue;

                            long allocMinutes = Math.round(alloc * 60.0);
                            LocalTime slotEnd = slotStart.plusMinutes(allocMinutes);

                            schedule.add(new DailyLogEntry(
                                    null, date, "REVISION".equalsIgnoreCase(ct.getPhase()) ? "TOPIC_REVISE" : "TOPIC_LEARN",
                                    ct.getTopicId(), slotStart, slotEnd, defaultStatus
                            ));

                            consumeSessionHours(
                                    "REVISION".equalsIgnoreCase(ct.getPhase()) ? "TOPIC_REVISE" : "TOPIC_LEARN",
                                    ct.getTopicId(), alloc, date, simTopicMap, simExamMap, simSkillMap, simSubtopicMap
                            );

                            lastScheduledSubject = getItemSubject(ce, simExamMap);
                            remainingSlot -= alloc;
                            ceBudget -= alloc;
                            leftoverBudget -= alloc;
                            slotStart = slotEnd;

                            if (alloc > 1.5 && breakMinutes > 0) {
                                breaks.add(new SlotMath.TimeInterval(slotEnd, slotEnd.plusMinutes(breakMinutes)));
                                slotStart = slotStart.plusMinutes(breakMinutes);
                                remainingSlot -= (breakMinutes / 60.0);
                            }
                        }
                    }
                }
            }

            // Build fallback queue at most once after exam-specific allocation
            if (fallbackQueue == null && remainingSlot >= 0.05 && leftoverBudget >= 0.05) {
                fallbackQueue = priorityQueueService.build(simTopics, new ArrayList<>(simSkillMap.values()), simSubtopics, simExams, date, skillSessionLength);
            }

            // Fill remainder from general queue, re-validating every item against simulated state
            List<PriorityItem> skippedHere = new ArrayList<>();
            while (remainingSlot >= 0.05 && leftoverBudget >= 0.05 && fallbackQueue != null && !fallbackQueue.isEmpty()) {
                PriorityItem item = fallbackQueue.poll();
                Integer trackSkillId = item.isSkill() ? (item.getExamId() != null ? item.getExamId() : item.getId()) : null;
                if (trackSkillId != null && scheduledSkillIdsToday.contains(trackSkillId)) {
                    continue;
                }
                double simRemaining = getSimulatedRemainingHours(item, simTopicMap, simExamMap, simSubtopicMap);
                if (simRemaining < 0.05) {
                    continue;
                }
                item.setRemainingHours(Math.min(item.getRemainingHours(), simRemaining));
                double simRemainingBeforeAlloc = item.getRemainingHours();
                double cap = item.isSkill() ? skillSessionLength : maxSessionTopic;
                double alloc = sessionLength(simRemainingBeforeAlloc, Math.min(remainingSlot, leftoverBudget), cap, minSessionHours);
                if (alloc <= 0) { skippedHere.add(item); continue; }

                long allocMinutes = Math.round(alloc * 60.0);
                LocalTime slotEnd = slotStart.plusMinutes(allocMinutes);

                schedule.add(new DailyLogEntry(
                        null, date, item.getItemType().name(), item.getId(),
                        slotStart, slotEnd, defaultStatus
                ));

                consumeSessionHours(item.getItemType().name(), item.getId(), alloc, date,
                        simTopicMap, simExamMap, simSkillMap, simSubtopicMap);

                lastScheduledSubject = getItemSubject(item, simExamMap);
                remainingSlot -= alloc;
                leftoverBudget -= alloc;
                slotStart = slotEnd;

                if (alloc > 1.5 && breakMinutes > 0) {
                    breaks.add(new SlotMath.TimeInterval(slotEnd, slotEnd.plusMinutes(breakMinutes)));
                    slotStart = slotStart.plusMinutes(breakMinutes);
                    remainingSlot -= (breakMinutes / 60.0);
                }

                if (item.isSkill()) {
                    scheduledSkillIdsToday.add(item.getExamId() != null ? item.getExamId() : item.getId());
                } else {
                    double remainingAfter = simRemainingBeforeAlloc - alloc;
                    if (remainingAfter >= 0.05) {
                        item.setRemainingHours(remainingAfter);
                        fallbackQueue.offer(item);
                    }
                }
            }
            if (fallbackQueue != null) fallbackQueue.addAll(skippedHere);   // did not fit this gap; may fit the next
        }

        // PHASE 2: Filler pass
        fillRemainingWithFiller(
                freeSlots, schedule, breaks,
                simExams, simTopics, new ArrayList<>(simSkillMap.values()),
                simSubtopics,
                date, simTopicMap, simExamMap, simSkillMap, simSubtopicMap,
                breakMinutes, minSessionHours, maxSessionTopic, skillSessionLength, lastScheduledSubject
        );

        return schedule;
    }

    /**
     * Same-day exam clash split: splits available study time across same-day exams by examWeight share.
     * Plan Section 8, User Guide Section 5, Fix Pass Item 4.
     */
    private List<DailyLogEntry> fillClashDayWithSimulation(List<FreeSlot> freeSlots, double totalFreeTime,
                                                          List<Exam> clashExams,
                                                          List<Topic> simTopics, List<Exam> simExams,
                                                          PriorityQueue<PriorityItem> queue,
                                                          double skillSessionLength, double maxSessionTopic,
                                                          LocalDate date,
                                                          Map<Integer, Topic> simTopicMap,
                                                          Map<Integer, Exam> simExamMap,
                                                          Map<Integer, Skill> simSkillMap,
                                                          Map<Integer, SkillSubtopic> simSubtopicMap,
                                                          List<SkillSubtopic> simSubtopics,
                                                          int breakMinutes, double minSessionHours) {
        List<DailyLogEntry> schedule = new ArrayList<>();
        double totalClashWeight = 0.0;
        for (Exam e : clashExams) totalClashWeight += scoringService.examWeight(e);
        if (totalClashWeight <= 0) totalClashWeight = 1.0;

        // Plan §8 & Guide §5: same-day clash budget derives from totalFreeTime (uncapped by weekday preference)
        double clashBudgetTotal = totalFreeTime;
        Map<Integer, Double> examBudgets = new HashMap<>();
        Map<Integer, PriorityQueue<PriorityItem>> examQueues = new HashMap<>();

        for (Exam e : clashExams) {
            double share = scoringService.examWeight(e) / totalClashWeight;
            examBudgets.put(e.getExamId(), clashBudgetTotal * share);

            PriorityQueue<PriorityItem> eq = new PriorityQueue<>(PriorityItem.TIER_COMPARATOR);
            for (Topic t : simTopics) {
                if (t.getExamId() == e.getExamId() && t.getImportantFlag() == 1) {
                    double rem = "REVISION".equalsIgnoreCase(t.getPhase()) ? t.getRevisionRemainingHours() : t.getRemainingHours();
                    if (rem > 0.05) {
                        double sc = "REVISION".equalsIgnoreCase(t.getPhase())
                                ? scoringService.revisionScore(t, date)
                                : ((t.getImportance() * Math.max(t.getHoursNeeded(), 1.0)) / (double) Math.max(scoringService.daysLeft(e, date), 1));
                        boolean inProg = "LEARNING".equalsIgnoreCase(t.getPhase())
                                ? (t.getRemainingHours() < t.getHoursNeeded() - 0.001 && t.getRemainingHours() > 0.001)
                                : (t.getRevisionRemainingHours() < t.getRevisionHoursNeeded() - 0.001 && t.getRevisionRemainingHours() > 0.001);
                        long dl = scoringService.daysLeft(e, date);
                        eq.offer(new PriorityItem(
                                "REVISION".equalsIgnoreCase(t.getPhase()) ? PriorityItem.ItemType.TOPIC_REVISE : PriorityItem.ItemType.TOPIC_LEARN,
                                t.getTopicId(), t.getName() + ("REVISION".equalsIgnoreCase(t.getPhase()) ? " (Revise)" : ""),
                                sc, rem, e.getExamId(),
                                inProg, dl, t.getSyllabusOrder(), t.getImportance(),
                                "REVISION".equalsIgnoreCase(t.getPhase()) ? t.getRevisionHoursNeeded() : t.getHoursNeeded()
                        ));
                    }
                }
            }
            if (scoringService.pyqEligible(e, getTopicsForExam(simTopics, e.getExamId()), date)) {
                double sc = scoringService.pyqScore(e, date);
                long dl = scoringService.daysLeft(e, date);
                eq.offer(new PriorityItem(
                        PriorityItem.ItemType.PYQ_PRACTICE, e.getExamId(), "PYQ: " + e.getSubject(),
                        sc, e.getPyqRemainingHours(), e.getExamId(),
                        false, dl, Long.MAX_VALUE, 1, e.getPyqRemainingHours()
                ));
            }
            examQueues.put(e.getExamId(), eq);
        }

        double clashBudgetRemaining = clashBudgetTotal;
        PriorityQueue<PriorityItem> fallbackQueue = null;
        Set<Integer> scheduledSkillIdsToday = new HashSet<>();
        String lastScheduledSubject = null;
        List<SlotMath.TimeInterval> breaks = new ArrayList<>();
        String defaultStatus = date.isAfter(LocalDate.now()) ? "PLANNED" : "PENDING";

        for (FreeSlot slot : freeSlots) {
            LocalTime slotStart = slot.getStart();
            double remainingSlot = slot.getDuration();

            // Fill each clash exam according to its weight share
            for (Exam e : clashExams) {
                double eBudget = examBudgets.getOrDefault(e.getExamId(), 0.0);
                PriorityQueue<PriorityItem> eq = examQueues.get(e.getExamId());

                while (remainingSlot >= 0.05 && eBudget >= 0.05 && eq != null && !eq.isEmpty()) {
                    PriorityItem item = eq.poll();
                    double alloc = sessionLength(item.getRemainingHours(), Math.min(remainingSlot, eBudget), maxSessionTopic, minSessionHours);
                    if (alloc <= 0) { eq.offer(item); break; }

                    long allocMinutes = Math.round(alloc * 60.0);
                    LocalTime slotEnd = slotStart.plusMinutes(allocMinutes);

                    schedule.add(new DailyLogEntry(
                            null, date, item.getItemType().name(), item.getId(),
                            slotStart, slotEnd, defaultStatus
                    ));

                    consumeSessionHours(item.getItemType().name(), item.getId(), alloc, date,
                            simTopicMap, simExamMap, simSkillMap, simSubtopicMap);

                    lastScheduledSubject = getItemSubject(e, simExamMap);
                    remainingSlot -= alloc;
                    eBudget -= alloc;
                    clashBudgetRemaining -= alloc;
                    examBudgets.put(e.getExamId(), eBudget);
                    slotStart = slotEnd;

                    if (alloc > 1.5 && breakMinutes > 0) {
                        breaks.add(new SlotMath.TimeInterval(slotEnd, slotEnd.plusMinutes(breakMinutes)));
                        slotStart = slotStart.plusMinutes(breakMinutes);
                        remainingSlot -= (breakMinutes / 60.0);
                    }

                    double newRem = item.getRemainingHours() - alloc;
                    if (newRem >= 0.05) {
                        item.setRemainingHours(newRem);
                        item.setInProgress(true);
                        eq.offer(item);
                    }
                }
            }

            // Build fallback queue at most once after exam-specific allocation
            if (fallbackQueue == null && remainingSlot >= 0.05 && clashBudgetRemaining >= 0.05) {
                fallbackQueue = priorityQueueService.build(simTopics, new ArrayList<>(simSkillMap.values()), simSubtopics, simExams, date, skillSessionLength);
            }

            // Unused share falls through to general queue up to clashBudgetRemaining, re-validating against simulated state
            List<PriorityItem> skippedHere = new ArrayList<>();
            while (remainingSlot >= 0.05 && clashBudgetRemaining >= 0.05 && fallbackQueue != null && !fallbackQueue.isEmpty()) {
                PriorityItem item = fallbackQueue.poll();
                Integer trackSkillId = item.isSkill() ? (item.getExamId() != null ? item.getExamId() : item.getId()) : null;
                if (trackSkillId != null && scheduledSkillIdsToday.contains(trackSkillId)) {
                    continue;
                }
                double simRemaining = getSimulatedRemainingHours(item, simTopicMap, simExamMap, simSubtopicMap);
                if (simRemaining < 0.05) {
                    continue;
                }
                item.setRemainingHours(Math.min(item.getRemainingHours(), simRemaining));
                double simRemainingBeforeAlloc = item.getRemainingHours();
                double cap = item.isSkill() ? skillSessionLength : maxSessionTopic;
                double alloc = sessionLength(simRemainingBeforeAlloc, Math.min(remainingSlot, clashBudgetRemaining), cap, minSessionHours);
                if (alloc <= 0) { skippedHere.add(item); continue; }

                long allocMinutes = Math.round(alloc * 60.0);
                LocalTime slotEnd = slotStart.plusMinutes(allocMinutes);

                schedule.add(new DailyLogEntry(
                        null, date, item.getItemType().name(), item.getId(),
                        slotStart, slotEnd, defaultStatus
                ));

                consumeSessionHours(item.getItemType().name(), item.getId(), alloc, date,
                        simTopicMap, simExamMap, simSkillMap, simSubtopicMap);

                lastScheduledSubject = getItemSubject(item, simExamMap);
                remainingSlot -= alloc;
                clashBudgetRemaining -= alloc;
                slotStart = slotEnd;

                if (alloc > 1.5 && breakMinutes > 0) {
                    breaks.add(new SlotMath.TimeInterval(slotEnd, slotEnd.plusMinutes(breakMinutes)));
                    slotStart = slotStart.plusMinutes(breakMinutes);
                    remainingSlot -= (breakMinutes / 60.0);
                }

                if (item.isSkill()) {
                    scheduledSkillIdsToday.add(trackSkillId);
                } else {
                    double remainingAfter = simRemainingBeforeAlloc - alloc;
                    if (remainingAfter >= 0.05) {
                        item.setRemainingHours(remainingAfter);
                        fallbackQueue.offer(item);
                    }
                }
            }
            if (fallbackQueue != null) fallbackQueue.addAll(skippedHere);   // did not fit this gap; may fit the next
        }

        // PHASE 2: Filler pass
        fillRemainingWithFiller(
                freeSlots, schedule, breaks,
                simExams, simTopics, new ArrayList<>(simSkillMap.values()),
                simSubtopics,
                date, simTopicMap, simExamMap, simSkillMap, simSubtopicMap,
                breakMinutes, minSessionHours, maxSessionTopic, skillSessionLength, lastScheduledSubject
        );

        return schedule;
    }

    /**
     * Phase 2: Filler pass that fills ANY remaining free slot time after Phase 1.
     * Ensures all free time is assigned with no daily cap, rotating across unfinished exams
     * and merging small leftover fragments (< minSessionHours) into preceding sessions in the same slot.
     */
    private void fillRemainingWithFiller(List<FreeSlot> freeSlots,
                                         List<DailyLogEntry> schedule,
                                         List<SlotMath.TimeInterval> breaks,
                                         List<Exam> simExams,
                                         List<Topic> simTopics,
                                         List<Skill> simSkills,
                                         List<SkillSubtopic> simSubtopics,
                                         LocalDate date,
                                         Map<Integer, Topic> simTopicMap,
                                         Map<Integer, Exam> simExamMap,
                                         Map<Integer, Skill> simSkillMap,
                                         Map<Integer, SkillSubtopic> simSubtopicMap,
                                         int breakMinutes,
                                         double minSessionHours,
                                         double maxSessionTopic,
                                         double skillSessionLength,
                                         String initialLastSubject) {
        if (freeSlots == null || freeSlots.isEmpty()) {
            return;
        }

        List<SlotMath.TimeInterval> usedIntervals = new ArrayList<>();
        for (DailyLogEntry entry : schedule) {
            if (entry.getSlotStart() != null && entry.getSlotEnd() != null) {
                usedIntervals.add(new SlotMath.TimeInterval(entry.getSlotStart(), entry.getSlotEnd()));
            }
        }
        if (breaks != null) {
            usedIntervals.addAll(breaks);
        }

        List<FreeSlot> unusedSlots = SlotMath.subtractIntervals(freeSlots, usedIntervals);
        if (unusedSlots.isEmpty()) {
            return;
        }

        List<Exam> unfinishedExams = new ArrayList<>();
        if (simExams != null) {
            for (Exam e : simExams) {
                if (e.isUnfinished(date)) {
                    unfinishedExams.add(e);
                }
            }
        }

        Map<Integer, Double> examFillerHours = new HashMap<>();
        Map<Integer, Integer> topicFillerCounts = new HashMap<>();
        Map<Integer, Integer> skillFillerCounts = new HashMap<>();
        // One session per skill per day, counting what the main pass already placed today.
        Set<Integer> skillsToday = new HashSet<>();
        for (DailyLogEntry e : schedule) {
            String type = e.getItemType() == null ? "" : e.getItemType().toUpperCase();
            if (type.equals("SKILL")) {
                skillsToday.add(e.getItemId());
            } else if (type.equals("SKILL_LEARN") || type.equals("SKILL_REVISE")) {
                SkillSubtopic st = simSubtopicMap == null ? null : simSubtopicMap.get(e.getItemId());
                if (st != null) skillsToday.add(st.getSkillId());
            }
        }
        String lastSubject = initialLastSubject;
        long minSessionMinutes = Math.max(1, Math.round(minSessionHours * 60.0));
        String defaultStatus = date.isAfter(LocalDate.now()) ? "PLANNED" : "PENDING";

        for (FreeSlot slot : unusedSlots) {
            LocalTime cursor = slot.getStart();
            while (cursor.isBefore(slot.getEnd())) {
                long remMin = java.time.Duration.between(cursor, slot.getEnd()).toMinutes();
                if (remMin < minSessionMinutes) {
                    // Leftover fragment shorter than min_session_minutes:
                    // merge into previous session in the same slot if possible, otherwise leave it.
                    DailyLogEntry prev = findContiguousSessionEndingAt(schedule, cursor, freeSlots);
                    if (prev != null) {
                        double addedHours = remMin / 60.0;
                        boolean canMerge = true;
                        if ("TOPIC_LEARN".equalsIgnoreCase(prev.getItemType())) {
                            Topic t = simTopicMap.get(prev.getItemId());
                            // Only merge if topic still needs hours, never exceed hoursNeeded
                            if (t == null || t.getRemainingHours() < addedHours - 0.001) {
                                canMerge = false;
                            }
                        } else if ("SKILL_LEARN".equalsIgnoreCase(prev.getItemType())) {
                            SkillSubtopic sub = simSubtopicMap != null ? simSubtopicMap.get(prev.getItemId()) : null;
                            if (sub == null || sub.getLearnRemainingHours() < addedHours - 0.001) {
                                canMerge = false;
                            }
                        } else if ("SKILL_REVISE".equalsIgnoreCase(prev.getItemType())) {
                            SkillSubtopic sub = simSubtopicMap != null ? simSubtopicMap.get(prev.getItemId()) : null;
                            if (sub == null || sub.getReviseRemainingHours() < addedHours - 0.001) {
                                canMerge = false;
                            }
                        } else {
                            canMerge = false;               // revision / PYQ blocks keep their planned length
                        }
                        // Merging must not leave a scrap behind: it finishes the item or leaves 30 min+.
                        if (canMerge) {
                            double left = Double.NaN;
                            if ("TOPIC_LEARN".equalsIgnoreCase(prev.getItemType())) {
                                Topic t = simTopicMap.get(prev.getItemId());
                                left = t == null ? 0 : t.getRemainingHours() - addedHours;
                            } else {
                                SkillSubtopic sub = simSubtopicMap != null ? simSubtopicMap.get(prev.getItemId()) : null;
                                if (sub != null) {
                                    left = ("SKILL_LEARN".equalsIgnoreCase(prev.getItemType())
                                            ? sub.getLearnRemainingHours() : sub.getReviseRemainingHours()) - addedHours;
                                }
                            }
                            if (!Double.isNaN(left) && left > 0.01 && left < MIN_REMAINDER - 1e-9) {
                                canMerge = false;
                            }
                        }
                        if (canMerge) {
                            prev.setSlotEnd(slot.getEnd());
                            consumeSessionHours(prev.getItemType(), prev.getItemId(), addedHours, date,
                                    simTopicMap, simExamMap, simSkillMap, simSubtopicMap);
                        }
                    }
                    break;
                }

                // Phase 2 Filler: the next syllabus topic first; if it cannot use this gap, a skill;
                // if not that either, a revision pass or PYQ practice, which fit any gap of 15 min+.
                // Never more than an item still needs, and topics split only into real pieces.
                FillerCandidate cand = null;
                double need = 0;
                double allocHoursPlanned = 0;
                for (int attempt = 0; attempt < 3 && cand == null; attempt++) {
                    FillerCandidate c = attempt == 0
                            ? pickUnfinishedLearnCandidate(unfinishedExams, simTopics, date, lastSubject, simExamMap)
                            : pickFillerCandidate(unfinishedExams, simTopics, simSkills, simSubtopics, date, lastSubject,
                                    examFillerHours, topicFillerCounts, skillFillerCounts, simExamMap, simSubtopicMap,
                                    skillsToday, attempt == 2);
                    if (c == null) continue;
                    double n = fillerNeed(c, simTopicMap, simExamMap, simSubtopicMap, skillSessionLength);
                    double a = sessionLength(n, remMin / 60.0, c.isSkill ? skillSessionLength : maxSessionTopic,
                            minSessionHours, isFlexible(c.itemType));
                    if (a > 0) {
                        cand = c;
                        need = n;
                        allocHoursPlanned = a;
                    }
                }
                if (cand == null) {
                    break;                                  // nothing at all fits this gap
                }
                long allocMin = Math.round(allocHoursPlanned * 60.0);

                // A sub-minimum gap after this session is absorbed only if the item really needs it
                long leftoverAfter = remMin - allocMin;
                if (leftoverAfter > 0 && leftoverAfter < minSessionMinutes
                        && need * 60.0 >= allocMin + leftoverAfter - 0.5
                        && (need * 60.0 - allocMin - leftoverAfter < 0.5 || need * 60.0 - allocMin - leftoverAfter >= MIN_REMAINDER * 60.0 - 0.5)) {
                    allocMin += leftoverAfter;
                    leftoverAfter = 0;
                }

                LocalTime sessionEnd = cursor.plusMinutes(allocMin);
                DailyLogEntry entry = new DailyLogEntry(
                        null, date, cand.itemType, cand.itemId, cursor, sessionEnd, defaultStatus
                );
                schedule.add(entry);

                double allocHours = allocMin / 60.0;
                consumeSessionHours(cand.itemType, cand.itemId, allocHours, date,
                        simTopicMap, simExamMap, simSkillMap, simSubtopicMap);

                lastSubject = cand.subject;
                if (cand.isSkill) {
                    if ("SKILL".equals(cand.itemType)) skillsToday.add(cand.itemId);
                    else if (cand.examId != null) skillsToday.add(cand.examId);   // skill id for subtopics
                }
                if (cand.examId != null) {
                    examFillerHours.put(cand.examId, examFillerHours.getOrDefault(cand.examId, 0.0) + allocHours);
                }
                if ("TOPIC_REVISE".equals(cand.itemType)) {
                    topicFillerCounts.put(cand.itemId, topicFillerCounts.getOrDefault(cand.itemId, 0) + 1);
                } else if ("SKILL".equals(cand.itemType) || "SKILL_LEARN".equals(cand.itemType) || "SKILL_REVISE".equals(cand.itemType)) {
                    skillFillerCounts.put(cand.itemId, skillFillerCounts.getOrDefault(cand.itemId, 0) + 1);
                }

                cursor = sessionEnd;
                if (allocHours > 1.5 && breakMinutes > 0 && leftoverAfter >= (breakMinutes + minSessionMinutes)) {
                    if (breaks != null) {
                        breaks.add(new SlotMath.TimeInterval(cursor, cursor.plusMinutes(breakMinutes)));
                    }
                    cursor = cursor.plusMinutes(breakMinutes);
                }
            }
        }
    }

    /** Revision passes and PYQ practice can be any length, so they may fill short gaps. */
    private static boolean isFlexible(String itemType) {
        return "TOPIC_REVISE".equalsIgnoreCase(itemType) || "PYQ_PRACTICE".equalsIgnoreCase(itemType);
    }

    /** Hours the filler candidate still really needs (a revision cycle, the PYQ hours left, ...). */
    private double fillerNeed(FillerCandidate cand, Map<Integer, Topic> simTopicMap, Map<Integer, Exam> simExamMap,
                              Map<Integer, SkillSubtopic> simSubtopicMap, double skillSessionLength) {
        switch (cand.itemType.toUpperCase()) {
            case "TOPIC_LEARN": {
                Topic t = simTopicMap.get(cand.itemId);
                return t == null ? 0 : t.getRemainingHours();
            }
            case "TOPIC_REVISE": {
                Topic t = simTopicMap.get(cand.itemId);
                return t == null ? 0 : t.getRevisionRemainingHours();
            }
            case "PYQ_PRACTICE": {
                Exam e = simExamMap.get(cand.itemId);
                return e == null ? 0 : e.getPyqRemainingHours();
            }
            case "SKILL_LEARN": {
                SkillSubtopic s = simSubtopicMap == null ? null : simSubtopicMap.get(cand.itemId);
                return s == null ? 0 : s.getLearnRemainingHours();
            }
            case "SKILL_REVISE": {
                SkillSubtopic s = simSubtopicMap == null ? null : simSubtopicMap.get(cand.itemId);
                return s == null ? 0 : s.getReviseRemainingHours();
            }
            default:
                return skillSessionLength;               // legacy skill without subtopics
        }
    }

    private FillerCandidate pickUnfinishedLearnCandidate(List<Exam> unfinishedExams,
                                                         List<Topic> simTopics,
                                                         LocalDate date,
                                                         String lastSubject,
                                                         Map<Integer, Exam> simExamMap) {
        if (unfinishedExams == null || unfinishedExams.isEmpty() || simTopics == null || simTopics.isEmpty()) {
            return null;
        }
        Set<Integer> unfinishedExamIds = new HashSet<>();
        for (Exam e : unfinishedExams) {
            if (e.getExamId() != null) {
                unfinishedExamIds.add(e.getExamId());
            }
        }

        List<Topic> candidates = new ArrayList<>();
        Map<Integer, Topic> topicById = new HashMap<>();
        for (Topic t : simTopics) topicById.put(t.getTopicId(), t);
        for (Topic t : simTopics) {
            if (unfinishedExamIds.contains(t.getExamId())
                    && "LEARNING".equalsIgnoreCase(t.getPhase())
                    && t.getImportantFlag() == 1
                    && t.getRemainingHours() > 0.05) {
                boolean hasEarlier = false;
                if (!(t.getRemainingHours() < t.getHoursNeeded() - 0.001)) {
                    for (Topic other : simTopics) {
                        if (other.getExamId() == t.getExamId()
                                && "LEARNING".equalsIgnoreCase(other.getPhase())
                                && other.getImportantFlag() == 1
                                && other.getRemainingHours() > 0.05
                                && !other.getTopicId().equals(t.getTopicId())) {
                            if (other.getSyllabusOrder() < t.getSyllabusOrder()) {
                                hasEarlier = true;
                                break;
                            } else if (other.getSyllabusOrder() == t.getSyllabusOrder() && other.getSyllabusOrder() == Long.MAX_VALUE) {
                                if (other.getTopicId() < t.getTopicId()) {
                                    hasEarlier = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (!hasEarlier && !(t.getRemainingHours() < t.getHoursNeeded() - 0.001)
                        && hasEarlierTopicInSubject(t.getTopicId(), t.getExamId(), topicById)) {
                    hasEarlier = true;   // an earlier syllabus topic of the same subject comes first
                }
                if (!hasEarlier) {
                    candidates.add(t);
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort((t1, t2) -> {
            // Topics the coverage optimizer fitted before their exam always come first.
            if (t1.isPlanned() != t2.isPlanned()) {
                return t1.isPlanned() ? -1 : 1;
            }
            boolean inProg1 = t1.getRemainingHours() < t1.getHoursNeeded() - 0.001;
            boolean inProg2 = t2.getRemainingHours() < t2.getHoursNeeded() - 0.001;
            if (inProg1 != inProg2) {
                return inProg1 ? -1 : 1;
            }

            Exam e1 = simExamMap.get(t1.getExamId());
            Exam e2 = simExamMap.get(t2.getExamId());
            long dl1 = e1 != null ? scoringService.daysLeft(e1, date) : Long.MAX_VALUE;
            long dl2 = e2 != null ? scoringService.daysLeft(e2, date) : Long.MAX_VALUE;
            int dlCmp = Long.compare(dl1, dl2);
            if (dlCmp != 0) return dlCmp;

            int sylCmp = Long.compare(t1.getSyllabusOrder(), t2.getSyllabusOrder());
            if (sylCmp != 0) return sylCmp;

            int impCmp = Integer.compare(t2.getImportance(), t1.getImportance());
            if (impCmp != 0) return impCmp;

            return Integer.compare(t1.getTopicId(), t2.getTopicId());
        });

        Topic chosen = null;
        for (Topic t : candidates) {
            Exam e = simExamMap.get(t.getExamId());
            String subj = getItemSubject(e, simExamMap);
            if (lastSubject == null || subj == null || !subj.equalsIgnoreCase(lastSubject)) {
                chosen = t;
                break;
            }
        }
        if (chosen == null) {
            chosen = candidates.get(0);
        }

        Exam chosenExam = simExamMap.get(chosen.getExamId());
        String subj = getItemSubject(chosenExam, simExamMap);
        return new FillerCandidate("TOPIC_LEARN", chosen.getTopicId(), subj, chosen.getExamId(), false, chosen.getRemainingHours());
    }

    private DailyLogEntry findContiguousSessionEndingAt(List<DailyLogEntry> schedule, LocalTime targetEnd, List<FreeSlot> freeSlots) {
        if (schedule == null || schedule.isEmpty()) return null;
        for (int i = schedule.size() - 1; i >= 0; i--) {
            DailyLogEntry entry = schedule.get(i);
            if (entry.getSlotEnd() != null && entry.getSlotEnd().equals(targetEnd)) {
                for (FreeSlot fs : freeSlots) {
                    if (!entry.getSlotStart().isBefore(fs.getStart()) && !targetEnd.isAfter(fs.getEnd())) {
                        return entry;
                    }
                }
            }
        }
        return null;
    }

    private static class FillerCandidate {
        final String itemType;
        final int itemId;
        final String subject;
        final Integer examId;
        final boolean isSkill;
        final double remainingHours;

        FillerCandidate(String itemType, int itemId, String subject, Integer examId, boolean isSkill, double remainingHours) {
            this.itemType = itemType;
            this.itemId = itemId;
            this.subject = subject;
            this.examId = examId;
            this.isSkill = isSkill;
            this.remainingHours = remainingHours;
        }

        FillerCandidate(String itemType, int itemId, String subject, Integer examId, boolean isSkill) {
            this(itemType, itemId, subject, examId, isSkill, 999.0);
        }
    }

    /**
     * What to do with free time once every learning topic is placed: skills first, then a
     * revision of an already-learned exam topic (most important first), then PYQ practice.
     * Every candidate still needs real hours, so leftover time is never padded.
     */
    private FillerCandidate pickFillerCandidate(List<Exam> unfinishedExams,
                                                List<Topic> simTopics,
                                                List<Skill> simSkills,
                                                List<SkillSubtopic> simSubtopics,
                                                LocalDate date,
                                                String lastSubject,
                                                Map<Integer, Double> examFillerHours,
                                                Map<Integer, Integer> topicFillerCounts,
                                                Map<Integer, Integer> skillFillerCounts,
                                                Map<Integer, Exam> simExamMap,
                                                Map<Integer, SkillSubtopic> simSubtopicMap,
                                                Set<Integer> skillsToday,
                                                boolean skipSkills) {
        // 1. Skills
        if (!skipSkills && simSubtopics != null && !simSubtopics.isEmpty()) {
            List<SkillSubtopic> candidates = new ArrayList<>();
            for (SkillSubtopic st : simSubtopics) {
                if (skillsToday.contains(st.getSkillId())) {
                    continue;
                }
                if (!st.isLearned() && st.getLearnRemainingHours() > 0.05) {
                    candidates.add(st);
                } else if (st.isLearned() && !st.isRevised() && st.getReviseRemainingHours() > 0.05
                        && st.getRevisionDueDate() != null && !st.getRevisionDueDate().isAfter(date)) {
                    candidates.add(st);
                }
            }
            if (!candidates.isEmpty()) {
                candidates.sort((s1, s2) -> {
                    int c1 = skillFillerCounts.getOrDefault(s1.getSubtopicId(), 0);
                    int c2 = skillFillerCounts.getOrDefault(s2.getSubtopicId(), 0);
                    if (c1 != c2) return Integer.compare(c1, c2);
                    return Integer.compare(s1.getPosition(), s2.getPosition());
                });
                SkillSubtopic chosen = candidates.get(0);
                String type = !chosen.isLearned() ? "SKILL_LEARN" : "SKILL_REVISE";
                double rem = !chosen.isLearned() ? chosen.getLearnRemainingHours() : chosen.getReviseRemainingHours();
                return new FillerCandidate(type, chosen.getSubtopicId(), "SKILL", chosen.getSkillId(), true, rem);
            }
        }

        // 2. Revision of learned exam topics, nearest exam first, most important topic first
        if (unfinishedExams != null && !unfinishedExams.isEmpty()) {
            List<Exam> exams = new ArrayList<>(unfinishedExams);
            exams.sort((e1, e2) -> {
                int cmp = Long.compare(scoringService.daysLeft(e1, date), scoringService.daysLeft(e2, date));
                if (cmp != 0) return cmp;
                double h1 = examFillerHours.getOrDefault(e1.getExamId(), 0.0);
                double h2 = examFillerHours.getOrDefault(e2.getExamId(), 0.0);
                cmp = Double.compare(h1, h2);
                if (cmp != 0) return cmp;
                return Integer.compare(e1.getExamId(), e2.getExamId());
            });
            for (Exam exam : exams) {
                List<Topic> learned = new ArrayList<>();
                for (Topic t : getTopicsForExam(simTopics, exam.getExamId())) {
                    if (t.getImportantFlag() == 1 && "REVISION".equalsIgnoreCase(t.getPhase())
                            && t.getRevisionRemainingHours() >= 0.25   // a 5-minute leftover cannot fill a gap
                            && topicFillerCounts.getOrDefault(t.getTopicId(), 0) == 0) {
                        learned.add(t);
                    }
                }
                if (!learned.isEmpty()) {
                    learned.sort((t1, t2) -> {
                        if (t1.getImportance() != t2.getImportance()) {
                            return Integer.compare(t2.getImportance(), t1.getImportance());
                        }
                        return Long.compare(t1.getSyllabusOrder(), t2.getSyllabusOrder());
                    });
                    Topic chosen = learned.get(0);
                    return new FillerCandidate("TOPIC_REVISE", chosen.getTopicId(), getItemSubject(exam, simExamMap),
                            exam.getExamId(), false, chosen.getRevisionRemainingHours());
                }
            }
            // 3. Previous-year papers, once the exam's planned topics are learned
            for (Exam exam : exams) {
                if (exam.getPyqRemainingHours() >= 0.25
                        && scoringService.pyqEligible(exam, getTopicsForExam(simTopics, exam.getExamId()), date)) {
                    return new FillerCandidate("PYQ_PRACTICE", exam.getExamId(), getItemSubject(exam, simExamMap),
                            exam.getExamId(), false, exam.getPyqRemainingHours());
                }
            }
        }

        // 4. Legacy skills without subtopics
        if (!skipSkills && simSkills != null && !simSkills.isEmpty()) {
            Set<Integer> withSubtopics = new HashSet<>();
            if (simSubtopics != null) for (SkillSubtopic st : simSubtopics) withSubtopics.add(st.getSkillId());
            List<Skill> candidates = new ArrayList<>();
            for (Skill s : simSkills) {
                if (!withSubtopics.contains(s.getSkillId()) && !skillsToday.contains(s.getSkillId())) {
                    candidates.add(s);
                }
            }
            if (!candidates.isEmpty()) {
                candidates.sort(Comparator.comparingInt((Skill s) -> -s.getPriority()).thenComparing(Skill::getSkillId));
                return new FillerCandidate("SKILL", candidates.get(0).getSkillId(), "SKILL", null, true);
            }
        }
        return null;
    }

    private String getItemSubject(Exam e, Map<Integer, Exam> simExamMap) {
        if (e == null) return null;
        return e.getSubjectCode() != null ? e.getSubjectCode() : e.getSubject();
    }

    /**
     * Updates simulated working copies when a session is scheduled or logged.
     */
    private void consumeSessionHours(String itemType, int itemId, double duration, LocalDate date,
                                     Map<Integer, Topic> simTopicMap,
                                     Map<Integer, Exam> simExamMap,
                                     Map<Integer, Skill> simSkillMap,
                                     Map<Integer, SkillSubtopic> simSubtopicMap) {
        if ("TOPIC_LEARN".equalsIgnoreCase(itemType)) {
            Topic t = simTopicMap.get(itemId);
            if (t != null) {
                double newRem = t.getRemainingHours() - duration;
                if (newRem <= 0.01) {
                    t.setRemainingHours(0.0);
                    t.setPhase("REVISION");
                    t.setRevisionRemainingHours(t.getRevisionHoursNeeded());
                    t.setLastRevised(date);
                } else {
                    t.setRemainingHours(newRem);
                }
            }
        } else if ("TOPIC_REVISE".equalsIgnoreCase(itemType)) {
            Topic t = simTopicMap.get(itemId);
            if (t != null) {
                double newRevRem = t.getRevisionRemainingHours() - duration;
                if (newRevRem <= 0.01) {
                    t.setLastRevised(date);
                    t.setRevisionRemainingHours(t.getRevisionHoursNeeded()); // cycle resets
                } else {
                    t.setRevisionRemainingHours(newRevRem);
                }
            }
        } else if ("PYQ_PRACTICE".equalsIgnoreCase(itemType)) {
            Exam e = simExamMap.get(itemId);
            if (e != null) {
                e.setPyqRemainingHours(Math.max(0.0, e.getPyqRemainingHours() - duration));
            }
        } else if ("SKILL_LEARN".equalsIgnoreCase(itemType)) {
            SkillSubtopic st = simSubtopicMap != null ? simSubtopicMap.get(itemId) : null;
            if (st != null) {
                double newRem = st.getLearnRemainingHours() - duration;
                if (newRem <= 0.01) {
                    st.setLearnRemainingHours(0.0);
                    st.markLearned(date);
                } else {
                    st.setLearnRemainingHours(newRem);
                }
            }
        } else if ("SKILL_REVISE".equalsIgnoreCase(itemType)) {
            SkillSubtopic st = simSubtopicMap != null ? simSubtopicMap.get(itemId) : null;
            if (st != null) {
                double newRem = st.getReviseRemainingHours() - duration;
                if (newRem <= 0.01) {
                    st.setReviseRemainingHours(0.0);
                    st.markRevised(date);
                } else {
                    st.setReviseRemainingHours(newRem);
                }
            }
        } else if ("SKILL".equalsIgnoreCase(itemType)) {
            Skill s = simSkillMap.get(itemId);
            if (s != null) {
                s.setLastPracticed(date);
            }
        }
    }

    private List<Topic> getTopicsForExam(List<Topic> topics, int examId) {
        List<Topic> list = new ArrayList<>();
        for (Topic t : topics) {
            if (t.getExamId() == examId) list.add(t);
        }
        return list;
    }

    private List<Exam> getNormalExamsWithEveOn(List<Exam> exams, LocalDate date) {
        List<Exam> list = new ArrayList<>();
        LocalDate tomorrow = date.plusDays(1);
        for (Exam e : exams) {
            if ("NORMAL".equalsIgnoreCase(e.getExamType()) && e.getExamDate() != null && e.getExamDate().equals(tomorrow)) {
                list.add(e);
            }
        }
        return list;
    }

    private List<Exam> getClashExamsOn(List<Exam> exams, LocalDate date) {
        List<Exam> list = new ArrayList<>();
        for (Exam e : exams) {
            if (e.getExamDate() != null && e.getExamDate().equals(date)) {
                list.add(e);
            }
        }
        return list;
    }


    public boolean checkAndResetBatchIfWindowClosed(int semesterId, String batchType, LocalDate today) throws SQLException {
        List<Exam> exams = examDAO.findBatch(semesterId, batchType);
        if (exams.isEmpty()) {
            return false;
        }
        LocalDate latestExamDate = null;
        for (Exam e : exams) {
            if (e.getExamDate() == null) {
                // Undated exam: if its rough window is still valid, the batch is not closed yet.
                // An undated exam with no window (or a lapsed window) does NOT block the reset.
                LocalDate windowEnd = e.getWindowEndDate() != null ? e.getWindowEndDate() : e.getWindowStartDate();
                if (windowEnd != null && !today.isAfter(windowEnd)) {
                    // This undated exam's window has not yet elapsed, so the batch is still live
                    return false;
                }
                // Window has elapsed without a datesheet: treat it as already finished and skip
                continue;
            }
            if (latestExamDate == null || e.getExamDate().isAfter(latestExamDate)) {
                latestExamDate = e.getExamDate();
            }
        }
        if (latestExamDate != null && today.isAfter(latestExamDate)) {
            topicDAO.setImportantFlagForBatch(semesterId, batchType, 1);
            settingsDAO.set("exam_period_extra_hours_" + batchType.toUpperCase(), "0.0");
            return true;
        }
        return false;
    }

    public void checkAndResetBatchImportantFlags(int semesterId, List<Exam> allExams, LocalDate today) throws SQLException {
        for (String type : List.of("MST", "EST")) {
            checkAndResetBatchIfWindowClosed(semesterId, type, today);
        }
    }

    private double getSimulatedRemainingHours(PriorityItem item,
                                               Map<Integer, Topic> simTopicMap,
                                               Map<Integer, Exam> simExamMap,
                                               Map<Integer, SkillSubtopic> simSubtopicMap) {
        if (item == null) return 0.0;
        if (item.getItemType() == PriorityItem.ItemType.TOPIC_LEARN) {
            Topic t = simTopicMap.get(item.getId());
            return (t != null && "LEARNING".equalsIgnoreCase(t.getPhase())) ? t.getRemainingHours() : 0.0;
        } else if (item.getItemType() == PriorityItem.ItemType.TOPIC_REVISE) {
            Topic t = simTopicMap.get(item.getId());
            return t != null ? t.getRevisionRemainingHours() : 0.0;
        } else if (item.getItemType() == PriorityItem.ItemType.PYQ_PRACTICE) {
            Exam e = simExamMap.get(item.getId());
            return e != null ? e.getPyqRemainingHours() : 0.0;
        } else if (item.getItemType() == PriorityItem.ItemType.SKILL_LEARN) {
            SkillSubtopic st = simSubtopicMap != null ? simSubtopicMap.get(item.getId()) : null;
            return (st != null && !st.isLearned()) ? st.getLearnRemainingHours() : 0.0;
        } else if (item.getItemType() == PriorityItem.ItemType.SKILL_REVISE) {
            SkillSubtopic st = simSubtopicMap != null ? simSubtopicMap.get(item.getId()) : null;
            return (st != null && st.isLearned() && !st.isRevised()) ? st.getReviseRemainingHours() : 0.0;
        } else {
            return item.getRemainingHours();
        }
    }
}
