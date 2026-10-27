package com.learntrack.dao;

import com.learntrack.model.Exam;
import com.learntrack.util.DBConnection;
import com.learntrack.util.DateUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for exams.
 * Plan Section 3, 4, 5, 12.
 */
public class ExamDAO {

    /**
     * Validates and inserts an exam. Enforces:
     * - At most one MST and one EST per subject per semester
     * - Unique eval_name within subject (case-insensitive) for NORMAL exams
     * - subject_code is required
     * 
     * @throws SQLException if validation fails or database error occurs
     * @throws IllegalArgumentException if validation rules are violated
     */
    public int insert(Exam exam) throws SQLException {
        validateExam(exam);
        
        String sql = "INSERT INTO exams (semester_id, subject, subject_code, exam_type, eval_name, credits, eval_marks, exam_date, " +
                "exam_date_tentative, exam_start_time, exam_end_time, travel_time_minutes, window_start_date, window_end_date, pyq_remaining_hours) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, exam.getSemesterId());
            stmt.setString(2, exam.getSubject());
            stmt.setString(3, exam.getSubjectCode());
            stmt.setString(4, exam.getExamType());
            stmt.setString(5, exam.getEvalName());
            stmt.setDouble(6, exam.getCredits());
            stmt.setDouble(7, exam.getEvalMarks());
            stmt.setString(8, DateUtils.formatDate(exam.getExamDate()));
            stmt.setInt(9, exam.isExamDateTentative() ? 1 : 0);
            stmt.setString(10, DateUtils.formatTime(exam.getExamStartTime()));
            stmt.setString(11, DateUtils.formatTime(exam.getExamEndTime()));
            stmt.setInt(12, exam.getTravelTimeMinutes());
            stmt.setString(13, DateUtils.formatDate(exam.getWindowStartDate()));
            stmt.setString(14, DateUtils.formatDate(exam.getWindowEndDate()));
            stmt.setDouble(15, exam.getPyqRemainingHours());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    exam.setExamId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    /**
     * Validates and updates an exam. Same validation rules as insert.
     */
    public void update(Exam exam) throws SQLException {
        validateExam(exam);
        
        String sql = "UPDATE exams SET subject = ?, subject_code = ?, exam_type = ?, eval_name = ?, credits = ?, eval_marks = ?, exam_date = ?, " +
                "exam_date_tentative = ?, exam_start_time = ?, exam_end_time = ?, travel_time_minutes = ?, window_start_date = ?, " +
                "window_end_date = ?, pyq_remaining_hours = ? WHERE exam_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, exam.getSubject());
            stmt.setString(2, exam.getSubjectCode());
            stmt.setString(3, exam.getExamType());
            stmt.setString(4, exam.getEvalName());
            stmt.setDouble(5, exam.getCredits());
            stmt.setDouble(6, exam.getEvalMarks());
            stmt.setString(7, DateUtils.formatDate(exam.getExamDate()));
            stmt.setInt(8, exam.isExamDateTentative() ? 1 : 0);
            stmt.setString(9, DateUtils.formatTime(exam.getExamStartTime()));
            stmt.setString(10, DateUtils.formatTime(exam.getExamEndTime()));
            stmt.setInt(11, exam.getTravelTimeMinutes());
            stmt.setString(12, DateUtils.formatDate(exam.getWindowStartDate()));
            stmt.setString(13, DateUtils.formatDate(exam.getWindowEndDate()));
            stmt.setDouble(14, exam.getPyqRemainingHours());
            stmt.setInt(15, exam.getExamId());
            stmt.executeUpdate();
        }
    }

