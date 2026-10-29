package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.Exam;
import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.Topic;
import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * Service for syncing exam syllabus scope with topics.
 * Implements the covered-before rule (cover once, then revise).
 */
public class ExamScopeService {

    private final ExamScopeDAO examScopeDAO;
    private final TopicDAO topicDAO;
    private final SyllabusDAO syllabusDAO;
    private final ExamDAO examDAO;

    public ExamScopeService() {
        this(new ExamScopeDAO(), new TopicDAO(), new SyllabusDAO(), new ExamDAO());
    }

    public ExamScopeService(ExamScopeDAO examScopeDAO, TopicDAO topicDAO, SyllabusDAO syllabusDAO) {
        this(examScopeDAO, topicDAO, syllabusDAO, new ExamDAO());
    }

    public ExamScopeService(ExamScopeDAO examScopeDAO, TopicDAO topicDAO, SyllabusDAO syllabusDAO, ExamDAO examDAO) {
        this.examScopeDAO = examScopeDAO;
        this.topicDAO = topicDAO;
        this.syllabusDAO = syllabusDAO;
        this.examDAO = examDAO;
    }

    /** Returns the underlying ExamScopeDAO (used by UI for badge lookups). */
    public ExamScopeDAO getExamScopeDAO() {
        return examScopeDAO;
    }

    // -----------------------------------------------------------------------
    // Coverage determination: single source of truth
    // -----------------------------------------------------------------------

    /** Classification of a subtopic's study status relative to a given context. */
    public enum CoverageType {
        /** Normal learning: no earlier exam contains it and not marked done. No badge shown. */
        NOT_STUDIED,
        /** User ticked "Already studied" checkbox. Badge: "Studied (revise only)". */
        DONE_BY_USER,
        /** Covered in an earlier exam whose date is strictly before today. Badge: "Covered in X (done), revise only". */
        COVERED_IN_PAST_EXAM,
        /** Covered in an earlier exam that has NOT happened yet (date >= today or undated).
         *  Badge: "Learn for X, revise for Y". NOT treated as done. */
        SHARED_WITH_EARLIER_EXAM
    }

    /** Result of coverage determination. */
    public static class TopicCoverage {
        public final CoverageType type;
        /** Human-readable badge text, or null for NOT_STUDIED. */
        public final String badgeText;
        /** Name of the earlier exam that first covers this subtopic, or null. */
        public final String earlierExamName;
        /** Badge CSS style class hint: "done", "past", "shared", or null. */
        public final String styleHint;

        public TopicCoverage(CoverageType type, String badgeText, String earlierExamName, String styleHint) {
            this.type = type;
            this.badgeText = badgeText;
            this.earlierExamName = earlierExamName;
            this.styleHint = styleHint;
        }
    }

