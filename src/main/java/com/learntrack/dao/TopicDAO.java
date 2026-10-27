package com.learntrack.dao;

import com.learntrack.model.Topic;
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
 * Data Access Object for topics.
 * Plan Section 3, 5, 9, 10, 12.
 */
public class TopicDAO {

    public int insert(Topic topic) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return insert(conn, topic);
        }
    }

    public int insert(Connection conn, Topic topic) throws SQLException {
        String sql = "INSERT INTO topics (exam_id, name, importance, difficulty, hours_needed, " +
                "remaining_hours, phase, revision_hours_needed, revision_remaining_hours, " +
                "revision_interval_days, last_revised, important_flag, subtopic_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, topic.getExamId());
            stmt.setString(2, topic.getName());
            stmt.setInt(3, topic.getImportance());
            stmt.setInt(4, topic.getDifficulty());
            stmt.setDouble(5, topic.getHoursNeeded());
            stmt.setDouble(6, topic.getRemainingHours());
            stmt.setString(7, topic.getPhase());
            stmt.setDouble(8, topic.getRevisionHoursNeeded());
            stmt.setDouble(9, topic.getRevisionRemainingHours());
            stmt.setInt(10, topic.getRevisionIntervalDays());
            stmt.setString(11, DateUtils.formatDate(topic.getLastRevised()));
            stmt.setInt(12, topic.getImportantFlag());
            if (topic.getSubtopicId() != null) {
                stmt.setInt(13, topic.getSubtopicId());
            } else {
                stmt.setNull(13, java.sql.Types.INTEGER);
            }
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    topic.setTopicId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    public void update(Topic topic) throws SQLException {
        String sql = "UPDATE topics SET exam_id = ?, name = ?, importance = ?, difficulty = ?, " +
                "hours_needed = ?, remaining_hours = ?, phase = ?, revision_hours_needed = ?, " +
                "revision_remaining_hours = ?, revision_interval_days = ?, last_revised = ?, " +
                "important_flag = ?, subtopic_id = ? WHERE topic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, topic.getExamId());
            stmt.setString(2, topic.getName());
            stmt.setInt(3, topic.getImportance());
            stmt.setInt(4, topic.getDifficulty());
            stmt.setDouble(5, topic.getHoursNeeded());
            stmt.setDouble(6, topic.getRemainingHours());
            stmt.setString(7, topic.getPhase());
            stmt.setDouble(8, topic.getRevisionHoursNeeded());
            stmt.setDouble(9, topic.getRevisionRemainingHours());
            stmt.setInt(10, topic.getRevisionIntervalDays());
            stmt.setString(11, DateUtils.formatDate(topic.getLastRevised()));
            stmt.setInt(12, topic.getImportantFlag());
            if (topic.getSubtopicId() != null) {
                stmt.setInt(13, topic.getSubtopicId());
            } else {
                stmt.setNull(13, java.sql.Types.INTEGER);
            }
            stmt.setInt(14, topic.getTopicId());
            stmt.executeUpdate();
        }
    }

    public Topic findById(int topicId) throws SQLException {
        String sql = "SELECT topic_id, exam_id, name, importance, difficulty, hours_needed, " +
                "remaining_hours, phase, revision_hours_needed, revision_remaining_hours, " +
                "revision_interval_days, last_revised, important_flag, subtopic_id " +
                "FROM topics WHERE topic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, topicId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapTopic(rs);
                }
            }
        }
        return null;
    }

    public List<Topic> findByExam(int examId) throws SQLException {
        List<Topic> list = new ArrayList<>();
        String sql = "SELECT topic_id, exam_id, name, importance, difficulty, hours_needed, " +
                "remaining_hours, phase, revision_hours_needed, revision_remaining_hours, " +
                "revision_interval_days, last_revised, important_flag, subtopic_id " +
                "FROM topics WHERE exam_id = ? ORDER BY topic_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, examId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapTopic(rs));
                }
            }
        }
        return list;
    }

    public List<Topic> findBySemester(int semesterId) throws SQLException {
        List<Topic> list = new ArrayList<>();
        String sql = "SELECT t.topic_id, t.exam_id, t.name, t.importance, t.difficulty, t.hours_needed, " +
                "t.remaining_hours, t.phase, t.revision_hours_needed, t.revision_remaining_hours, " +
                "t.revision_interval_days, t.last_revised, t.important_flag, t.subtopic_id " +
                "FROM topics t JOIN exams e ON t.exam_id = e.exam_id " +
                "WHERE e.semester_id = ? ORDER BY t.topic_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapTopic(rs));
                }
            }
        }
        return list;
    }

    public void updateImportantFlag(int topicId, int flag) throws SQLException {
        String sql = "UPDATE topics SET important_flag = ? WHERE topic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, flag);
            stmt.setInt(2, topicId);
            stmt.executeUpdate();
        }
    }

    /**
     * Resets or sets important_flag for all topics belonging to exams of a specific batch in a semester.
     * Plan Section 6 & 9.
     */
    public void setImportantFlagForBatch(int semesterId, String examType, int flag) throws SQLException {
        String sql = "UPDATE topics SET important_flag = ? WHERE exam_id IN (" +
                "SELECT exam_id FROM exams WHERE semester_id = ? AND exam_type = ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, flag);
            stmt.setInt(2, semesterId);
            stmt.setString(3, examType);
            stmt.executeUpdate();
        }
    }

    public void delete(int topicId) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Delete daily_log rows for this topic (never touch SKILL rows)
                String delLogs = "DELETE FROM daily_log WHERE item_type IN ('TOPIC_LEARN', 'TOPIC_REVISE') AND item_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(delLogs)) {
                    stmt.setInt(1, topicId);
                    stmt.executeUpdate();
                }

                String sql = "DELETE FROM topics WHERE topic_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, topicId);
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
        String sql = "DELETE FROM topics WHERE exam_id IN (SELECT exam_id FROM exams WHERE semester_id = ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }

    private Topic mapTopic(ResultSet rs) throws SQLException {
        Integer subtopicId = rs.getInt("subtopic_id");
        if (rs.wasNull()) {
            subtopicId = null;
        }
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
                DateUtils.parseDate(rs.getString("last_revised")),
                rs.getInt("important_flag"),
                subtopicId
        );
    }

    /**
     * Find topic by exam and subtopic ID.
     */
    public Topic findByExamAndSubtopic(int examId, int subtopicId) throws SQLException {
        String sql = "SELECT topic_id, exam_id, name, importance, difficulty, hours_needed, " +
                "remaining_hours, phase, revision_hours_needed, revision_remaining_hours, " +
                "revision_interval_days, last_revised, important_flag, subtopic_id " +
                "FROM topics WHERE exam_id = ? AND subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, examId);
            stmt.setInt(2, subtopicId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapTopic(rs);
                }
            }
        }
        return null;
    }

    /**
     * Check if a topic has been studied (has daily_log entries or has changed from initial state).
     */
    public boolean hasBeenStudied(int topicId) throws SQLException {
        // Check daily_log - only DONE or CONTINUING rows represent real progress
        String logSql = "SELECT COUNT(*) FROM daily_log WHERE item_type IN ('TOPIC_LEARN', 'TOPIC_REVISE') AND item_id = ? AND status IN ('DONE', 'CONTINUING')";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(logSql)) {
            stmt.setInt(1, topicId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
        }

        // Check if topic state changed from initial
        Topic topic = findById(topicId);
        if (topic == null) {
            return false;
        }

        // A topic is untouched if:
        // - LEARNING phase and remaining_hours == hours_needed, OR
        // - REVISION phase, last_revised == null, and revision_remaining_hours == revision_hours_needed
        boolean isUntouched = ("LEARNING".equals(topic.getPhase()) &&
                Math.abs(topic.getRemainingHours() - topic.getHoursNeeded()) < 0.01)
                || ("REVISION".equals(topic.getPhase()) && topic.getLastRevised() == null &&
                Math.abs(topic.getRevisionRemainingHours() - topic.getRevisionHoursNeeded()) < 0.01);

        return !isUntouched;
    }
}
