package com.learntrack.service.datesheet;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layout-independent tokenizer for datesheet text.
 *
 * <p>Different PDF viewers cut the same datesheet into different lines: some keep one row per line
 * ("1 2026-09-21 09:00 AM UCS503 SOFTWARE ENGINEERING"), some emit every table column as its own
 * block of lines, and some glue the first row of a page into a single line
 * ("24/08/2026 17:30 UAI201"). The tokenizer therefore never assumes that a date, a time or a
 * course code stands alone on a line: it scans the text character by character and classifies every
 * fragment it recognises.</p>
 */
public final class DatesheetTokenizer {

    /** Kind of a recognised fragment. */
    public enum Type {
        DATE,
        TIME,
        WEEKDAY,
        SERIAL,
        COURSE_CODE,
        PAGE_MARKER,
        HEADER,
        NAME
    }

    /** One recognised fragment together with its parsed value (if it has one). */
    public static final class Token {
        private final Type type;
        private final String text;
        private final LocalDate date;
        private final LocalTime start;
        private final LocalTime end;
        private final int line;
        private final int column;

        Token(Type type, String text, LocalDate date, LocalTime start, LocalTime end, int line, int column) {
            this.type = type;
            this.text = text;
            this.date = date;
            this.start = start;
            this.end = end;
            this.line = line;
            this.column = column;
        }

        public Type getType() {
            return type;
        }

        public String getText() {
            return text;
        }

        public LocalDate getDate() {
            return date;
        }

        public LocalTime getStart() {
            return start;
        }

        public LocalTime getEnd() {
            return end;
        }

        public int getLine() {
            return line;
        }

        public int getColumn() {
            return column;
        }

        @Override
        public String toString() {
            return type + "(" + text + ")@" + line + ":" + column;
        }
    }

    /**
     * A course code: one to four uppercase letters, three or four digits and an optional trailing
     * batch letter (AH3401, NP1214, MCA205, PMA301, UCS101A). At least one digit is required, so
     * ordinary words never qualify.
     */
    public static final Pattern COURSE_CODE_PATTERN = Pattern.compile(
            "\\b[A-Z]{1,4}\\d{3,4}[A-Z]?\\b"
    );

    /** "Page 3 of 12". */
    public static final Pattern PAGE_MARKER_PATTERN = Pattern.compile(
            "(?i)\\bpage\\s+\\d+\\s+of\\s+\\d+\\b"
    );

    /** Recognised header / table-label text that never carries data. */
    public static final Pattern HEADER_PATTERN = Pattern.compile(
            "(?i)(THAPAR\\s+INSTITUTE\\s+OF\\s+ENGINEERING"
                    + "|DEEMED\\s+TO\\s+BE\\s+UNIVERSITY"
                    + "|INDIA\\s*-\\s*147004"
                    + "|DATE\\s+SHEET\\s+FOR"
                    + "|DATE\\s+SHEET\\s+CODE"
                    + "|SL\\.?\\s*NO\\.?"
                    + "|COURSE\\s+NO\\.?"
                    + "|COURSE\\s+NAME)"
    );

    private static final Pattern WEEKDAY_PATTERN = Pattern.compile(
            "(?i)\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday"
                    + "|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\\b"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b"
                    + "|\\b\\d{1,2}[/.]\\d{1,2}[/.]\\d{4}\\b"
                    + "|\\b\\d{1,2}-\\d{1,2}-\\d{4}\\b"
                    + "|\\b\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4}\\b"
    );

    private static final String SINGLE_TIME =
            "\\d{1,2}[:.]\\d{2}(?:\\s*[AaPp][Mm])?";

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "\\b" + SINGLE_TIME
                    + "(?:\\s*(?:-|–|to)\\s*" + SINGLE_TIME + ")?\\b"
    );

    private static final Pattern SERIAL_PATTERN = Pattern.compile("\\b\\d{1,3}\\b");

    /** Combined scanner: the alternation order decides which classification wins. */
    private static final Pattern SCAN_PATTERN = Pattern.compile(
            "(?<date>" + DATE_PATTERN.pattern() + ")"
                    + "|(?<time>" + TIME_PATTERN.pattern() + ")"
                    + "|(?<page>" + PAGE_MARKER_PATTERN.pattern() + ")"
                    + "|(?<header>" + HEADER_PATTERN.pattern() + ")"
                    + "|(?<weekday>" + WEEKDAY_PATTERN.pattern() + ")"
                    + "|(?<code>" + COURSE_CODE_PATTERN.pattern() + ")"
                    + "|(?<serial>" + SERIAL_PATTERN.pattern() + ")"
                    + "|(?<word>\\S+)"
    );

    private DatesheetTokenizer() {
    }

    /** Tokenizes the whole text, keeping line numbers for diagnostics. */
    public static List<Token> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ');
        List<Token> tokens = new ArrayList<>();
        String[] lines = normalised.split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
            tokens.addAll(tokenizeLine(lines[line], line));
        }
        return tokens;
    }

    /** Tokenizes a single line; a line may yield several tokens of different types. */
    public static List<Token> tokenizeLine(String line, int lineNumber) {
        if (line == null || line.isBlank()) {
            return Collections.emptyList();
        }
        List<Token> tokens = new ArrayList<>();
        Matcher m = SCAN_PATTERN.matcher(line);
        while (m.find()) {
            String value = m.group();
            int column = m.start();
            if (m.group("date") != null) {
                LocalDate date = DatesheetTokenUtils.parseDate(value);
                if (date != null) {
                    tokens.add(new Token(Type.DATE, value, date, null, null, lineNumber, column));
                    continue;
                }
            } else if (m.group("time") != null) {
                DatesheetTokenUtils.TimeSpan span = DatesheetTokenUtils.parseTimeOrRange(value);
                if (span != null) {
                    tokens.add(new Token(Type.TIME, value, null, span.getStart(), span.getEnd(), lineNumber, column));
                    continue;
                }
            } else if (m.group("page") != null) {
                tokens.add(new Token(Type.PAGE_MARKER, value, null, null, null, lineNumber, column));
                continue;
            } else if (m.group("header") != null) {
                tokens.add(new Token(Type.HEADER, value, null, null, null, lineNumber, column));
                continue;
            } else if (m.group("weekday") != null) {
                tokens.add(new Token(Type.WEEKDAY, value, null, null, null, lineNumber, column));
                continue;
            } else if (m.group("code") != null) {
                tokens.add(new Token(Type.COURSE_CODE, value, null, null, null, lineNumber, column));
                continue;
            } else if (m.group("serial") != null) {
                tokens.add(new Token(Type.SERIAL, value, null, null, null, lineNumber, column));
                continue;
            }
            tokens.add(new Token(Type.NAME, value, null, null, null, lineNumber, column));
        }
        return tokens;
    }

    /** True when the token is a course code (at least one digit, uppercase letters). */
    public static boolean isCourseCode(String token) {
        return token != null && COURSE_CODE_PATTERN.matcher(token.trim()).matches();
    }
}
