package com.learntrack.tools;

import com.learntrack.model.DatesheetEntry;
import com.learntrack.model.ParsedDatesheet;
import com.learntrack.service.DatesheetParser;
import com.learntrack.service.datesheet.RowWiseFormat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Developer harness (not part of the test suite): takes a row-aligned datesheet, generates the
 * paste variants a PDF viewer can produce, and checks the parser against the row-aligned truth.
 *
 * <p>Run with
 * {@code java -cp target/classes:target/test-classes com.learntrack.tools.DatesheetVariantHarness}.
 * Nothing in production code refers to this class.</p>
 *
 * <p>Per sheet it reports the number of codes in the truth, of codes assigned, of codes reported as
 * ambiguous, and of codes assigned to the wrong slot (which must always be zero). Variants: column
 * blocks with the institute header glued to the previous line and each page's first table row
 * collapsed into one "date time code" line (the Chrome copy artefact), plain block-per-column text
 * (pdftotext without -layout), the same with two times swapped on the largest page, and every
 * single-slot page on its own (which reproduces the page-1/page-12 loss).</p>
 */
public final class DatesheetVariantHarness {

    private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_OUT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** One slot of the truth: a date/time together with the codes that belong to it. */
    private static final class TruthSlot {
        final LocalDate date;
        final LocalTime time;
        final List<String> codes = new ArrayList<>();

        TruthSlot(LocalDate date, LocalTime time) {
            this.date = date;
            this.time = time;
        }

        boolean is(LocalDate otherDate, LocalTime otherTime) {
            return Objects.equals(date, otherDate) && Objects.equals(time, otherTime);
        }
    }

    /** The part of a slot that appears on one page. */
    private static final class PageSlot {
        final LocalDate date;
        final LocalTime time;
        final List<String> codes = new ArrayList<>();

        PageSlot(LocalDate date, LocalTime time) {
            this.date = date;
            this.time = time;
        }
    }

    private static final class TruthPage {
        final List<PageSlot> slots = new ArrayList<>();
    }

    /** Everything the harness knows about one sheet. */
    private static final class TruthSheet {
        String name;
        String sheetCode;
        String layout;
        final List<TruthSlot> slots = new ArrayList<>();
        final List<TruthPage> pages = new ArrayList<>();
        final Map<String, TruthSlot> codeToSlot = new LinkedHashMap<>();
        final Set<String> forcedAmbiguous = new LinkedHashSet<>();
    }

    /** Result of checking one paste variant. */
    private static final class Check {
        String variant;
        String format = "";
        int truthSlots;
        int codes;
        int assigned;
        int ambiguous;
        int wrong;
        int missing;
        int lacksTrueCandidate;
        int ambiguousWithoutNeed;
        final List<String> details = new ArrayList<>();

        boolean ok() {
            return wrong == 0 && missing == 0 && lacksTrueCandidate == 0 && ambiguousWithoutNeed == 0;
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> sources = new ArrayList<>(List.of(
                "/est_2024_layout.txt",
                "/datesheet_full_MST-2026-ODD.txt"));

        List<TruthSheet> sheets = new ArrayList<>();
        for (String source : sources) {
            String text = readResource(source);
            if (text != null) {
                sheets.add(buildTruth(source, text));
            }
        }
        Path samples = Paths.get("docs/samples");
        if (Files.isDirectory(samples)) {
            try (var stream = Files.list(samples)) {
                for (Path path : stream.sorted().toList()) {
                    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!fileName.endsWith(".txt")) continue;
                    if (!fileName.contains("datesheet") && !fileName.contains("layout")) continue;
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    if (!text.contains("Date Sheet Code")) continue;
                    sheets.add(buildTruth("docs/samples/" + path.getFileName(), text));
                }
            }
        }

        System.out.printf("%-30s %-46s %6s %6s %8s %9s %6s %-14s%n",
                "sheet", "variant", "codes", "slots", "assigned", "ambiguous", "wrong", "format");
        boolean anyFailure = false;
        for (TruthSheet sheet : sheets) {
            for (Check check : runAllVariants(sheet)) {
                System.out.printf("%-30s %-46s %6d %6d %8d %9d %6d %-14s%s%n",
                        shorten(sheet.name, 30), shorten(check.variant, 46),
                        check.codes, check.truthSlots, check.assigned, check.ambiguous, check.wrong,
                        check.format, check.ok() ? "" : "   <-- FAIL");
                for (String detail : check.details) {
                    System.out.println("      " + detail);
                }
                anyFailure |= !check.ok();
            }
        }

