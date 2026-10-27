package com.learntrack.service.datesheet;

import com.learntrack.model.DatesheetEntry;
import com.learntrack.model.ParsedDatesheet;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;

/**
 * Column-block datesheet format parser.
 *
 * <p>Handles text copied from a PDF viewer, where the table columns arrive as separate blocks
 * (all serial numbers, then all dates, then all times, then all course codes, then all names) and
 * pages are separated by repeated headers that may even be glued to the previous line.</p>
 *
 * <p>Reconstruction works on tokens, not lines: every page contributes an ordered list of dates,
 * times and course codes. The codes are cut into maximal ascending runs, and those runs are paired
 * with the page's time slots. When the alphabet simply continues across a slot boundary the runs are
 * fewer than the slots; the runs that can be anchored from the start of the page are assigned, and
 * only the run that must span the remaining slots is reported as ambiguous, with those slots offered
 * as candidates.</p>
 */
public class ColumnBlockFormat implements DatesheetFormat {

    /** A date/time pair found on a page. */
    private static final class PageSlot {
        final LocalDate date;
        final LocalTime time;

        PageSlot(LocalDate date, LocalTime time) {
            this.date = date;
            this.time = time;
        }

        boolean sameAs(PageSlot other) {
            if (other == null) return false;
            return Objects.equals(date, other.date) && Objects.equals(time, other.time);
        }

        String display() {
            String d = date != null ? date.toString() : "unknown date";
            String t = time != null ? time.toString() : "unknown time";
            return d + " " + t;
        }
    }

    @Override
    public String name() {
        return "Column-block";
    }

    @Override
    public double score(String text) {
        if (text == null || text.isBlank()) return 0.0;
        String[] lines = text.split("\\r?\\n");
        int dateAndTimeTogether = 0;
        int dateTokens = 0;
        int codeTokens = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (RowWiseFormat.SLOT_START_PATTERN.matcher(line).matches()) {
                dateAndTimeTogether++;
            }
            for (DatesheetTokenizer.Token token : DatesheetTokenizer.tokenizeLine(line, 0)) {
                if (token.getType() == DatesheetTokenizer.Type.DATE) dateTokens++;
                if (token.getType() == DatesheetTokenizer.Type.COURSE_CODE) codeTokens++;
            }
        }

        // A line that carries a date and a time together is a table row, not a column block. The
        // more such rows the text has, the less this format applies: dates and codes existing side
        // by side must never be enough to score this format highly.
        if (dateAndTimeTogether >= 2) {
            return 0.0;
        }
        if (dateAndTimeTogether == 1) {
            return 0.2;
        }

        if (dateTokens >= 1 && codeTokens >= 2) {
            return 0.85;
        }

