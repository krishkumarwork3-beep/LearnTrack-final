# Add Improvement Subject - University Baseline Conflict Detection

## Summary

Fixed the Add Improvement feature to check conflicts against the university timetable of the student's batch (from Excel) rather than their personal timetable. This ensures that improvement subjects are validated against the actual class schedule, not personal edits.

## Problem

Previously, `Add Improvement Subject` checked conflicts against `entriesList` (the personal timetable), which could have:
- Subjects removed
- Times changed by the user
- Custom entries added

This gave incorrect results - an improvement slot might show as "fits" when it actually conflicts with a university class.

## Solution

### 1. Build University Baseline

New method: `ImprovementService.buildUniversityBaseline()`

Creates a baseline list of academic entries from the Excel workbook for the student's batch, including:
- Slots from the workbook for subjects in the "included codes" set (S)
- **S** = subject codes in personal timetable + kept subjects (from `kept_subjects` table)
- For electives: only the option whose base code is in S
- Excludes the improvement subject being searched (target code)
- Personal academic entries (CLASS/TUTORIAL/LAB) for subjects NOT in the batch workbook (covers previously added improvement subjects and manual entries)
- Ignores personal entries of batch workbook subjects (Excel is source of truth)

### 2. Split Conflict Detection

- **Hard conflicts**: Overlaps with baseline entries (academic only)
- **Soft conflicts**: Overlaps with personal MEAL/SLEEP/OTHER entries

Methods:
- `checkHardConflicts(slot, universityBaseline)` - checks academic overlaps
- `checkSoftConflicts(slot, personalSoftEntries)` - checks meal/sleep/other overlaps

### 3. Updated Method Signatures

**ImprovementService:**
- `findComponentOptions(wb, code, incL, incT, incP, universityBaseline, personalSoftEntries, studentBatch)`
- `findRecommendations(wb, code, incL, incT, incP, universityBaseline, personalSoftEntries, studentBatch)`

**TimetableSetupWizard:**
- Builds `includedCodes` set on FX thread from `entriesList` + `KeptSubjectDAO`
- Builds `universityBaseline` in background Task after workbook loaded
- Extracts `personalSoftEntries` (MEAL/SLEEP/OTHER only)
- Passes both lists to service

### 4. UI Changes

**Results dialog header:**
- Added baseline summary: "Conflicts are checked against the university timetable of batch 3Q22: 9 subjects (UCS420, UCS421, ... + kept: UTD004) and 2 improvement slots."
- Full list in tooltip with `(kept)` markers

**Status text:**
- Rows with only meal/other overlaps: `Fits (overlaps Breakfast, you will be asked before replacing)` - amber style, Conflicts=0
- Green rows tooltip: "No clash with your batch's university timetable"

**Confirmation dialog:**
- Wording: "Replace these personal entries with the improvement slots?"
- Buttons: "Yes, replace" / "No"

## Files Modified

1. **src/main/java/com/learntrack/service/ImprovementService.java**
   - Added `buildUniversityBaseline()` method
   - Added `findBatchSlots()` helper
   - Split `checkSlotConflicts()` into `checkHardConflicts()` and `checkSoftConflicts()`
   - Updated `findComponentOptions()` signature and implementation
   - Updated `findRecommendations()` signature and implementation

2. **src/main/java/com/learntrack/ui/setup/TimetableSetupWizard.java**
   - Updated `handleAddImprovementSubject()` to build baseline
   - Updated search Task to compute baseline and split entries
   - Updated `openImprovementResultsDialog()` header text
   - Updated `confirmAndApplyComponentOptionAdjustment()` wording

## Technical Details

### Baseline Construction Logic

```java
// 1. Get all workbook codes for student's batch
Set<String> batchWorkbookCodes = {codes from workbook slots};

// 2. For each workbook slot:
if (slot.code in includedCodes && slot.code != targetCode) {
    if (elective) {
        add only the option whose baseCode is in includedCodes;
    } else {
        add slot;
    }
}

// 3. For each personal entry (CLASS/TUTORIAL/LAB):
if (entry.code NOT in batchWorkbookCodes && entry.code != targetCode) {
    add entry;  // Improvement subjects already added
}
```

### Conflict Detection

**Hard** (counts in Conflicts column):
- Strict overlap with baseline academic entry
- Shown in red status text

**Soft** (doesn't count, only for replacement dialog):
- Overlap with personal MEAL/SLEEP/OTHER
- Shown in amber "Fits (overlaps ...)" status

### Subject Code Determination

Uses existing `TimetableSetupWizard.determineSubjectCode()`:
1. `entry.getSubjectCode()` if not null
2. Label base if matches `^[A-Za-z]{2,5}\d{2,4}[A-Za-z]?$`
3. `nameToSubjectCode` lookup
4. null (unknown)

## Testing Required

### Unit Tests (JUnit 5)
1. Baseline contains exactly 3Q22 slots for codes in S
2. Removed subject's slots disappear from baseline
3. Kept subject's slots appear in baseline
4. Target code excluded from baseline
5. Elective: only chosen option included
6. Personal entry with changed time doesn't create conflict
7. Previously added improvement entry causes conflict
8. MEAL overlap: Conflicts=0, status "Fits (overlaps...)"
9. Best Batch ordering unchanged
10. Back-to-back slots don't conflict

### Manual Testing (3Q22 batch)
1. Search UPH013: header shows baseline summary
2. Remove subject: its slots no longer conflict
3. Keep subject: its slots still conflict
4. Move personal class: conflicts unchanged
5. MEAL overlap: asks to replace, Yes/No work correctly
6. Add first improvement, search second: first causes conflicts

## Status

Implementation complete. Ready for testing.
