package com.learntrack.service;

import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes a subject and all its DB leftovers (kept_subjects, subject_names,
 * subject_credits, exams, topics, syllabus_units, non-DONE daily_log rows)
 * from the given semester in a single transaction.
 *
 * DONE rows in daily_log are never touched (history preservation).
 */
public class SubjectCleanupService {

    /**
     * Delete every trace of {@code subjectCode} from the active semester.
     *
     * @param semesterId active semester
     * @param subjectCode canonical UPPER-CASE code to remove
     * @throws SQLException on any DB error (transaction is rolled back)
     */
    public void removeSubjectCompletely(int semesterId, String subjectCode) throws SQLException {
        if (subjectCode == null || subjectCode.trim().isEmpty()) return;
        String code = subjectCode.trim().toUpperCase();

        try (Connection conn = DBConnection.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. Collect exam_ids for this subject
                List<Integer> examIds = new ArrayList<>();
                String qExams = "SELECT exam_id FROM exams WHERE semester_id = ? AND UPPER(COALESCE(subject_code, subject)) = ?";
                try (PreparedStatement ps = conn.prepareStatement(qExams)) {
                    ps.setInt(1, semesterId);
                    ps.setString(2, code);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) examIds.add(rs.getInt(1));
                    }
                }

                for (int examId : examIds) {
                    // 1a. Delete non-DONE topic daily_log rows for this exam's topics
                    String delTopicLog = "DELETE FROM daily_log WHERE item_type IN ('TOPIC_LEARN','TOPIC_REVISE') " +
                            "AND status != 'DONE' AND item_id IN (SELECT topic_id FROM topics WHERE exam_id = ?)";
                    try (PreparedStatement ps = conn.prepareStatement(delTopicLog)) {
                        ps.setInt(1, examId); ps.executeUpdate();
                    }

                    // 1b. Delete non-DONE PYQ daily_log rows
                    String delPyqLog = "DELETE FROM daily_log WHERE item_type = 'PYQ_PRACTICE' " +
                            "AND status != 'DONE' AND item_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(delPyqLog)) {
                        ps.setInt(1, examId); ps.executeUpdate();
                    }

                    // 1c. Delete topics
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM topics WHERE exam_id = ?")) {
                        ps.setInt(1, examId); ps.executeUpdate();
                    }

                    // 1d. Delete exam (cascades exam_syllabus_scope via FK ON DELETE CASCADE)
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM exams WHERE exam_id = ?")) {
                        ps.setInt(1, examId); ps.executeUpdate();
                    }
                }

                // 2. Delete syllabus units (subtopics cascade via ON DELETE CASCADE)
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM syllabus_units WHERE semester_id = ? AND UPPER(subject_code) = ?")) {
                    ps.setInt(1, semesterId); ps.setString(2, code); ps.executeUpdate();
                }

                // 3. Delete subject_credits
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM subject_credits WHERE semester_id = ? AND UPPER(subject_code) = ?")) {
                    ps.setInt(1, semesterId); ps.setString(2, code); ps.executeUpdate();
                }

                // 4. Delete subject_names
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM subject_names WHERE semester_id = ? AND UPPER(subject_code) = ?")) {
                    ps.setInt(1, semesterId); ps.setString(2, code); ps.executeUpdate();
                }

                // 5. Delete kept_subjects
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM kept_subjects WHERE semester_id = ? AND UPPER(subject_code) = ?")) {
                    ps.setInt(1, semesterId); ps.setString(2, code); ps.executeUpdate();
                }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(prev);
            }
        }
    }

    /**
     * Returns stale subject codes: present in exams but NOT in timetable
     * CLASS/TUTORIAL/LAB and NOT in kept_subjects, and none of their exams
     * has a confirmed (non-tentative) exam_date.
     */
    public List<String> findOrphanedExamSubjects(int semesterId) throws SQLException {
        String sql =
            "SELECT DISTINCT UPPER(COALESCE(e.subject_code, e.subject)) AS code " +
            "FROM exams e " +
            "WHERE e.semester_id = ? " +
            "  AND UPPER(COALESCE(e.subject_code, e.subject)) NOT IN (" +
            "      SELECT DISTINCT UPPER(COALESCE(wt.subject_code," +
            "         TRIM(CASE WHEN LOWER(wt.label) LIKE '% lecture' THEN SUBSTR(wt.label,1,LENGTH(wt.label)-8)" +
            "                   WHEN LOWER(wt.label) LIKE '% tutorial' THEN SUBSTR(wt.label,1,LENGTH(wt.label)-9)" +
            "                   WHEN LOWER(wt.label) LIKE '% practical' THEN SUBSTR(wt.label,1,LENGTH(wt.label)-10)" +
            "                   WHEN LOWER(wt.label) LIKE '% lab' THEN SUBSTR(wt.label,1,LENGTH(wt.label)-4)" +
            "                   ELSE wt.label END))) " +
            "      FROM weekly_timetable wt " +
            "      WHERE wt.semester_id = ? AND UPPER(wt.activity_type) IN ('CLASS','TUTORIAL','LAB')) " +
            "  AND UPPER(COALESCE(e.subject_code, e.subject)) NOT IN (" +
            "      SELECT UPPER(subject_code) FROM kept_subjects WHERE semester_id = ?) " +
            "  AND NOT EXISTS (" +
            "      SELECT 1 FROM exams e2 " +
            "      WHERE e2.semester_id = e.semester_id " +
            "        AND UPPER(COALESCE(e2.subject_code, e2.subject)) = UPPER(COALESCE(e.subject_code, e.subject)) " +
            "        AND e2.exam_date IS NOT NULL AND e2.exam_date_tentative = 0) " +
            "ORDER BY code";

        List<String> codes = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.setInt(2, semesterId);
            ps.setInt(3, semesterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) codes.add(rs.getString("code"));
            }
        }
        return codes;
    }
}
