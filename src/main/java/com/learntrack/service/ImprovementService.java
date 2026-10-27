package com.learntrack.service;

import com.learntrack.model.WeeklyTimetableEntry;

import java.time.LocalTime;
import java.util.*;

/**
 * Service to find and recommend batches offering an improvement subject.
 */
public class ImprovementService {

    public static class ImprovementSlot {
        private final String day;
        private final LocalTime startTime;
        private final LocalTime endTime;
        private final String subjectCode;
        private final char suffix; // L, T, P
        private final String activityType; // CLASS, TUTORIAL, LAB
        private final String kind; // lecture, tutorial, practical
        private final String room;
        private final List<WeeklyTimetableEntry> hardConflicts = new ArrayList<>();
        private final List<WeeklyTimetableEntry> softConflicts = new ArrayList<>();

        public ImprovementSlot(String day, LocalTime startTime, LocalTime endTime,
                               String subjectCode, char suffix, String activityType, String kind, String room) {
            this.day = day;
            this.startTime = startTime;
            this.endTime = endTime;
            this.subjectCode = subjectCode;
            this.suffix = suffix;
            this.activityType = activityType;
            this.kind = kind;
            this.room = room;
        }

        public String getDay() { return day; }
        public LocalTime getStartTime() { return startTime; }
        public LocalTime getEndTime() { return endTime; }
        public String getSubjectCode() { return subjectCode; }
        public char getSuffix() { return suffix; }
        public String getActivityType() { return activityType; }
        public String getKind() { return kind; }
        public String getRoom() { return room; }
        public List<WeeklyTimetableEntry> getHardConflicts() { return hardConflicts; }
        public List<WeeklyTimetableEntry> getSoftConflicts() { return softConflicts; }

        public String getFormattedLabel(Map<String, String> renames) {
            String name = (renames != null && renames.containsKey(subjectCode)) ? renames.get(subjectCode) : subjectCode;
            return name + " " + kind;
        }

        public WeeklyTimetableEntry toEntry(Map<String, String> renames) {
            return new WeeklyTimetableEntry(null, 0, day, startTime, endTime, activityType, getFormattedLabel(renames), room, subjectCode);
        }

        public String getConflictDescription() {
            String timeStr = day.substring(0, 1) + day.substring(1, 3).toLowerCase() + " " + startTime + "-" + endTime;
            if (!hardConflicts.isEmpty()) {
                StringBuilder sb = new StringBuilder(timeStr).append(" overlaps ");
                for (int i = 0; i < hardConflicts.size(); i++) {
                    if (i > 0) sb.append(", ");
                    WeeklyTimetableEntry c = hardConflicts.get(i);
                    sb.append(c.getLabel() != null ? c.getLabel() : c.getActivityType());
                }
                return sb.toString();
            } else if (!softConflicts.isEmpty()) {
                StringBuilder sb = new StringBuilder(timeStr).append(" overlaps ");
                for (int i = 0; i < softConflicts.size(); i++) {
                    if (i > 0) sb.append(", ");
                    WeeklyTimetableEntry c = softConflicts.get(i);
                    sb.append(c.getLabel() != null ? c.getLabel() : c.getActivityType());
                }
                return sb.toString();
            }
            return "Fits";
        }
    }

    public static class ComponentResult {
        private final char suffix;
        private final String status; // "fits", "needs adjustment", "conflicts", "not offered", "not selected"
        private final List<ImprovementSlot> slots;
        private final int hardConflictCount;
        private final int softConflictCount;
        private final boolean offered;

        public ComponentResult(char suffix, String status, List<ImprovementSlot> slots,
                               int hardConflictCount, int softConflictCount, boolean offered) {
            this.suffix = suffix;
            this.status = status;
            this.slots = slots != null ? slots : List.of();
            this.hardConflictCount = hardConflictCount;
            this.softConflictCount = softConflictCount;
            this.offered = offered;
        }

        public char getSuffix() { return suffix; }
        public String getStatus() { return status; }
        public List<ImprovementSlot> getSlots() { return slots; }
        public int getHardConflictCount() { return hardConflictCount; }
        public int getSoftConflictCount() { return softConflictCount; }
        public int getAcademicConflictSlotCount() {
            return (int) slots.stream().filter(s -> !s.getHardConflicts().isEmpty()).count();
        }
        public int getMealOtherConflictSlotCount() {
            return (int) slots.stream().filter(s -> !s.getSoftConflicts().isEmpty()).count();
        }
        public boolean isOffered() { return offered; }
        public boolean canAdd() { return "fits".equals(status) || "needs adjustment".equals(status); }
    }

    public static class BatchRecommendation {
        private final String sheetName;
        private final String batchName;
        private final ComponentResult lecture;
        private final ComponentResult tutorial;
        private final ComponentResult practical;
        private final int totalHardConflicts;
        private final int totalSoftConflicts;
        private final int fittingComponentsCount;
        private final boolean allSelectedFit;
        private final boolean recommended;

        public BatchRecommendation(String sheetName, String batchName,
                                   ComponentResult lecture, ComponentResult tutorial, ComponentResult practical,
                                   int totalHardConflicts, int totalSoftConflicts, int fittingComponentsCount,
                                   boolean allSelectedFit, boolean recommended) {
            this.sheetName = sheetName;
            this.batchName = batchName;
            this.lecture = lecture;
            this.tutorial = tutorial;
            this.practical = practical;
            this.totalHardConflicts = totalHardConflicts;
            this.totalSoftConflicts = totalSoftConflicts;
            this.fittingComponentsCount = fittingComponentsCount;
            this.allSelectedFit = allSelectedFit;
            this.recommended = recommended;
        }

