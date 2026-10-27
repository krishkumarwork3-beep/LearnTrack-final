package com.learntrack.service.datesheet;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for parsing date, time, course codes, and ungluing tokens in datesheets.
 */
public class DatesheetTokenUtils {

    public static class TimeSpan {
        private final LocalTime start;
        private final LocalTime end;

        public TimeSpan(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }

        public LocalTime getStart() {
            return start;
        }

        public LocalTime getEnd() {
            return end;
        }
    }

    public static final Pattern SHEET_CODE_PATTERN = Pattern.compile(
            "Date\\s*Sheet\\s*Code\\s*:\\s*([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE
    );

    // Matches standard course code: 2-4 uppercase letters, 3-4 digits, optional 1 uppercase letter
    public static final Pattern COURSE_CODE_PATTERN = Pattern.compile(
            "^[A-Z]{2,4}\\d{3,4}[A-Z]?$"
    );

    public static final Pattern COURSE_CODE_START_PATTERN = Pattern.compile(
            "^([A-Z]{2,4}\\d{3,4}[A-Z]?)\\s+(.*)$"
    );

    public static final Pattern GLUED_COURSE_PATTERN = Pattern.compile(
            "([A-Za-z]{2,}[A-Za-z\\-]*?)(U[A-Z]{2}\\d{3}[A-Z]?|[A-Z]{2}\\d{4}[A-Z]?|[A-Z]{3,4}\\d{3,4}[A-Z]?)(?=\\s+|$)"
    );

    public static final Pattern WEEKDAY_START_PATTERN = Pattern.compile(
            "^(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\b\\s*",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("yyyy-M-d").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/M/yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-M-yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d.M.yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM yyyy").toFormatter(Locale.ENGLISH)
    );

    private static final DateTimeFormatter SINGLE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("[hh:mm a][h:mm a][hh:mma][h:mma][HH:mm][H:mm]")
            .toFormatter(Locale.ENGLISH);

    /**
     * Parses date string into LocalDate. Supports:
     * yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy, d MMM yyyy, Monday, 21 Sep 2026.
     */
    public static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        String clean = text.trim();

        // Strip weekday prefix if present (e.g. "Monday, 21 Sep 2026" or "Monday 21 Sep 2026")
        Matcher wm = Pattern.compile("^(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)[,\\s]+", Pattern.CASE_INSENSITIVE).matcher(clean);
        if (wm.find()) {
            clean = clean.substring(wm.end()).trim();
        }

        for (DateTimeFormatter f : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(clean, f);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Parses time string or range into TimeSpan.
     * Supports: 09:00, 9:00 AM, 1:00 PM, 13:00, 9.00 AM, and ranges (09:00 - 11:00, 09:00 AM to 11:00 AM).
     */
    public static TimeSpan parseTimeOrRange(String text) {
        if (text == null || text.isBlank()) return null;
        String s = text.trim().replaceAll("\\s+", " ");

        // Check for range with "-" or "to"
        String[] parts = null;
        if (s.contains(" to ")) {
            parts = s.split(" to ");
        } else if (s.contains(" - ")) {
            parts = s.split(" - ");
        } else if (s.matches(".*\\d\\s*-\\s*\\d.*")) {
            parts = s.split("\\s*-\\s*");
        }

        if (parts != null && parts.length == 2) {
            LocalTime start = parseSingleTime(parts[0].trim());
            LocalTime end = parseSingleTime(parts[1].trim());
            if (start != null) {
                return new TimeSpan(start, end);
            }
        }

        LocalTime single = parseSingleTime(s);
        if (single != null) {
            return new TimeSpan(single, null);
        }
        return null;
    }

    public static LocalTime parseSingleTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        String cleaned = timeStr.replaceAll("\\s+", " ").trim();

        // Handle dot as separator: 9.00 AM -> 9:00 AM, 09.00 -> 09:00
        cleaned = cleaned.replaceAll("(?i)(\\d{1,2})\\.(\\d{2})", "$1:$2");

        // Insert space before AM/PM if missing (e.g. 09:00AM -> 09:00 AM, 1:00PM -> 1:00 PM)
        cleaned = cleaned.replaceAll("(?i)(\\d{1,2}:\\d{2})([AP]M)", "$1 $2");

        try {
            return LocalTime.parse(cleaned, SINGLE_TIME_FORMATTER);
        } catch (Exception e) {
            try {
                return LocalTime.parse(cleaned.toUpperCase());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static boolean isValidCourseCode(String token) {
        if (token == null) return false;
        return COURSE_CODE_PATTERN.matcher(token.trim()).matches();
    }

    public static String splitGluedCourses(String line) {
        if (line == null) return "";
        Matcher m = GLUED_COURSE_PATTERN.matcher(line);
        if (m.find()) {
            return m.replaceAll("$1\n$2");
        }
        return line;
    }

    /**
     * Determines exam type from sheetCode or text. Returns "MST", "EST", or null.
     */
    public static String detectExamType(String sheetCode, String fullText) {
        if (sheetCode != null && !sheetCode.isBlank()) {
            String upper = sheetCode.toUpperCase();
            if (upper.contains("EST") || upper.contains("END")) {
                return "EST";
            }
            if (upper.contains("MST") || upper.contains("MID")) {
                return "MST";
            }
        }

        if (fullText != null) {
            String upper = fullText.toUpperCase();
            if (upper.contains("END SEMESTER") || upper.contains("EST -") || upper.contains("EST-")) {
                return "EST";
            }
            if (upper.contains("MID SEMESTER") || upper.contains("MST -") || upper.contains("MST-")) {
                return "MST";
            }
        }

        return null;
    }
}
