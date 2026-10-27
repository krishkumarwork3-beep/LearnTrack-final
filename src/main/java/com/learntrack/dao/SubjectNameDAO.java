package com.learntrack.dao;

import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DAO for subject_names table: maps subject codes to display names per semester.
 */
public class SubjectNameDAO {

    /**
     * Insert or update a subject name for a semester.
     */
    public static void upsert(int semesterId, String subjectCode, String subjectName) throws SQLException {
        String sql = "INSERT INTO subject_names (semester_id, subject_code, subject_name) "
                + "VALUES (?, ?, ?) "
                + "ON CONFLICT(semester_id, subject_code) DO UPDATE SET subject_name = excluded.subject_name";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.setString(2, subjectCode);
            ps.setString(3, subjectName);
            ps.executeUpdate();
        }
    }

    /**
     * Remove a subject name from the registry.
     */
    public static void delete(int semesterId, String subjectCode) throws SQLException {
        String sql = "DELETE FROM subject_names WHERE semester_id = ? AND subject_code = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.setString(2, subjectCode);
            ps.executeUpdate();
        }
    }

    /**
     * Find all subject names for a semester as a map: subject_code → subject_name.
     */
    public static Map<String, String> findAllAsMap(int semesterId) throws SQLException {
        String sql = "SELECT subject_code, subject_name FROM subject_names WHERE semester_id = ? ORDER BY subject_code";
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("subject_code"), rs.getString("subject_name"));
                }
            }
        }
        return result;
    }

    /**
     * Delete all subject names for a semester (used on semester rollover/cleanup).
     */
    public static void deleteAllForSemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM subject_names WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.executeUpdate();
        }
    }
}