    /**
     * Determines the coverage status of a subtopic in the context of the given exam.
     * If {@code currentExamId} is null the method searches all exams in the semester
     * for the subject and returns the status for the LAST exam containing the subtopic
     * (useful for the general Subjects syllabus view where no single exam is selected).
     *
     * @param semesterId    active semester
     * @param subjectCode   subject code (or display name)
     * @param subtopicId    the subtopic to classify
     * @param currentExamId the exam in whose context this is being shown (may be null)
     * @param today         reference date (typically LocalDate.now())
     */
    public TopicCoverage getTopicCoverage(int semesterId, String subjectCode,
                                          int subtopicId, Integer currentExamId,
                                          LocalDate today) {
        try {
            SyllabusSubtopic subtopic = syllabusDAO.findSubtopicById(subtopicId);
            if (subtopic != null && subtopic.isDone()) {
                return new TopicCoverage(CoverageType.DONE_BY_USER,
                        "Studied (revise only)", null, "done");
            }

            // Collect all exams for this subject, sorted chronologically
            List<Exam> exams;
            try (Connection conn = DBConnection.getConnection()) {
                exams = findExamsForSubject(conn, semesterId, subjectCode, subjectCode);
            }
            exams.sort(ExamScopeService::compareExamOrder);

            if (exams.size() <= 1) {
                return new TopicCoverage(CoverageType.NOT_STUDIED, null, null, null);
            }

            // Find which exams actually contain this subtopic
            List<Exam> examsWithSubtopic = new ArrayList<>();
            try (Connection conn = DBConnection.getConnection()) {
                for (Exam e : exams) {
                    Set<Integer> subs = findSubtopicIdsForExam(conn, e.getExamId());
                    if (subs.contains(subtopicId)) {
                        examsWithSubtopic.add(e);
                    }
                }
            }

            if (examsWithSubtopic.size() <= 1) {
                // Only in one exam: straightforward learning
                return new TopicCoverage(CoverageType.NOT_STUDIED, null, null, null);
            }

            // The subtopic appears in multiple exams.  Determine the "current" exam.
            Exam currentExam = null;
            if (currentExamId != null) {
                for (Exam e : examsWithSubtopic) {
                    if (e.getExamId().equals(currentExamId)) {
                        currentExam = e;
                        break;
                    }
                }
            }
            if (currentExam == null) {
                currentExam = examsWithSubtopic.get(examsWithSubtopic.size() - 1);
            }

            // Find the first exam in the SHARED list that precedes currentExam
            Exam earlierExam = null;
            for (Exam e : examsWithSubtopic) {
                if (e.getExamId().equals(currentExam.getExamId())) break;
                earlierExam = e;
            }

            if (earlierExam == null) {
                // currentExam IS the first exam, with no earlier coverage
                return new TopicCoverage(CoverageType.NOT_STUDIED, null, null, null);
            }

            String earlierName = getExamDisplayName(earlierExam);
            String currentName = getExamDisplayName(currentExam);

            // Determine earlier exam date
            LocalDate earlierDate = earlierExam.getExamDate() != null ? earlierExam.getExamDate()
                    : (earlierExam.getWindowStartDate() != null ? earlierExam.getWindowStartDate()
                    : earlierExam.getWindowEndDate());

            boolean earlierIsPast = earlierDate != null && earlierDate.isBefore(today);

            if (earlierIsPast) {
                return new TopicCoverage(CoverageType.COVERED_IN_PAST_EXAM,
                        "Covered in " + earlierName + " (done), revise only",
                        earlierName, "past");
            } else {
                return new TopicCoverage(CoverageType.SHARED_WITH_EARLIER_EXAM,
                        "Learn for " + earlierName + ", revise for " + currentName,
                        earlierName, "shared");
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new TopicCoverage(CoverageType.NOT_STUDIED, null, null, null);
    }

    /**
     * Find all exams in a semester that have no syllabus scope defined.
     * @param semesterId the semester ID
     * @return list of exams with zero rows in exam_syllabus_scope
     */
    public List<Exam> findExamsWithoutScope(int semesterId) throws SQLException {
        List<Exam> examsWithoutScope = new ArrayList<>();
        List<Exam> allExams = examDAO.findBySemester(semesterId);
        for (Exam exam : allExams) {
            int scopeCount = examScopeDAO.countForExam(exam.getExamId());
            if (scopeCount == 0 && topicDAO.findByExam(exam.getExamId()).isEmpty()) {
                examsWithoutScope.add(exam);
            }
        }
        return examsWithoutScope;
    }

    /**
     * Convenience overload: opens its own connection and delegates to the full
     * {@link #syncTopicPhasesForSubject(Connection, int, String, String)} method.
     * Matches a subject by code; subject display name is passed as-is.
     */
    public void syncTopicPhasesForSubject(int semesterId, String subjectCode) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                syncTopicPhasesForSubject(conn, semesterId, subjectCode, subjectCode);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        }
    }

    /**
     * Save scope and sync topics in one transaction.
     * Returns result with counts of added, removed, and kept studied topics.
     */
    public ScopeSyncResult saveScopeAndSyncTopics(int examId, Set<Integer> selectedSubtopicIds) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Exam currentExam = examDAO.findById(examId);

                // Get old scope
                Set<Integer> oldScope = findSubtopicIdsForExam(conn, examId);
                
                // 1. Replace exam_syllabus_scope
                replaceScope(conn, examId, selectedSubtopicIds);
                