        public String getSheetName() { return sheetName; }
        public String getBatchName() { return batchName; }
        public ComponentResult getLecture() { return lecture; }
        public ComponentResult getTutorial() { return tutorial; }
        public ComponentResult getPractical() { return practical; }
        public int getTotalHardConflicts() { return totalHardConflicts; }
        public int getTotalSoftConflicts() { return totalSoftConflicts; }
        public int getFittingComponentsCount() { return fittingComponentsCount; }
        public boolean isAllSelectedFit() { return allSelectedFit; }
        public boolean isRecommended() { return recommended; }
    }

    /**
     * Evaluates all batches across the workbook offering the given subject and components.
     */
    public List<BatchRecommendation> findRecommendations(
            ParsedWorkbook workbook,
            String subjectBaseCode,
            boolean includeLecture,
            boolean includeTutorial,
            boolean includePractical,
            List<WeeklyTimetableEntry> universityBaseline,
            List<WeeklyTimetableEntry> personalSoftEntries,
            String studentBatch) {

        if (workbook == null || subjectBaseCode == null || subjectBaseCode.trim().isEmpty()) {
            return List.of();
        }

        String targetCode = subjectBaseCode.trim().toUpperCase();

        // 1. Collect all candidates
        List<BatchRecommendation> rawResults = new ArrayList<>();
        boolean subjectFoundAnywhere = false;
        boolean onlyInStudentBatch = true;

        for (Map.Entry<String, Map<String, List<TimetableSlot>>> sheetEntry : workbook.getSheetBatchSlots().entrySet()) {
            String sheet = sheetEntry.getKey();
            for (Map.Entry<String, List<TimetableSlot>> batchEntry : sheetEntry.getValue().entrySet()) {
                String batch = batchEntry.getKey();
                List<TimetableSlot> slots = batchEntry.getValue();

                List<ImprovementSlot> lecSlots = new ArrayList<>();
                List<ImprovementSlot> tutSlots = new ArrayList<>();
                List<ImprovementSlot> pracSlots = new ArrayList<>();

                for (TimetableSlot s : slots) {
                    if (s.isElective()) {
                        for (ElectiveOption opt : s.getElectiveOptions()) {
                            if (targetCode.equalsIgnoreCase(opt.getBaseCode())) {
                                subjectFoundAnywhere = true;
                                if (studentBatch == null || !batch.equalsIgnoreCase(studentBatch)) {
                                    onlyInStudentBatch = false;
                                }
                                ImprovementSlot is = new ImprovementSlot(
                                        s.getDay(), opt.getStartTime(), opt.getEndTime(),
                                        opt.getBaseCode(), opt.getSuffix(), opt.getActivityType(), opt.getKind(), opt.getRoom()
                                );
                                checkHardConflicts(is, universityBaseline);
                                checkSoftConflicts(is, personalSoftEntries);
                                if (opt.getSuffix() == 'L') lecSlots.add(is);
                                else if (opt.getSuffix() == 'T') tutSlots.add(is);
                                else if (opt.getSuffix() == 'P') pracSlots.add(is);
                            }
                        }
                    } else if (targetCode.equalsIgnoreCase(s.getSubjectCode())) {
                        subjectFoundAnywhere = true;
                        if (studentBatch == null || !batch.equalsIgnoreCase(studentBatch)) {
                            onlyInStudentBatch = false;
                        }
                        ImprovementSlot is = new ImprovementSlot(
                                s.getDay(), s.getStartTime(), s.getEndTime(),
                                s.getSubjectCode(), s.getSuffix(), s.getActivityType(), s.getKind(), s.getRoom()
                        );
                        checkHardConflicts(is, universityBaseline);
                        checkSoftConflicts(is, personalSoftEntries);
                        if (s.getSuffix() == 'L') lecSlots.add(is);
                        else if (s.getSuffix() == 'T') tutSlots.add(is);
                        else if (s.getSuffix() == 'P') pracSlots.add(is);
                    }
                }

                boolean offersLec = !lecSlots.isEmpty();
                boolean offersTut = !tutSlots.isEmpty();
                boolean offersPrac = !pracSlots.isEmpty();

                boolean offersAnySelected = (includeLecture && offersLec)
                        || (includeTutorial && offersTut)
                        || (includePractical && offersPrac);

                if (!offersAnySelected) continue;

                ComponentResult lecRes = evaluateComponent('L', includeLecture, offersLec, lecSlots);
                ComponentResult tutRes = evaluateComponent('T', includeTutorial, offersTut, tutSlots);
                ComponentResult pracRes = evaluateComponent('P', includePractical, offersPrac, pracSlots);

                int totalHard = 0;
                int totalSoft = 0;
                int fittingCount = 0;
                int selectedCount = 0;
                int selectedAndFitCount = 0;

                if (includeLecture) {
                    selectedCount++;
                    totalHard += lecRes.getHardConflictCount();
                    totalSoft += lecRes.getSoftConflictCount();
                    if (lecRes.canAdd()) {
                        fittingCount++;
                        selectedAndFitCount++;
                    }
                }
                if (includeTutorial) {
                    selectedCount++;
                    totalHard += tutRes.getHardConflictCount();
                    totalSoft += tutRes.getSoftConflictCount();
                    if (tutRes.canAdd()) {
                        fittingCount++;
                        selectedAndFitCount++;
                    }
                }
                if (includePractical) {
                    selectedCount++;
                    totalHard += pracRes.getHardConflictCount();
                    totalSoft += pracRes.getSoftConflictCount();
                    if (pracRes.canAdd()) {
                        fittingCount++;
                        selectedAndFitCount++;
                    }
                }

                boolean allFit = selectedCount > 0 && selectedAndFitCount == selectedCount && totalHard == 0;

                rawResults.add(new BatchRecommendation(
                        sheet, batch, lecRes, tutRes, pracRes,
                        totalHard, totalSoft, fittingCount, allFit, allFit
                ));
            }
        }

        if (!subjectFoundAnywhere) {
            throw new NoSuchElementException("Subject code '" + targetCode + "' was not found anywhere in the workbook.");
        }

        // Filter out student's own batch unless it's the only place the subject appears
        List<BatchRecommendation> filtered = new ArrayList<>();
        for (BatchRecommendation r : rawResults) {
            if (studentBatch != null && r.getBatchName().equalsIgnoreCase(studentBatch)) {
                if (onlyInStudentBatch) {
                    filtered.add(r);
                }
            } else {
                filtered.add(r);
            }
        }

        // Sort: batches where every selected component fits first, then by number of fitting components (descending), then fewer soft conflicts
        filtered.sort((a, b) -> {
            // 1. All selected components fit first
            if (a.isAllSelectedFit() != b.isAllSelectedFit()) {
                return a.isAllSelectedFit() ? -1 : 1;
            }
            // 2. Number of fitting components (descending)
            if (a.getFittingComponentsCount() != b.getFittingComponentsCount()) {
                return Integer.compare(b.getFittingComponentsCount(), a.getFittingComponentsCount());
            }
            // 3. Fewer soft conflicts
            if (a.getTotalSoftConflicts() != b.getTotalSoftConflicts()) {
                return Integer.compare(a.getTotalSoftConflicts(), b.getTotalSoftConflicts());
            }
            // 4. Fewer hard conflicts
            return Integer.compare(a.getTotalHardConflicts(), b.getTotalHardConflicts());
        });

        return filtered;
    }

