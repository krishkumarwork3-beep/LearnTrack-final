package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamTimelineDAO;
import com.learntrack.model.Exam;
import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing exam timeline windows (MST/EST rough windows before datesheet).
 * Handles the tentative exam window logic where undated exams use the window start as their effective date.
 */
public class ExamTimelineService {

    private final ExamTimelineDAO timelineDAO;
    private final ExamDAO examDAO;

    public ExamTimelineService() {
        this.timelineDAO = new ExamTimelineDAO();
        this.examDAO = new ExamDAO();
    }

    public ExamTimelineService(ExamTimelineDAO timelineDAO, ExamDAO examDAO) {
        this.timelineDAO = timelineDAO;
        this.examDAO = examDAO;
    }

    /**
     * Save a tentative window: stores window in settings and sets ALL exams of this type to exam_date = window start with tentative = 1.
     * Also sets window_start_date/window_end_date.
     * Returns the number of exams updated.
     * Does NOT trigger replan - caller must do that.
     */
    public int saveTentativeWindow(int semesterId, String examType, LocalDate start, LocalDate end) throws SQLException {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Window end date must be >= start date");
        }

        if (!isValidExamType(examType)) {
            throw new IllegalArgumentException("Window only applies to MST or EST exam types");
        }

        try (Connection conn = DBConnection.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Save to settings
                timelineDAO.setWindow(semesterId, examType, start, end);

                // Update ALL exams of this type to tentative
                List<Exam> exams = examDAO.findBySemester(semesterId);
                int count = 0;
                for (Exam exam : exams) {
                    if (examType.equalsIgnoreCase(exam.getExamType())) {
                        exam.setExamDate(start);  // Set to window start
                        exam.setExamDateTentative(true);
                        exam.setWindowStartDate(start);
                        exam.setWindowEndDate(end);
                        // Clear times for tentative dates
                        exam.setExamStartTime(null);
                        exam.setExamEndTime(null);
                        examDAO.update(exam);
                        count++;
                    }
                }

                conn.commit();
                return count;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Apply stored window to a new exam if it's MST/EST.
     * In tentative mode: sets exam_date to window start with tentative flag.
     * Should be called when creating/provisioning a new exam row.
     */
    public void applyWindowToNewExam(int semesterId, Exam exam) throws SQLException {
        if (!isValidExamType(exam.getExamType())) {
            return; // Only MST/EST get windows
        }

        Optional<LocalDate[]> window = timelineDAO.getWindow(semesterId, exam.getExamType());
        if (window.isPresent()) {
            LocalDate[] dates = window.get();
            // Check if in tentative mode (datesheet not announced)
            boolean datesheetAnnounced = timelineDAO.isDatesheetAnnounced(semesterId, exam.getExamType());
            if (!datesheetAnnounced) {
                // Tentative mode: set exam_date to window start
                exam.setExamDate(dates[0]);
                exam.setExamDateTentative(true);
            }
            exam.setWindowStartDate(dates[0]);
            exam.setWindowEndDate(dates[1]);
        }
    }

    /**
     * Get the effective exam date for scheduling purposes.
     * Returns exam_date if set, else window_start_date if set, else null.
     * The planner should use this to determine when an undated MST/EST exam is "due".
     */
    public LocalDate effectiveExamDate(Exam exam) {
        if (exam.getExamDate() != null) {
            return exam.getExamDate();
        }
        if (exam.getWindowStartDate() != null) {
            return exam.getWindowStartDate();
        }
        return null;
    }

    /**
     * Recompute the window from actual exam dates (when datesheet is announced).
     * If at least one exam of this type has an exact date, set the stored window to min/max of those dates,
     * and update window_start_date/window_end_date of remaining undated exams.
     * If none have dates, leave unchanged.
     * Returns the number of undated exams updated.
     */
    public int recomputeWindowFromDatesheet(int semesterId, String examType) throws SQLException {
        if (!isValidExamType(examType)) {
            return 0;
        }

        try (Connection conn = DBConnection.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                List<Exam> exams = examDAO.findBySemester(semesterId);

                // Find EXACT dated exams of this type (tentative = false)
                LocalDate minDate = null;
                LocalDate maxDate = null;
                for (Exam exam : exams) {
                    if (examType.equalsIgnoreCase(exam.getExamType()) 
                            && exam.getExamDate() != null 
                            && !exam.isExamDateTentative()) {
                        LocalDate date = exam.getExamDate();
                        if (minDate == null || date.isBefore(minDate)) {
                            minDate = date;
                        }
                        if (maxDate == null || date.isAfter(maxDate)) {
                            maxDate = date;
                        }
                    }
                }

                // If no exact dated exams, leave window unchanged
                if (minDate == null) {
                    conn.commit();
                    return 0;
                }

                // Update stored window
                timelineDAO.setWindow(semesterId, examType, minDate, maxDate);

                // Update undated/tentative exams with the new window
                int count = 0;
                for (Exam exam : exams) {
                    if (examType.equalsIgnoreCase(exam.getExamType()) 
                            && (exam.getExamDate() == null || exam.isExamDateTentative())) {
                        exam.setWindowStartDate(minDate);
                        exam.setWindowEndDate(maxDate);
                        examDAO.update(exam);
                        count++;
                    }
                }

                conn.commit();
                return count;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Check if all exams of this type have exact dates (datesheet complete).
     * Only counts non-tentative dates.
     */
    public boolean isDatesheetComplete(int semesterId, String examType) throws SQLException {
        List<Exam> exams = examDAO.findBySemester(semesterId);
        boolean foundAny = false;

        for (Exam exam : exams) {
            if (examType.equalsIgnoreCase(exam.getExamType())) {
                foundAny = true;
                if (exam.getExamDate() == null || exam.isExamDateTentative()) {
                    return false; // Found undated or tentative exam
                }
            }
        }

        return foundAny; // True if all are dated (non-tentative), false if none exist
    }

    /**
     * Count how many exams of this type have exact (non-tentative) dates.
     */
    public int countDatedExams(int semesterId, String examType) throws SQLException {
        List<Exam> exams = examDAO.findBySemester(semesterId);
        int count = 0;

        for (Exam exam : exams) {
            if (examType.equalsIgnoreCase(exam.getExamType()) && 
                exam.getExamDate() != null && !exam.isExamDateTentative()) {
                count++;
            }
        }

        return count;
    }

    /**
     * Count total exams of this type.
     */
    public int countTotalExams(int semesterId, String examType) throws SQLException {
        List<Exam> exams = examDAO.findBySemester(semesterId);
        int count = 0;

        for (Exam exam : exams) {
            if (examType.equalsIgnoreCase(exam.getExamType())) {
                count++;
            }
        }

        return count;
    }

    /**
     * Get the derived window from exact (non-tentative) exam dates.
     * Returns null if no exact dates exist.
     */
    public LocalDate[] getDerivedWindow(int semesterId, String examType) throws SQLException {
        List<Exam> exams = examDAO.findBySemester(semesterId);
        LocalDate minDate = null;
        LocalDate maxDate = null;

        for (Exam exam : exams) {
            if (examType.equalsIgnoreCase(exam.getExamType()) && 
                exam.getExamDate() != null && !exam.isExamDateTentative()) {
                LocalDate date = exam.getExamDate();
                if (minDate == null || date.isBefore(minDate)) {
                    minDate = date;
                }
                if (maxDate == null || date.isAfter(maxDate)) {
                    maxDate = date;
                }
            }
        }

        if (minDate == null) {
            return null;
        }

        return new LocalDate[]{minDate, maxDate};
    }

    /**
     * Reset all exams of this type to tentative mode: exam_date = stored window start (or null), tentative = 1, clear times.
     * Returns the number of exams reset.
     */
    public int resetToTentative(int semesterId, String examType) throws SQLException {
        if (!isValidExamType(examType)) {
            return 0;
        }

        try (Connection conn = DBConnection.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Optional<LocalDate[]> window = timelineDAO.getWindow(semesterId, examType);
                LocalDate tentativeDate = window.isPresent() ? window.get()[0] : null;

                List<Exam> exams = examDAO.findBySemester(semesterId);
                int count = 0;

                for (Exam exam : exams) {
                    if (examType.equalsIgnoreCase(exam.getExamType())) {
                        exam.setExamDate(tentativeDate);
                        exam.setExamDateTentative(tentativeDate != null);
                        exam.setExamStartTime(null);
                        exam.setExamEndTime(null);
                        if (window.isPresent()) {
                            exam.setWindowStartDate(window.get()[0]);
                            exam.setWindowEndDate(window.get()[1]);
                        }
                        examDAO.update(exam);
                        count++;
                    }
                }

                conn.commit();
                return count;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private boolean isValidExamType(String examType) {
        return "MST".equalsIgnoreCase(examType) || "EST".equalsIgnoreCase(examType);
    }
}
