package com.learntrack.dao;

import com.learntrack.util.DBConnection;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

/**
 * DAO for managing exam syllabus scope (which subtopics are selected for each exam).
 */
public class ExamScopeDAO {

    /**
     * Find all subtopic IDs selected for a specific exam.
     */
    public Set<Integer> findSubtopicIdsForExam(int examId) throws SQLException {
        Set<Integer> subtopicIds = new HashSet<>();
        String sql = "SELECT subtopic_id FROM exam_syllabus_scope WHERE exam_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, examId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    subtopicIds.add(rs.getInt("subtopic_id"));
                }
            }
        }
        return subtopicIds;
    }

    /**
     * Replace the entire scope for an exam with a new set of subtopic IDs.
     * Atomically deletes old scope and inserts new one.
     */
    public void replaceScope(int examId, Set<Integer> subtopicIds) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Delete existing scope
                String deleteSql = "DELETE FROM exam_syllabus_scope WHERE exam_id = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                    pstmt.setInt(1, examId);
                    pstmt.executeUpdate();
                }

                // Insert new scope
                if (!subtopicIds.isEmpty()) {
                    String insertSql = "INSERT INTO exam_syllabus_scope (exam_id, subtopic_id) VALUES (?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (Integer subtopicId : subtopicIds) {
                            pstmt.setInt(1, examId);
                            pstmt.setInt(2, subtopicId);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
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

    /**
     * Copy scope from one exam to another.
     */
    public void copyScope(int fromExamId, int toExamId) throws SQLException {
        Set<Integer> sourceScope = findSubtopicIdsForExam(fromExamId);
        replaceScope(toExamId, sourceScope);
    }

    /**
     * Delete all scope entries for a specific exam.
     * (This happens automatically on exam deletion if CASCADE is working,
     * but provided as an explicit method for clarity.)
     */
    public void deleteScope(int examId) throws SQLException {
        String sql = "DELETE FROM exam_syllabus_scope WHERE exam_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, examId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Count selected subtopics for an exam.
     */
    public int countForExam(int examId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM exam_syllabus_scope WHERE exam_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, examId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }
}
