package com.learntrack.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads and parses Excel (.xlsx) timetables using only JDK libraries (ZipFile + DOM).
 * <p>
 * Column layout is detected dynamically per sheet:
 * - Period-number column: the column A..H with the most integer cells (1..14) below the header.
 * - Hours column: the column A..H (excluding the period column) with the most time-parseable cells.
 */
public class TimetableXlsxReader {

    private static final List<String> DAYS = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    );

    // Columns to search for period/hours detection (A=1 .. H=8)
    private static final List<String> LAYOUT_CANDIDATE_COLS = List.of("A", "B", "C", "D", "E", "F", "G", "H");

    private static final Pattern BATCH_HEADER_PATTERN = Pattern.compile("^\\d[A-Z]\\d{2}$");

    /**
     * A cell is a timetable slot if it begins with 3-4 uppercase letters, 3 digits,
     * then optionally whitespace, then either [LTP] or '/' (start of an elective list).
     * Teacher initials like "SPP/RSJ" are excluded because they have no digits.
     */
    private static final Pattern SLOT_START_PATTERN =
            Pattern.compile("^[A-Z]{3,4}\\d{3}(\\s*[LTP]|/).*", Pattern.CASE_INSENSITIVE);

    /** Parses a single subject code token: base, optional suffix, remainder. */
    private static final Pattern CODE_PARSE_PATTERN =
            Pattern.compile("^([A-Z]{3,4}\\d{3})\\s*([LTP])?(.*)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern UPTO_PATTERN =
            Pattern.compile("(?i)\\(upto\\s+([0-9]{1,2}:[0-9]{2})\\s*([AP]M)?\\)");

    /** Last period (14) ends at 7:40 PM; slots must never extend past this. */
    static final LocalTime LAST_PERIOD_END = LocalTime.of(19, 40);

    /** Matches time text after normalization (digits, colon, optional AM/PM). */
    private static final Pattern TEXT_TIME_PATTERN =
            Pattern.compile("^(\\d{1,2}):(\\d{2})(?::)?((?:[AP]M)?)$");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ParsedWorkbook parseWorkbook(File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("File does not exist: " + file);
        }

        try (ZipFile zip = new ZipFile(file)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

            // 1. Read shared strings
            List<String> sharedStrings = loadSharedStrings(zip, dbf);

            // 2. Read sheet name -> r:id mapping
            Map<String, String> sheetNameToRId = new LinkedHashMap<>();
            ZipEntry wbEntry = zip.getEntry("xl/workbook.xml");
            if (wbEntry == null) throw new IllegalStateException("Corrupt .xlsx: missing xl/workbook.xml");
            Document wbDoc = dbf.newDocumentBuilder().parse(zip.getInputStream(wbEntry));
            NodeList sheetNodes = wbDoc.getElementsByTagName("sheet");
            for (int i = 0; i < sheetNodes.getLength(); i++) {
                Element s = (Element) sheetNodes.item(i);
                sheetNameToRId.put(s.getAttribute("name"), s.getAttribute("r:id"));
            }

            // 3. Read r:id -> file target mapping
            Map<String, String> rIdToTarget = new HashMap<>();
            ZipEntry relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels");
            if (relsEntry == null) throw new IllegalStateException("Corrupt .xlsx: missing xl/_rels/workbook.xml.rels");
            Document relsDoc = dbf.newDocumentBuilder().parse(zip.getInputStream(relsEntry));
            NodeList relNodes = relsDoc.getElementsByTagName("Relationship");
            for (int i = 0; i < relNodes.getLength(); i++) {
                Element r = (Element) relNodes.item(i);
                rIdToTarget.put(r.getAttribute("Id"), r.getAttribute("Target"));
            }

            List<String> sheetNames = new ArrayList<>();
            Map<String, List<String>> sheetBatches = new LinkedHashMap<>();
            Map<String, Map<String, List<TimetableSlot>>> sheetBatchSlots = new LinkedHashMap<>();
            Map<String, ParsedWorkbook.SheetDetectionInfo> sheetDetectionInfos = new LinkedHashMap<>();

            // 4. Parse each sheet
            for (Map.Entry<String, String> sheetEntry : sheetNameToRId.entrySet()) {
                String sheetName = sheetEntry.getKey();
                String target = rIdToTarget.get(sheetEntry.getValue());
                if (target == null) continue;
                if (!target.startsWith("xl/")) target = "xl/" + target;

                ZipEntry sEntry = zip.getEntry(target);
                if (sEntry == null) continue;

                Document sheetDoc = dbf.newDocumentBuilder().parse(zip.getInputStream(sEntry));
                parseSheet(sheetName, sheetDoc, sharedStrings,
                        sheetNames, sheetBatches, sheetBatchSlots, sheetDetectionInfos);
            }

            return new ParsedWorkbook(file, sheetNames, sheetBatches, sheetBatchSlots, sheetDetectionInfos);
        }
    }

    // -------------------------------------------------------------------------
    // Per-sheet parsing
    // -------------------------------------------------------------------------

    private void parseSheet(String sheetName,
                             Document sheetDoc,
                             List<String> sharedStrings,
                             List<String> outSheetNames,
                             Map<String, List<String>> outSheetBatches,
                             Map<String, Map<String, List<TimetableSlot>>> outSheetBatchSlots,
                             Map<String, ParsedWorkbook.SheetDetectionInfo> outSheetDetectionInfos) {

        Map<String, String> mergeMap = new HashMap<>();
        Map<String, int[]> mergeRanges = new HashMap<>();
        populateMerges(sheetDoc, mergeMap, mergeRanges);
        NodeList rowNodes = sheetDoc.getElementsByTagName("row");

        // --- Step 1: Build full cell-value cache ---
        // rowCellValues[rowNum][colRef] = raw cell value (string)
        Map<Integer, Map<String, String>> rowCellValues = new HashMap<>();
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element rowEl = (Element) rowNodes.item(i);
            int rNum = Integer.parseInt(rowEl.getAttribute("r"));
            Map<String, String> cellMap = new HashMap<>();
            NodeList cNodes = rowEl.getElementsByTagName("c");
            for (int j = 0; j < cNodes.getLength(); j++) {
                Element c = (Element) cNodes.item(j);
                String ref = c.getAttribute("r");
                String val = getCellValue(c, sharedStrings);
                if (val != null) cellMap.put(ref, val);
            }
            rowCellValues.put(rNum, cellMap);
        }

        // --- Step 2: Detect batch header row (top 10 rows, ^\d[A-Z]\d{2}$) ---
        int batchHeaderRow = -1;
        Map<String, String> batchColumns = new LinkedHashMap<>(); // batchName -> colRef (e.g. "AN")

        for (int i = 0; i < Math.min(10, rowNodes.getLength()); i++) {
            Element rowEl = (Element) rowNodes.item(i);
            int rNum = Integer.parseInt(rowEl.getAttribute("r"));
            Map<String, String> cells = rowCellValues.getOrDefault(rNum, Map.of());
            int matchCount = 0;
            for (Map.Entry<String, String> cell : cells.entrySet()) {
                if (BATCH_HEADER_PATTERN.matcher(cell.getValue().trim()).matches()) matchCount++;
            }
            if (matchCount > 0) {
                batchHeaderRow = rNum;
                for (Map.Entry<String, String> cell : cells.entrySet()) {
                    String val = cell.getValue().trim();
                    if (BATCH_HEADER_PATTERN.matcher(val).matches()) {
                        String col = cell.getKey().replaceAll("\\d+", "");
                        batchColumns.put(val, col);
                    }
                }
                break;
            }
        }

        if (batchHeaderRow == -1 || batchColumns.isEmpty()) {
            System.out.println("Skipping sheet '" + sheetName + "': no batch header row found.");
            return;
        }

        // --- Step 3: Detect period column and hours column ---
        String periodCol = detectPeriodColumn(rowNodes, rowCellValues, batchHeaderRow, null);
        String hoursCol  = detectHoursColumn(rowNodes, rowCellValues, batchHeaderRow, periodCol);

        int periodRowCount = countPeriodRows(rowNodes, rowCellValues, batchHeaderRow, periodCol);

        System.out.printf("Sheet '%-20s': headerRow=%d, periodCol=%s, hoursCol=%s, %d period rows, %d batches%n",
                sheetName, batchHeaderRow, periodCol, hoursCol, periodRowCount, batchColumns.size());

        if (periodCol == null || hoursCol == null) {
            System.out.println("  WARNING: could not detect period/hours columns for sheet '" + sheetName + "'.");
            return;
        }

        // --- Step 4: Initialise batch slot lists ---
        outSheetNames.add(sheetName);
        outSheetBatches.put(sheetName, new ArrayList<>(batchColumns.keySet()));
        outSheetDetectionInfos.put(sheetName,
                new ParsedWorkbook.SheetDetectionInfo(batchHeaderRow, periodCol, hoursCol, periodRowCount, batchColumns));
        Map<String, List<TimetableSlot>> batchSlotsMap = new LinkedHashMap<>();
        for (String b : batchColumns.keySet()) batchSlotsMap.put(b, new ArrayList<>());

        // --- Step 5: Walk rows in document order ---
        int currentDayIndex = -1;

        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element rowEl = (Element) rowNodes.item(i);
            int rNum = Integer.parseInt(rowEl.getAttribute("r"));
            if (rNum <= batchHeaderRow) continue;

            Map<String, String> currentRowCells = rowCellValues.getOrDefault(rNum, Map.of());
            String periodVal = currentRowCells.get(periodCol + rNum);

            if (periodVal == null || !periodVal.trim().matches("^\\d+$")) continue;

            int period = Integer.parseInt(periodVal.trim());
            if (period < 1 || period > 14) continue;

            if (period == 1) {
                currentDayIndex++;
                if (currentDayIndex >= 7) break;
            }

            // Determine start time
            String hoursVal = currentRowCells.get(hoursCol + rNum);
            LocalTime startTime = parseExcelTime(hoursVal);
            if (startTime == null) {
                // Fall back: 08:00 + 50 min * (period - 1)
                startTime = LocalTime.of(8, 0).plusMinutes(50L * (period - 1));
                if (hoursVal != null && !hoursVal.trim().isEmpty()) {
                    System.out.printf("  WARN sheet '%s' row %d: cannot parse time '%s', using fallback %s%n",
                            sheetName, rNum, hoursVal, startTime);
                }
            }

            String dayName = DAYS.get(currentDayIndex);

            // Check each batch column
            for (Map.Entry<String, String> bEntry : batchColumns.entrySet()) {
                String batch = bEntry.getKey();
                String batchColRef = bEntry.getValue();

                String cellVal = resolvedValue(batchColRef + rNum, mergeMap, rowCellValues);
                if (cellVal == null) continue;
                String trimmedVal = cellVal.trim();
                if (trimmedVal.isEmpty()) continue;

                // Normalise: collapse internal spaces inside a code token (e.g. "UMA022 L" -> "UMA022L")
                trimmedVal = normalizeCodeSpaces(trimmedVal);

                if (!SLOT_START_PATTERN.matcher(trimmedVal).matches()) continue;

                String belowRaw = resolvedValue(batchColRef + (rNum + 2), mergeMap, rowCellValues);
                String roomRaw = resolveRoom(batchColRef, rNum, mergeMap, mergeRanges, rowCellValues);

                List<TimetableSlot> batchList = batchSlotsMap.get(batch);
                if (alreadyCoveredByPriorSlot(batchList, dayName, startTime, trimmedVal)) {
                    continue;
                }

                if (trimmedVal.contains("/")) {
                    TimetableSlot slot = parseElectiveSlot(
                            sheetName, batch, dayName, startTime, trimmedVal, roomRaw, belowRaw);
                    if (slot != null) batchList.add(slot);
                } else {
                    TimetableSlot slot = parseNormalSlot(
                            sheetName, batch, dayName, startTime, trimmedVal, roomRaw, belowRaw);
                    if (slot != null) batchList.add(slot);
                }
            }
        }

        for (Map.Entry<String, List<TimetableSlot>> e : batchSlotsMap.entrySet()) {
            e.setValue(mergeBackToBackPtSlots(e.getValue()));
        }

        // Log slot counts
        int totalSlots = batchSlotsMap.values().stream().mapToInt(List::size).sum();
        System.out.printf("  -> %d total slots across %d batches%n", totalSlots, batchSlotsMap.size());

        outSheetBatchSlots.put(sheetName, batchSlotsMap);
    }

    // -------------------------------------------------------------------------
    // Slot parsing helpers
    // -------------------------------------------------------------------------

    /**
     * Collapses spaces between a subject code base and its suffix:
     * "UMA022 L" -> "UMA022L", "UCS503 P" -> "UCS503P".
     * Also handles elective lists: "UCS002 / UTD004 /UMA069L" -> preserves "/" delimiters
     * but normalises each token.
     */
    private static String normalizeCodeSpaces(String raw) {
        // For each token separated by "/", strip internal space between base and suffix
        String[] parts = raw.split("/");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim().replaceAll("(?<=[A-Z]{3,4}\\d{3})\\s+(?=[LTP])", "");
        }
        return String.join("/", parts);
    }

    /**
     * Number of 50-minute periods for a slot, given the suffix and the text two rows
     * below the code cell (resolved through the merge map).
     * <ul>
     *   <li>Practical (P): 2 periods (100 min) unless {@code below} starts a different slot
     *       (another code), in which case 1 period. So LAB, faculty text, or blank all mean 2 periods.</li>
     *   <li>Tutorial (T): 2 periods only if {@code below} is LAB or non-blank text that is not a slot code
     *       (a faculty row in the next period). Otherwise 1 period.</li>
     *   <li>Lecture (L): always 1 period.</li>
     * </ul>
     * Explicit "(upto …)" times bypass this and are handled by {@link #endTimeFor}.
     */
    static int periodCountFor(char suffix, String below) {
        if (suffix != 'P' && suffix != 'T') return 1;
        String b = below == null ? "" : below.trim();
        boolean belowIsSlot = isSlotStart(b);
        
        if (suffix == 'P') {
            // Practical: 2 periods unless below starts another slot code
            return belowIsSlot ? 1 : 2;
        }
        
        // Tutorial: 2 periods only if below is LAB or non-blank non-slot text (faculty row)
        if (b.isEmpty() || belowIsSlot) return 1;
        return 2;
    }

    static boolean isSlotStart(String text) {
        if (text == null || text.isBlank()) return false;
        return SLOT_START_PATTERN.matcher(normalizeCodeSpaces(text.trim())).matches();
    }

    /**
     * End time is {@code start + 50 * periods} from the sheet's real start time,
     * clamped so it never extends past period 14 (19:40).
     */
    static LocalTime endTimeFor(LocalTime start, int numPeriods, String sheetName, String context) {
        LocalTime end = start.plusMinutes(50L * numPeriods);
        if (end.isAfter(LAST_PERIOD_END)) {
            System.out.printf("  WARN sheet '%s': clamping end of %s from %s to %s%n",
                    sheetName, context, end, LAST_PERIOD_END);
            return LAST_PERIOD_END;
        }
        return end;
    }

    static LocalTime endTimeFromCellText(String cellText, char suffix, String below,
                                         LocalTime startTime, String sheetName, String context) {
        Matcher uptoM = UPTO_PATTERN.matcher(cellText != null ? cellText : "");
        if (uptoM.find()) {
            String ts = uptoM.group(1) + (uptoM.group(2) != null ? " " + uptoM.group(2) : "");
            LocalTime parsed = parseTextTime(ts);
            if (parsed == null) parsed = startTime.plusMinutes(50);
            if (parsed.isAfter(LAST_PERIOD_END)) {
                System.out.printf("  WARN sheet '%s': clamping upto-end of %s from %s to %s%n",
                        sheetName, context, parsed, LAST_PERIOD_END);
                return LAST_PERIOD_END;
            }
            return parsed;
        }
        return endTimeFor(startTime, periodCountFor(suffix, below), sheetName, context);
    }

    private TimetableSlot parseNormalSlot(String sheetName, String batch, String day, LocalTime startTime,
                                          String trimmedVal, String roomRaw, String belowRaw) {
        Matcher m = CODE_PARSE_PATTERN.matcher(trimmedVal);
        if (!m.matches()) return null;

        String baseCode  = m.group(1).toUpperCase();
        char suffix      = (m.group(2) != null && !m.group(2).isEmpty()) ? m.group(2).toUpperCase().charAt(0) : 'L';
        String actType   = suffixToActivityType(suffix);
        String kind      = suffixToKind(suffix);

        LocalTime endTime = endTimeFromCellText(
                trimmedVal, suffix, belowRaw, startTime, sheetName, batch + " " + trimmedVal);

        String room = cleanRoom(roomRaw);
        return new TimetableSlot(day, startTime, endTime,
                baseCode, suffix, actType, kind,
                trimmedVal, room, false, null);
    }

    private TimetableSlot parseElectiveSlot(String sheetName, String batch, String day, LocalTime startTime,
                                            String trimmedVal, String roomRaw, String belowRaw) {
        String[] codeParts = trimmedVal.split("/");
        String[] roomParts = roomRaw != null ? roomRaw.split("/") : new String[0];

        // Find the suffix of the last code in the list to inherit
        char inheritedSuffix = findLastSuffix(codeParts, 'L');

        List<ElectiveOption> options = new ArrayList<>();
        for (int optIdx = 0; optIdx < codeParts.length; optIdx++) {
            String optStr = codeParts[optIdx].trim();
            if (optStr.isEmpty()) continue;

            Matcher m = CODE_PARSE_PATTERN.matcher(optStr);
            if (!m.matches()) continue;

            String baseCode = m.group(1).toUpperCase();
            char suffix     = (m.group(2) != null && !m.group(2).isEmpty())
                    ? m.group(2).toUpperCase().charAt(0)
                    : inheritedSuffix;
            String actType  = suffixToActivityType(suffix);
            String kind     = suffixToKind(suffix);

            LocalTime optEndTime = endTimeFromCellText(
                    optStr, suffix, belowRaw, startTime, sheetName, batch + " elective " + optStr);

            String optRoom = null;
            if (optIdx < roomParts.length) {
                String r = roomParts[optIdx].trim();
                if (!r.isEmpty() && !r.equals("---")) optRoom = r;
            }

            // Reconstruct full code with suffix for display
            String displayCode = baseCode + suffix + (m.group(3) != null ? m.group(3) : "");

            options.add(new ElectiveOption(
                    displayCode, baseCode, suffix, actType, kind,
                    startTime, optEndTime, optRoom));
        }

        if (options.isEmpty()) return null;

        // Representative end time (first option's end time)
        LocalTime repEnd = options.get(0).getEndTime();
        return new TimetableSlot(day, startTime, repEnd,
                null, ' ', "CLASS", "elective",
                trimmedVal, roomRaw, true, options);
    }

    /**
     * Skip a period-row parse when a previous 2-period slot already covers this start
     * (the code cell is merged down, or faculty/LAB occupies the next period row).
     */
    static boolean alreadyCoveredByPriorSlot(List<TimetableSlot> existing, String day,
                                             LocalTime startTime, String trimmedVal) {
        if (existing == null || existing.isEmpty() || startTime == null) return false;
        for (int i = existing.size() - 1; i >= 0; i--) {
            TimetableSlot prev = existing.get(i);
            if (!day.equals(prev.getDay())) continue;
            if (prev.isElective()) continue;
            if (prev.getEndTime() == null || !prev.getEndTime().isAfter(startTime)) continue;
            if (!prev.getStartTime().isAfter(startTime)
                    && prev.getSubjectCode() != null
                    && trimmedVal != null
                    && trimmedVal.toUpperCase().startsWith(prev.getSubjectCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merge two back-to-back 50-minute P (or T) slots in the same batch that share
     * code, suffix and room into one 100-minute slot.
     */
    static List<TimetableSlot> mergeBackToBackPtSlots(List<TimetableSlot> slots) {
        if (slots == null || slots.size() < 2) return slots;
        List<TimetableSlot> sorted = new ArrayList<>(slots);
        sorted.sort(Comparator
                .comparing(TimetableSlot::getDay, Comparator.nullsLast(String::compareTo))
                .thenComparing(TimetableSlot::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.getSubjectCode() == null ? "" : s.getSubjectCode()));
        List<TimetableSlot> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            TimetableSlot a = sorted.get(i);
            if (i + 1 < sorted.size()) {
                TimetableSlot b = sorted.get(i + 1);
                if (canMergeBackToBack(a, b)) {
                    out.add(new TimetableSlot(
                            a.getDay(), a.getStartTime(), b.getEndTime(),
                            a.getSubjectCode(), a.getSuffix(), a.getActivityType(), a.getKind(),
                            a.getRawCellText(), a.getRoom(), false, null));
                    i++;
                    continue;
                }
            }
            out.add(a);
        }
        return out;
    }

    static boolean canMergeBackToBack(TimetableSlot a, TimetableSlot b) {
        if (a == null || b == null) return false;
        if (a.isElective() || b.isElective()) return false;
        if (a.getSuffix() != 'P' && a.getSuffix() != 'T') return false;
        if (a.getSuffix() != b.getSuffix()) return false;
        if (!Objects.equals(a.getDay(), b.getDay())) return false;
        if (!Objects.equals(a.getSubjectCode(), b.getSubjectCode())) return false;
        if (!Objects.equals(a.getRoom(), b.getRoom())) return false;
        if (a.getStartTime() == null || a.getEndTime() == null
                || b.getStartTime() == null || b.getEndTime() == null) return false;
        long minsA = java.time.Duration.between(a.getStartTime(), a.getEndTime()).toMinutes();
        long minsB = java.time.Duration.between(b.getStartTime(), b.getEndTime()).toMinutes();
        if (minsA != 50 || minsB != 50) return false;
        return a.getEndTime().equals(b.getStartTime());
    }

    /** Finds the suffix of the last code part that has one. */
    private static char findLastSuffix(String[] parts, char defaultSuffix) {
        for (int i = parts.length - 1; i >= 0; i--) {
            Matcher m = CODE_PARSE_PATTERN.matcher(parts[i].trim());
            if (m.matches() && m.group(2) != null && !m.group(2).isEmpty()) {
                return m.group(2).toUpperCase().charAt(0);
            }
        }
        return defaultSuffix;
    }

    private static String suffixToActivityType(char suffix) {
        return switch (suffix) {
            case 'P' -> "LAB";
            case 'T' -> "TUTORIAL";
            default  -> "CLASS";
        };
    }

    private static String suffixToKind(char suffix) {
        return switch (suffix) {
            case 'P' -> "practical";
            case 'T' -> "tutorial";
            default  -> "lecture";
        };
    }

    private static String cleanRoom(String raw) {
        if (raw == null) return null;
        String r = raw.trim();
        return (r.isEmpty() || r.equals("---")) ? null : r;
    }

    /**
     * Room sits on row {@code codeRow+1} at the leftmost column of the code cell's
     * merged range (faculty initials are at the rightmost). Falls back to the batch
     * column, then scans left-to-right within the range.
     */
    static String resolveRoom(String batchCol, int codeRow,
                              Map<String, String> mergeMap,
                              Map<String, int[]> mergeRanges,
                              Map<Integer, Map<String, String>> rowCellValues) {
        int roomRow = codeRow + 1;
        int[] range = mergeRanges != null ? mergeRanges.get(batchCol + codeRow) : null;
        if (range != null) {
            String left = cleanRoom(resolvedValue(numToCol(range[0]) + roomRow, mergeMap, rowCellValues));
            if (left != null) return left;
            String atBatch = cleanRoom(resolvedValue(batchCol + roomRow, mergeMap, rowCellValues));
            if (atBatch != null) return atBatch;
            for (int c = range[0]; c <= range[2]; c++) {
                String scanned = cleanRoom(resolvedValue(numToCol(c) + roomRow, mergeMap, rowCellValues));
                if (scanned != null) return scanned;
            }
        }
        return cleanRoom(resolvedValue(batchCol + roomRow, mergeMap, rowCellValues));
    }

    // -------------------------------------------------------------------------
    // Dynamic column detection
    // -------------------------------------------------------------------------

    /**
     * Detects the period-number column: the column in LAYOUT_CANDIDATE_COLS with
     * the most cells in rows below batchHeaderRow whose value is an integer 1..14.
     * If {@code excludeCol} is non-null it is skipped.
     */
    static String detectPeriodColumn(NodeList rowNodes,
                                     Map<Integer, Map<String, String>> rowCellValues,
                                     int batchHeaderRow,
                                     String excludeCol) {
        String best = null;
        int bestCount = 0;
        for (String col : LAYOUT_CANDIDATE_COLS) {
            if (col.equals(excludeCol)) continue;
            int count = 0;
            for (int i = 0; i < rowNodes.getLength(); i++) {
                Element rowEl = (Element) rowNodes.item(i);
                int rNum = Integer.parseInt(rowEl.getAttribute("r"));
                if (rNum <= batchHeaderRow) continue;
                Map<String, String> cells = rowCellValues.getOrDefault(rNum, Map.of());
                String v = cells.get(col + rNum);
                if (v != null && v.trim().matches("^\\d+$")) {
                    int p = Integer.parseInt(v.trim());
                    if (p >= 1 && p <= 14) count++;
                }
            }
            if (count > bestCount) { bestCount = count; best = col; }
        }
        return best;
    }

    /**
     * Detects the hours column: the column in LAYOUT_CANDIDATE_COLS (excluding periodCol)
     * with the most cells in rows below batchHeaderRow that parse as a time.
     */
    static String detectHoursColumn(NodeList rowNodes,
                                    Map<Integer, Map<String, String>> rowCellValues,
                                    int batchHeaderRow,
                                    String periodCol) {
        String best = null;
        int bestCount = 0;
        for (String col : LAYOUT_CANDIDATE_COLS) {
            if (col.equals(periodCol)) continue;
            int count = 0;
            for (int i = 0; i < rowNodes.getLength(); i++) {
                Element rowEl = (Element) rowNodes.item(i);
                int rNum = Integer.parseInt(rowEl.getAttribute("r"));
                if (rNum <= batchHeaderRow) continue;
                Map<String, String> cells = rowCellValues.getOrDefault(rNum, Map.of());
                String v = cells.get(col + rNum);
                if (parseExcelTime(v) != null) count++;
            }
            if (count > bestCount) { bestCount = count; best = col; }
        }
        return best;
    }

    private static int countPeriodRows(NodeList rowNodes,
                                       Map<Integer, Map<String, String>> rowCellValues,
                                       int batchHeaderRow, String periodCol) {
        if (periodCol == null) return 0;
        int count = 0;
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element rowEl = (Element) rowNodes.item(i);
            int rNum = Integer.parseInt(rowEl.getAttribute("r"));
            if (rNum <= batchHeaderRow) continue;
            Map<String, String> cells = rowCellValues.getOrDefault(rNum, Map.of());
            String v = cells.get(periodCol + rNum);
            if (v != null && v.trim().matches("^\\d+$")) {
                int p = Integer.parseInt(v.trim());
                if (p >= 1 && p <= 14) count++;
            }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Cell resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a cell reference through the merge map and returns its string value.
     * If cellRef is within a merged range, returns the value stored at the top-left master cell.
     */
    private static String resolvedValue(String cellRef,
                                        Map<String, String> mergeMap,
                                        Map<Integer, Map<String, String>> rowCellValues) {
        String master = mergeMap.getOrDefault(cellRef, cellRef);
        int rNum = Integer.parseInt(master.replaceAll("\\D+", ""));
        Map<String, String> row = rowCellValues.get(rNum);
        return row != null ? row.get(master) : null;
    }

    // -------------------------------------------------------------------------
    // Time parsing (public for use in tests / detection)
    // -------------------------------------------------------------------------

    /**
     * Parses an Excel cell value as a time.
     * Accepts numeric fractions-of-a-day (0.333... = 08:00) and text in many
     * formats: "8:50:AM", "9:40: AM ", "10:30: AM", "6:00PM", "08:00", etc.
     */
    public static LocalTime parseExcelTime(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        String s = val.trim();
        // Try numeric fraction first
        try {
            double frac = Double.parseDouble(s);
            if (frac >= 0 && frac < 1) {
                int totalMin = (int) Math.round(frac * 24.0 * 60.0);
                return LocalTime.of(totalMin / 60, totalMin % 60);
            }
        } catch (NumberFormatException ignored) { /* fall through to text parsing */ }
        return parseTextTime(s);
    }

    /**
     * Normalise and parse a text time string.
     * Steps: trim → remove all internal whitespace → upper-case →
     * turn ":AM"/":PM" into "AM"/"PM".
     */
    public static LocalTime parseTextTime(String val) {
        if (val == null) return null;
        // Remove all whitespace, upper-case
        String s = val.replaceAll("\\s+", "").toUpperCase();
        // ":AM" / ":PM" at the end (e.g. "8:50:AM") → strip the extra colon
        s = s.replaceAll(":([AP]M)$", "$1");
        // Now expect HH:MM or HH:MMAM / HH:MMPM
        Matcher m = TEXT_TIME_PATTERN.matcher(s);
        if (m.matches()) {
            int h   = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            String ampm = m.group(3);
            if ("PM".equals(ampm) && h < 12) h += 12;
            if ("AM".equals(ampm) && h == 12) h = 0;
            return LocalTime.of(h, min);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // XML helpers
    // -------------------------------------------------------------------------

    private static String getCellValue(Element c, List<String> sharedStrings) {
        String type = c.getAttribute("t");
        NodeList vNodes = c.getElementsByTagName("v");
        if (vNodes.getLength() == 0) return null;
        String val = vNodes.item(0).getTextContent();
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(val);
                if (idx >= 0 && idx < sharedStrings.size()) return sharedStrings.get(idx);
            } catch (NumberFormatException ignored) {}
        }
        return val;
    }

    private static List<String> loadSharedStrings(ZipFile zip, DocumentBuilderFactory dbf) throws Exception {
        List<String> list = new ArrayList<>();
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry != null) {
            Document doc = dbf.newDocumentBuilder().parse(zip.getInputStream(entry));
            NodeList siNodes = doc.getElementsByTagName("si");
            for (int i = 0; i < siNodes.getLength(); i++) {
                Element si = (Element) siNodes.item(i);
                NodeList tNodes = si.getElementsByTagName("t");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < tNodes.getLength(); j++) sb.append(tNodes.item(j).getTextContent());
                list.add(sb.toString());
            }
        }
        return list;
    }

    /**
     * Builds a map: every slave cell ref → master cell ref for all merged ranges.
     * Package-visible so tests can inspect it.
     */
    static Map<String, String> loadMergeMap(Document sheetDoc) {
        Map<String, String> map = new HashMap<>();
        populateMerges(sheetDoc, map, null);
        return map;
    }

    static void populateMerges(Document sheetDoc,
                               Map<String, String> mergeMap,
                               Map<String, int[]> mergeRanges) {
        NodeList merges = sheetDoc.getElementsByTagName("mergeCell");
        for (int i = 0; i < merges.getLength(); i++) {
            String ref = ((Element) merges.item(i)).getAttribute("ref");
            String[] parts = ref.split(":");
            if (parts.length == 2) {
                String from = parts[0];
                String to   = parts[1];
                int c1 = colToNum(from.replaceAll("\\d+", ""));
                int r1 = Integer.parseInt(from.replaceAll("\\D+", ""));
                int c2 = colToNum(to.replaceAll("\\d+", ""));
                int r2 = Integer.parseInt(to.replaceAll("\\D+", ""));
                int[] range = new int[]{c1, r1, c2, r2};
                for (int c = c1; c <= c2; c++) {
                    String colStr = numToCol(c);
                    for (int r = r1; r <= r2; r++) {
                        String cell = colStr + r;
                        if (mergeMap != null) mergeMap.put(cell, from);
                        if (mergeRanges != null) mergeRanges.put(cell, range);
                    }
                }
            }
        }
    }

    static int colToNum(String col) {
        int res = 0;
        for (char ch : col.toUpperCase().toCharArray()) res = res * 26 + (ch - 'A' + 1);
        return res;
    }

    private static String numToCol(int num) {
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            int rem = (num - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            num = (num - 1) / 26;
        }
        return sb.toString();
    }
}
