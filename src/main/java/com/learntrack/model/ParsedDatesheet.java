package com.learntrack.model;

import java.time.LocalDate;
import java.util.*;

public class ParsedDatesheet {
    private final String sheetCode;
    private final String examType; // "MST" | "EST" | null
    private final List<DatesheetEntry> entries;
    private final int skippedLines;
    private final List<String> warnings;
    private final String formatName;
    private final Map<String, List<DatesheetEntry>> ambiguous;
    private final LocalDate firstDate;
    private final LocalDate lastDate;

    public ParsedDatesheet(String sheetCode, String examType, List<DatesheetEntry> entries, int skippedLines, List<String> warnings) {
        this(sheetCode, examType, entries, skippedLines, warnings, "Row-wise", Collections.emptyMap(), null, null);
    }

    public ParsedDatesheet(String sheetCode, String examType, List<DatesheetEntry> entries, int skippedLines,
                           List<String> warnings, String formatName, Map<String, List<DatesheetEntry>> ambiguous,
                           LocalDate firstDate, LocalDate lastDate) {
        this.sheetCode = sheetCode != null ? sheetCode : "";
        this.examType = examType;
        this.entries = entries != null ? entries : new ArrayList<>();
        this.skippedLines = skippedLines;
        this.warnings = warnings != null ? warnings : new ArrayList<>();
        this.formatName = formatName != null ? formatName : "Row-wise";
        this.ambiguous = ambiguous != null ? ambiguous : Collections.emptyMap();

        if (firstDate != null && lastDate != null) {
            this.firstDate = firstDate;
            this.lastDate = lastDate;
        } else {
            LocalDate min = null;
            LocalDate max = null;
            for (DatesheetEntry e : this.entries) {
                if (e.getDate() != null) {
                    if (min == null || e.getDate().isBefore(min)) min = e.getDate();
                    if (max == null || e.getDate().isAfter(max)) max = e.getDate();
                }
            }
            this.firstDate = min;
            this.lastDate = max;
        }
    }

    public String getSheetCode() {
        return sheetCode;
    }

    public String getExamType() {
        return examType;
    }

    public List<DatesheetEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int getSkippedLines() {
        return skippedLines;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public String getFormatName() {
        return formatName;
    }

    public Map<String, List<DatesheetEntry>> getAmbiguous() {
        return Collections.unmodifiableMap(ambiguous);
    }

    public LocalDate getFirstDate() {
        return firstDate;
    }

    public LocalDate getLastDate() {
        return lastDate;
    }

    public int getTotalUniqueCourses() {
        Set<String> unique = new HashSet<>();
        for (DatesheetEntry e : entries) {
            if (e.getCourseCode() != null && !e.getCourseCode().isBlank()) {
                unique.add(e.getCourseCode().toUpperCase().trim());
            }
        }
        for (String ambiguousCode : ambiguous.keySet()) {
            if (ambiguousCode != null && !ambiguousCode.isBlank()) {
                unique.add(ambiguousCode.toUpperCase().trim());
            }
        }
        return unique.size();
    }
}
