package com.learntrack.dao;

import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * DAO for managing subject credits and syncing with exams.
 */
public class SubjectCreditDAO {

    public double getExplicitSubjectCredits(int semesterId, String subjectCode) throws SQLException {
        if (subjectCode == null || subjectCode.trim().isEmpty()) {
            return 0.0;
        }
        String upperCode = subjectCode.trim().toUpperCase();
        String sql = "SELECT credits FROM subject_credits WHERE semester_id = ? AND UPPER(subject_code) = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, semesterId);
            pstmt.setString(2, upperCode);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("credits");
                }
            }
        }
        return 0.0;
    }

    public double getCredits(int semesterId, String subjectCode) throws SQLException {
        if (subjectCode == null || subjectCode.trim().isEmpty()) {
            return 4.0;
        }
        String upperCode = subjectCode.trim().toUpperCase();
        String sql = "SELECT credits FROM subject_credits WHERE semester_id = ? AND UPPER(subject_code) = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, semesterId);
            pstmt.setString(2, upperCode);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    double cr = rs.getDouble("credits");
                    if (cr > 0) return cr;
                }
            }
        }
        
        // Fallback: check exams
        String examSql = "SELECT credits FROM exams WHERE semester_id = ? AND (UPPER(subject_code) = ? OR UPPER(subject) = ?) AND credits > 0 LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(examSql)) {
            pstmt.setInt(1, semesterId);
            pstmt.setString(2, upperCode);
            pstmt.setString(3, upperCode);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("credits");
                }
            }
        }

        return 3.0; // Default
    }

    public Map<String, Double> getAllCredits(int semesterId) throws SQLException {
        Map<String, Double> map = new HashMap<>();
        String sql = "SELECT UPPER(subject_code) as code, credits FROM subject_credits WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, semesterId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("code"), rs.getDouble("credits"));
                }
            }
        }
        return map;
    }

    public void setCredits(int semesterId, String subjectCode, double credits) throws SQLException {
        if (credits <= 0) {
            throw new IllegalArgumentException("Credits must be greater than 0");
        }
        if (subjectCode == null || subjectCode.trim().isEmpty()) {
            return;
        }
        String upperCode = subjectCode.trim().toUpperCase();

        try (Connection conn = DBConnection.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. Update/insert subject_credits
                String upsertSql = "INSERT INTO subject_credits (semester_id, subject_code, credits) VALUES (?, ?, ?) " +
                        "ON CONFLICT(semester_id, subject_code) DO UPDATE SET credits = excluded.credits";
                try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
                    pstmt.setInt(1, semesterId);
                    pstmt.setString(2, upperCode);
                    pstmt.setDouble(3, credits);
                    pstmt.executeUpdate();
                }

                // 2. Update all exams for this subject in the active semester
                String updateExamsSql = "UPDATE exams SET credits = ? WHERE semester_id = ? AND " +
                        "(UPPER(subject_code) = ? OR UPPER(subject) = ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(updateExamsSql)) {
                    pstmt.setDouble(1, credits);
                    pstmt.setInt(2, semesterId);
                    pstmt.setString(3, upperCode);
                    pstmt.setString(4, upperCode);
                    pstmt.executeUpdate();
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
}
