package com.learntrack.dao;

import com.learntrack.model.WeekdayRoutine;
import com.learntrack.model.WeekdayStudyPreference;
import com.learntrack.model.WeeklyTimetableEntry;
import com.learntrack.util.DBConnection;
import com.learntrack.util.DateUtils;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data Access Object for weekly_timetable and weekday_study_preference.
 * Plan Section 3.
 */
public class TimetableDAO {

    public int insertEntry(WeeklyTimetableEntry entry) throws SQLException {
        String sql = "INSERT INTO weekly_timetable (semester_id, day_of_week, start_time, end_time, activity_type, label, room, subject_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, entry.getSemesterId());
            stmt.setString(2, entry.getDayOfWeek());
            stmt.setString(3, DateUtils.formatTime(entry.getStartTime()));
            stmt.setString(4, DateUtils.formatTime(entry.getEndTime()));
            stmt.setString(5, entry.getActivityType());
            stmt.setString(6, entry.getLabel());
            stmt.setString(7, entry.getRoom());
            stmt.setString(8, entry.getSubjectCode());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    entry.setEntryId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    /**
     * ID-preserving save: updates rows that already have an entryId, inserts new ones,
     * and deletes only rows that were removed from the list.
     * Returns the set of entry IDs that were deleted (for the caller to clean up date_overrides).
     * Plan Section 3, Fix 2.
     */
    public Set<Integer> saveTimetableEntries(int semesterId, List<WeeklyTimetableEntry> entries) throws SQLException {
        // Fetch existing ids
        List<WeeklyTimetableEntry> existing = findBySemester(semesterId);
        Set<Integer> existingIds = new HashSet<>();
        for (WeeklyTimetableEntry e : existing) {
            if (e.getEntryId() != null) existingIds.add(e.getEntryId());
        }

        // Build set of ids that the caller wants to keep
        Set<Integer> keepIds = new HashSet<>();
        for (WeeklyTimetableEntry e : entries) {
            if (e.getEntryId() != null) keepIds.add(e.getEntryId());
        }

        // Ids that were removed
        Set<Integer> removedIds = new HashSet<>(existingIds);
        removedIds.removeAll(keepIds);

        // Delete removed entries
        if (!removedIds.isEmpty()) {
            String delSql = "DELETE FROM weekly_timetable WHERE entry_id = ?";
            try (Connection conn = DBConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(delSql)) {
                for (int id : removedIds) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
            }
        }

        // Update existing rows, insert new ones
        String updateSql = "UPDATE weekly_timetable SET day_of_week = ?, start_time = ?, end_time = ?, activity_type = ?, label = ?, room = ?, subject_code = ? WHERE entry_id = ?";
        String insertSql = "INSERT INTO weekly_timetable (semester_id, day_of_week, start_time, end_time, activity_type, label, room, subject_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement updateStmt = conn.prepareStatement(updateSql);
             PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            for (WeeklyTimetableEntry entry : entries) {
                entry.setSemesterId(semesterId);
                if (entry.getEntryId() != null && existingIds.contains(entry.getEntryId())) {
                    updateStmt.setString(1, entry.getDayOfWeek());
                    updateStmt.setString(2, DateUtils.formatTime(entry.getStartTime()));
                    updateStmt.setString(3, DateUtils.formatTime(entry.getEndTime()));
                    updateStmt.setString(4, entry.getActivityType());
                    updateStmt.setString(5, entry.getLabel());
                    updateStmt.setString(6, entry.getRoom());
                    updateStmt.setString(7, entry.getSubjectCode());
                    updateStmt.setInt(8, entry.getEntryId());
                    updateStmt.executeUpdate();
                } else {
                    insertStmt.setInt(1, semesterId);
                    insertStmt.setString(2, entry.getDayOfWeek());
                    insertStmt.setString(3, DateUtils.formatTime(entry.getStartTime()));
                    insertStmt.setString(4, DateUtils.formatTime(entry.getEndTime()));
                    insertStmt.setString(5, entry.getActivityType());
                    insertStmt.setString(6, entry.getLabel());
                    insertStmt.setString(7, entry.getRoom());
                    insertStmt.setString(8, entry.getSubjectCode());
                    insertStmt.executeUpdate();
                    try (ResultSet rs = insertStmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            entry.setEntryId(rs.getInt(1));
                        }
                    }
                }
            }
        }
        return removedIds;
    }

    public List<WeeklyTimetableEntry> findBySemester(int semesterId) throws SQLException {
        List<WeeklyTimetableEntry> list = new ArrayList<>();
        String sql = "SELECT entry_id, semester_id, day_of_week, start_time, end_time, activity_type, label, room, subject_code " +
                "FROM weekly_timetable WHERE semester_id = ? ORDER BY day_of_week, start_time";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapEntry(rs));
                }
            }
        }
        return list;
    }

    public List<WeeklyTimetableEntry> findBySemesterAndDay(int semesterId, String dayOfWeek) throws SQLException {
        List<WeeklyTimetableEntry> list = new ArrayList<>();
        String sql = "SELECT entry_id, semester_id, day_of_week, start_time, end_time, activity_type, label, room, subject_code " +
                "FROM weekly_timetable WHERE semester_id = ? AND day_of_week = ? ORDER BY start_time";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, dayOfWeek);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapEntry(rs));
                }
            }
        }
        return list;
    }

    public void deleteEntriesBySemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM weekly_timetable WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }

    public void saveStudyPreferences(int semesterId, List<WeekdayStudyPreference> prefs) throws SQLException {
        deleteStudyPreferencesBySemester(semesterId);
        String sql = "INSERT INTO weekday_study_preference (semester_id, day_of_week, study_hours, limit_enabled) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (WeekdayStudyPreference pref : prefs) {
                stmt.setInt(1, semesterId);
                stmt.setString(2, pref.getDayOfWeek());
                stmt.setDouble(3, pref.getStudyHours());
                stmt.setInt(4, 0); // Never enforce limits - always use all free time
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Save a single study preference with limit_enabled flag.
     * @param semesterId semester ID
     * @param dayOfWeek day of week (e.g. "MONDAY")
     * @param studyHours study hours value
     * @param limitEnabled whether the limit is enforced (true) or optional (false)
     */
    public void saveStudyPreference(int semesterId, String dayOfWeek, double studyHours, boolean limitEnabled) throws SQLException {
        String sql = "INSERT INTO weekday_study_preference (semester_id, day_of_week, study_hours, limit_enabled) " +
                     "VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT(semester_id, day_of_week) DO UPDATE SET study_hours = excluded.study_hours, limit_enabled = excluded.limit_enabled";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, dayOfWeek);
            stmt.setDouble(3, studyHours);
            stmt.setInt(4, limitEnabled ? 1 : 0);
            stmt.executeUpdate();
        }
    }

    public Map<String, Double> findStudyPreferencesBySemester(int semesterId) throws SQLException {
        Map<String, Double> map = new HashMap<>();
        String sql = "SELECT day_of_week, study_hours FROM weekday_study_preference WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("day_of_week"), rs.getDouble("study_hours"));
                }
            }
        }
        return map;
    }

    /**
     * Returns a map of day_of_week -> Boolean indicating if the study limit is enabled.
     * If limit_enabled = 1, the study_hours value is enforced as a cap.
     * If limit_enabled = 0 (default), all free time is used.
     */
    public Map<String, Boolean> findStudyLimitEnabledBySemester(int semesterId) throws SQLException {
        Map<String, Boolean> map = new HashMap<>();
        String sql = "SELECT day_of_week, limit_enabled FROM weekday_study_preference WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("day_of_week"), rs.getInt("limit_enabled") == 1);
                }
            }
        }
        return map;
    }

    public double getStudyHours(int semesterId, String dayOfWeek) throws SQLException {
        String sql = "SELECT study_hours FROM weekday_study_preference WHERE semester_id = ? AND day_of_week = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, dayOfWeek);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("study_hours");
                }
            }
        }
        return 4.0; // default reasonable fallback if not explicitly set
    }

    public void deleteStudyPreferencesBySemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM weekday_study_preference WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }

    public void saveRoutines(int semesterId, List<WeekdayRoutine> routines) throws SQLException {
        deleteRoutinesBySemester(semesterId);
        if (routines == null || routines.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO weekday_routine (semester_id, day_of_week, wake_time, sleep_time) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (WeekdayRoutine r : routines) {
                stmt.setInt(1, semesterId);
                stmt.setString(2, r.getDayOfWeek().toUpperCase());
                stmt.setString(3, DateUtils.formatTime(r.getWakeTime()));
                stmt.setString(4, DateUtils.formatTime(r.getSleepTime()));
                stmt.executeUpdate();
            }
        }
    }

    public List<WeekdayRoutine> findRoutinesBySemester(int semesterId) throws SQLException {
        List<WeekdayRoutine> list = new ArrayList<>();
        String sql = "SELECT semester_id, day_of_week, wake_time, sleep_time FROM weekday_routine WHERE semester_id = ? ORDER BY day_of_week";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new WeekdayRoutine(
                            rs.getInt("semester_id"),
                            rs.getString("day_of_week"),
                            DateUtils.parseTime(rs.getString("wake_time")),
                            DateUtils.parseTime(rs.getString("sleep_time"))
                    ));
                }
            }
        }
        return list;
    }

    public Map<String, WeekdayRoutine> findRoutinesMapBySemester(int semesterId) throws SQLException {
        Map<String, WeekdayRoutine> map = new HashMap<>();
        for (WeekdayRoutine r : findRoutinesBySemester(semesterId)) {
            map.put(r.getDayOfWeek().toUpperCase(), r);
        }
        return map;
    }

    public void deleteRoutinesBySemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM weekday_routine WHERE semester_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.executeUpdate();
        }
    }

    public void deleteBySemester(int semesterId) throws SQLException {
        deleteEntriesBySemester(semesterId);
        deleteStudyPreferencesBySemester(semesterId);
        deleteRoutinesBySemester(semesterId);
    }


    private WeeklyTimetableEntry mapEntry(ResultSet rs) throws SQLException {
        return new WeeklyTimetableEntry(
                rs.getInt("entry_id"),
                rs.getInt("semester_id"),
                rs.getString("day_of_week"),
                DateUtils.parseTime(rs.getString("start_time")),
                DateUtils.parseTime(rs.getString("end_time")),
                rs.getString("activity_type"),
                rs.getString("label"),
                rs.getString("room"),
                rs.getString("subject_code")
        );
    }
}
