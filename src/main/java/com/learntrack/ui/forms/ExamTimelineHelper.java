package com.learntrack.ui.forms;

import com.learntrack.model.Exam;
import com.learntrack.service.ExamTimelineService;

import java.time.LocalDate;
import java.util.Comparator;

/**
 * Helper class for exam timeline UI logic.
 * Provides ordering and validation for exams with tentative windows.
 */
public class ExamTimelineHelper {

    private final ExamTimelineService timelineService;

    public ExamTimelineHelper() {
        this.timelineService = new ExamTimelineService();
    }

    public ExamTimelineHelper(ExamTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    /**
     * Comparator for ordering exams by effective date.
     * Dated exams first (sorted by date, then start time), then undated (alphabetical by subject).
     */
    public Comparator<Exam> getExamComparator() {
        return (e1, e2) -> {
            boolean e1Dated = e1.getExamDate() != null;
            boolean e2Dated = e2.getExamDate() != null;

            // Dated exams come before undated
            if (e1Dated && !e2Dated) return -1;
            if (!e1Dated && e2Dated) return 1;

            if (e1Dated) {
                // Both dated: compare by date, then by start time
                int dateComp = e1.getExamDate().compareTo(e2.getExamDate());
                if (dateComp != 0) return dateComp;

                // Same date: compare by start time
                if (e1.getExamStartTime() != null && e2.getExamStartTime() != null) {
                    return e1.getExamStartTime().compareTo(e2.getExamStartTime());
                }
                return 0;
            } else {
                // Both undated: alphabetical by subject
                return e1.getSubject().compareToIgnoreCase(e2.getSubject());
            }
        };
    }

    /**
     * Get effective exam date for display purposes.
     */
    public LocalDate getEffectiveDate(Exam exam) {
        return timelineService.effectiveExamDate(exam);
    }

    /**
     * Validate that end >= start for window dates.
     */
    public boolean isValidWindow(LocalDate start, LocalDate end) {
        if (start == null || end == null) return false;
        return !end.isBefore(start);
    }

    /**
     * Format display string for tentative exam.
     */
    public String formatTentativeDisplay(LocalDate windowStart) {
        if (windowStart == null) {
            return "Set the timeline above";
        }
        return "Planned as: " + formatDate(windowStart) + " (tentative)";
    }

    /**
     * Format date for display.
     */
    public String formatDate(LocalDate date) {
        if (date == null) return "";
        return date.getDayOfMonth() + " " + 
               date.getMonth().toString().substring(0, 3).charAt(0) + 
               date.getMonth().toString().substring(1, 3).toLowerCase();
    }

    /**
     * Format date and time chip for row summary.
     */
    public String formatDateTimeChip(Exam exam) {
        if (exam.getExamDate() != null) {
            String dateStr = formatDate(exam.getExamDate());
            if (exam.getExamStartTime() != null) {
                dateStr += ", " + exam.getExamStartTime().toString();
            }
            return dateStr;
        } else if (exam.getWindowStartDate() != null) {
            return "Tentative";
        }
        return "No date";
    }
}
