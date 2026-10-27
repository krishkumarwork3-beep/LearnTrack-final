package com.learntrack.dao;

import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for semesters.
 * Plan Section 3 & 12.
 */
public class SemesterDAO {

    public int insert(Semester semester) throws SQLException {
        String sql = "INSERT INTO semesters (name, status) VALUES (?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, semester.getName());
            stmt.setString(2, semester.getStatus());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    semester.setSemesterId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    public Semester getActiveSemester() throws SQLException {
        String sql = "SELECT semester_id, name, status FROM semesters WHERE status = 'ACTIVE' LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return new Semester(
                        rs.getInt("semester_id"),
                        rs.getString("name"),
                        rs.getString("status")
                );
            }
        }
        return null;
    }

    public Semester findById(int semesterId) throws SQLException {
        String sql = "SELECT semester_id, name, status FROM semesters WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new Semester(
                            rs.getInt("semester_id"),
                            rs.getString("name"),
                            rs.getString("status")
                    );
                }
            }
        }
        return null;
    }

    public List<Semester> getAll() throws SQLException {
        List<Semester> list = new ArrayList<>();
        String sql = "SELECT semester_id, name, status FROM semesters ORDER BY semester_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(new Semester(
                        rs.getInt("semester_id"),
                        rs.getString("name"),
                        rs.getString("status")
                ));
            }
        }
        return list;
    }

    public void update(Semester semester) throws SQLException {
        String sql = "UPDATE semesters SET name = ?, status = ? WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, semester.getName());
            stmt.setString(2, semester.getStatus());
            stmt.setInt(3, semester.getSemesterId());
            stmt.executeUpdate();
        }
    }

    public void delete(int semesterId) throws SQLException {
        String sql = "DELETE FROM semesters WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }
}
