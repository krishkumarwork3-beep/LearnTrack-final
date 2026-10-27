package com.learntrack.service.datesheet;

import com.learntrack.model.DatesheetEntry;
import com.learntrack.model.ParsedDatesheet;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Row-wise datesheet format parser (covers Format A and Format B).
 *
 * <p>A row-wise datesheet prints one table row per time slot and lets the row wrap. Two shapes are
 * common: the classic "serial date time CODE NAME" row followed by "CODE NAME" continuation lines,
 * and the "count" sheet where the row is "serial date time total CODE (n) CODE (n)" and the wrapped
 * lines carry only more "CODE (n)" tokens.</p>
 *
 * <p>Lines are therefore never assumed to hold exactly one item: a line is scanned for course codes,
 * and everything between two codes is the name of the first one. A line without any code continues
 * the name of the previous course, and a line that holds only codes (with or without the bracketed
 * candidate count) belongs to the slot that is currently open. The open slot survives page headers,
 * "Page N", "Candidates" and similar noise, so a slot that continues onto the next page keeps its
 * date even though the date row is not repeated there.</p>
 */
public class RowWiseFormat implements DatesheetFormat {

    /**
     * A slot line: optional serial, date, time and the rest of the line. Group 4 is the remainder,
     * which may start with the total candidate count and then hold any number of course codes.
     */
    public static final Pattern SLOT_START_PATTERN = Pattern.compile(
            "^(\\d+\\s+)?(\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{4}|\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4})\\s+(\\d{1,2}[:.]\\d{2}(?:\\s*[AaPp][Mm]\\b)?(?:\\s*(?:-|to)\\s*\\d{1,2}[:.]\\d{2}(?:\\s*[AaPp][Mm]\\b)?)?)\\s*(.*)$"
    );

    /**
     * A course code with an optional bracketed candidate count ("UCS503 (30)"). The code may be
     * glued to the end of the previous name ("...SENSORUMA401"), which is why only a preceding digit
     * rules a match out. The count is metadata: it never influences the slot a code belongs to.
     */
    public static final Pattern COURSE_TOKEN_PATTERN = Pattern.compile(
            "(?<![0-9])([A-Z]{1,4}\\d{3,4}[A-Z]?)(?:\\s*\\(\\s*(\\d{1,4})\\s*\\))?");

    /** The total candidate count in front of a slot's first course code. */
    private static final Pattern LEADING_COUNT_PATTERN = Pattern.compile("^(\\d{1,4})(?=\\s|$)");

    /** A trailing bracketed number is a candidate count, never part of a course name. */
    private static final Pattern TRAILING_COUNT_PATTERN = Pattern.compile("\\s*\\(\\s*\\d{1,4}\\s*\\)\\s*$");

    private final List<String> customNoiseList;

    public RowWiseFormat() {
        this(Collections.emptyList());
    }

    public RowWiseFormat(List<String> customNoiseList) {
        this.customNoiseList = customNoiseList != null ? customNoiseList : Collections.emptyList();
    }

    @Override
    public String name() {
        return "Row-wise";
    }

    @Override
    public double score(String text) {
        // How much of the text is printed row by row; a column-block paste of the same sheet, whose
        // dates and times arrive as separate columns, scores low even though it has a date and codes.
        return layoutScore(text);
    }

    /**
     * How much of the text's date/time material is printed row by row, between 0 and 1.
     *
     * <p>Every slot line counts towards the row-wise layout; every line that carries nothing but a
     * date, or nothing but a time, counts against it (that is what a paste of separated columns
     * looks like). 1.0 therefore means a genuine row-wise table, while a column-block paste of the
     * same sheet stays well below 0.5 even though its first row of each page happens to arrive as a
     * single "date time code" line.</p>
     */
    public static double layoutScore(String text) {
        LayoutEvidence evidence = layoutEvidence(text);
        int total = evidence.slotLines + evidence.bareDates + evidence.bareTimes;
        if (total == 0) {
            return 0.0;
        }
        return (double) evidence.slotLines / total;
    }

    private static final class LayoutEvidence {
        int slotLines;
        int bareDates;
        int bareTimes;
    }

    private static LayoutEvidence layoutEvidence(String text) {
        LayoutEvidence evidence = new LayoutEvidence();
        if (text == null || text.isBlank()) {
            return evidence;
        }
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ');
        for (String raw : normalised.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (SLOT_START_PATTERN.matcher(line).matches()) {
                evidence.slotLines++;
                continue;
            }
            boolean onlyDate = true;
            boolean onlyTime = true;
            boolean sawData = false;
            for (DatesheetTokenizer.Token token : DatesheetTokenizer.tokenizeLine(line, 0)) {
                switch (token.getType()) {
                    case DATE:
                        sawData = true;
                        onlyTime = false;
                        break;
                    case TIME:
                        sawData = true;
                        onlyDate = false;
                        break;
                    case WEEKDAY:
                    case HEADER:
                    case PAGE_MARKER:
                        break;
                    default:
                        sawData = true;
                        onlyDate = false;
                        onlyTime = false;
                        break;
                }
            }
            if (!sawData) {
                continue;
            }
            if (onlyDate) {
                evidence.bareDates++;
            } else if (onlyTime) {
                evidence.bareTimes++;
            }
        }
        return evidence;
    }

