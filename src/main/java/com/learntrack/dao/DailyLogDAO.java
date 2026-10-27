package com.learntrack.dao;

import com.learntrack.model.DailyLogEntry;
import com.learntrack.util.DBConnection;
import com.learntrack.util.DateUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Access Object for daily_log.
 * Plan Section 3, 6, 10, 12.
 */
public class DailyLogDAO {

    public int insert(DailyLogEntry entry) throws SQLException {
        String sql = "INSERT INTO daily_log (log_date, item_type, item_id, slot_start, slot_end, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, DateUtils.formatDate(entry.getLogDate()));
            stmt.setString(2, entry.getItemType());
            stmt.setInt(3, entry.getItemId());
            stmt.setString(4, DateUtils.formatTime(entry.getSlotStart()));
            stmt.setString(5, DateUtils.formatTime(entry.getSlotEnd()));
            stmt.setString(6, entry.getStatus());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    entry.setLogId(id);
                    return id;
                }
            }
        }
        return -1;
    }

    /**
     * Convenience method for inserting a log entry (for testing).
     */
    public int insert(int semesterId, int itemId, String itemType, LocalDate logDate, 
                      double hours, String status) throws SQLException {
        DailyLogEntry entry = new DailyLogEntry();
        entry.setItemId(itemId);
        entry.setItemType(itemType);
        entry.setLogDate(logDate);
        entry.setStatus(status);
        // Set slot times based on hours (e.g., 09:00 to 09:00 + hours)
        entry.setSlotStart(java.time.LocalTime.of(9, 0));
        entry.setSlotEnd(java.time.LocalTime.of(9, 0).plusMinutes((long)(hours * 60)));
        return insert(entry);
    }

    /**
     * Inserts all entries in a single transaction.
     * Executes each row and immediately retrieves its generated key,
     * committing once for the entire batch and rolling back on failure.
     */
    public void insertBatch(List<DailyLogEntry> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) return;
        String sql = "INSERT INTO daily_log (log_date, item_type, item_id, slot_start, slot_end, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (DailyLogEntry entry : entries) {
                    stmt.setString(1, DateUtils.formatDate(entry.getLogDate()));
                    stmt.setString(2, entry.getItemType());
                    stmt.setInt(3, entry.getItemId());
                    stmt.setString(4, DateUtils.formatTime(entry.getSlotStart()));
                    stmt.setString(5, DateUtils.formatTime(entry.getSlotEnd()));
                    stmt.setString(6, entry.getStatus());
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            entry.setLogId(rs.getInt(1));
                        }
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Atomically deletes future replan rows on or after fromDate and inserts newEntries in a single transaction.
     * Deletes PENDING and CONTINUING rows on fromDate, and PENDING, CONTINUING, and MANUAL rows after fromDate.
     * Preserves DONE, MISSED, and MANUAL rows on fromDate, and all rows before fromDate.
     * If newEntries is null or empty, the delete still executes and commits.
     * On failure, rolls back and rethrows. Restores autocommit in finally.
     */
    public void replaceFutureRows(LocalDate fromDate, List<DailyLogEntry> newEntries) throws SQLException {
        String deleteSql = "DELETE FROM daily_log WHERE (log_date = ? AND status IN ('PENDING', 'CONTINUING', 'PLANNED')) " +
                "OR (log_date > ? AND status IN ('PENDING', 'CONTINUING', 'MANUAL', 'PLANNED'))";
        String insertSql = "INSERT INTO daily_log (log_date, item_type, item_id, slot_start, slot_end, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    deleteStmt.setString(1, DateUtils.formatDate(fromDate));
                    deleteStmt.setString(2, DateUtils.formatDate(fromDate));
                    deleteStmt.executeUpdate();
                }

                if (newEntries != null && !newEntries.isEmpty()) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                        for (DailyLogEntry entry : newEntries) {
                            insertStmt.setString(1, DateUtils.formatDate(entry.getLogDate()));
                            insertStmt.setString(2, entry.getItemType());
                            insertStmt.setInt(3, entry.getItemId());
                            insertStmt.setString(4, DateUtils.formatTime(entry.getSlotStart()));
                            insertStmt.setString(5, DateUtils.formatTime(entry.getSlotEnd()));
                            insertStmt.setString(6, entry.getStatus());
                            insertStmt.executeUpdate();
                            try (ResultSet rs = insertStmt.getGeneratedKeys()) {
                                if (rs.next()) {
                                    entry.setLogId(rs.getInt(1));
                                }
                            }
                        }
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void update(DailyLogEntry entry) throws SQLException {
        String sql = "UPDATE daily_log SET log_date = ?, item_type = ?, item_id = ?, slot_start = ?, " +
                "slot_end = ?, status = ? WHERE log_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(entry.getLogDate()));
            stmt.setString(2, entry.getItemType());
            stmt.setInt(3, entry.getItemId());
            stmt.setString(4, DateUtils.formatTime(entry.getSlotStart()));
            stmt.setString(5, DateUtils.formatTime(entry.getSlotEnd()));
            stmt.setString(6, entry.getStatus());
            stmt.setInt(7, entry.getLogId());
            stmt.executeUpdate();
        }
    }

    public void updateStatus(int logId, String status) throws SQLException {
        String sql = "UPDATE daily_log SET status = ? WHERE log_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setInt(2, logId);
            stmt.executeUpdate();
        }
    }

    public DailyLogEntry findById(int logId) throws SQLException {
        String sql = "SELECT log_id, log_date, item_type, item_id, slot_start, slot_end, status " +
                "FROM daily_log WHERE log_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, logId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapEntry(rs);
                }
            }
        }
        return null;
    }

    public List<DailyLogEntry> findByDate(LocalDate date) throws SQLException {
        List<DailyLogEntry> list = new ArrayList<>();
        String sql = "SELECT log_id, log_date, item_type, item_id, slot_start, slot_end, status " +
                "FROM daily_log WHERE log_date = ? ORDER BY slot_start";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(date));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapEntry(rs));
                }
            }
        }
        return list;
    }

    public List<DailyLogEntry> findByDateRange(LocalDate from, LocalDate to) throws SQLException {
        List<DailyLogEntry> list = new ArrayList<>();
        String sql = "SELECT log_id, log_date, item_type, item_id, slot_start, slot_end, status " +
                "FROM daily_log WHERE log_date >= ? AND log_date <= ? ORDER BY log_date, slot_start";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(from));
            stmt.setString(2, DateUtils.formatDate(to));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapEntry(rs));
                }
            }
        }
        return list;
    }

    /**
     * Marks all PENDING and CONTINUING rows with log_date strictly before today as MISSED.
     * Returns the number of rows changed. Does NOT touch DONE or MANUAL rows.
     * Plan Fix 4, User Guide §7.
     */
    public int markMissedBeforeDate(LocalDate today) throws SQLException {
        String sql = "UPDATE daily_log SET status = 'MISSED' WHERE log_date < ? AND status IN ('PENDING', 'CONTINUING', 'PLANNED')";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(today));
            return stmt.executeUpdate();
        }
    }

    /**
     * Clears only PENDING, CONTINUING, and PLANNED rows on or after fromDate (Plan Section 6).
     * Locks history before fromDate, and preserves DONE or MISSED rows.
     */
    public void clearFutureRows(LocalDate fromDate) throws SQLException {
        String sql = "DELETE FROM daily_log WHERE (log_date = ? AND status IN ('PENDING', 'CONTINUING', 'PLANNED')) " +
                "OR (log_date > ? AND status IN ('PENDING', 'CONTINUING', 'MANUAL', 'PLANNED'))";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, DateUtils.formatDate(fromDate));
            stmt.setString(2, DateUtils.formatDate(fromDate));
            stmt.executeUpdate();
        }
    }

    /**
     * Deletes daily_log rows for topics and PYQ practice tied to old semester.
     * SKILL rows are NEVER touched (Plan Section 12).
     */
    public void deleteNonSkillBySemester(int semesterId) throws SQLException {
        String sql = "DELETE FROM daily_log WHERE (" +
                "(item_type IN ('TOPIC_LEARN', 'TOPIC_REVISE') AND item_id IN (" +
                "    SELECT topic_id FROM topics WHERE exam_id IN (" +
                "        SELECT exam_id FROM exams WHERE semester_id = ?" +
                "    )" +
                ")) OR (" +
                "item_type = 'PYQ_PRACTICE' AND item_id IN (" +
                "    SELECT exam_id FROM exams WHERE semester_id = ?" +
                "))" +
                ")";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            stmt.setInt(2, semesterId);
            stmt.executeUpdate();
        }
    }

    public void delete(int logId) throws SQLException {
        String sql = "DELETE FROM daily_log WHERE log_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, logId);
            stmt.executeUpdate();
        }
    }

    /**
     * Find all daily_log entries (for testing).
     */
    public List<DailyLogEntry> findAll(int semesterId) throws SQLException {
        String sql = "SELECT dl.* FROM daily_log dl " +
                     "LEFT JOIN topics t ON dl.item_id = t.topic_id AND dl.item_type IN ('TOPIC_LEARN', 'TOPIC_REVISE') " +
                     "LEFT JOIN exams e ON (dl.item_id = e.exam_id AND dl.item_type = 'PYQ_PRACTICE') OR (t.exam_id = e.exam_id) " +
                     "WHERE e.semester_id = ? OR dl.item_type IN ('SKILL', 'SKILL_LEARN', 'SKILL_REVISE')";
        List<DailyLogEntry> entries = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, semesterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapEntry(rs));
                }
            }
        }
        return entries;
    }

    public void markSubtopicLogsDone(int subtopicId, String itemType) throws SQLException {
        String sql = "UPDATE daily_log SET status = 'DONE' WHERE item_type = ? AND item_id = ? AND status IN ('PENDING', 'PLANNED', 'CONTINUING')";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, itemType);
            stmt.setInt(2, subtopicId);
            stmt.executeUpdate();
        }
    }

    public void purgeLegacySkillRows() throws SQLException {
        String sql = "DELETE FROM daily_log WHERE item_type = 'SKILL' AND status IN ('PENDING', 'PLANNED', 'CONTINUING')";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }

    public Map<Integer, List<LocalDate>> findUpcomingDatesByItemTypes(List<String> itemTypes, LocalDate fromDate) throws SQLException {
        Map<Integer, List<LocalDate>> map = new HashMap<>();
        if (itemTypes == null || itemTypes.isEmpty()) return map;
        String placeholders = String.join(",", Collections.nCopies(itemTypes.size(), "?"));
        String sql = "SELECT item_id, log_date FROM daily_log WHERE item_type IN (" + placeholders + ") AND log_date >= ? AND status IN ('PENDING', 'PLANNED') ORDER BY log_date";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String t : itemTypes) {
                stmt.setString(idx++, t);
            }
            stmt.setString(idx, DateUtils.formatDate(fromDate));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    LocalDate d = DateUtils.parseDate(rs.getString("log_date"));
                    map.computeIfAbsent(itemId, k -> new ArrayList<>()).add(d);
                }
            }
        }
        return map;
    }

    private DailyLogEntry mapEntry(ResultSet rs) throws SQLException {
        return new DailyLogEntry(
                rs.getInt("log_id"),
                DateUtils.parseDate(rs.getString("log_date")),
                rs.getString("item_type"),
                rs.getInt("item_id"),
                DateUtils.parseTime(rs.getString("slot_start")),
                DateUtils.parseTime(rs.getString("slot_end")),
                rs.getString("status")
        );
    }
}