    private static ComponentResult evaluateComponent(char suffix, boolean selected, boolean offered, List<ImprovementSlot> slots) {
        if (!selected) {
            return new ComponentResult(suffix, "not selected", slots, 0, 0, offered);
        }
        if (!offered) {
            return new ComponentResult(suffix, "not offered", slots, 0, 0, false);
        }

        int hard = 0;
        int soft = 0;
        for (ImprovementSlot s : slots) {
            hard += s.getHardConflicts().size();
            soft += s.getSoftConflicts().size();
        }

        String status;
        if (hard > 0) {
            status = "conflicts";
        } else if (soft > 0) {
            status = "needs adjustment";
        } else {
            status = "fits";
        }

        return new ComponentResult(suffix, status, slots, hard, soft, true);
    }

    /**
     * Checks for hard conflicts (academic entries from university baseline).
     */
    private static void checkHardConflicts(ImprovementSlot slot, List<WeeklyTimetableEntry> baselineEntries) {
        if (baselineEntries == null || baselineEntries.isEmpty()) return;

        for (WeeklyTimetableEntry cur : baselineEntries) {
            if (slot.getDay().equalsIgnoreCase(cur.getDayOfWeek())) {
                if (cur.getStartTime() != null && cur.getEndTime() != null) {
                    // Strict overlap check
                    if (slot.getStartTime().isBefore(cur.getEndTime()) && cur.getStartTime().isBefore(slot.getEndTime())) {
                        slot.getHardConflicts().add(cur);
                    }
                }
            }
        }
    }

    /**
     * Checks for soft conflicts (MEAL/SLEEP/OTHER personal entries).
     */
    private static void checkSoftConflicts(ImprovementSlot slot, List<WeeklyTimetableEntry> personalSoftEntries) {
        if (personalSoftEntries == null || personalSoftEntries.isEmpty()) return;

        for (WeeklyTimetableEntry cur : personalSoftEntries) {
            if (slot.getDay().equalsIgnoreCase(cur.getDayOfWeek())) {
                if (cur.getStartTime() != null && cur.getEndTime() != null) {
                    // Strict overlap check
                    if (slot.getStartTime().isBefore(cur.getEndTime()) && cur.getStartTime().isBefore(slot.getEndTime())) {
                        slot.getSoftConflicts().add(cur);
                    }
                }
            }
        }
    }

    private static final List<String> DAY_ORDER = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

