package com.learntrack.ui.forms;

import com.learntrack.model.Exam;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;

/**
 * Helper class for exam row ordering and validation.
 * Pure logic, no UI dependencies.
 */
public class ExamRowHelper {

    /**
     * Comparator for ordering exam rows:
     * - Exact dated exams first, sorted by date then start time
     * - Tentative exams after, sorted alphabetically by subject name
     */
    public static Comparator<Exam> getExamOrderingComparator() {
        return (e1, e2) -> {
            boolean e1Exact = e1.getExamDate() != null && !e1.isExamDateTentative();
            boolean e2Exact = e2.getExamDate() != null && !e2.isExamDateTentative();

            // Both exact: sort by date, then start time
            if (e1Exact && e2Exact) {
                int dateCompare = e1.getExamDate().compareTo(e2.getExamDate());
                if (dateCompare != 0) {
                    return dateCompare;
                }
                
                // Same date, sort by start time
                LocalTime t1 = e1.getExamStartTime();
                LocalTime t2 = e2.getExamStartTime();
                if (t1 != null && t2 != null) {
                    return t1.compareTo(t2);
                }
                if (t1 != null) return -1;
                if (t2 != null) return 1;
                return 0;
            }

            // e1 exact, e2 tentative: e1 first
            if (e1Exact) {
                return -1;
            }

            // e1 tentative, e2 exact: e2 first
            if (e2Exact) {
                return 1;
            }

            // Both tentative: alphabetical by subject
            String s1 = e1.getSubject() != null ? e1.getSubject() : "";
            String s2 = e2.getSubject() != null ? e2.getSubject() : "";
            return s1.compareToIgnoreCase(s2);
        };
    }

    /**
     * Format a date chip for display.
     * @param exam The exam
     * @return "12 Nov, 10:00" for exact dates, "Tentative" for tentative
     */
    public static String formatDateChip(Exam exam) {
        if (exam.getExamDate() == null) {
            return "No date";
        }

        if (exam.isExamDateTentative()) {
            return "Tentative";
        }

        // Format: "12 Nov, 10:00"
        String dateStr = formatShortDate(exam.getExamDate());
        if (exam.getExamStartTime() != null) {
            return dateStr + ", " + exam.getExamStartTime().toString();
        }
        return dateStr;
    }

    /**
     * Format date as "12 Nov".
     */
    private static String formatShortDate(LocalDate date) {
        String month = date.getMonth().toString().substring(0, 3);
        month = month.charAt(0) + month.substring(1).toLowerCase();
        return date.getDayOfMonth() + " " + month;
    }

    /**
     * Validate exam date fields in datesheet mode.
     * @return null if valid, error message otherwise
     */
    public static String validateDateFields(LocalDate examDate, LocalTime startTime, LocalTime endTime) {
        if (examDate == null) {
            return "Exam date is required";
        }

        if (endTime != null && startTime != null && endTime.isBefore(startTime)) {
            return "End time must be after start time";
        }

        return null; // Valid
    }

    /**
     * Format tentative display: "Planned as: 10 Nov (tentative)".
     */
    public static String formatTentativeDisplay(LocalDate date) {
        if (date == null) {
            return "Set the timeline above";
        }
        return "Planned as: " + formatShortDate(date) + " (tentative)";
    }
}
