package com.learntrack.dao;

import com.learntrack.model.SkillSubtopic;
import com.learntrack.util.DBConnection;
import com.learntrack.util.DateUtils;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for skill_subtopics.
 */
public class SkillSubtopicDAO {

    public int insert(SkillSubtopic subtopic) throws SQLException {
        String sql = "INSERT INTO skill_subtopics (skill_id, name, position, learn_hours, learn_remaining_hours, " +
                "revise_hours, revise_remaining_hours, revise_after_days, learned, learned_on, revised, revised_on, revision_due_date) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, subtopic.getSkillId());
            stmt.setString(2, subtopic.getName());
            stmt.setInt(3, subtopic.getPosition());
            stmt.setDouble(4, subtopic.getLearnHours());
            stmt.setDouble(5, subtopic.getLearnRemainingHours());
            stmt.setDouble(6, subtopic.getReviseHours());
            stmt.setDouble(7, subtopic.getReviseRemainingHours());
            stmt.setInt(8, subtopic.getReviseAfterDays());
            stmt.setInt(9, subtopic.isLearned() ? 1 : 0);
            stmt.setString(10, DateUtils.formatDate(subtopic.getLearnedOn()));
            stmt.setInt(11, subtopic.isRevised() ? 1 : 0);
            stmt.setString(12, DateUtils.formatDate(subtopic.getRevisedOn()));
            stmt.setString(13, DateUtils.formatDate(subtopic.getRevisionDueDate()));

            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    subtopic.setSubtopicId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    public void update(SkillSubtopic subtopic) throws SQLException {
        String sql = "UPDATE skill_subtopics SET skill_id = ?, name = ?, position = ?, learn_hours = ?, " +
                "learn_remaining_hours = ?, revise_hours = ?, revise_remaining_hours = ?, revise_after_days = ?, " +
                "learned = ?, learned_on = ?, revised = ?, revised_on = ?, revision_due_date = ? " +
                "WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, subtopic.getSkillId());
            stmt.setString(2, subtopic.getName());
            stmt.setInt(3, subtopic.getPosition());
            stmt.setDouble(4, subtopic.getLearnHours());
            stmt.setDouble(5, subtopic.getLearnRemainingHours());
            stmt.setDouble(6, subtopic.getReviseHours());
            stmt.setDouble(7, subtopic.getReviseRemainingHours());
            stmt.setInt(8, subtopic.getReviseAfterDays());
            stmt.setInt(9, subtopic.isLearned() ? 1 : 0);
            stmt.setString(10, DateUtils.formatDate(subtopic.getLearnedOn()));
            stmt.setInt(11, subtopic.isRevised() ? 1 : 0);
            stmt.setString(12, DateUtils.formatDate(subtopic.getRevisedOn()));
            stmt.setString(13, DateUtils.formatDate(subtopic.getRevisionDueDate()));
            stmt.setInt(14, subtopic.getSubtopicId());

            stmt.executeUpdate();
        }
    }

    public void delete(int subtopicId) throws SQLException {
        String sql = "DELETE FROM skill_subtopics WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, subtopicId);
            stmt.executeUpdate();
        }
    }

    public SkillSubtopic findById(int subtopicId) throws SQLException {
        String sql = "SELECT * FROM skill_subtopics WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, subtopicId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    public List<SkillSubtopic> findBySkill(int skillId) throws SQLException {
        List<SkillSubtopic> list = new ArrayList<>();
        String sql = "SELECT * FROM skill_subtopics WHERE skill_id = ? ORDER BY position, subtopic_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, skillId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<SkillSubtopic> findAll() throws SQLException {
        List<SkillSubtopic> list = new ArrayList<>();
        String sql = "SELECT * FROM skill_subtopics ORDER BY skill_id, position, subtopic_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public void markLearned(int subtopicId, LocalDate date, int reviseAfterDays) throws SQLException {
        LocalDate dueDate = date != null ? date.plusDays(reviseAfterDays) : null;
        String sql = "UPDATE skill_subtopics SET learned = 1, learned_on = ?, learn_remaining_hours = 0, " +
                "revision_due_date = ? WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(date));
            stmt.setString(2, DateUtils.formatDate(dueDate));
            stmt.setInt(3, subtopicId);
            stmt.executeUpdate();
        }
    }

    public void unmarkLearned(int subtopicId, double learnHours, double reviseHours) throws SQLException {
        String sql = "UPDATE skill_subtopics SET learned = 0, learned_on = NULL, revision_due_date = NULL, " +
                "learn_remaining_hours = ?, revised = 0, revised_on = NULL, revise_remaining_hours = ? " +
                "WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, learnHours);
            stmt.setDouble(2, reviseHours);
            stmt.setInt(3, subtopicId);
            stmt.executeUpdate();
        }
    }

    public void unmarkLearned(int subtopicId) throws SQLException {
        SkillSubtopic st = findById(subtopicId);
        if (st != null) {
            unmarkLearned(subtopicId, st.getLearnHours(), st.getReviseHours());
        }
    }

    public void markRevised(int subtopicId, LocalDate date) throws SQLException {
        String sql = "UPDATE skill_subtopics SET revised = 1, revised_on = ?, revise_remaining_hours = 0 " +
                "WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(date));
            stmt.setInt(2, subtopicId);
            stmt.executeUpdate();
        }
    }

    public void unmarkRevised(int subtopicId, double reviseHours) throws SQLException {
        String sql = "UPDATE skill_subtopics SET revised = 0, revised_on = NULL, revise_remaining_hours = ? " +
                "WHERE subtopic_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, reviseHours);
            stmt.setInt(2, subtopicId);
            stmt.executeUpdate();
        }
    }

    public void unmarkRevised(int subtopicId) throws SQLException {
        SkillSubtopic st = findById(subtopicId);
        if (st != null) {
            unmarkRevised(subtopicId, st.getReviseHours());
        }
    }

    private SkillSubtopic mapRow(ResultSet rs) throws SQLException {
        return new SkillSubtopic(
                rs.getInt("subtopic_id"),
                rs.getInt("skill_id"),
                rs.getString("name"),
                rs.getInt("position"),
                rs.getDouble("learn_hours"),
                rs.getDouble("learn_remaining_hours"),
                rs.getDouble("revise_hours"),
                rs.getDouble("revise_remaining_hours"),
                rs.getInt("revise_after_days"),
                rs.getInt("learned") == 1,
                DateUtils.parseDate(rs.getString("learned_on")),
                rs.getInt("revised") == 1,
                DateUtils.parseDate(rs.getString("revised_on")),
                DateUtils.parseDate(rs.getString("revision_due_date"))
        );
    }
}
