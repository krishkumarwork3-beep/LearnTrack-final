package com.learntrack.model;

import java.time.LocalDate;
import java.util.*;

/**
 * Result of analysing a datesheet against the active semester subjects.
 */
public class DatesheetAnalysisResult {

    private final String sheetCode;
    private final String examType;
    private final List<DatesheetMatchRow> matchedRows;
    private final List<SubjectInfo> unmatchedSubjects;
    private final List<String> warnings;
    private final List<String> clashes;
    private final int skippedLines;
    private final String formatName;
    private final int totalUniqueCourses;
    private final LocalDate firstDate;
    private final LocalDate lastDate;
    private final Map<String, List<DatesheetEntry>> ambiguous;
    private final int assignedCourses;
    private final int ambiguousCourses;

    public DatesheetAnalysisResult(String sheetCode, String examType,
                                   List<DatesheetMatchRow> matchedRows,
                                   List<SubjectInfo> unmatchedSubjects,
                                   List<String> warnings,
                                   int skippedLines) {
        this(sheetCode, examType, matchedRows, unmatchedSubjects, warnings, skippedLines,
                "Row-wise", 0, null, null, Collections.emptyMap(), 0, 0, Collections.emptyList());
    }

    public DatesheetAnalysisResult(String sheetCode, String examType,
                                   List<DatesheetMatchRow> matchedRows,
                                   List<SubjectInfo> unmatchedSubjects,
                                   List<String> warnings,
                                   int skippedLines,
                                   String formatName,
                                   int totalUniqueCourses,
                                   LocalDate firstDate,
                                   LocalDate lastDate,
                                   Map<String, List<DatesheetEntry>> ambiguous) {
        this(sheetCode, examType, matchedRows, unmatchedSubjects, warnings, skippedLines, formatName,
                totalUniqueCourses, firstDate, lastDate, ambiguous,
                matchedRows != null ? matchedRows.size() : 0, 0, Collections.emptyList());
    }

    public DatesheetAnalysisResult(String sheetCode, String examType,
                                   List<DatesheetMatchRow> matchedRows,
                                   List<SubjectInfo> unmatchedSubjects,
                                   List<String> warnings,
                                   int skippedLines,
                                   String formatName,
                                   int totalUniqueCourses,
                                   LocalDate firstDate,
                                   LocalDate lastDate,
                                   Map<String, List<DatesheetEntry>> ambiguous,
                                   int assignedCourses,
                                   int ambiguousCourses) {
        this(sheetCode, examType, matchedRows, unmatchedSubjects, warnings, skippedLines, formatName,
                totalUniqueCourses, firstDate, lastDate, ambiguous, assignedCourses, ambiguousCourses,
                Collections.emptyList());
    }

    public DatesheetAnalysisResult(String sheetCode, String examType,
                                   List<DatesheetMatchRow> matchedRows,
                                   List<SubjectInfo> unmatchedSubjects,
                                   List<String> warnings,
                                   int skippedLines,
                                   String formatName,
                                   int totalUniqueCourses,
                                   LocalDate firstDate,
                                   LocalDate lastDate,
                                   Map<String, List<DatesheetEntry>> ambiguous,
                                   int assignedCourses,
                                   int ambiguousCourses,
                                   List<String> clashes) {
        this.sheetCode = sheetCode != null ? sheetCode : "";
        // No silent default: a sheet whose type cannot be detected stays undecided until the user picks one.
        this.examType = examType;
        this.matchedRows = matchedRows != null ? matchedRows : Collections.emptyList();
        this.unmatchedSubjects = unmatchedSubjects != null ? unmatchedSubjects : Collections.emptyList();
        this.warnings = warnings != null ? warnings : Collections.emptyList();
        this.clashes = clashes != null ? clashes : Collections.emptyList();
        this.skippedLines = skippedLines;
        this.formatName = formatName != null ? formatName : "Row-wise";
        this.totalUniqueCourses = totalUniqueCourses;
        this.firstDate = firstDate;
        this.lastDate = lastDate;
        this.ambiguous = ambiguous != null ? ambiguous : Collections.emptyMap();
        this.assignedCourses = assignedCourses;
        this.ambiguousCourses = ambiguousCourses;
    }

    /** Number of course codes the parser placed on an exact date and time. */
    public int getAssignedCourses() {
        return assignedCourses;
    }

    /** Number of course codes the parser could not place with certainty. */
    public int getAmbiguousCourses() {
        return ambiguousCourses;
    }

    public String getSheetCode() {
        return sheetCode;
    }

    public String getExamType() {
        return examType;
    }

    public List<DatesheetMatchRow> getMatchedRows() {
        return matchedRows;
    }

    public List<SubjectInfo> getUnmatchedSubjects() {
        return unmatchedSubjects;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * Real timetable clashes only: two of the user's own matched subjects whose exam start and
     * end times overlap on the same date. Everything else stays in {@link #getWarnings()}.
     */
    public List<String> getClashes() {
        return clashes;
    }

    public int getSkippedLines() {
        return skippedLines;
    }

    public String getFormatName() {
        return formatName;
    }

    public int getTotalUniqueCourses() {
        return totalUniqueCourses;
    }

    public LocalDate getFirstDate() {
        return firstDate;
    }

    public LocalDate getLastDate() {
        return lastDate;
    }

    public Map<String, List<DatesheetEntry>> getAmbiguous() {
        return Collections.unmodifiableMap(ambiguous);
    }

    /**
     * Returns ambiguous courses that match the user's subjects.
     * Key is the user's subject code, value is the list of candidate date/time slots.
     */
    public Map<String, List<DatesheetEntry>> getAmbiguousUserSubjects() {
        return Collections.unmodifiableMap(ambiguous);
    }
}
