package com.learntrack.dao;

import com.learntrack.model.ExamPeriodRoutineEntry;
import com.learntrack.util.DBConnection;
import com.learntrack.util.DateUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for exam_period_routine.
 * Plan Section 3.
 */
public class ExamPeriodRoutineDAO {

    public int insertEntry(ExamPeriodRoutineEntry entry) throws SQLException {
        String sql = "INSERT INTO exam_period_routine (semester_id, start_time, end_time, activity_type, label) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, entry.getSemesterId());
            stmt.setString(2, DateUtils.formatTime(entry.getStartTime()));
            stmt.setString(3, DateUtils.formatTime(entry.getEndTime()));
            stmt.setString(4, entry.getActivityType());
            stmt.setString(5, entry.getLabel());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    entry.setRoutineId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    public void saveRoutineEntries(int semesterId, List<ExamPeriodRoutineEntry> entries) throws SQLException {
        deleteBySemester(semesterId);
        String sql = "INSERT INTO exam_period_routine (semester_id, start_time, end_time, activity_type, label) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (ExamPeriodRoutineEntry entry : entries) {
                stmt.setInt(1, semesterId);
                stmt.setString(2, DateUtils.formatTime(entry.getStartTime()));
                stmt.setString(3, DateUtils.formatTime(entry.getEndTime()));
                stmt.setString(4, entry.getActivityType());
                stmt.setString(5, entry.getLabel());
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        entry.setRoutineId(rs.getInt(1));
                    }
                }
            }
        }
    }

    public List<ExamPeriodRoutineEntry> findBySemester(int semesterId) throws SQLException {
        List<ExamPeriodRoutineEntry> list = new ArrayList<>();
        String sql = "SELECT routine_id, semester_id, start_time, end_time, activity_type, label " +
                "FROM exam_period_routine WHERE semester_id = ? ORDER BY start_time";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new ExamPeriodRoutineEntry(
                            rs.getInt("routine_id"),
                            rs.getInt("semester_id"),
                            DateUtils.parseTime(rs.getString("start_time")),
                            DateUtils.parseTime(rs.getString("end_time")),
                            rs.getString("activity_type"),
                            rs.getString("label")
                    ));
                }
            }
        }
        return list;
    }

    public void deleteBySemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM exam_period_routine WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }
}