        if (args.length > 0) {
            printSpotChecks(sheets, args);
        }

        if (anyFailure) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS (0 wrong assignments, every forced ambiguity reported)");
    }

    /**
     * Prints the slot the parser chose for the codes named on the command line, next to the truth.
     * Nothing here knows any particular sheet: the codes are simply looked up in each sheet's truth.
     */
    private static void printSpotChecks(List<TruthSheet> sheets, String[] codes) {
        System.out.println();
        System.out.printf("%-28s %-49s %-10s %-8s %-10s %-8s %s%n",
                "sheet", "code", "parsed date", "time", "truth date", "time", "agrees");
        for (TruthSheet sheet : sheets) {
            ParsedDatesheet parsed = DatesheetParser.parse(buildCountLayoutPaste(sheet, false));
            Map<String, DatesheetEntry> byCode = new LinkedHashMap<>();
            for (DatesheetEntry entry : parsed.getEntries()) {
                byCode.put(entry.getCourseCode().toUpperCase(Locale.ROOT), entry);
            }
            for (String wanted : codes) {
                String code = wanted.toUpperCase(Locale.ROOT);
                TruthSlot truth = sheet.codeToSlot.get(code);
                if (truth == null) continue;
                DatesheetEntry entry = byCode.get(code);
                System.out.printf("%-28s %-49s %-10s %-8s %-10s %-8s %s%n",
                        shorten(sheet.name, 28), code,
                        entry != null && entry.getDate() != null ? entry.getDate() : "-",
                        entry != null && entry.getStart() != null ? entry.getStart() : "-",
                        truth.date, truth.time,
                        entry != null && truth.date.equals(entry.getDate())
                                && truth.time.equals(entry.getStart()) ? "yes" : "NO");
            }
        }
    }

    // ------------------------------------------------------------------ truth

    private static TruthSheet buildTruth(String name, String layout) {
        TruthSheet truth = new TruthSheet();
        truth.name = name;
        truth.sheetCode = findSheetCode(layout);
        truth.layout = layout;

        String[] lines = layout.split("\n", -1);
        List<Integer> boundaries = pageBoundaryLines(lines);

        ParsedDatesheet full = new RowWiseFormat().parse(layout);
        Map<String, Integer> pageOfCode = new LinkedHashMap<>();
        int cursorLine = 0;
        TruthSlot current = null;

        for (DatesheetEntry entry : full.getEntries()) {
            String code = entry.getCourseCode().toUpperCase(Locale.ROOT);
            int line = findCodeLine(lines, cursorLine, code);
            cursorLine = Math.max(cursorLine, line);

            if (current == null || !current.is(entry.getDate(), entry.getStart())) {
                current = new TruthSlot(entry.getDate(), entry.getStart());
                truth.slots.add(current);
            }
            if (current.codes.contains(code)) continue;
            current.codes.add(code);
            truth.codeToSlot.put(code, current);
            pageOfCode.put(code, pageOfLine(line, boundaries));
        }

        // Pages: the codes of each slot are attributed to the page they were printed on, so a slot
        // that continues across a page break shows up on both pages exactly as the PDF repeats it.
        TreeMap<Integer, TruthPage> pageMap = new TreeMap<>();
        for (TruthSlot slot : truth.slots) {
            Map<Integer, PageSlot> views = new LinkedHashMap<>();
            for (String code : slot.codes) {
                Integer page = pageOfCode.get(code);
                if (page == null) continue;
                PageSlot view = views.computeIfAbsent(page, p -> new PageSlot(slot.date, slot.time));
                view.codes.add(code);
            }
            for (Map.Entry<Integer, PageSlot> view : views.entrySet()) {
                pageMap.computeIfAbsent(view.getKey(), p -> new TruthPage()).slots.add(view.getValue());
            }
        }
        truth.pages.addAll(pageMap.values());

        // Forced ambiguity, derived from the truth (not from the implementation):
        //  * a run of codes whose letters continue across a slot boundary cannot be split;
        //  * a page whose first slot repeats the previous page's last slot can only be trusted when
        //    its runs match its slots one-to-one.
        String previousLast = null;
        for (TruthPage page : truth.pages) {
            List<String> codes = new ArrayList<>();
            for (PageSlot slot : page.slots) codes.addAll(slot.codes);
            String firstKey = page.slots.get(0).date + "|" + page.slots.get(0).time;
            int runsOnPage = countRuns(codes);
            if ((firstKey.equals(previousLast) && runsOnPage != page.slots.size())
                    || runsOnPage > page.slots.size()) {
                // Either the page repeats a slot header, or its course order has more restarts than
                // slots; in both cases the run structure alone cannot place the codes.
                truth.forcedAmbiguous.addAll(codes);
            } else {
                List<List<String>> runs = splitRuns(codes);
                for (List<String> run : runs) {
                    Set<String> slotsOfRun = new LinkedHashSet<>();
                    for (String code : run) {
                        TruthSlot slot = truth.codeToSlot.get(code);
                        if (slot != null) slotsOfRun.add(slot.date + "|" + slot.time);
                    }
                    if (slotsOfRun.size() > 1) truth.forcedAmbiguous.addAll(run);
                }
            }
            PageSlot last = page.slots.get(page.slots.size() - 1);
            previousLast = last.date + "|" + last.time;
        }
        return truth;
    }

