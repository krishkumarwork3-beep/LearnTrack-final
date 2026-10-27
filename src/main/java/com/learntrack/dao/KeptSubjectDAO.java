package com.learntrack.dao;

import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DAO for kept_subjects table: subjects removed from timetable but kept for exam/study tracking.
 */
public class KeptSubjectDAO {

    /**
     * Represents a kept subject (removed from timetable, kept for exams/studies).
     */
    public static class KeptSubject {
        private int semesterId;
        private String subjectCode;
        private String displayName;

        public KeptSubject(int semesterId, String subjectCode, String displayName) {
            this.semesterId = semesterId;
            this.subjectCode = subjectCode;
            this.displayName = displayName;
        }

        public int getSemesterId() {
            return semesterId;
        }

        public String getSubjectCode() {
            return subjectCode;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Insert or update a kept subject for a semester.
     */
    public static void upsert(int semesterId, String subjectCode, String displayName) throws SQLException {
        String sql = "INSERT INTO kept_subjects (semester_id, subject_code, display_name) "
                + "VALUES (?, ?, ?) "
                + "ON CONFLICT(semester_id, subject_code) DO UPDATE SET display_name = excluded.display_name";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.setString(2, subjectCode);
            ps.setString(3, displayName);
            ps.executeUpdate();
        }
    }

    /**
     * Remove a kept subject from the registry.
     */
    public static void delete(int semesterId, String subjectCode) throws SQLException {
        String sql = "DELETE FROM kept_subjects WHERE semester_id = ? AND subject_code = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.setString(2, subjectCode);
            ps.executeUpdate();
        }
    }

    /**
     * Find all kept subjects for a semester.
     */
    public static List<KeptSubject> findAll(int semesterId) throws SQLException {
        String sql = "SELECT subject_code, display_name FROM kept_subjects WHERE semester_id = ? ORDER BY subject_code";
        List<KeptSubject> result = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KeptSubject(semesterId, rs.getString("subject_code"), rs.getString("display_name")));
                }
            }
        }
        return result;
    }

    /**
     * Find all kept subjects for a semester as a map: subject_code → display_name.
     */
    public static Map<String, String> findAllAsMap(int semesterId) throws SQLException {
        String sql = "SELECT subject_code, display_name FROM kept_subjects WHERE semester_id = ?";
        Map<String, String> result = new HashMap<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("subject_code"), rs.getString("display_name"));
                }
            }
        }
        return result;
    }

    /**
     * Check if a subject is in the kept registry.
     */
    public static boolean exists(int semesterId, String subjectCode) throws SQLException {
        String sql = "SELECT 1 FROM kept_subjects WHERE semester_id = ? AND subject_code = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.setString(2, subjectCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Delete all kept subjects for a semester (used on semester rollover/cleanup).
     */
    public static void deleteAllForSemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM kept_subjects WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, semesterId);
            ps.executeUpdate();
        }
    }
}
