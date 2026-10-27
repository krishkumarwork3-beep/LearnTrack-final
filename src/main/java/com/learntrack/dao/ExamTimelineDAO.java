package com.learntrack.dao;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * DAO for managing exam timeline windows (MST/EST rough windows).
 * Stores per-semester, per-type windows in the settings table.
 */
public class ExamTimelineDAO {

    private final SettingsDAO settingsDAO;

    public ExamTimelineDAO() {
        this.settingsDAO = new SettingsDAO();
    }

    public ExamTimelineDAO(SettingsDAO settingsDAO) {
        this.settingsDAO = settingsDAO;
    }

    /**
     * Get the window for a given semester and exam type (MST or EST).
     * Returns Optional containing [start, end] dates if both are set, empty otherwise.
     */
    public Optional<LocalDate[]> getWindow(int semesterId, String examType) throws SQLException {
        String typeKey = examType.toLowerCase();
        String startKey = typeKey + "_window_start_" + semesterId;
        String endKey = typeKey + "_window_end_" + semesterId;

        String startStr = settingsDAO.get(startKey, null);
        String endStr = settingsDAO.get(endKey, null);

        if (startStr == null || endStr == null) {
            return Optional.empty();
        }

        try {
            LocalDate start = LocalDate.parse(startStr);
            LocalDate end = LocalDate.parse(endStr);
            return Optional.of(new LocalDate[]{start, end});
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Save the window for a given semester and exam type.
     */
    public void setWindow(int semesterId, String examType, LocalDate start, LocalDate end) throws SQLException {
        String typeKey = examType.toLowerCase();
        String startKey = typeKey + "_window_start_" + semesterId;
        String endKey = typeKey + "_window_end_" + semesterId;

        settingsDAO.set(startKey, start.toString());
        settingsDAO.set(endKey, end.toString());
    }

    /**
     * Clear the window for a given semester and exam type.
     */
    public void clearWindow(int semesterId, String examType) throws SQLException {
        String typeKey = examType.toLowerCase();
        String startKey = typeKey + "_window_start_" + semesterId;
        String endKey = typeKey + "_window_end_" + semesterId;

        settingsDAO.delete(startKey);
        settingsDAO.delete(endKey);
    }

    /**
     * Check if datesheet is announced for this exam type.
     */
    public boolean isDatesheetAnnounced(int semesterId, String examType) throws SQLException {
        String key = examType.toLowerCase() + "_datesheet_announced_" + semesterId;
        return "true".equals(settingsDAO.get(key, "false"));
    }

    /**
     * Set datesheet announced state for this exam type.
     */
    public void setDatesheetAnnounced(int semesterId, String examType, boolean announced) throws SQLException {
        String key = examType.toLowerCase() + "_datesheet_announced_" + semesterId;
        settingsDAO.set(key, String.valueOf(announced));
    }
}