    private static List<Integer> pageBoundaryLines(String[] lines) {
        List<Integer> headers = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toUpperCase(Locale.ROOT).contains("THAPAR INSTITUTE")) headers.add(i);
        }
        if (headers.size() >= 2) return headers;

        List<Integer> formFeeds = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].indexOf('\f') >= 0) formFeeds.add(i);
        }
        if (!formFeeds.isEmpty()) return formFeeds;

        List<Integer> markers = new ArrayList<>();
        Pattern marker = Pattern.compile("(?i)\\bpage\\s+\\d+\\s+of\\s+\\d+\\b");
        for (int i = 0; i < lines.length; i++) {
            if (marker.matcher(lines[i]).find()) markers.add(i);
        }
        return markers;
    }

    private static int pageOfLine(int line, List<Integer> boundaries) {
        int page = 0;
        for (Integer boundary : boundaries) {
            if (boundary <= line) page++;
            else break;
        }
        return page;
    }

    private static int findCodeLine(String[] lines, int from, String code) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(code) + "\\b");
        for (int i = Math.max(0, from); i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) return i;
        }
        return Math.max(0, from);
    }

    private static String findSheetCode(String layout) {
        java.util.regex.Matcher matcher = Pattern
                .compile("Date\\s*Sheet\\s*Code\\s*:\\s*([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE)
                .matcher(layout);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int countRuns(List<String> codes) {
        return splitRuns(codes).size();
    }

    private static List<List<String>> splitRuns(List<String> codes) {
        List<List<String>> runs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            if (i > 0 && code.compareTo(codes.get(i - 1)) < 0) {
                runs.add(current);
                current = new ArrayList<>();
            }
            current.add(code);
        }
        if (!current.isEmpty()) runs.add(current);
        return runs;
    }

    // --------------------------------------------------------------- variants

    private static List<Check> runAllVariants(TruthSheet sheet) {
        List<Check> checks = new ArrayList<>();
        Set<String> allCodes = new LinkedHashSet<>(sheet.codeToSlot.keySet());

        String first = buildPaste(sheet, true, true, false);
        String dumpMatch = System.getProperty("harness.dump", "");
        if (!dumpMatch.isEmpty() && sheet.name.contains(dumpMatch)) {
            try {
                Path out = Paths.get("target", "harness-dump.txt");
                Files.writeString(out, first, StandardCharsets.UTF_8);
                System.out.println("dumped " + sheet.name + " to " + out.toAbsolutePath());
            } catch (Exception ex) {
                System.out.println("dump failed: " + ex);
            }
        }

        checks.add(check(sheet, "column blocks, glued header, first row one line",
                first, allCodes, Collections.emptySet(), true));
        String countLayout = buildCountLayoutPaste(sheet, false);
        if (!dumpMatch.isEmpty() && sheet.name.contains(dumpMatch)) {
            try {
                Path out = Paths.get("target", "harness-count-dump.txt");
                Files.writeString(out, countLayout, StandardCharsets.UTF_8);
                System.out.println("dumped count layout of " + sheet.name + " to " + out.toAbsolutePath());
            } catch (Exception ex) {
                System.out.println("dump failed: " + ex);
            }
        }
        checks.add(check(sheet, "row-aligned paste (the sheet as printed)",
                sheet.layout, allCodes, Collections.emptySet(), false));
        checks.add(check(sheet, "count layout, total + CODE (n), no repeated row",
                countLayout, allCodes, Collections.emptySet(), false));
        checks.add(check(sheet, "count layout, glued page marker + header repeated",
                buildCountLayoutPaste(sheet, true), allCodes, Collections.emptySet(), false));
        checks.add(check(sheet, "column blocks, block-per-column",
                buildPaste(sheet, false, false, false), allCodes, Collections.emptySet(), true));

        TruthPage largest = sheet.pages.stream()
                .max(Comparator.comparingInt(p -> totalCodes(p))).orElse(null);
        Set<String> scrambledCodes = new LinkedHashSet<>();
        if (largest != null) {
            for (PageSlot slot : largest.slots) scrambledCodes.addAll(slot.codes);
        }
        checks.add(check(sheet, "column blocks, scrambled times on largest page",
                buildPaste(sheet, true, true, true), allCodes, scrambledCodes, true));

        for (TruthSlot slot : sheet.slots) {
            // A paste that contains only this slot has no page alignment to guess, so the page-level
            // forced ambiguities do not apply here.
            checks.add(check(sheet, "single-slot page " + slot.date.format(DATE_DISPLAY) + " "
                            + slot.time.format(TIME_OUT),
                    buildSingleSlotPaste(sheet.sheetCode, slot),
                    new LinkedHashSet<>(slot.codes), Collections.emptySet(), false));
        }
        return checks;
    }

    private static int totalCodes(TruthPage page) {
        int total = 0;
        for (PageSlot slot : page.slots) total += slot.codes.size();
        return total;
    }

    private static String buildPaste(TruthSheet sheet, boolean glueHeader, boolean combineFirstRow,
                                     boolean scrambleTimesOnLargestPage) {
        TruthPage largest = sheet.pages.stream()
                .max(Comparator.comparingInt(DatesheetVariantHarness::totalCodes)).orElse(null);

        StringBuilder out = new StringBuilder();
        for (TruthPage page : sheet.pages) {
            List<PageSlot> slots = page.slots;
            boolean scramble = scrambleTimesOnLargestPage && page == largest && slots.size() >= 2;
            appendPage(out, sheet.sheetCode, slots, glueHeader, combineFirstRow, scramble);
        }
        return out.toString();
    }

    private static final DateTimeFormatter TIME_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    /**
     * A "count" datesheet: one line per slot, "serial date time total CODE (n) CODE (n)", with the
     * wrapped lines carrying only more "CODE (n)" tokens, a table header whose last word sits alone
     * on its line, and a slot that continues onto the next page without repeating its date row.
     */
    private static String buildCountLayoutPaste(TruthSheet sheet, boolean gluePageMarker) {
        StringBuilder out = new StringBuilder();
        int serial = 0;
        int pageNumber = 0;
        LocalDate previousDate = null;
        LocalTime previousTime = null;

        for (TruthPage page : sheet.pages) {
            pageNumber++;
            if (pageNumber == 1) {
                out.append("THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA\n");
            } else if (gluePageMarker) {
                out.append("Page ").append(pageNumber)
                        .append("THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA\n");
            } else {
                out.append("Page ").append(pageNumber).append("\n\n");
                out.append("THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA\n");
            }
            out.append("(Deemed to be University) Patiala 147004 Punjab\n");
            out.append("India - 147004\n");
            out.append("Date Sheet For Program B.E. / B.Tech. / M.E. / M.Tech. / MCA / M.Sc. and Semester I,\n");
            out.append("III, V, VII\n");
            out.append("Date Sheet Code: ").append(sheet.sheetCode).append('\n');
            out.append("Sl No. Date Time Total Paper Code / Count\n");
            out.append("Candidates\n\n");

            for (PageSlot slot : page.slots) {
                boolean continues = previousDate != null && previousDate.equals(slot.date)
                        && previousTime != null && previousTime.equals(slot.time);
                if (!continues) {
                    serial++;
                    int total = 0;
                    for (int i = 0; i < slot.codes.size(); i++) {
                        total += countFor(slot, i);
                    }
                    out.append(serial).append(' ').append(slot.date)
                            .append(' ').append(slot.time.format(TIME_12))
                            .append(' ').append(total);
                    int onSlotLine = Math.min(2, slot.codes.size());
                    for (int i = 0; i < onSlotLine; i++) {
                        out.append(' ').append(slot.codes.get(i))
                                .append(" (").append(countFor(slot, i)).append(')');
                    }
                    out.append('\n');
                    appendCountCodes(out, slot, onSlotLine);
                } else {
                    // The slot began on the previous page: only its remaining codes are printed.
                    appendCountCodes(out, slot, 0);
                }
                previousDate = slot.date;
                previousTime = slot.time;
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void appendCountCodes(StringBuilder out, PageSlot slot, int from) {
        int perLine = 3;
        for (int i = from; i < slot.codes.size(); i++) {
            out.append(slot.codes.get(i)).append(" (").append(countFor(slot, i)).append(')');
            boolean last = i == slot.codes.size() - 1;
            out.append(last || (i - from + 1) % perLine == 0 ? '\n' : ' ');
        }
    }

    /** A stable, arbitrary candidate count per course: the parser must treat it as metadata only. */
    private static int countFor(PageSlot slot, int index) {
        return 15 + ((slot.codes.get(index).hashCode() & 0x1f) % 40);
    }

    private static String buildSingleSlotPaste(String sheetCode, TruthSlot slot) {
        PageSlot view = new PageSlot(slot.date, slot.time);
        view.codes.addAll(slot.codes);
        StringBuilder out = new StringBuilder();
        appendPage(out, sheetCode, List.of(view), false, true, false);
        return out.toString();
    }

    private static void appendPage(StringBuilder out, String sheetCode, List<PageSlot> slots,
                                   boolean glueHeader, boolean combineFirstRow, boolean scrambleTimes) {
        List<LocalTime> times = new ArrayList<>();
        for (PageSlot slot : slots) times.add(slot.time);
        if (scrambleTimes) {
            LocalTime first = times.get(0);
            times.set(0, times.get(1));
            times.set(1, first);
        }

        String header = "THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY, PATIALA";
        if (glueHeader && out.length() > 0) {
            out.append("Semester 1,6,2,5,4,8,3,7,").append(header).append('\n');
        } else {
            out.append(header).append('\n');
        }
        out.append("(Deemed to be University) Patiala 147004 Punjab\n");
        out.append("Date Sheet Code: ").append(sheetCode).append('\n');
        out.append("Sl No. Date / Day       Time      Course No   Course Name\n\n");

        if (combineFirstRow) {
            PageSlot first = slots.get(0);
            out.append(first.date.format(DATE_OUT)).append(' ').append(times.get(0).format(TIME_OUT))
                    .append(' ').append(first.codes.get(0)).append('\n');
        }

        for (int i = 0; i < slots.size(); i++) {
            out.append(i + 1).append('\n');
        }
        out.append('\n');

        for (int i = 0; i < slots.size(); i++) {
            if (combineFirstRow && i == 0) continue;
            out.append(slots.get(i).date.format(DATE_OUT)).append('\n');
            out.append(weekday(slots.get(i).date)).append('\n');
        }
        out.append('\n');

        for (int i = 0; i < times.size(); i++) {
            if (combineFirstRow && i == 0) continue;
            out.append(times.get(i).format(TIME_OUT)).append('\n');
        }
        out.append('\n');

        for (int i = 0; i < slots.size(); i++) {
            List<String> codes = slots.get(i).codes;
            for (int c = 0; c < codes.size(); c++) {
                if (combineFirstRow && i == 0 && c == 0) continue;
                out.append(codes.get(c)).append('\n');
            }
        }
        out.append('\n');

        for (PageSlot slot : slots) {
            for (int c = 0; c < slot.codes.size(); c++) {
                out.append("SUBJECT NAME AND LAB\n");
            }
        }
        out.append("\n\f\n");
    }

    private static String weekday(LocalDate date) {
        String name = date.getDayOfWeek().toString().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------- validation

    private static Check check(TruthSheet sheet, String variant, String paste,
                               Set<String> expectedCodes, Set<String> allowedExtraAmbiguous,
                               boolean enforceForcedAmbiguity) {
        Check check = new Check();
        check.variant = variant;
        check.truthSlots = sheet.slots.size();
        check.codes = expectedCodes.size();

        ParsedDatesheet parsed = DatesheetParser.parse(paste);
        check.format = parsed.getFormatName();

        Set<String> assignedCodes = new HashSet<>();
        for (DatesheetEntry entry : parsed.getEntries()) {
            String code = entry.getCourseCode().toUpperCase(Locale.ROOT);
            assignedCodes.add(code);
            if (expectedCodes.contains(code)) check.assigned++;

            TruthSlot truth = sheet.codeToSlot.get(code);
            if (truth == null) {
                check.wrong++;
                if (check.details.size() < 6) check.details.add("unknown code assigned: " + code);
            } else if (!truth.date.equals(entry.getDate()) || !truth.time.equals(entry.getStart())) {
                check.wrong++;
                if (check.details.size() < 6) {
                    check.details.add("wrong slot for " + code + ": got " + entry.getDate() + " "
                            + entry.getStart() + ", truth " + truth.date + " " + truth.time);
                }
            }
        }

        Set<String> ambiguousCodes = new HashSet<>();
        for (Map.Entry<String, List<DatesheetEntry>> ambiguous : parsed.getAmbiguous().entrySet()) {
            String code = ambiguous.getKey().toUpperCase(Locale.ROOT);
            ambiguousCodes.add(code);
            if (expectedCodes.contains(code)) check.ambiguous++;

            TruthSlot truth = sheet.codeToSlot.get(code);
            if (truth == null) {
                check.lacksTrueCandidate++;
                check.details.add("ambiguous code unknown to the truth: " + code);
                continue;
            }
            boolean containsTruth = false;
            for (DatesheetEntry candidate : ambiguous.getValue()) {
                if (truth.date.equals(candidate.getDate()) && truth.time.equals(candidate.getStart())) {
                    containsTruth = true;
                    break;
                }
            }
            if (!containsTruth) {
                check.lacksTrueCandidate++;
                if (check.details.size() < 8) {
                    check.details.add("candidate list lacks the true slot for " + code);
                }
            }
            if (expectedCodes.contains(code)
                    && !(enforceForcedAmbiguity && sheet.forcedAmbiguous.contains(code))
                    && !allowedExtraAmbiguous.contains(code)) {
                check.ambiguousWithoutNeed++;
                if (check.details.size() < 8) {
                    check.details.add("ambiguous although the truth pins it: " + code);
                }
            }
        }

        Set<String> covered = new LinkedHashSet<>(assignedCodes);
        covered.addAll(ambiguousCodes);
        for (String code : expectedCodes) {
            if (!covered.contains(code)) {
                check.missing++;
                if (check.details.size() < 8) check.details.add("code not found at all: " + code);
            }
        }
        for (String code : enforceForcedAmbiguity ? sheet.forcedAmbiguous : Collections.<String>emptySet()) {
            if (!expectedCodes.contains(code)) continue;
            if (!ambiguousCodes.contains(code)) {
                check.wrong++;
                if (check.details.size() < 8) {
                    check.details.add("forced-ambiguous code was silently assigned: " + code);
                }
            }
        }
        return check;
    }

    private static String readResource(String name) throws Exception {
        try (InputStream is = DatesheetVariantHarness.class.getResourceAsStream(name)) {
            return is == null ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : ".." + value.substring(value.length() - max + 2);
    }
}