        return 0.0;
    }

    @Override
    public ParsedDatesheet parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedDatesheet("", "MST", Collections.emptyList(), 0, Collections.emptyList(),
                    name(), Collections.emptyMap(), null, null);
        }

        String normalised = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ');

        String sheetCode = "";
        Matcher sheetMatcher = DatesheetTokenUtils.SHEET_CODE_PATTERN.matcher(normalised);
        if (sheetMatcher.find()) {
            sheetCode = sheetMatcher.group(1).trim();
        }
        String examType = DatesheetTokenUtils.detectExamType(sheetCode, text);

        List<DatesheetEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, List<DatesheetEntry>> ambiguousMap = new LinkedHashMap<>();
        int skippedLines = 0;

        if (looksRowWise(normalised)) {
            warnings.add("This text keeps dates and course codes on the same lines; it is read as a row-wise datesheet.");
            return new ParsedDatesheet(sheetCode, examType, entries, skippedLines, warnings,
                    name(), ambiguousMap, null, null);
        }

        List<String> pages = DatesheetPageSplitter.split(normalised);
        if (pages.isEmpty()) {
            pages = List.of(normalised);
        }

        Set<String> seenCodes = new HashSet<>();
        PageSlot previousLastSlot = null;

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            PageExtract extract = extractPage(pages.get(pageIndex));

            if (extract.codes.isEmpty()) {
                previousLastSlot = extract.slots.isEmpty() ? previousLastSlot
                        : extract.slots.get(extract.slots.size() - 1);
                continue;
            }

            List<PageSlot> slots = extract.slots;
            List<List<String>> groups = splitIntoRuns(extract.codes);

            // A slot header repeated at the top of a page belongs to a slot that started on the
            // previous page. When the page's runs match its slots one-to-one the repetition is
            // harmless; otherwise the page can be read either way and is reported for a decision.
            boolean repeatedFirstSlot = !slots.isEmpty() && slots.get(0).sameAs(previousLastSlot);

            assignPage(pageIndex + 1, slots, extract.trusted, repeatedFirstSlot, groups, extract.codes,
                    seenCodes, entries, ambiguousMap, warnings);

            if (!slots.isEmpty()) {
                previousLastSlot = slots.get(slots.size() - 1);
            }
        }

        // Safety net: every code printed in the paste must appear exactly once, either on a slot or
        // in the decision map. A code the page reconstruction could not place is offered with the
        // sheet's known slots as candidates instead of disappearing.
        Set<String> knownSlots = new LinkedHashSet<>();
        for (DatesheetEntry e : entries) {
            if (e.getDate() != null) knownSlots.add(e.getDate() + "|" + e.getStart());
        }
        for (List<DatesheetEntry> candidates : ambiguousMap.values()) {
            for (DatesheetEntry candidate : candidates) {
                if (candidate.getDate() != null) {
                    knownSlots.add(candidate.getDate() + "|" + candidate.getStart());
                }
            }
        }
        for (String code : codeTokens(normalised)) {
            if (isPlaced(code, entries, ambiguousMap)) continue;
            List<DatesheetEntry> candidates = new ArrayList<>();
            int index = 1;
            for (String slot : knownSlots) {
                String[] parts = slot.split("\\|");
                candidates.add(new DatesheetEntry(index++,
                        LocalDate.parse(parts[0]),
                        parts.length > 1 && !"null".equals(parts[1]) ? LocalTime.parse(parts[1]) : null,
                        code, ""));
            }
            if (candidates.isEmpty()) {
                candidates.add(new DatesheetEntry(0, null, null, code, ""));
            }
            ambiguousMap.put(code, candidates);
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
        for (List<DatesheetEntry> candidates : ambiguousMap.values()) {
            for (DatesheetEntry candidate : candidates) {
                if (candidate.getDate() != null) {
                    if (firstDate == null || candidate.getDate().isBefore(firstDate)) firstDate = candidate.getDate();
                    if (lastDate == null || candidate.getDate().isAfter(lastDate)) lastDate = candidate.getDate();
                }
            }
        }

        return new ParsedDatesheet(sheetCode, examType, entries, skippedLines, warnings,
                name(), ambiguousMap, firstDate, lastDate);
    }

    /** Every distinct course code printed in the text. */
    private Set<String> codeTokens(String text) {
        Set<String> codes = new LinkedHashSet<>();
        for (String raw : text.split("\n")) {
            for (DatesheetTokenizer.Token token : DatesheetTokenizer.tokenizeLine(raw, 0)) {
                if (token.getType() == DatesheetTokenizer.Type.COURSE_CODE) {
                    codes.add(token.getText().toUpperCase(Locale.ROOT));
                }
            }
        }
        return codes;
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

    /**
     * True when the majority of course codes sit on lines that also carry a date and a time, which
     * means the paste is row-wise and this format must not touch it.
     */
    private boolean looksRowWise(String text) {
        int codeTokens = 0;
        int codesOnSlotLines = 0;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            List<DatesheetTokenizer.Token> tokens = DatesheetTokenizer.tokenizeLine(line, 0);
            int codesHere = 0;
            for (DatesheetTokenizer.Token token : tokens) {
                if (token.getType() == DatesheetTokenizer.Type.COURSE_CODE) codesHere++;
            }
            if (codesHere == 0) continue;
            codeTokens += codesHere;
            if (RowWiseFormat.SLOT_START_PATTERN.matcher(line).matches()) {
                codesOnSlotLines += codesHere;
            }
        }
        return codeTokens > 0 && codesOnSlotLines >= 2 && codesOnSlotLines * 2 >= codeTokens;
    }

    /** Ordered content of one page. */
    private static final class PageExtract {
        final List<PageSlot> slots = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        /** False when the page's dates and times cannot be paired reliably. */
        boolean trusted = true;
    }

    /** Collects dates, times and course codes of one page in document order. */
    private PageExtract extractPage(String pageText) {
        PageExtract extract = new PageExtract();
        List<LocalDate> dates = new ArrayList<>();
        List<LocalTime> times = new ArrayList<>();

        for (String raw : pageText.split("\n")) {
            for (DatesheetTokenizer.Token token : DatesheetTokenizer.tokenizeLine(raw, 0)) {
                if (token.getType() == DatesheetTokenizer.Type.DATE) {
                    dates.add(token.getDate());
                } else if (token.getType() == DatesheetTokenizer.Type.TIME) {
                    times.add(token.getStart());
                } else if (token.getType() == DatesheetTokenizer.Type.COURSE_CODE) {
                    extract.codes.add(token.getText());
                }
            }
        }

        extract.trusted = pairSlots(dates, times, extract.slots);
        return extract;
    }

    /**
     * Pairs the page's dates with its times and reports whether the pairing can be trusted.
     * A page with dates but no times keeps date-only slots. A page whose dates do not run forwards,
     * or whose times for one date are scrambled, is paired anyway (so the UI still has candidates)
     * but is marked untrusted, and the caller reports every code of that page as ambiguous.
     */
    private boolean pairSlots(List<LocalDate> dates, List<LocalTime> times, List<PageSlot> slots) {
        if (dates.isEmpty()) {
            return true;
        }
        if (times.isEmpty()) {
            for (LocalDate date : dates) {
                slots.add(new PageSlot(date, null));
            }
            return true;
        }
        if (times.size() != dates.size()) {
            addAllPlausibleSlots(dates, times, slots);
            return false;
        }
        for (int i = 0; i < dates.size(); i++) {
            slots.add(new PageSlot(dates.get(i), times.get(i)));
        }

        boolean ordered = true;
        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i).isBefore(dates.get(i - 1))) {
                ordered = false;
                break;
            }
            if (dates.get(i).equals(dates.get(i - 1)) && !times.get(i).isAfter(times.get(i - 1))) {
                ordered = false;
                break;
            }
        }
        if (ordered) {
            return true;
        }

        // Scrambled columns: the true slot is some page date combined with some page time, so offer
        // every distinct combination (in practice two or three times per page, so the list stays short).
        slots.clear();
        addAllPlausibleSlots(dates, times, slots);
        return false;
    }

    /** Every distinct page date combined with every distinct page time. */
    private void addAllPlausibleSlots(List<LocalDate> dates, List<LocalTime> times, List<PageSlot> slots) {
        LinkedHashSet<LocalDate> distinctDates = new LinkedHashSet<>(dates);
        distinctDates.remove(null);
        List<LocalTime> distinctTimes = new ArrayList<>(new LinkedHashSet<>(times));
        distinctTimes.remove(null);
        Collections.sort(distinctTimes);

        if (distinctDates.isEmpty() || distinctTimes.isEmpty()) {
            int max = Math.max(dates.size(), times.size());
            for (int i = 0; i < max; i++) {
                slots.add(new PageSlot(i < dates.size() ? dates.get(i) : null,
                        i < times.size() ? times.get(i) : null));
            }
            return;
        }

        for (LocalDate date : distinctDates) {
            for (LocalTime time : distinctTimes) {
                slots.add(new PageSlot(date, time));
            }
        }
    }

    /** Cuts the page's codes into maximal ascending runs. */
    private List<List<String>> splitIntoRuns(List<String> codes) {
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            if (i > 0 && code.compareTo(codes.get(i - 1)) < 0) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(code);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * Assigns the page's runs to its slots and records anything that cannot be placed with certainty.
     *
     * <p>Runs are anchored from the start of the page: run {@code i} belongs to slot {@code i} as
     * long as that leaves enough slots for the remaining runs. Whatever surplus remains is reported
     * as ambiguous with the plausible slots as candidates, never silently guessed.</p>
     */
    private void assignPage(int pageNumber, List<PageSlot> slots, boolean trusted, boolean repeatedFirstSlot,
                            List<List<String>> groups, List<String> pageCodes, Set<String> seenCodes,
                            List<DatesheetEntry> entries, Map<String, List<DatesheetEntry>> ambiguousMap,
                            List<String> warnings) {
        int slotCount = slots.size();
        int groupCount = groups.size();

        if (repeatedFirstSlot && slotCount != groupCount) {
            List<DatesheetEntry> candidateEntries = candidateEntries(slots, pageCodes.get(0));
            addAmbiguous(pageNumber, pageCodes, seenCodes, candidateEntries, ambiguousMap, warnings,
                    "the first slot of this page repeats the last slot of the previous page, so the "
                            + "alignment of its courses cannot be trusted (candidates: "
                            + describeSlots(slots) + ")");
            return;
        }

        if (!trusted && slotCount > 0) {
            List<DatesheetEntry> candidateEntries = candidateEntries(slots, pageCodes.get(0));
            addAmbiguous(pageNumber, pageCodes, seenCodes, candidateEntries, ambiguousMap, warnings,
                    "the dates and times on this page could not be paired reliably");
            return;
        }

        if (slotCount == 0) {
            List<DatesheetEntry> candidates = new ArrayList<>();
            candidates.add(new DatesheetEntry(0, null, null, "", ""));
            addAmbiguous(pageNumber, pageCodes, seenCodes, candidates, ambiguousMap, warnings,
                    "no date/time could be read on this page");
            return;
        }

        if (slotCount == 1 || groupCount == slotCount) {
            for (int i = 0; i < groups.size(); i++) {
                PageSlot slot = slots.get(Math.min(i, slotCount - 1));
                addAssigned(groups.get(i), slot, i + 1, seenCodes, entries, warnings);
            }
            return;
        }

        if (groupCount < slotCount) {
            int anchored = groupCount - 1;
            for (int i = 0; i < anchored; i++) {
                addAssigned(groups.get(i), slots.get(i), i + 1, seenCodes, entries, warnings);
            }
            List<PageSlot> candidates = new ArrayList<>(slots.subList(anchored, slotCount));
            List<String> runCodes = groups.get(groupCount - 1);
            List<DatesheetEntry> candidateEntries = candidateEntries(candidates, runCodes.get(0));
            addAmbiguous(pageNumber, runCodes, seenCodes, candidateEntries, ambiguousMap, warnings,
                    "the course order continues without a restart, so these courses fit "
                            + describeSlots(candidates));
            return;
        }

        // More runs than slots. An extra alphabetical restart can sit anywhere on the page, so the
        // page cannot be reconstructed from the run structure without guessing: report it whole.
        List<DatesheetEntry> candidateEntries = candidateEntries(slots, pageCodes.get(0));
        addAmbiguous(pageNumber, pageCodes, seenCodes, candidateEntries, ambiguousMap, warnings,
                "this page has " + groupCount + " alphabetical runs for " + slotCount
                        + " time slots, so its course order carries no reliable boundary");
    }

    private List<DatesheetEntry> candidateEntries(List<PageSlot> slots, String code) {
        List<DatesheetEntry> candidates = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            PageSlot slot = slots.get(i);
            candidates.add(new DatesheetEntry(i + 1, slot.date, slot.time, code, ""));
        }
        return candidates;
    }

    private String describeSlots(List<PageSlot> slots) {
        if (slots.size() == 1) {
            return "slot " + slots.get(0).display();
        }
        return "slots " + slots.get(0).display() + " to " + slots.get(slots.size() - 1).display();
    }

    private void addAssigned(List<String> codes, PageSlot slot, int slNo, Set<String> seenCodes,
                             List<DatesheetEntry> entries, List<String> warnings) {
        for (String code : codes) {
            String key = code.toUpperCase();
            if (!seenCodes.add(key)) {
                warnings.add("Duplicate course code ignored: " + code);
                continue;
            }
            entries.add(new DatesheetEntry(slNo, slot.date, slot.time, code, ""));
        }
    }

    private void addAmbiguous(int pageNumber, List<String> codes, Set<String> seenCodes,
                              List<DatesheetEntry> candidates,
                              Map<String, List<DatesheetEntry>> ambiguousMap, List<String> warnings,
                              String reason) {
        int added = 0;
        for (String code : codes) {
            String key = code.toUpperCase();
            if (!seenCodes.add(key)) {
                warnings.add("Duplicate course code ignored: " + code);
                continue;
            }
            List<DatesheetEntry> perCode = new ArrayList<>();
            for (DatesheetEntry candidate : candidates) {
                perCode.add(new DatesheetEntry(candidate.getSlNo(), candidate.getDate(),
                        candidate.getStart(), candidate.getEnd(), code, ""));
            }
            ambiguousMap.put(code, perCode);
            added++;
        }
        if (added > 0) {
            warnings.add("Page " + pageNumber + ": " + added + " course(s) marked ambiguous: " + reason + ".");
        }
    }
}