    public static String cleanSubjectCode(String rawCode) {
        if (rawCode == null) return "";
        String trimmed = rawCode.trim().toUpperCase();
        if (trimmed.length() > 3 && (trimmed.endsWith("L") || trimmed.endsWith("T") || trimmed.endsWith("P"))) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Builds the university baseline timetable for conflict detection.
     * Returns academic entries (CLASS/TUTORIAL/LAB) from the workbook for the student's batch,
     * filtered to only subjects that are currently in the personal timetable or kept-subject list,
     * plus personal academic entries for subjects not in the batch's workbook.
     * 
     * @param wb Parsed workbook
     * @param studentBatch The student's batch name
     * @param studentSheet The sheet name where the batch was imported (null to auto-detect)
     * @param includedCodes Set of subject codes to include (from personal timetable + kept subjects)
     * @param excludedTargetCode The improvement subject being searched (excluded from baseline)
     * @param personalEntries The student's current personal timetable entries
     * @param renames Map of subject code renames for label formatting
     * @return List of baseline entries for conflict detection
     */
    public static List<WeeklyTimetableEntry> buildUniversityBaseline(
            ParsedWorkbook wb,
            String studentBatch,
            String studentSheet,
            Set<String> includedCodes,
            String excludedTargetCode,
            List<WeeklyTimetableEntry> personalEntries,
            Map<String, String> renames) {
        
        if (wb == null || studentBatch == null || includedCodes == null) {
            return List.of();
        }
        
        List<WeeklyTimetableEntry> baseline = new ArrayList<>();
        String targetCode = excludedTargetCode != null ? cleanSubjectCode(excludedTargetCode).toUpperCase() : null;
        
        // Normalize included codes
        Set<String> normalizedIncluded = new HashSet<>();
        for (String code : includedCodes) {
            if (code != null) {
                normalizedIncluded.add(code.trim().toUpperCase());
            }
        }
        
        // Collect all subject codes that appear in the workbook for this batch
        Set<String> batchWorkbookCodes = new HashSet<>();
        List<TimetableSlot> batchSlots = findBatchSlots(wb, studentBatch, studentSheet);
        for (TimetableSlot slot : batchSlots) {
            if (slot.isElective()) {
                for (ElectiveOption opt : slot.getElectiveOptions()) {
                    String baseCode = cleanSubjectCode(opt.getBaseCode()).toUpperCase();
                    batchWorkbookCodes.add(baseCode);
                }
            } else {
                String code = cleanSubjectCode(slot.getSubjectCode()).toUpperCase();
                batchWorkbookCodes.add(code);
            }
        }
        
        // Add workbook slots for subjects in includedCodes (excluding target)
        for (TimetableSlot slot : batchSlots) {
            if (slot.isElective()) {
                // For elective slots, include only the option whose base code is in includedCodes
                for (ElectiveOption opt : slot.getElectiveOptions()) {
                    String baseCode = cleanSubjectCode(opt.getBaseCode()).toUpperCase();
                    if (normalizedIncluded.contains(baseCode) && !baseCode.equals(targetCode)) {
                        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(
                            null, 0, slot.getDay(), opt.getStartTime(), opt.getEndTime(),
                            opt.getActivityType(), opt.getBaseCode() + " " + opt.getKind().toLowerCase(), opt.getRoom(), baseCode
                        );
                        // Apply renames if present
                        if (renames != null && renames.containsKey(baseCode)) {
                            String displayName = renames.get(baseCode);
                            entry.setLabel(displayName + " " + opt.getKind().toLowerCase());
                        }
                        baseline.add(entry);
                    }
                }
            } else {
                String code = cleanSubjectCode(slot.getSubjectCode()).toUpperCase();
                if (normalizedIncluded.contains(code) && !code.equals(targetCode)) {
                    String kind = "";
                    if (slot.getSuffix() == 'L') kind = "lecture";
                    else if (slot.getSuffix() == 'T') kind = "tutorial";
                    else if (slot.getSuffix() == 'P') kind = "practical";
                    
                    WeeklyTimetableEntry entry = new WeeklyTimetableEntry(
                        null, 0, slot.getDay(), slot.getStartTime(), slot.getEndTime(),
                        slot.getActivityType(), slot.getSubjectCode() + " " + kind, slot.getRoom(), code
                    );
                    // Apply renames if present
                    if (renames != null && renames.containsKey(code)) {
                        String displayName = renames.get(code);
                        entry.setLabel(displayName + " " + kind);
                    }
                    baseline.add(entry);
                }
            }
        }
        
        // Add personal academic entries (CLASS/TUTORIAL/LAB) for subjects NOT in the batch workbook
        // This covers improvement subjects already added and manually added classes
        if (personalEntries != null) {
            for (WeeklyTimetableEntry entry : personalEntries) {
                String type = entry.getActivityType();
                if (type != null && ("CLASS".equals(type.toUpperCase()) || 
                                    "TUTORIAL".equals(type.toUpperCase()) || 
                                    "LAB".equals(type.toUpperCase()))) {
                    String entryCode = entry.getSubjectCode();
                    if (entryCode != null) {
                        entryCode = cleanSubjectCode(entryCode).toUpperCase();
                    }
                    
                    // Include if: code not in batch workbook, or code is unknown
                    if (entryCode == null || !batchWorkbookCodes.contains(entryCode)) {
                        // Exclude target code
                        if (targetCode == null || !targetCode.equals(entryCode)) {
                            baseline.add(entry);
                        }
                    }
                }
            }
        }
        
        return baseline;
    }
    
    /**
     * Finds all slots for the student's batch, using the specified sheet or auto-detecting.
     */
    private static List<TimetableSlot> findBatchSlots(ParsedWorkbook wb, String studentBatch, String studentSheet) {
        if (studentSheet != null) {
            List<TimetableSlot> slots = wb.getSlots(studentSheet, studentBatch);
            if (!slots.isEmpty()) {
                return slots;
            }
        }
        
        // Auto-detect sheet
        List<String> sheets = wb.findSheetsForBatch(studentBatch);
        if (!sheets.isEmpty()) {
            return wb.getSlots(sheets.get(0), studentBatch);
        }
        
        return List.of();
    }

    public static class ConflictInfo {
        private final String day;
        private final LocalTime start;
        private final LocalTime end;
        private final String label;
        private final String type;

        public ConflictInfo(String day, LocalTime start, LocalTime end, String label, String type) {
            this.day = day;
            this.start = start;
            this.end = end;
            this.label = label;
            this.type = type;
        }

        public static ConflictInfo fromEntry(WeeklyTimetableEntry e) {
            String label = e.getLabel() != null && !e.getLabel().isBlank() ? e.getLabel() : e.getActivityType();
            return new ConflictInfo(e.getDayOfWeek(), e.getStartTime(), e.getEndTime(), label, e.getActivityType());
        }

        public String getDay() { return day; }
        public LocalTime getStart() { return start; }
        public LocalTime getEnd() { return end; }
        public String getLabel() { return label; }
        public String getType() { return type; }

        public String formatShort() {
            return (label != null ? label : type) + " " + shortDay(day) + " " + start + "-" + end;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ConflictInfo other)) return false;
            return Objects.equals(day, other.day)
                    && Objects.equals(start, other.start)
                    && Objects.equals(end, other.end)
                    && Objects.equals(label, other.label)
                    && Objects.equals(type, other.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, start, end, label, type);
        }
    }

    public static class ComponentOption {
        private final String sheetName;
        private final String batchName;
        private final char componentType; // 'L', 'T', 'P'
        private final String componentName; // "Lecture", "Tutorial", "Practical"
        private final List<ImprovementSlot> slots;
        private final String status;
        private final String statusCategory; // "fits", "needs adjustment", "conflicts"
        private final String slotsSummary;
        private final List<ConflictInfo> conflicts;
        private final int conflictCount;

        public ComponentOption(String sheetName, String batchName, char componentType, String componentName,
                               List<ImprovementSlot> slots, String status, String statusCategory, String slotsSummary) {
            this(sheetName, batchName, componentType, componentName, slots, status, statusCategory, slotsSummary, List.of());
        }

        public ComponentOption(String sheetName, String batchName, char componentType, String componentName,
                               List<ImprovementSlot> slots, String status, String statusCategory, String slotsSummary,
                               List<ConflictInfo> conflicts) {
            this.sheetName = sheetName;
            this.batchName = batchName;
            this.componentType = componentType;
            this.componentName = componentName;
            this.slots = slots != null ? slots : List.of();
            this.status = status;
            this.statusCategory = statusCategory;
            this.slotsSummary = slotsSummary;
            this.conflicts = conflicts != null ? List.copyOf(conflicts) : List.of();
            this.conflictCount = this.conflicts.size();
        }

        public String getSheetName() { return sheetName; }
        public String getBatchName() { return batchName; }
        public char getComponentType() { return componentType; }
        public String getComponentName() { return componentName; }
        public List<ImprovementSlot> getSlots() { return slots; }
        public String getStatus() { return status; }
        public String getStatusCategory() { return statusCategory; }
        public String getSlotsSummary() { return slotsSummary; }
        public List<ConflictInfo> getConflicts() { return conflicts; }
        public int getConflictCount() { return conflictCount; }
        public boolean isFits() { return "fits".equalsIgnoreCase(statusCategory); }
        public boolean isNeedsAdjustment() { return "needs adjustment".equalsIgnoreCase(statusCategory); }
        public boolean isConflicts() { return "conflicts".equalsIgnoreCase(statusCategory); }
        public int getSoftConflictCount() {
            int n = 0;
            for (ImprovementSlot s : slots) n += s.getSoftConflicts().size();
            return n;
        }
        public String getRoomsSummary() {
            LinkedHashSet<String> rooms = new LinkedHashSet<>();
            for (ImprovementSlot s : slots) {
                if (s.getRoom() != null && !s.getRoom().isBlank()) rooms.add(s.getRoom());
            }
            return String.join(", ", rooms);
        }
    }

    /**
     * One row in the Best Batch tab: a single batch scored across ticked components.
     * Ranking comparator (valid total order), documented key tuple:
     * {@code (practicalConflictCount > 0, practicalConflictCount,
     * lectureConflictCount * 2 + tutorialConflictCount, totalConflicts, softCount, sheet, batch)}.
     * Practical conflicts are strictly last. Among batches without a practical conflict,
     * lecture conflicts weigh twice a tutorial conflict so attending lecture is preferred.
     */
    public static class BestBatchOption {
        private final String sheetName;
        private final String batchName;
        private final ComponentOption lecture;
        private final ComponentOption tutorial;
        private final ComponentOption practical;
        private final boolean includeLecture;
        private final boolean includeTutorial;
        private final boolean includePractical;
        private final int lectureConflicts;
        private final int tutorialConflicts;
        private final int practicalConflicts;
        private final int totalConflicts;
        private final int softCount;
        private final String lectureStatus;
        private final String tutorialStatus;
        private final String practicalStatus;
        private final String rooms;

        public BestBatchOption(String sheetName, String batchName,
                               ComponentOption lecture, ComponentOption tutorial, ComponentOption practical,
                               boolean includeLecture, boolean includeTutorial, boolean includePractical) {
            this.sheetName = sheetName;
            this.batchName = batchName;
            this.lecture = lecture;
            this.tutorial = tutorial;
            this.practical = practical;
            this.includeLecture = includeLecture;
            this.includeTutorial = includeTutorial;
            this.includePractical = includePractical;
            this.lectureConflicts = includeLecture && lecture != null ? lecture.getConflictCount() : 0;
            this.tutorialConflicts = includeTutorial && tutorial != null ? tutorial.getConflictCount() : 0;
            this.practicalConflicts = includePractical && practical != null ? practical.getConflictCount() : 0;
            this.totalConflicts = this.lectureConflicts + this.tutorialConflicts + this.practicalConflicts;
            int soft = 0;
            if (includeLecture && lecture != null) soft += lecture.getSoftConflictCount();
            if (includeTutorial && tutorial != null) soft += tutorial.getSoftConflictCount();
            if (includePractical && practical != null) soft += practical.getSoftConflictCount();
            this.softCount = soft;
            this.lectureStatus = statusFor(includeLecture, lecture);
            this.tutorialStatus = statusFor(includeTutorial, tutorial);
            this.practicalStatus = statusFor(includePractical, practical);
            LinkedHashSet<String> roomSet = new LinkedHashSet<>();
            if (lecture != null && !lecture.getRoomsSummary().isBlank()) roomSet.add(lecture.getRoomsSummary());
            if (tutorial != null && !tutorial.getRoomsSummary().isBlank()) roomSet.add(tutorial.getRoomsSummary());
            if (practical != null && !practical.getRoomsSummary().isBlank()) roomSet.add(practical.getRoomsSummary());
            this.rooms = String.join("; ", roomSet);
        }

        private static String statusFor(boolean included, ComponentOption opt) {
            if (!included) return "not selected";
            if (opt == null) return "not offered";
            return opt.getStatusCategory();
        }

        public String getSheetName() { return sheetName; }
        public String getBatchName() { return batchName; }
        public ComponentOption getLecture() { return lecture; }
        public ComponentOption getTutorial() { return tutorial; }
        public ComponentOption getPractical() { return practical; }
        public int getLectureConflicts() { return lectureConflicts; }
        public int getTutorialConflicts() { return tutorialConflicts; }
        public int getPracticalConflicts() { return practicalConflicts; }
        public int getTotalConflicts() { return totalConflicts; }
        public int getSoftCount() { return softCount; }
        public String getLectureStatus() { return lectureStatus; }
        public String getTutorialStatus() { return tutorialStatus; }
        public String getPracticalStatus() { return practicalStatus; }
        public String getRooms() { return rooms; }
        public int getLectureWeight() { return lectureConflicts * 2 + tutorialConflicts; }
    }

    /**
     * Total order for Best Batch. Tuple:
     * (hasPracticalConflict, practicalConflictCount, lecture*2+tutorial, total, soft, sheet, batch).
     */
    public static final Comparator<BestBatchOption> BEST_BATCH_COMPARATOR = (a, b) -> {
        int c;
        c = Boolean.compare(a.getPracticalConflicts() > 0, b.getPracticalConflicts() > 0);
        if (c != 0) return c;
        c = Integer.compare(a.getPracticalConflicts(), b.getPracticalConflicts());
        if (c != 0) return c;
        c = Integer.compare(a.getLectureWeight(), b.getLectureWeight());
        if (c != 0) return c;
        c = Integer.compare(a.getTotalConflicts(), b.getTotalConflicts());
        if (c != 0) return c;
        c = Integer.compare(a.getSoftCount(), b.getSoftCount());
        if (c != 0) return c;
        c = a.getSheetName().compareToIgnoreCase(b.getSheetName());
        if (c != 0) return c;
        return a.getBatchName().compareToIgnoreCase(b.getBatchName());
    };

    public static List<BestBatchOption> rankBestBatches(
            Map<Character, List<ComponentOption>> options,
            boolean includeLecture, boolean includeTutorial, boolean includePractical) {
        Map<String, ComponentOption> lec = indexByBatch(options.getOrDefault('L', List.of()));
        Map<String, ComponentOption> tut = indexByBatch(options.getOrDefault('T', List.of()));
        Map<String, ComponentOption> prac = indexByBatch(options.getOrDefault('P', List.of()));
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(lec.keySet());
        keys.addAll(tut.keySet());
        keys.addAll(prac.keySet());
        List<BestBatchOption> rows = new ArrayList<>();
        for (String key : keys) {
            ComponentOption l = lec.get(key);
            ComponentOption t = tut.get(key);
            ComponentOption p = prac.get(key);
            ComponentOption any = l != null ? l : (t != null ? t : p);
            rows.add(new BestBatchOption(
                    any.getSheetName(), any.getBatchName(), l, t, p,
                    includeLecture, includeTutorial, includePractical));
        }
        rows.sort(BEST_BATCH_COMPARATOR);
        return rows;
    }

    private static Map<String, ComponentOption> indexByBatch(List<ComponentOption> list) {
        Map<String, ComponentOption> map = new LinkedHashMap<>();
        for (ComponentOption o : list) {
            map.put(o.getSheetName() + "\0" + o.getBatchName(), o);
        }
        return map;
    }

    public static class PlannedSlot {
        private final ComponentOption option;
        private final ImprovementSlot slot;
        private final boolean needsAdjustment;

        public PlannedSlot(ComponentOption option, ImprovementSlot slot, boolean needsAdjustment) {
            this.option = option;
            this.slot = slot;
            this.needsAdjustment = needsAdjustment;
        }

        public ComponentOption getOption() { return option; }
        public ImprovementSlot getSlot() { return slot; }
        public boolean isNeedsAdjustment() { return needsAdjustment; }
    }

    public static class SkippedSlot {
        private final ComponentOption option;
        private final ImprovementSlot slot;
        private final String reason;

        public SkippedSlot(ComponentOption option, ImprovementSlot slot, String reason) {
            this.option = option;
            this.slot = slot;
            this.reason = reason;
        }

        public ComponentOption getOption() { return option; }
        public ImprovementSlot getSlot() { return slot; }
        public String getReason() { return reason; }
    }

    public static class AddPlan {
        private final List<PlannedSlot> toAdd;
        private final List<SkippedSlot> skipped;

        public AddPlan(List<PlannedSlot> toAdd, List<SkippedSlot> skipped) {
            this.toAdd = toAdd != null ? toAdd : List.of();
            this.skipped = skipped != null ? skipped : List.of();
        }

        public List<PlannedSlot> getToAdd() { return toAdd; }
        public List<SkippedSlot> getSkipped() { return skipped; }
        public boolean isEmpty() { return toAdd.isEmpty(); }
        public boolean hasAdjustments() {
            return toAdd.stream().anyMatch(PlannedSlot::isNeedsAdjustment);
        }
    }

    /**
     * Keeps only slots that do not overlap an existing CLASS/TUTORIAL/LAB, and skips a later
     * component's slot if it overlaps an earlier selected component's accepted slot.
     */
    public static AddPlan planAdd(List<ComponentOption> chosen) {
        List<PlannedSlot> toAdd = new ArrayList<>();
        List<SkippedSlot> skipped = new ArrayList<>();
        List<ImprovementSlot> accepted = new ArrayList<>();
        if (chosen == null) return new AddPlan(toAdd, skipped);
        for (ComponentOption comp : chosen) {
            for (ImprovementSlot slot : comp.getSlots()) {
                if (!slot.getHardConflicts().isEmpty()) {
                    ConflictInfo first = ConflictInfo.fromEntry(slot.getHardConflicts().get(0));
                    skipped.add(new SkippedSlot(comp, slot,
                            "overlaps " + first.formatShort()));
                    continue;
                }
                ImprovementSlot earlier = findOverlapping(slot, accepted);
                if (earlier != null) {
                    skipped.add(new SkippedSlot(comp, slot,
                            "overlaps earlier selected component "
                                    + shortDay(earlier.getDay()) + " " + earlier.getStartTime() + "-" + earlier.getEndTime()));
                    continue;
                }
                toAdd.add(new PlannedSlot(comp, slot, !slot.getSoftConflicts().isEmpty()));
                accepted.add(slot);
            }
        }
        return new AddPlan(toAdd, skipped);
    }

    private static ImprovementSlot findOverlapping(ImprovementSlot slot, List<ImprovementSlot> accepted) {
        for (ImprovementSlot a : accepted) {
            if (strictOverlap(slot.getDay(), slot.getStartTime(), slot.getEndTime(),
                    a.getDay(), a.getStartTime(), a.getEndTime())) {
                return a;
            }
        }
        return null;
    }

    public static boolean strictOverlap(String dayA, LocalTime aStart, LocalTime aEnd,
                                        String dayB, LocalTime bStart, LocalTime bEnd) {
        if (dayA == null || dayB == null || !dayA.equalsIgnoreCase(dayB)) return false;
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) return false;
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    public static String shortDay(String day) {
        if (day == null || day.length() < 3) return day == null ? "" : day;
        return day.substring(0, 1) + day.substring(1, 3).toLowerCase();
    }

    public static int dayIndex(String day) {
        if (day == null) return 99;
        int i = DAY_ORDER.indexOf(day.toUpperCase());
        return i < 0 ? 99 : i;
    }

    public static class CrossComponentOverlap {
        private final ComponentOption optionA;
        private final ComponentOption optionB;
        private final ImprovementSlot slotA;
        private final ImprovementSlot slotB;
        private final String description;

        public CrossComponentOverlap(ComponentOption optionA, ComponentOption optionB,
                                     ImprovementSlot slotA, ImprovementSlot slotB,
                                     String description) {
            this.optionA = optionA;
            this.optionB = optionB;
            this.slotA = slotA;
            this.slotB = slotB;
            this.description = description;
        }

        public ComponentOption getOptionA() { return optionA; }
        public ComponentOption getOptionB() { return optionB; }
        public ImprovementSlot getSlotA() { return slotA; }
        public ImprovementSlot getSlotB() { return slotB; }
        public String getDescription() { return description; }
    }

    public static List<CrossComponentOverlap> findCrossComponentOverlaps(List<ComponentOption> chosen) {
        if (chosen == null || chosen.size() < 2) return List.of();
        List<CrossComponentOverlap> overlaps = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            for (int j = i + 1; j < chosen.size(); j++) {
                ComponentOption a = chosen.get(i);
                ComponentOption b = chosen.get(j);
                for (ImprovementSlot sA : a.getSlots()) {
                    for (ImprovementSlot sB : b.getSlots()) {
                        if (sA.getDay().equalsIgnoreCase(sB.getDay())) {
                            if (sA.getStartTime().isBefore(sB.getEndTime()) && sB.getStartTime().isBefore(sA.getEndTime())) {
                                String desc = a.getComponentName() + " (Batch " + a.getBatchName() + ", " + sA.getStartTime() + "-" + sA.getEndTime() + ") overlaps "
                                        + b.getComponentName() + " (Batch " + b.getBatchName() + ", " + sB.getStartTime() + "-" + sB.getEndTime() + ") on " + sA.getDay();
                                overlaps.add(new CrossComponentOverlap(a, b, sA, sB, desc));
                            }
                        }
                    }
                }
            }
        }
        return overlaps;
    }

    private static String formatSlotsSummary(List<ImprovementSlot> slots) {
        if (slots == null || slots.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) sb.append("; ");
            ImprovementSlot s = slots.get(i);
            String d = s.getDay();
            String shortDay = d.length() >= 3 ? d.substring(0, 1) + d.substring(1, 3).toLowerCase() : d;
            sb.append(shortDay).append(" ").append(s.getStartTime()).append("-").append(s.getEndTime());
        }
        return sb.toString();
    }

    private static int statusRank(String category) {
        if ("fits".equalsIgnoreCase(category)) return 0;
        if ("needs adjustment".equalsIgnoreCase(category)) return 1;
        return 2; // conflicts
    }

    public static final Comparator<ComponentOption> COMPONENT_OPTION_COMPARATOR = (a, b) -> {
        int rA = statusRank(a.getStatusCategory());
        int rB = statusRank(b.getStatusCategory());
        if (rA != rB) return Integer.compare(rA, rB);
        int sComp = a.getSheetName().compareToIgnoreCase(b.getSheetName());
        if (sComp != 0) return sComp;
        return a.getBatchName().compareToIgnoreCase(b.getBatchName());
    };

    private static ComponentOption buildComponentOption(
            String sheet, String batch, char compType, String compName, List<ImprovementSlot> slots) {
        int softCount = 0;
        WeeklyTimetableEntry firstSoftEntry = null;
        List<ConflictInfo> conflicts = new ArrayList<>();

        for (ImprovementSlot s : slots) {
            for (WeeklyTimetableEntry h : s.getHardConflicts()) {
                ConflictInfo ci = ConflictInfo.fromEntry(h);
                if (!conflicts.contains(ci)) conflicts.add(ci);
            }
            if (!s.getSoftConflicts().isEmpty()) {
                softCount += s.getSoftConflicts().size();
                if (firstSoftEntry == null) {
                    firstSoftEntry = s.getSoftConflicts().get(0);
                }
            }
        }

        conflicts.sort(Comparator
                .comparingInt((ConflictInfo c) -> dayIndex(c.getDay()))
                .thenComparing(ConflictInfo::getStart, Comparator.nullsLast(Comparator.naturalOrder())));

        String statusCategory;
        String status;
        if (!conflicts.isEmpty()) {
            statusCategory = "conflicts";
            StringBuilder sb = new StringBuilder("Conflicts (").append(conflicts.size()).append("): ");
            for (int i = 0; i < conflicts.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(conflicts.get(i).formatShort());
            }
            status = sb.toString();
        } else if (softCount > 0) {
            statusCategory = "needs adjustment";
            String label = firstSoftEntry.getLabel() != null && !firstSoftEntry.getLabel().isBlank()
                    ? firstSoftEntry.getLabel() : firstSoftEntry.getActivityType();
            status = "Needs adjustment (overlaps " + label + ")";
        } else {
            statusCategory = "fits";
            status = "Fits";
        }

        String slotsSummary = formatSlotsSummary(slots);
        return new ComponentOption(sheet, batch, compType, compName, slots, status, statusCategory, slotsSummary, conflicts);
    }

    public Map<Character, List<ComponentOption>> findComponentOptions(
            ParsedWorkbook workbook,
            String subjectBaseCode,
            boolean includeLecture,
            boolean includeTutorial,
            boolean includePractical,
            List<WeeklyTimetableEntry> universityBaseline,
            List<WeeklyTimetableEntry> personalSoftEntries,
            String studentBatch) {

        if (workbook == null || subjectBaseCode == null || subjectBaseCode.trim().isEmpty()) {
            return Map.of('L', List.of(), 'T', List.of(), 'P', List.of());
        }

        String targetCode = cleanSubjectCode(subjectBaseCode);

        boolean subjectFoundAnywhere = false;
        boolean onlyInStudentBatch = true;

        for (Map.Entry<String, Map<String, List<TimetableSlot>>> sheetEntry : workbook.getSheetBatchSlots().entrySet()) {
            for (Map.Entry<String, List<TimetableSlot>> batchEntry : sheetEntry.getValue().entrySet()) {
                String batch = batchEntry.getKey();
                for (TimetableSlot s : batchEntry.getValue()) {
                    if (s.isElective()) {
                        for (ElectiveOption opt : s.getElectiveOptions()) {
                            if (targetCode.equalsIgnoreCase(opt.getBaseCode())) {
                                subjectFoundAnywhere = true;
                                if (studentBatch == null || !batch.equalsIgnoreCase(studentBatch)) {
                                    onlyInStudentBatch = false;
                                }
                            }
                        }
                    } else if (targetCode.equalsIgnoreCase(s.getSubjectCode())) {
                        subjectFoundAnywhere = true;
                        if (studentBatch == null || !batch.equalsIgnoreCase(studentBatch)) {
                            onlyInStudentBatch = false;
                        }
                    }
                }
            }
        }

        if (!subjectFoundAnywhere) {
            throw new NoSuchElementException("Subject code '" + targetCode + "' was not found anywhere in the workbook.");
        }

        List<ComponentOption> lecOptions = new ArrayList<>();
        List<ComponentOption> tutOptions = new ArrayList<>();
        List<ComponentOption> pracOptions = new ArrayList<>();

        for (Map.Entry<String, Map<String, List<TimetableSlot>>> sheetEntry : workbook.getSheetBatchSlots().entrySet()) {
            String sheet = sheetEntry.getKey();
            for (Map.Entry<String, List<TimetableSlot>> batchEntry : sheetEntry.getValue().entrySet()) {
                String batch = batchEntry.getKey();

                if (studentBatch != null && batch.equalsIgnoreCase(studentBatch) && !onlyInStudentBatch) {
                    continue;
                }

                List<TimetableSlot> slots = batchEntry.getValue();
                List<ImprovementSlot> lecSlots = new ArrayList<>();
                List<ImprovementSlot> tutSlots = new ArrayList<>();
                List<ImprovementSlot> pracSlots = new ArrayList<>();

                for (TimetableSlot s : slots) {
                    if (s.isElective()) {
                        for (ElectiveOption opt : s.getElectiveOptions()) {
                            if (targetCode.equalsIgnoreCase(opt.getBaseCode())) {
                                ImprovementSlot is = new ImprovementSlot(
                                        s.getDay(), opt.getStartTime(), opt.getEndTime(),
                                        opt.getBaseCode(), opt.getSuffix(), opt.getActivityType(), opt.getKind(), opt.getRoom()
                                );
                                checkHardConflicts(is, universityBaseline);
                                checkSoftConflicts(is, personalSoftEntries);
                                if (opt.getSuffix() == 'L') lecSlots.add(is);
                                else if (opt.getSuffix() == 'T') tutSlots.add(is);
                                else if (opt.getSuffix() == 'P') pracSlots.add(is);
                            }
                        }
                    } else if (targetCode.equalsIgnoreCase(s.getSubjectCode())) {
                        ImprovementSlot is = new ImprovementSlot(
                                s.getDay(), s.getStartTime(), s.getEndTime(),
                                s.getSubjectCode(), s.getSuffix(), s.getActivityType(), s.getKind(), s.getRoom()
                        );
                        checkHardConflicts(is, universityBaseline);
                        checkSoftConflicts(is, personalSoftEntries);
                        if (s.getSuffix() == 'L') lecSlots.add(is);
                        else if (s.getSuffix() == 'T') tutSlots.add(is);
                        else if (s.getSuffix() == 'P') pracSlots.add(is);
                    }
                }

                if (includeLecture && !lecSlots.isEmpty()) {
                    lecOptions.add(buildComponentOption(sheet, batch, 'L', "Lecture", lecSlots));
                }
                if (includeTutorial && !tutSlots.isEmpty()) {
                    tutOptions.add(buildComponentOption(sheet, batch, 'T', "Tutorial", tutSlots));
                }
                if (includePractical && !pracSlots.isEmpty()) {
                    pracOptions.add(buildComponentOption(sheet, batch, 'P', "Practical", pracSlots));
                }
            }
        }

        lecOptions.sort(COMPONENT_OPTION_COMPARATOR);
        tutOptions.sort(COMPONENT_OPTION_COMPARATOR);
        pracOptions.sort(COMPONENT_OPTION_COMPARATOR);

        Map<Character, List<ComponentOption>> result = new LinkedHashMap<>();
        result.put('L', lecOptions);
        result.put('T', tutOptions);
        result.put('P', pracOptions);
        return result;
    }
}