    @Override
    public ParsedDatesheet parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedDatesheet("", "MST", Collections.emptyList(), 0, Collections.emptyList(),
                    name(), Collections.emptyMap(), null, null);
        }

        // 1. Normalise Windows line endings, form feeds, non-breaking spaces
        String normalised = text.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace('\u00A0', ' ')
                .replace('\u000C', '\n');

        String sheetCode = "";
        String examType = null;
        Matcher sheetCodeMatcher = DatesheetTokenUtils.SHEET_CODE_PATTERN.matcher(normalised);
        if (sheetCodeMatcher.find()) {
            sheetCode = sheetCodeMatcher.group(1).trim();
            examType = DatesheetTokenUtils.detectExamType(sheetCode, text);
        }
        List<DatesheetEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, List<DatesheetEntry>> ambiguous = new LinkedHashMap<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        Set<String> allCodes = new LinkedHashSet<>();
        Set<String> knownSlots = new LinkedHashSet<>();
        int skippedLines = 0;

        // A paste of separated columns is not a row-wise table: leave it to ColumnBlockFormat rather
        // than attaching every code of a page to the single combined line at the top of that page.
        LayoutEvidence evidence = layoutEvidence(normalised);
        if (evidence.bareDates + evidence.bareTimes >= 2
                && evidence.bareDates + evidence.bareTimes >= evidence.slotLines) {
            warnings.add("This text keeps dates and times in separate columns; it is read as a "
                    + "column-block datesheet.");
            return new ParsedDatesheet(sheetCode, examType, entries, skippedLines, warnings,
                    name(), ambiguous, null, null);
        }

        int currentSlNo = 0;
        LocalDate currentDate = null;
        LocalTime currentStartTime = null;
        LocalTime currentEndTime = null;

        DatesheetEntry activeEntry = null;

        String[] rawLines = normalised.split("\n");

        // Learn initial header lines seen before the first slot line
        Set<String> repeatedHeaderLines = new HashSet<>();
        boolean beforeFirstSlot = true;

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Check for Sheet Code
            Matcher sheetMatcher = DatesheetTokenUtils.SHEET_CODE_PATTERN.matcher(line);
            if (sheetMatcher.find()) {
                sheetCode = sheetMatcher.group(1).trim();
                examType = DatesheetTokenUtils.detectExamType(sheetCode, text);
                continue;
            }

            // Check if noise
            if (isNoise(line, repeatedHeaderLines)) {
                if (beforeFirstSlot) {
                    repeatedHeaderLines.add(line.toUpperCase(Locale.ROOT));
                }
                continue;
            }

            if (line.matches("^[~\\-=*#]+.*[~\\-=*#]+$")) {
                skippedLines++;
                continue;
            }

            // Check for slot-start line: serial, date, time, then the total and the first codes
            Matcher slotMatcher = SLOT_START_PATTERN.matcher(line);
            if (slotMatcher.matches()) {
                beforeFirstSlot = false;
                try {
                    String slNoStr = slotMatcher.group(1);
                    if (slNoStr != null && !slNoStr.isBlank()) {
                        currentSlNo = Integer.parseInt(slNoStr.trim());
                    }
                    currentDate = DatesheetTokenUtils.parseDate(slotMatcher.group(2).trim());
                    DatesheetTokenUtils.TimeSpan timeSpan = DatesheetTokenUtils.parseTimeOrRange(
                            slotMatcher.group(3).trim());
                    if (timeSpan != null) {
                        currentStartTime = timeSpan.getStart();
                        currentEndTime = timeSpan.getEnd();
                    }
                    if (currentDate != null && currentStartTime != null) {
                        knownSlots.add(currentDate + "|" + currentStartTime);
                    }

                    String remainder = slotMatcher.group(4).trim();

                    // "45 UCS503 (30)": a plain integer in front of the first code is the slot's
                    // total candidate count. It is metadata for the whole slot, not a course.
                    Matcher totalMatcher = LEADING_COUNT_PATTERN.matcher(remainder);
                    if (totalMatcher.find()) {
                        remainder = remainder.substring(totalMatcher.end()).trim();
                    }

                    activeEntry = addLineText(remainder, currentSlNo, currentDate, currentStartTime,
                            currentEndTime, null, seenCodes, allCodes, entries, warnings);
                    continue;
                } catch (Exception ex) {
                    skippedLines++;
                    continue;
                }
            }

            if (beforeFirstSlot) {
                // Top header lines before any slot
                repeatedHeaderLines.add(line.toUpperCase(Locale.ROOT));
                continue;
            }

            if (currentDate == null || currentStartTime == null) {
                skippedLines++;
                continue;
            }

            // A weekday may open a line that carries a course, the wrapped tail of a name, or nothing.
            String body = line;
            Matcher weekdayMatcher = DatesheetTokenUtils.WEEKDAY_START_PATTERN.matcher(line);
            if (weekdayMatcher.find()) {
                body = line.substring(weekdayMatcher.end()).trim();
                if (body.isEmpty()) {
                    // Bare weekday alone on this line
                    continue;
                }
            }

            // Codes on this line (also when the weekday opens it, and also when they arrive as a
            // continuation of the slot opened above) belong to the slot that is open.
            activeEntry = addLineText(body, currentSlNo, currentDate, currentStartTime, currentEndTime,
                    activeEntry, seenCodes, allCodes, entries, warnings);
            if (activeEntry == null) {
                skippedLines++;
            }
        }

        if (examType == null) {
            examType = DatesheetTokenUtils.detectExamType(sheetCode, text);
        }

        // Safety net: a code the sheet printed must never disappear. Anything that could not be put
        // on a slot is offered with the sheet's slots as candidates instead of being dropped.
        for (String code : allCodes) {
            if (isPlaced(code, entries, ambiguous)) {
                continue;
            }
            ambiguous.put(code, candidateSlots(code, knownSlots));
            warnings.add("Course " + code + " could not be placed on a slot; pick its date in "
                    + "'Needs Your Decision'.");
        }

        LocalDate firstDate = null;
        LocalDate lastDate = null;
        for (DatesheetEntry e : entries) {
            if (e.getDate() != null) {
                if (firstDate == null || e.getDate().isBefore(firstDate)) firstDate = e.getDate();
                if (lastDate == null || e.getDate().isAfter(lastDate)) lastDate = e.getDate();
            }
        }

        return new ParsedDatesheet(sheetCode, examType, entries, skippedLines, warnings,
                name(), ambiguous, firstDate, lastDate);
    }

    private boolean isPlaced(String code, List<DatesheetEntry> entries,
                             Map<String, List<DatesheetEntry>> ambiguous) {
        if (ambiguous.containsKey(code)) {
            return true;
        }
        for (DatesheetEntry entry : entries) {
            if (entry.getCourseCode() != null && entry.getCourseCode().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    private List<DatesheetEntry> candidateSlots(String code, Set<String> knownSlots) {
        List<DatesheetEntry> candidates = new ArrayList<>();
        int index = 1;
        for (String slot : knownSlots) {
            String[] parts = slot.split("\\|");
            LocalDate date = parts.length > 0 ? LocalDate.parse(parts[0]) : null;
            LocalTime time = parts.length > 1 ? LocalTime.parse(parts[1]) : null;
            candidates.add(new DatesheetEntry(index++, date, time, code, ""));
        }
        if (candidates.isEmpty()) {
            // Keep the invariant "no empty candidate list" even for text with no date at all.
            candidates.add(new DatesheetEntry(0, null, null, code, ""));
        }
        return candidates;
    }

    /** One course code found on a line, with its optional candidate count and name. */
    private static final class CourseToken {
        final String code;
        final Integer count;
        final String name;

        CourseToken(String code, Integer count, String name) {
            this.code = code;
            this.count = count;
            this.name = name;
        }
    }

    /** The result of scanning one line: text before the first code, and the codes themselves. */
    private static final class LineScan {
        final String leading;
        final List<CourseToken> tokens;

        LineScan(String leading, List<CourseToken> tokens) {
            this.leading = leading != null ? leading : "";
            this.tokens = tokens;
        }
    }

    /**
     * Finds every course code in a line, whether it stands alone, carries a bracketed count, opens
     * the line or is glued to the end of the previous name. The text between two codes is the name
     * of the first one, which is what makes wrapped and glued names come out right.
     */
    private LineScan scanCourses(String text) {
        List<CourseToken> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new LineScan("", tokens);
        }

        Matcher matcher = COURSE_TOKEN_PATTERN.matcher(text);
        List<Integer> matchStart = new ArrayList<>();
        List<Integer> matchEnd = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        while (matcher.find()) {
            matchStart.add(matcher.start());
            matchEnd.add(matcher.end());
            codes.add(matcher.group(1));
            counts.add(matcher.group(2) != null ? Integer.valueOf(matcher.group(2)) : null);
        }

        if (codes.isEmpty()) {
            return new LineScan(text.trim(), tokens);
        }

        String leading = text.substring(0, matchStart.get(0)).trim();
        for (int i = 0; i < codes.size(); i++) {
            int nameStart = matchEnd.get(i);
            int nameEnd = i + 1 < codes.size() ? matchStart.get(i + 1) : text.length();
            String name = cleanName(text.substring(nameStart, nameEnd));
            tokens.add(new CourseToken(codes.get(i), counts.get(i), name));
        }
        return new LineScan(leading, tokens);
    }

    private String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        return TRAILING_COUNT_PATTERN.matcher(raw.trim()).replaceAll("")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * Reads one line of a slot. Glued name+code text ("...SENSORUMA401") is split first so the codes
     * keep their real prefix, then each piece is scanned: text without a code continues the previous
     * course name, text with codes adds them to the open slot and returns the last entry added.
     */
    private DatesheetEntry addLineText(String body, int slNo, LocalDate date, LocalTime start,
                                       LocalTime end, DatesheetEntry previous, Set<String> seenCodes,
                                       Set<String> allCodes, List<DatesheetEntry> entries,
                                       List<String> warnings) {
        if (body == null || body.isBlank()) {
            return previous;
        }
        for (String segment : DatesheetTokenUtils.splitGluedCourses(body).split("\n")) {
            String part = segment.trim();
            if (part.isEmpty()) {
                continue;
            }
            LineScan scan = scanCourses(part);
            if (scan.tokens.isEmpty()) {
                if (previous != null && !scan.leading.isBlank()) {
                    previous = appendNameToEntry(previous, scan.leading, entries);
                }
                continue;
            }
            if (!scan.leading.isEmpty() && previous != null && scan.leading.matches(".*[A-Za-z].*")) {
                previous = appendNameToEntry(previous, scan.leading, entries);
            }
            previous = addCourses(scan.tokens, slNo, date, start, end, seenCodes, allCodes,
                    entries, warnings, previous);
        }
        return previous;
    }

    private DatesheetEntry addCourses(List<CourseToken> tokens, int slNo, LocalDate date,
                                      LocalTime start, LocalTime end, Set<String> seenCodes,
                                      Set<String> allCodes, List<DatesheetEntry> entries,
                                      List<String> warnings, DatesheetEntry activeEntry) {
        DatesheetEntry lastAdded = activeEntry;
        for (CourseToken token : tokens) {
            String codeUpper = token.code.toUpperCase(Locale.ROOT);
            allCodes.add(codeUpper);
            if (!seenCodes.add(codeUpper)) {
                warnings.add("Duplicate course code ignored: " + token.code);
                lastAdded = null;
                continue;
            }
            DatesheetEntry entry = new DatesheetEntry(slNo, date, start, end, token.code, token.name,
                    token.count != null ? token.count : -1);
            entries.add(entry);
            lastAdded = entry;
        }
        return lastAdded;
    }

    private DatesheetEntry appendNameToEntry(DatesheetEntry entry, String fragment, List<DatesheetEntry> entries) {
        int lastIdx = entries.size() - 1;
        if (lastIdx >= 0 && entries.get(lastIdx) == entry) {
            String updatedName = (entry.getCourseName() == null || entry.getCourseName().isBlank())
                    ? fragment
                    : entry.getCourseName() + " " + fragment;
            DatesheetEntry updated = new DatesheetEntry(
                    entry.getSlNo(), entry.getDate(), entry.getStart(), entry.getEnd(),
                    entry.getCourseCode(), updatedName, entry.getCandidateCount()
            );
            entries.set(lastIdx, updated);
            return updated;
        }
        return entry;
    }

    private boolean isNoise(String line, Set<String> repeatedHeaderLines) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (repeatedHeaderLines.contains(upper)) {
            return true;
        }
        for (String custom : customNoiseList) {
            if (upper.contains(custom.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        if (upper.matches("^(CANDIDATES|TOTAL|TOTAL CANDIDATES|DATE|TIME|DAY|COUNT|PAPER CODE)"
                + "(\\s*/\\s*[A-Z ]+)?\\W*$")) {
            return true;
        }
        return upper.contains("THAPAR INSTITUTE OF ENGINEERING")
                || upper.contains("DEEMED TO BE UNIVERSITY")
                || upper.contains("INDIA - 147004")
                || upper.contains("PATIALA")
                || upper.contains("DATE SHEET FOR")
                || upper.startsWith("SEMESTER ")
                || upper.contains("SL NO.")
                || upper.contains("SL. NO.")
                || upper.contains("COURSE NO")
                || upper.contains("COURSE NAME")
                || upper.contains("III, V, VII")
                || upper.matches("^(?:I|II|III|IV|V|VI|VII|VIII)(?:\\s*,\\s*(?:I|II|III|IV|V|VI|VII|VIII))*$")
                || upper.matches("^PAGE\\s*\\d+.*$");
    }
}