                // 2. Add topics for newly selected subtopics
                Set<Integer> added = new HashSet<>(selectedSubtopicIds);
                added.removeAll(oldScope);
                int addedCount = 0;
                for (Integer subtopicId : added) {
                    Topic existing = findByExamAndSubtopic(conn, examId, subtopicId);
                    if (existing == null) {
                        SyllabusSubtopic subtopic = syllabusDAO.findSubtopicById(subtopicId);
                        if (subtopic != null) {
                            double coverHrs = subtopic.getHoursToCover() > 0 ? subtopic.getHoursToCover() : 1.0;
                            double reviseHrs = subtopic.getHoursToRevise() > 0 ? subtopic.getHoursToRevise() : 0.5;

                            Topic topic = new Topic(
                                null, examId, subtopic.getName(),
                                subtopic.getImportance(), 1, // difficulty = neutral 1 (DifficultyMapper removed from planning)
                                coverHrs, coverHrs, "LEARNING",
                                reviseHrs, reviseHrs, 7, null, 1, subtopicId
                            );
                            topicDAO.insert(conn, topic);
                            addedCount++;
                        }
                    }
                }
                
                // 3. Remove or keep topics for deselected subtopics
                Set<Integer> removed = new HashSet<>(oldScope);
                removed.removeAll(selectedSubtopicIds);
                int removedCount = 0;
                int keptStudied = 0;
                for (Integer subtopicId : removed) {
                    Topic topic = findByExamAndSubtopic(conn, examId, subtopicId);
                    if (topic != null) {
                        if (!topicDAO.hasBeenStudied(topic.getTopicId())) {
                            deleteTopicById(conn, topic.getTopicId());
                            removedCount++;
                        } else {
                            keptStudied++;
                        }
                    }
                }

                // 4. Sync covered-before phases across all exams of this subject
                if (currentExam != null) {
                    syncTopicPhasesForSubject(conn, currentExam.getSemesterId(), currentExam.getSubjectCode(), currentExam.getSubject());
                }
                
                conn.commit();
                return new ScopeSyncResult(addedCount, removedCount, keptStudied);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Synchronizes topic phases (LEARNING vs REVISION) for all exams of a subject
     * according to the covered-before rule.
     */
    public void syncTopicPhasesForSubject(Connection conn, int semesterId, String subjectCode, String subject) throws SQLException {
        LocalDate today = LocalDate.now();
        List<Exam> exams = findExamsForSubject(conn, semesterId, subjectCode, subject);
        if (exams.isEmpty()) return;

        exams.sort(ExamScopeService::compareExamOrder);

        // Map examId -> Set of subtopicIds in scope
        Map<Integer, Set<Integer>> examScopeMap = new HashMap<>();
        Set<Integer> allSubtopicIds = new HashSet<>();
        for (Exam e : exams) {
            Set<Integer> subs = findSubtopicIdsForExam(conn, e.getExamId());
            examScopeMap.put(e.getExamId(), subs);
            allSubtopicIds.addAll(subs);
        }

        // For each subtopic in scope of any exam of this subject
        for (Integer subtopicId : allSubtopicIds) {
            SyllabusSubtopic subtopic = syllabusDAO.findSubtopicById(subtopicId);
            if (subtopic == null) continue;

            double coverHrs = subtopic.getHoursToCover() > 0 ? subtopic.getHoursToCover() : 1.0;
            double reviseHrs = subtopic.getHoursToRevise() > 0 ? subtopic.getHoursToRevise() : 0.5;

            // Find exams in order that include this subtopic
            List<Exam> examsWithSubtopic = new ArrayList<>();
            for (Exam e : exams) {
                if (examScopeMap.get(e.getExamId()).contains(subtopicId)) {
                    examsWithSubtopic.add(e);
                }
            }

            if (examsWithSubtopic.isEmpty()) continue;

            Exam firstExam = examsWithSubtopic.get(0);

            for (Exam e : examsWithSubtopic) {
                Topic topic = findByExamAndSubtopic(conn, e.getExamId(), subtopicId);
                if (topic == null) continue;

                // Check if user has actively studied this topic (has log rows or progress)
                if (topicDAO.hasBeenStudied(topic.getTopicId())) {
                    continue; // Preserve logged user progress
                }

                if (subtopic.isDone()) {
                    // DONE_BY_USER: REVISION for all exams
                    topic.setPhase("REVISION");
                    topic.setHoursNeeded(0.0);
                    topic.setRemainingHours(0.0);
                    topic.setRevisionHoursNeeded(reviseHrs);
                    topic.setRevisionRemainingHours(reviseHrs);
                    updateTopicDirectly(conn, topic);
                } else if (e.getExamId().equals(firstExam.getExamId())) {
                    // First exam containing this subtopic: always LEARN
                    topic.setPhase("LEARNING");
                    topic.setHoursNeeded(coverHrs);
                    topic.setRemainingHours(coverHrs);
                    topic.setRevisionHoursNeeded(reviseHrs);
                    topic.setRevisionRemainingHours(reviseHrs);
                    updateTopicDirectly(conn, topic);
                } else {
                    // Later exam of a SHARED pair: REVISION only (existing logic, keep it)
                    topic.setPhase("REVISION");
                    topic.setHoursNeeded(0.0);
                    topic.setRemainingHours(0.0);
                    topic.setRevisionHoursNeeded(reviseHrs);
                    topic.setRevisionRemainingHours(reviseHrs);
                    updateTopicDirectly(conn, topic);
                }
            }
        }
    }