    /**
     * Validates exam business rules:
     * - subject_code is preferred but not strictly required (for backward compatibility)
     * - At most one MST and one EST per subject (if subject_code is set)
     * - eval_name is required for NORMAL exams, NULL for MST/EST
     * - eval_name must be unique within subject (case-insensitive, if subject_code is set)
     */
    private void validateExam(Exam exam) throws SQLException {
        String examType = exam.getExamType();
        if (examType == null || examType.trim().isEmpty()) {
            throw new IllegalArgumentException("Exam type is required");
        }
        
        String subjectCode = exam.getSubjectCode();
        
        // MST/EST: check for duplicates (only if subject_code is set)
        if ("MST".equals(examType) || "EST".equals(examType)) {
            if (exam.getEvalName() != null && !exam.getEvalName().trim().isEmpty()) {
                throw new IllegalArgumentException(examType + " exams cannot have an evaluation name");
            }
            
            if (subjectCode != null && !subjectCode.trim().isEmpty()) {
                try (Connection conn = DBConnection.getConnection()) {
                    String sql = "SELECT exam_id FROM exams WHERE semester_id = ? AND subject_code = ? AND exam_type = ? AND exam_id != ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, exam.getSemesterId());
                        stmt.setString(2, subjectCode);
                        stmt.setString(3, examType);
                        stmt.setInt(4, exam.getExamId() != null ? exam.getExamId() : -1);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                throw new IllegalArgumentException("Only one " + examType + " allowed per subject");
                            }
                        }
                    }
                }
            }
        }
        
        // NORMAL: check eval_name (only strictly required if subject_code is set)
        if ("NORMAL".equals(examType)) {
            boolean hasSubjectCode = subjectCode != null && !subjectCode.trim().isEmpty();
            boolean hasEvalName = exam.getEvalName() != null && !exam.getEvalName().trim().isEmpty();
            
            // For new exams with subject_code, eval_name is required
            // For old exams without subject_code, eval_name is optional (backward compatibility)
            if (hasSubjectCode && !hasEvalName) {
                throw new IllegalArgumentException("Other evaluations require a name");
            }
            
            // Check for duplicate eval_name within subject (case-insensitive, only if both are set)
            if (hasSubjectCode && hasEvalName) {
                try (Connection conn = DBConnection.getConnection()) {
                    String sql = "SELECT exam_id FROM exams WHERE semester_id = ? AND subject_code = ? AND " +
                            "exam_type = 'NORMAL' AND UPPER(eval_name) = ? AND exam_id != ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, exam.getSemesterId());
                        stmt.setString(2, subjectCode);
                        stmt.setString(3, exam.getEvalName().trim().toUpperCase());
                        stmt.setInt(4, exam.getExamId() != null ? exam.getExamId() : -1);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                throw new IllegalArgumentException("An evaluation with this name already exists for this subject");
                            }
                        }
                    }
                }
            }
        }
    }

    public Exam findById(int examId) throws SQLException {
        String sql = "SELECT exam_id, semester_id, subject, subject_code, exam_type, eval_name, credits, eval_marks, exam_date, " +
                "exam_date_tentative, exam_start_time, exam_end_time, travel_time_minutes, window_start_date, window_end_date, pyq_remaining_hours " +
                "FROM exams WHERE exam_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, examId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapExam(rs);
                }
            }
        }
        return null;
    }

    public List<Exam> findBySemester(int semesterId) throws SQLException {
        List<Exam> list = new ArrayList<>();
        String sql = "SELECT exam_id, semester_id, subject, subject_code, exam_type, eval_name, credits, eval_marks, exam_date, " +
                "exam_date_tentative, exam_start_time, exam_end_time, travel_time_minutes, window_start_date, window_end_date, pyq_remaining_hours " +
                "FROM exams WHERE semester_id = ? ORDER BY exam_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapExam(rs));
                }
            }
        }
        return list;
    }

    /**
     * Find all exams for a specific subject in a semester.
     */
    public List<Exam> findBySubject(int semesterId, String subjectCode) throws SQLException {
        List<Exam> list = new ArrayList<>();
        String sql = "SELECT exam_id, semester_id, subject, subject_code, exam_type, eval_name, credits, eval_marks, exam_date, " +
                "exam_date_tentative, exam_start_time, exam_end_time, travel_time_minutes, window_start_date, window_end_date, pyq_remaining_hours " +
                "FROM exams WHERE semester_id = ? AND subject_code = ? ORDER BY " +
                "CASE exam_type WHEN 'MST' THEN 1 WHEN 'EST' THEN 2 ELSE 3 END, eval_name";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, subjectCode);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapExam(rs));
                }
            }
        }
        return list;
    }

    /**
     * Find all exams for a specific subject in a semester (by subject code).
     */
    public List<Exam> findBySemesterAndSubject(int semesterId, String subjectCode) throws SQLException {
        return findBySubject(semesterId, subjectCode);
    }

    /**
     * Finds all exams of the same exam_type within a semester (the batch key: 'MST' or 'EST').
     * Plan Section 4 & 9.
     */
    public List<Exam> findBatch(int semesterId, String examType) throws SQLException {
        List<Exam> list = new ArrayList<>();
        String sql = "SELECT exam_id, semester_id, subject, subject_code, exam_type, eval_name, credits, eval_marks, exam_date, " +
                "exam_date_tentative, exam_start_time, exam_end_time, travel_time_minutes, window_start_date, window_end_date, pyq_remaining_hours " +
                "FROM exams WHERE semester_id = ? AND exam_type = ? ORDER BY exam_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, examType);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapExam(rs));
                }
            }
        }
        return list;
    }

    /**
     * Finds all exams sitting on a specific date in the semester.
     * Plan Section 4.
     */
    public List<Exam> findDatedOnDate(int semesterId, LocalDate date) throws SQLException {
        List<Exam> list = new ArrayList<>();
        String sql = "SELECT exam_id, semester_id, subject, subject_code, exam_type, eval_name, credits, eval_marks, exam_date, " +
                "exam_date_tentative, exam_start_time, exam_end_time, travel_time_minutes, window_start_date, window_end_date, pyq_remaining_hours " +
                "FROM exams WHERE semester_id = ? AND exam_date = ? ORDER BY exam_start_time";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, DateUtils.formatDate(date));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapExam(rs));
                }
            }
        }
        return list;
    }

    /**
     * Finds all unfinished exams for the semester.
     * Plan Section 5: exam_date is unset or not yet past.
     */
    public List<Exam> findUnfinished(int semesterId, LocalDate today) throws SQLException {
        List<Exam> all = findBySemester(semesterId);
        List<Exam> unfinished = new ArrayList<>();
        for (Exam e : all) {
            if (e.isUnfinished(today)) {
                unfinished.add(e);
            }
        }
        return unfinished;
    }

    public void delete(int examId) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. Delete daily_log rows for topics belonging to this exam
                String delTopicLogs = "DELETE FROM daily_log WHERE item_type IN ('TOPIC_LEARN', 'TOPIC_REVISE') AND item_id IN (" +
                        "SELECT topic_id FROM topics WHERE exam_id = ?)";
                try (PreparedStatement stmt = conn.prepareStatement(delTopicLogs)) {
                    stmt.setInt(1, examId);
                    stmt.executeUpdate();
                }

                // 2. Delete daily_log rows for PYQ_PRACTICE for this exam
                String delPyqLogs = "DELETE FROM daily_log WHERE item_type = 'PYQ_PRACTICE' AND item_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(delPyqLogs)) {
                    stmt.setInt(1, examId);
                    stmt.executeUpdate();
                }

                // 3. Delete topics under this exam
                String delTopics = "DELETE FROM topics WHERE exam_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(delTopics)) {
                    stmt.setInt(1, examId);
                    stmt.executeUpdate();
                }

                // 4. Delete the exam
                String sql = "DELETE FROM exams WHERE exam_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, examId);
                    stmt.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    public void deleteBySemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM exams WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }

    private Exam mapExam(ResultSet rs) throws SQLException {
        Exam exam = new Exam(
                rs.getInt("exam_id"),
                rs.getInt("semester_id"),
                rs.getString("subject"),
                rs.getString("subject_code"),
                rs.getString("exam_type"),
                rs.getString("eval_name"),
                rs.getDouble("credits"),
                rs.getDouble("eval_marks"),
                DateUtils.parseDate(rs.getString("exam_date")),
                DateUtils.parseTime(rs.getString("exam_start_time")),
                DateUtils.parseTime(rs.getString("exam_end_time")),
                rs.getInt("travel_time_minutes"),
                DateUtils.parseDate(rs.getString("window_start_date")),
                DateUtils.parseDate(rs.getString("window_end_date")),
                rs.getDouble("pyq_remaining_hours")
        );
        
        // Set tentative flag (default to 0 if column doesn't exist yet for migration compatibility)
        try {
            int tentative = rs.getInt("exam_date_tentative");
            exam.setExamDateTentative(tentative == 1);
        } catch (SQLException e) {
            // Column doesn't exist yet, default to false
            exam.setExamDateTentative(false);
        }
        
        return exam;
    }
}
