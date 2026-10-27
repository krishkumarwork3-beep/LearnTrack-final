package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamTimelineDAO;
import com.learntrack.model.Exam;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for exam window evaluation (MST/EST).
 * Handles whole-datesheet window, exact exam fallback, and dated window status.
 */
public class ExamWindowService {

    private final ExamTimelineDAO timelineDAO;
    private final ExamDAO examDAO;

    public ExamWindowService() {
        this(new ExamTimelineDAO(), new ExamDAO());
    }

    public ExamWindowService(ExamTimelineDAO timelineDAO, ExamDAO examDAO) {
        this.timelineDAO = timelineDAO != null ? timelineDAO : new ExamTimelineDAO();
        this.examDAO = examDAO != null ? examDAO : new ExamDAO();
    }

    /**
     * Returns the dated exam window [start, end] for a given semester and exam type.
     * 1. Window from stored sheet dates (if datesheet is announced).
     * 2. Fallback to min/max of user's exact dated exams (start = first exam date, NOT today).
     * 3. Null if nothing dated.
     */
    public LocalDate[] windowFor(int semesterId, String type) throws SQLException {
        if (type == null) return null;
        String examType = type.toUpperCase().trim();

        // 1. Check stored window from datesheet (when announced)
        boolean announced = timelineDAO.isDatesheetAnnounced(semesterId, examType);
        Optional<LocalDate[]> stored = timelineDAO.getWindow(semesterId, examType);
        if (announced && stored.isPresent()) {
            return stored.get();
        }

        // 2. Fallback: min/max of exact dated exams for this type
        List<Exam> exams = examDAO.findBySemester(semesterId);
        LocalDate minStart = null;
        LocalDate maxEnd = null;
        for (Exam e : exams) {
            if (examType.equalsIgnoreCase(e.getExamType()) && e.getExamDate() != null && !e.isExamDateTentative()) {
                LocalDate d = e.getExamDate();
                if (minStart == null || d.isBefore(minStart)) {
                    minStart = d;
                }
                if (maxEnd == null || d.isAfter(maxEnd)) {
                    maxEnd = d;
                }
            }
        }
        if (minStart != null && maxEnd != null) {
            return new LocalDate[]{minStart, maxEnd};
        }

        // If stored window exists (even if not explicitly marked announced) and non-tentative exams exist:
        if (stored.isPresent() && announced) {
            return stored.get();
        }

        return null;
    }

    /**
     * Checks if a given date falls within any dated MST or EST exam window.
     */
    public boolean isInDatedWindow(int semesterId, LocalDate date) throws SQLException {
        if (date == null) return false;
        for (String type : List.of("MST", "EST")) {
            LocalDate[] win = windowFor(semesterId, type);
            if (win != null && !date.isBefore(win[0]) && !date.isAfter(win[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the exam type ("MST" or "EST") if date is in an exam window, or Optional.empty().
     */
    public Optional<String> examTypeOnDate(int semesterId, LocalDate date) throws SQLException {
        if (date == null) return Optional.empty();
        for (String type : List.of("MST", "EST")) {
            LocalDate[] win = windowFor(semesterId, type);
            if (win != null && !date.isBefore(win[0]) && !date.isAfter(win[1])) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