    /**
     * Synchronizes topic phases across ALL subjects in the active semester.
     * Can be invoked on app open, datesheet changes, replan, etc.
     */
    public void syncAllTopicPhases(int semesterId) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Get all distinct subjects for this semester
                String sql = "SELECT DISTINCT COALESCE(subject_code, subject) as subj_code, subject FROM exams WHERE semester_id = ?";
                List<String[]> subjects = new ArrayList<>();
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, semesterId);
                    try (var rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            subjects.add(new String[]{rs.getString("subj_code"), rs.getString("subject")});
                        }
                    }
                }

                for (String[] pair : subjects) {
                    syncTopicPhasesForSubject(conn, semesterId, pair[0], pair[1]);
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        }
    }

    /**
     * Overload that uses subjectCode as both subjectCode and subject name matchers.
     */
    public void resyncHoursForSubject(int semesterId, String subjectCode) throws SQLException {
        resyncHoursForSubject(semesterId, subjectCode, subjectCode);
    }

    /**
     * Re-syncs hours for ALL topics linked to subtopics of a given subject.
     * Called when subtopic hours are edited in the UI.
     * Preserves topics with real progress (logged study), overwrites everything else.
     * The 3rd argument 'subject' is the subject display name (or fallback match).
     */
    public void resyncHoursForSubject(int semesterId, String subjectCode, String subject) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                syncTopicPhasesForSubject(conn, semesterId, subjectCode, subject);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        }
    }

    /**
     * Rebuilds the entire study plan: re-syncs all topics from subtopics,
     * deletes PLANNED/PENDING daily_log rows, and returns stats.
     */
    public RebuildPlanResult rebuildPlan(int semesterId) throws SQLException {
        int plannedDeleted = 0;
        int pendingDeleted = 0;
        int missedDeleted = 0;
        
        try (Connection conn = DBConnection.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. Re-sync all topic hours from subtopics
                syncAllTopicPhases(semesterId);
                
                // 2. Delete PLANNED daily_log rows for topics in this semester
                String deletePlanned = "DELETE FROM daily_log WHERE status = 'PLANNED' AND (" +
                        "item_id IN (SELECT t.topic_id FROM topics t JOIN exams e ON t.exam_id = e.exam_id WHERE e.semester_id = ?) " +
                        "OR item_id IN (SELECT exam_id FROM exams WHERE semester_id = ?))";
                try (var pstmt = conn.prepareStatement(deletePlanned)) {
                    pstmt.setInt(1, semesterId);
                    pstmt.setInt(2, semesterId);
                    plannedDeleted = pstmt.executeUpdate();
                }
                
                // 3. Delete PENDING or MISSED PYQ_PRACTICE rows for this semester
                String deletePyq = "DELETE FROM daily_log WHERE item_type = 'PYQ_PRACTICE' AND status IN ('PENDING', 'MISSED') " +
                        "AND item_id IN (SELECT exam_id FROM exams WHERE semester_id = ?)";
                try (var pstmt = conn.prepareStatement(deletePyq)) {
                    pstmt.setInt(1, semesterId);
                    int pyqDeleted = pstmt.executeUpdate();
                    pendingDeleted = pyqDeleted; // Count as pending
                }
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        }
        
        return new RebuildPlanResult(plannedDeleted, pendingDeleted, missedDeleted);
    }

    public static class RebuildPlanResult {
        private final int plannedDeleted;
        private final int pendingDeleted;
        private final int missedDeleted;
        
        public RebuildPlanResult(int plannedDeleted, int pendingDeleted, int missedDeleted) {
            this.plannedDeleted = plannedDeleted;
            this.pendingDeleted = pendingDeleted;
            this.missedDeleted = missedDeleted;
        }
        
        public int getPlannedDeleted() { return plannedDeleted; }
        public int getPendingDeleted() { return pendingDeleted; }
        public int getMissedDeleted() { return missedDeleted; }
        public int getTotal() { return plannedDeleted + pendingDeleted + missedDeleted; }
    }

    /**
     * Returns a badge text for a subtopic in the context of the given exam.
     * Delegates to {@link #getTopicCoverage} for correctness.
     * Returns null when no badge is needed (NOT_STUDIED).
     */
    public String getCoveredBeforeBadge(int examId, int subtopicId) {
        try {
            Exam currentExam = examDAO.findById(examId);
            if (currentExam == null) return null;
            TopicCoverage cov = getTopicCoverage(
                    currentExam.getSemesterId(),
                    currentExam.getSubjectCode() != null ? currentExam.getSubjectCode() : currentExam.getSubject(),
                    subtopicId, examId, LocalDate.now());
            return cov.badgeText;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Returns a badge text if the topic's subtopic was covered earlier.
     */
    public String getCoveredBeforeBadgeForTopic(Topic topic) {
        if (topic == null || topic.getSubtopicId() == null) {
            return null;
        }
        return getCoveredBeforeBadge(topic.getExamId(), topic.getSubtopicId());
    }

    /**
     * Friendly display name for an exam (e.g. "MST1", "MST", "EST", "Midterm").
     */
    public static String getExamDisplayName(Exam exam) {
        if (exam == null) return "";
        if (exam.getEvalName() != null && !exam.getEvalName().trim().isEmpty()) {
            return exam.getEvalName().trim();
        }
        if (exam.getExamType() != null && !exam.getExamType().trim().isEmpty()) {
            return exam.getExamType().trim();
        }
        return exam.getSubject() != null ? exam.getSubject() : "Exam";
    }

    /**
     * Compares two exams chronologically by exam date, then window date, then ID.
     */
    public static int compareExamOrder(Exam a, Exam b) {
        LocalDate dateA = a.getExamDate() != null ? a.getExamDate()
                : (a.getWindowStartDate() != null ? a.getWindowStartDate() : a.getWindowEndDate());
        LocalDate dateB = b.getExamDate() != null ? b.getExamDate()
                : (b.getWindowStartDate() != null ? b.getWindowStartDate() : b.getWindowEndDate());

        if (dateA == null && dateB == null) {
            return Integer.compare(a.getExamId(), b.getExamId());
        }
        if (dateA == null) return 1;
        if (dateB == null) return -1;
        int cmp = dateA.compareTo(dateB);
        if (cmp != 0) return cmp;
        return Integer.compare(a.getExamId(), b.getExamId());
    }

    private List<Exam> findExamsForSubject(Connection conn, int semesterId, String subjectCode, String subject) throws SQLException {
        List<Exam> list = new ArrayList<>();
        String sql = "SELECT exam_id, semester_id, subject, subject_code, exam_type, eval_name, credits, eval_marks, " +
                "exam_date, exam_date_tentative, exam_start_time, exam_end_time, travel_time_minutes, window_start_date, " +
                "window_end_date, pyq_remaining_hours FROM exams " +
                "WHERE semester_id = ? AND ((subject_code IS NOT NULL AND (UPPER(subject_code) = UPPER(?) OR UPPER(subject_code) = UPPER(?))) " +
                "OR (subject IS NOT NULL AND (UPPER(subject) = UPPER(?) OR UPPER(subject) = UPPER(?))))";

        String c1 = subjectCode != null ? subjectCode.trim() : "";
        String c2 = subject != null ? subject.trim() : "";

        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, semesterId);
            pstmt.setString(2, c1);
            pstmt.setString(3, c2);
            pstmt.setString(4, c1);
            pstmt.setString(5, c2);
            try (var rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new Exam(
                            rs.getInt("exam_id"),
                            rs.getInt("semester_id"),
                            rs.getString("subject"),
                            rs.getString("subject_code"),
                            rs.getString("exam_type"),
                            rs.getString("eval_name"),
                            rs.getDouble("credits"),
                            rs.getDouble("eval_marks"),
                            parseDate(rs.getString("exam_date")),
                            rs.getInt("exam_date_tentative") == 1,
                            parseTime(rs.getString("exam_start_time")),
                            parseTime(rs.getString("exam_end_time")),
                            rs.getInt("travel_time_minutes"),
                            parseDate(rs.getString("window_start_date")),
                            parseDate(rs.getString("window_end_date")),
                            rs.getDouble("pyq_remaining_hours")
                    ));
                }
            }
        }
        return list;
    }

    private void updateTopicDirectly(Connection conn, Topic topic) throws SQLException {
        String sql = "UPDATE topics SET hours_needed = ?, remaining_hours = ?, phase = ?, " +
                "revision_hours_needed = ?, revision_remaining_hours = ? WHERE topic_id = ?";
        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, topic.getHoursNeeded());
            pstmt.setDouble(2, topic.getRemainingHours());
            pstmt.setString(3, topic.getPhase());
            pstmt.setDouble(4, topic.getRevisionHoursNeeded());
            pstmt.setDouble(5, topic.getRevisionRemainingHours());
            pstmt.setInt(6, topic.getTopicId());
            pstmt.executeUpdate();
        }
    }

    private Set<Integer> findSubtopicIdsForExam(Connection conn, int examId) throws SQLException {
        Set<Integer> subtopicIds = new HashSet<>();
        String sql = "SELECT subtopic_id FROM exam_syllabus_scope WHERE exam_id = ?";
        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, examId);
            try (var rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    subtopicIds.add(rs.getInt("subtopic_id"));
                }
            }
        }
        return subtopicIds;
    }

    private Topic findByExamAndSubtopic(Connection conn, int examId, int subtopicId) throws SQLException {
        String sql = "SELECT topic_id, exam_id, name, importance, difficulty, hours_needed, " +
                "remaining_hours, phase, revision_hours_needed, revision_remaining_hours, " +
                "revision_interval_days, last_revised, important_flag, subtopic_id " +
                "FROM topics WHERE exam_id = ? AND subtopic_id = ?";
        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, examId);
            pstmt.setInt(2, subtopicId);
            try (var rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Integer subId = rs.getInt("subtopic_id");
                    if (rs.wasNull()) subId = null;
                    return new Topic(
                        rs.getInt("topic_id"),
                        rs.getInt("exam_id"),
                        rs.getString("name"),
                        rs.getInt("importance"),
                        rs.getInt("difficulty"),
                        rs.getDouble("hours_needed"),
                        rs.getDouble("remaining_hours"),
                        rs.getString("phase"),
                        rs.getDouble("revision_hours_needed"),
                        rs.getDouble("revision_remaining_hours"),
                        rs.getInt("revision_interval_days"),
                        parseDate(rs.getString("last_revised")),
                        rs.getInt("important_flag"),
                        subId
                    );
                }
            }
        }
        return null;
    }

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static java.time.LocalTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) return null;
        try {
            return java.time.LocalTime.parse(timeStr.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteTopicById(Connection conn, int topicId) throws SQLException {
        String sql = "DELETE FROM topics WHERE topic_id = ?";
        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, topicId);
            pstmt.executeUpdate();
        }
    }

    private void replaceScope(Connection conn, int examId, Set<Integer> subtopicIds) throws SQLException {
        String deleteSql = "DELETE FROM exam_syllabus_scope WHERE exam_id = ?";
        try (var pstmt = conn.prepareStatement(deleteSql)) {
            pstmt.setInt(1, examId);
            pstmt.executeUpdate();
        }

        if (!subtopicIds.isEmpty()) {
            String insertSql = "INSERT INTO exam_syllabus_scope (exam_id, subtopic_id) VALUES (?, ?)";
            try (var pstmt = conn.prepareStatement(insertSql)) {
                for (Integer subtopicId : subtopicIds) {
                    pstmt.setInt(1, examId);
                    pstmt.setInt(2, subtopicId);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
        }
    }

    /**
     * Result of scope sync operation.
     */
    public static class ScopeSyncResult {
        private final int added;
        private final int removed;
        private final int keptStudied;

        public ScopeSyncResult(int added, int removed, int keptStudied) {
            this.added = added;
            this.removed = removed;
            this.keptStudied = keptStudied;
        }

        public int getAdded() {
            return added;
        }

        public int getRemoved() {
            return removed;
        }

        public int getKeptStudied() {
            return keptStudied;
        }
    }
}
