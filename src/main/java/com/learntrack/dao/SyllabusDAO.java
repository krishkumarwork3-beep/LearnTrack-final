package com.learntrack.dao;

import com.learntrack.model.SyllabusUnit;
import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.util.DBConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DAO for managing syllabus units and subtopics.
 */
public class SyllabusDAO {

    // ========== UNIT OPERATIONS ==========

    public int insertUnit(int semesterId, String subjectCode, String name, int position) throws SQLException {
        String sql = "INSERT INTO syllabus_units (semester_id, subject_code, name, position) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, semesterId);
            pstmt.setString(2, subjectCode);
            pstmt.setString(3, name);
            pstmt.setInt(4, position);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve generated unit_id");
    }

    public void updateUnitName(int unitId, String newName) throws SQLException {
        String sql = "UPDATE syllabus_units SET name = ? WHERE unit_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newName);
            pstmt.setInt(2, unitId);
            pstmt.executeUpdate();
        }
    }

    public void updateUnitPosition(int unitId, int newPosition) throws SQLException {
        String sql = "UPDATE syllabus_units SET position = ? WHERE unit_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newPosition);
            pstmt.setInt(2, unitId);
            pstmt.executeUpdate();
        }
    }

    public void deleteUnit(int unitId) throws SQLException {
        // Cascade will delete subtopics automatically if ON DELETE CASCADE is enabled
        // Otherwise, explicitly delete subtopics first
        String deleteSubs = "DELETE FROM syllabus_subtopics WHERE unit_id = ?";
        String deleteUnit = "DELETE FROM syllabus_units WHERE unit_id = ?";
        try (Connection conn = DBConnection.getConnection()) {
            try (PreparedStatement pstmt = conn.prepareStatement(deleteSubs)) {
                pstmt.setInt(1, unitId);
                pstmt.executeUpdate();
            }
            try (PreparedStatement pstmt = conn.prepareStatement(deleteUnit)) {
                pstmt.setInt(1, unitId);
                pstmt.executeUpdate();
            }
        }
    }

    public List<SyllabusUnit> findUnitsBySubject(int semesterId, String subjectCode) throws SQLException {
        String sql = "SELECT unit_id, semester_id, subject_code, name, position " +
                     "FROM syllabus_units WHERE semester_id = ? AND subject_code = ? ORDER BY position";
        List<SyllabusUnit> units = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, semesterId);
            pstmt.setString(2, subjectCode);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    units.add(new SyllabusUnit(
                            rs.getInt("unit_id"),
                            rs.getInt("semester_id"),
                            rs.getString("subject_code"),
                            rs.getString("name"),
                            rs.getInt("position")
                    ));
                }
            }
        }
        return units;
    }

    public SyllabusUnit findUnitById(int unitId) throws SQLException {
        String sql = "SELECT unit_id, semester_id, subject_code, name, position " +
                     "FROM syllabus_units WHERE unit_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, unitId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new SyllabusUnit(
                            rs.getInt("unit_id"),
                            rs.getInt("semester_id"),
                            rs.getString("subject_code"),
                            rs.getString("name"),
                            rs.getInt("position")
                    );
                }
            }
        }
        return null;
    }

    // ========== SUBTOPIC OPERATIONS ==========

    public int insertSubtopic(int unitId, String name, int position) throws SQLException {
        return insertSubtopic(unitId, name, position, 5, 1.0, 0.5);
    }

    public int insertSubtopic(int unitId, String name, int position, int difficulty) throws SQLException {
        return insertSubtopic(unitId, name, position, difficulty, 1.0, 0.5);
    }

    public int insertSubtopic(int unitId, String name, int position, int difficulty, double hoursToCover, double hoursToRevise) throws SQLException {
        String sql = "INSERT INTO syllabus_subtopics (unit_id, name, position, done, difficulty, hours_to_cover, hours_to_revise) VALUES (?, ?, ?, 0, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, unitId);
            pstmt.setString(2, name);
            pstmt.setInt(3, position);
            pstmt.setInt(4, Math.max(1, Math.min(10, difficulty))); // Clamp 1-10
            pstmt.setDouble(5, hoursToCover > 0 ? hoursToCover : 1.0);
            pstmt.setDouble(6, hoursToRevise > 0 ? hoursToRevise : 0.5);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve generated subtopic_id");
    }

    public void updateSubtopicName(int subtopicId, String newName) throws SQLException {
        String sql = "UPDATE syllabus_subtopics SET name = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newName);
            pstmt.setInt(2, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public void updateSubtopicHours(int subtopicId, double hoursToCover, double hoursToRevise) throws SQLException {
        String sql = "UPDATE syllabus_subtopics SET hours_to_cover = ?, hours_to_revise = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, hoursToCover > 0 ? hoursToCover : 1.0);
            pstmt.setDouble(2, hoursToRevise > 0 ? hoursToRevise : 0.5);
            pstmt.setInt(3, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public void updateSubtopicHoursToCover(int subtopicId, double hoursToCover) throws SQLException {
        String sql = "UPDATE syllabus_subtopics SET hours_to_cover = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, hoursToCover > 0 ? hoursToCover : 1.0);
            pstmt.setInt(2, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public void updateSubtopicHoursToRevise(int subtopicId, double hoursToRevise) throws SQLException {
        String sql = "UPDATE syllabus_subtopics SET hours_to_revise = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, hoursToRevise > 0 ? hoursToRevise : 0.5);
            pstmt.setInt(2, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public void bulkUpdateHours(List<Integer> subtopicIds, Double hoursToCover, Double hoursToRevise) throws SQLException {
        if (subtopicIds == null || subtopicIds.isEmpty()) return;
        StringBuilder sql = new StringBuilder("UPDATE syllabus_subtopics SET ");
        boolean hasCover = hoursToCover != null && hoursToCover > 0;
        boolean hasRevise = hoursToRevise != null && hoursToRevise > 0;
        if (!hasCover && !hasRevise) return;

        if (hasCover && hasRevise) {
            sql.append("hours_to_cover = ?, hours_to_revise = ? ");
        } else if (hasCover) {
            sql.append("hours_to_cover = ? ");
        } else {
            sql.append("hours_to_revise = ? ");
        }
        sql.append("WHERE subtopic_id = ?");

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (Integer id : subtopicIds) {
                int param = 1;
                if (hasCover) pstmt.setDouble(param++, hoursToCover);
                if (hasRevise) pstmt.setDouble(param++, hoursToRevise);
                pstmt.setInt(param, id);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    public void updateSubtopicDifficulty(int subtopicId, int difficulty) throws SQLException {
        String sql = "UPDATE syllabus_subtopics SET difficulty = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, Math.max(1, Math.min(10, difficulty))); // Clamp 1-10
            pstmt.setInt(2, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public void updateSubtopicPosition(int subtopicId, int newPosition) throws SQLException {
        String sql = "UPDATE syllabus_subtopics SET position = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newPosition);
            pstmt.setInt(2, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public void updateSubtopicDone(int subtopicId, boolean done) throws SQLException {
        String sql = "UPDATE syllabus_subtopics SET done = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, done ? 1 : 0);
            pstmt.setInt(2, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public void deleteSubtopic(int subtopicId) throws SQLException {
        String sql = "DELETE FROM syllabus_subtopics WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, subtopicId);
            pstmt.executeUpdate();
        }
    }

    public List<SyllabusSubtopic> findSubtopicsByUnit(int unitId) throws SQLException {
        String sql = "SELECT subtopic_id, unit_id, name, position, done, difficulty, hours_to_cover, hours_to_revise, importance " +
                     "FROM syllabus_subtopics WHERE unit_id = ? ORDER BY position";
        List<SyllabusSubtopic> subtopics = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, unitId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    subtopics.add(mapSubtopic(rs));
                }
            }
        }
        return subtopics;
    }

    private static SyllabusSubtopic mapSubtopic(ResultSet rs) throws SQLException {
        SyllabusSubtopic subtopic = new SyllabusSubtopic(
                rs.getInt("subtopic_id"),
                rs.getInt("unit_id"),
                rs.getString("name"),
                rs.getInt("position"),
                rs.getInt("done") == 1,
                rs.getInt("difficulty"),
                rs.getDouble("hours_to_cover"),
                rs.getDouble("hours_to_revise")
        );
        subtopic.setImportance(rs.getInt("importance"));
        return subtopic;
    }

    /**
     * Sets a subtopic's exam importance (1..5) and mirrors it onto every topic linked to it, so the
     * planner and the exam views read the same value. Progress is never touched.
     */
    public void updateSubtopicImportance(int subtopicId, int importance) throws SQLException {
        int value = SyllabusSubtopic.clampImportance(importance);
        try (Connection conn = DBConnection.getConnection()) {
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE syllabus_subtopics SET importance = ? WHERE subtopic_id = ?")) {
                pstmt.setInt(1, value);
                pstmt.setInt(2, subtopicId);
                pstmt.executeUpdate();
            }
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE topics SET importance = ? WHERE subtopic_id = ?")) {
                pstmt.setInt(1, value);
                pstmt.setInt(2, subtopicId);
                pstmt.executeUpdate();
            }
        }
    }

    /** Applies one importance level to every subtopic of a unit (and their linked topics). */
    public void updateUnitImportance(int unitId, int importance) throws SQLException {
        for (SyllabusSubtopic subtopic : findSubtopicsByUnit(unitId)) {
            updateSubtopicImportance(subtopic.getSubtopicId(), importance);
        }
    }

    /** subtopic_id -> importance for every subtopic of the semester; one query per replan. */
    public Map<Integer, Integer> getImportanceMap(int semesterId) throws SQLException {
        String sql = "SELECT s.subtopic_id, s.importance FROM syllabus_subtopics s " +
                     "JOIN syllabus_units u ON s.unit_id = u.unit_id WHERE u.semester_id = ?";
        Map<Integer, Integer> map = new HashMap<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, semesterId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getInt("subtopic_id"), SyllabusSubtopic.clampImportance(rs.getInt("importance")));
                }
            }
        }
        return map;
    }

    public SyllabusSubtopic findSubtopicById(int subtopicId) throws SQLException {
        String sql = "SELECT subtopic_id, unit_id, name, position, done, difficulty, hours_to_cover, hours_to_revise, importance " +
                     "FROM syllabus_subtopics WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, subtopicId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapSubtopic(rs);
                }
            }
        }
        return null;
    }

    // ========== BULK OPERATIONS ==========

    public void deleteAllForSubject(int semesterId, String subjectCode) throws SQLException {
        List<SyllabusUnit> units = findUnitsBySubject(semesterId, subjectCode);
        for (SyllabusUnit unit : units) {
            deleteUnit(unit.getUnitId());
        }
    }

    public void deleteAllForSemester(int semesterId) throws SQLException {
        String deleteSubtopics = "DELETE FROM syllabus_subtopics WHERE unit_id IN " +
                                  "(SELECT unit_id FROM syllabus_units WHERE semester_id = ?)";
        String deleteUnits = "DELETE FROM syllabus_units WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection()) {
            try (PreparedStatement pstmt = conn.prepareStatement(deleteSubtopics)) {
                pstmt.setInt(1, semesterId);
                pstmt.executeUpdate();
            }
            try (PreparedStatement pstmt = conn.prepareStatement(deleteUnits)) {
                pstmt.setInt(1, semesterId);
                pstmt.executeUpdate();
            }
        }
    }

    /**
     * Update difficulty of untouched topics linked to this subtopic.
     * Only affects topics in LEARNING phase where remaining_hours == hours_needed.
     */
    public void updateLinkedTopicDifficulties(int subtopicId, int newDifficulty) throws SQLException {
        int topicDifficulty = com.learntrack.service.DifficultyMapper.toTopicDifficulty(newDifficulty);
        String sql = "UPDATE topics SET difficulty = ? " +
                     "WHERE subtopic_id = ? " +
                     "AND phase = 'LEARNING' " +
                     "AND remaining_hours = hours_needed";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, topicDifficulty);
            pstmt.setInt(2, subtopicId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Returns a map of subtopic_id -> syllabusOrder for all subtopics in a semester.
     * syllabusOrder is computed as unitPosition * 100000L + subtopicPosition.
     * Single query per replan for high performance.
     */
    public Map<Integer, Long> getSyllabusOrderMap(int semesterId) throws SQLException {
        String sql = "SELECT s.subtopic_id, u.position AS unit_pos, s.position AS sub_pos " +
                     "FROM syllabus_subtopics s " +
                     "JOIN syllabus_units u ON s.unit_id = u.unit_id " +
                     "WHERE u.semester_id = ?";
        Map<Integer, Long> map = new HashMap<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, semesterId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int subId = rs.getInt("subtopic_id");
                    long uPos = rs.getLong("unit_pos");
                    long sPos = rs.getLong("sub_pos");
                    map.put(subId, uPos * 100000L + sPos);
                }
            }
        }
        return map;
    }
}

