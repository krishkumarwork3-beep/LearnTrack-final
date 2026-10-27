package com.learntrack.service;

import java.io.File;
import java.util.*;

/**
 * Encapsulates parsed timetable sheets and batches from an Excel workbook.
 */
public class ParsedWorkbook {
    public static class SheetDetectionInfo {
        public final int headerRow;
        public final String periodCol;
        public final String hoursCol;
        public final int periodRowCount;
        public final Map<String, String> batchColumns;

        public SheetDetectionInfo(int headerRow, String periodCol, String hoursCol,
                                  int periodRowCount, Map<String, String> batchColumns) {
            this.headerRow = headerRow;
            this.periodCol = periodCol;
            this.hoursCol = hoursCol;
            this.periodRowCount = periodRowCount;
            this.batchColumns = batchColumns != null ? batchColumns : Map.of();
        }
    }

    private final File file;
    private final List<String> sheetNames;
    private final Map<String, List<String>> sheetBatches;
    private final Map<String, Map<String, List<TimetableSlot>>> sheetBatchSlots;
    private final Map<String, SheetDetectionInfo> sheetDetectionInfos;

    public ParsedWorkbook(File file,
                          List<String> sheetNames,
                          Map<String, List<String>> sheetBatches,
                          Map<String, Map<String, List<TimetableSlot>>> sheetBatchSlots) {
        this(file, sheetNames, sheetBatches, sheetBatchSlots, Map.of());
    }

    public ParsedWorkbook(File file,
                          List<String> sheetNames,
                          Map<String, List<String>> sheetBatches,
                          Map<String, Map<String, List<TimetableSlot>>> sheetBatchSlots,
                          Map<String, SheetDetectionInfo> sheetDetectionInfos) {
        this.file = file;
        this.sheetNames = sheetNames != null ? sheetNames : List.of();
        this.sheetBatches = sheetBatches != null ? sheetBatches : Map.of();
        this.sheetBatchSlots = sheetBatchSlots != null ? sheetBatchSlots : Map.of();
        this.sheetDetectionInfos = sheetDetectionInfos != null ? sheetDetectionInfos : Map.of();
    }

    public File getFile() {
        return file;
    }

    public List<String> getSheetNames() {
        return sheetNames;
    }

    public Map<String, List<String>> getSheetBatches() {
        return sheetBatches;
    }

    public Map<String, Map<String, List<TimetableSlot>>> getSheetBatchSlots() {
        return sheetBatchSlots;
    }

    public Map<String, SheetDetectionInfo> getSheetDetectionInfos() {
        return sheetDetectionInfos;
    }

    public SheetDetectionInfo getDetectionInfo(String sheetName) {
        if (sheetName == null) return null;
        for (Map.Entry<String, SheetDetectionInfo> e : sheetDetectionInfos.entrySet()) {
            if (e.getKey().trim().equalsIgnoreCase(sheetName.trim())) return e.getValue();
        }
        return null;
    }

    public String getDetectionSummary(String sheetName, String batchName) {
        SheetDetectionInfo info = getDetectionInfo(sheetName);
        if (info == null) return null;
        String col = null;
        for (Map.Entry<String, String> bEntry : info.batchColumns.entrySet()) {
            if (bEntry.getKey().trim().equalsIgnoreCase(batchName.trim())) {
                col = bEntry.getValue();
                break;
            }
        }
        int slots = getSlots(sheetName, batchName).size();
        if (col != null) {
            return String.format(
                "Batch %s found on sheet '%s' (header row %d, column %s).\n"
                + "Detected period column %s, hours column %s, %d period rows, %d slots.",
                batchName.trim(), sheetName.trim(), info.headerRow, col,
                info.periodCol, info.hoursCol, info.periodRowCount, slots);
        } else {
            return String.format(
                "Sheet '%s': header row %d, detected period column %s, hours column %s, %d period rows. Batch %s not found in header row.",
                sheetName.trim(), info.headerRow, info.periodCol, info.hoursCol, info.periodRowCount, batchName.trim());
        }
    }

    /**
     * Finds all sheets containing the specified batch name (case-insensitive, trimmed).
     */
    public List<String> findSheetsForBatch(String batchName) {
        if (batchName == null || batchName.trim().isEmpty()) return List.of();
        String target = batchName.trim();
        List<String> found = new ArrayList<>();
        for (String sheet : sheetNames) {
            List<String> batches = sheetBatches.get(sheet);
            if (batches != null) {
                for (String b : batches) {
                    if (b.trim().equalsIgnoreCase(target)) {
                        found.add(sheet);
                        break;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Finds the canonical batch name from the workbook headers matching typedBatch case-insensitively.
     */
    public String getCanonicalBatchName(String sheetName, String typedBatch) {
        if (typedBatch == null) return null;
        String target = typedBatch.trim();
        if (sheetName != null) {
            List<String> batches = sheetBatches.get(sheetName);
            if (batches == null) {
                for (Map.Entry<String, List<String>> e : sheetBatches.entrySet()) {
                    if (e.getKey().trim().equalsIgnoreCase(sheetName.trim())) {
                        batches = e.getValue();
                        break;
                    }
                }
            }
            if (batches != null) {
                for (String b : batches) {
                    if (b.trim().equalsIgnoreCase(target)) {
                        return b.trim();
                    }
                }
            }
        }
        for (List<String> batches : sheetBatches.values()) {
            if (batches != null) {
                for (String b : batches) {
                    if (b.trim().equalsIgnoreCase(target)) {
                        return b.trim();
                    }
                }
            }
        }
        return target;
    }

    /**
     * Finds similar batch names (matching the first 2 characters) across all sheets.
     */
    public List<String> findSimilarBatches(String batchName) {
        if (batchName == null) return List.of();
        String target = batchName.trim().toUpperCase();
        String prefix = target.length() >= 2 ? target.substring(0, 2) : target;
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (List<String> batches : sheetBatches.values()) {
            for (String b : batches) {
                if (prefix.isEmpty() || b.trim().toUpperCase().startsWith(prefix)) {
                    set.add(b.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * Gets all slots for a given sheet and batch (case-insensitive).
     */
    public List<TimetableSlot> getSlots(String sheetName, String batchName) {
        if (sheetName == null || batchName == null) return List.of();
        for (Map.Entry<String, Map<String, List<TimetableSlot>>> sheetEntry : sheetBatchSlots.entrySet()) {
            if (sheetEntry.getKey().trim().equalsIgnoreCase(sheetName.trim())) {
                for (Map.Entry<String, List<TimetableSlot>> batchEntry : sheetEntry.getValue().entrySet()) {
                    if (batchEntry.getKey().trim().equalsIgnoreCase(batchName.trim())) {
                        return batchEntry.getValue();
                    }
                }
            }
        }
        return List.of();
    }
}
