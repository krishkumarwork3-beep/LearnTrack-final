package com.learntrack.dao;

import com.learntrack.model.DateOverride;
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
 * Data Access Object for date_overrides.
 * Plan Section 3 & 11.
 */
public class DateOverrideDAO {

    public int insert(DateOverride override) throws SQLException {
        String sql = "INSERT INTO date_overrides (override_date, entry_id, action, start_time, end_time, activity_type, label) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, DateUtils.formatDate(override.getOverrideDate()));
            if (override.getEntryId() != null) {
                stmt.setInt(2, override.getEntryId());
            } else {
                stmt.setNull(2, java.sql.Types.INTEGER);
            }
            stmt.setString(3, override.getAction());
            stmt.setString(4, DateUtils.formatTime(override.getStartTime()));
            stmt.setString(5, DateUtils.formatTime(override.getEndTime()));
            stmt.setString(6, override.getActivityType());
            stmt.setString(7, override.getLabel());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    override.setOverrideId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    public List<DateOverride> findByDate(LocalDate date) throws SQLException {
        List<DateOverride> list = new ArrayList<>();
        String sql = "SELECT override_id, override_date, entry_id, action, start_time, end_time, activity_type, label " +
                "FROM date_overrides WHERE override_date = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(date));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Integer entryId = rs.getObject("entry_id") != null ? rs.getInt("entry_id") : null;
                    list.add(new DateOverride(
                            rs.getInt("override_id"),
                            DateUtils.parseDate(rs.getString("override_date")),
                            entryId,
                            rs.getString("action"),
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

    public void delete(int overrideId) throws SQLException {
        String sql = "DELETE FROM date_overrides WHERE override_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, overrideId);
            stmt.executeUpdate();
        }
    }

    /**
     * Deletes all date_overrides associated with a specific entry_id.
     * Used when a weekly_timetable row is removed during an ID-preserving save (Fix 2).
     */
    public void deleteByEntryId(int entryId) throws SQLException {
        String sql = "DELETE FROM date_overrides WHERE entry_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entryId);
            stmt.executeUpdate();
        }
    }
    public void deleteByDateAndEntry(LocalDate date, int entryId, String action) throws SQLException {
        String sql = "DELETE FROM date_overrides WHERE override_date = ? AND entry_id = ? AND action = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(date));
            stmt.setInt(2, entryId);
            stmt.setString(3, action);
            stmt.executeUpdate();
        }
    }

    public boolean hasCancellation(LocalDate date, int entryId) throws SQLException {
        String sql = "SELECT 1 FROM date_overrides WHERE override_date = ? AND entry_id = ? AND action = 'CANCEL' LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(date));
            stmt.setInt(2, entryId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void deleteBySemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM date_overrides WHERE entry_id IN (SELECT entry_id FROM weekly_timetable WHERE semester_id = ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }

    public void clearAll() throws SQLException {
        String sql = "DELETE FROM date_overrides";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }
}
