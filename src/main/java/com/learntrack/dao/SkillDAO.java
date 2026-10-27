package com.learntrack.dao;

import com.learntrack.model.Skill;
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
 * Data Access Object for skills.
 * Note: By design, has NO delete-by-semester method (Plan Section 12).
 */
public class SkillDAO {

    public int insert(Skill skill) throws SQLException {
        String sql = "INSERT INTO skills (name, category, proficiency, last_practiced) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, skill.getName());
            stmt.setString(2, skill.getCategory());
            stmt.setInt(3, skill.getProficiency());
            stmt.setString(4, lastPracticedForStorage(skill));
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    skill.setSkillId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    public void update(Skill skill) throws SQLException {
        String sql = "UPDATE skills SET name = ?, category = ?, proficiency = ?, last_practiced = ? WHERE skill_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, skill.getName());
            stmt.setString(2, skill.getCategory());
            stmt.setInt(3, skill.getProficiency());
            stmt.setString(4, lastPracticedForStorage(skill));
            stmt.setInt(5, skill.getSkillId());
            stmt.executeUpdate();
        }
    }

    public void updateLastPracticed(int skillId, LocalDate date) throws SQLException {
        String sql = "UPDATE skills SET last_practiced = ? WHERE skill_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(date));
            stmt.setInt(2, skillId);
            stmt.executeUpdate();
        }
    }

    public Skill findById(int skillId) throws SQLException {
        String sql = "SELECT skill_id, name, category, proficiency, last_practiced FROM skills WHERE skill_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, skillId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapSkill(rs);
                }
            }
        }
        return null;
    }

    public List<Skill> getAll() throws SQLException {
        List<Skill> list = new ArrayList<>();
        String sql = "SELECT skill_id, name, category, proficiency, last_practiced FROM skills ORDER BY skill_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapSkill(rs));
            }
        }
        return list;
    }

    public void delete(int skillId) throws SQLException {
        String sql = "DELETE FROM skills WHERE skill_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, skillId);
            stmt.executeUpdate();
        }
    }

    private Skill mapSkill(ResultSet rs) throws SQLException {
        LocalDate lastPracticed = DateUtils.parseDate(rs.getString("last_practiced"));
        // The stored sentinel means "never practiced"; callers keep seeing null for that, so a new
        // skill behaves exactly as it did when the column could still be empty.
        if (Skill.NEVER_PRACTICED.equals(lastPracticed)) {
            lastPracticed = null;
        }
        return new Skill(
                rs.getInt("skill_id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getInt("proficiency"),
                lastPracticed
        );
    }

    /**
     * The skills table's last_practiced column is NOT NULL in older databases (and defaults to the
     * sentinel in newer ones), so "never practiced" is stored as {@link Skill#NEVER_PRACTICED}.
     */
    private static String lastPracticedForStorage(Skill skill) {
        LocalDate date = skill.getLastPracticed();
        return DateUtils.formatDate(date != null ? date : Skill.NEVER_PRACTICED);
    }
}
