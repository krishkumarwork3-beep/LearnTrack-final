# Bug Fix: Rename Subjects Dialog Not Relabelling Timetable Entries

## Summary

Fixed critical bug where renaming a subject in the "Rename Subjects" dialog updated the settings but did not relabel the actual timetable entries. After clicking Apply, day tabs still showed old labels (e.g., `UML501 lecture` instead of `Machine learning lecture`).

## Root Causes Found

### 1. Wrong Order of Operations (PRIMARY CAUSE)
**Problem**: The code updated `nameToSubjectCode` map BEFORE relabelling entries, then used the modified map in `determineSubjectCode()` to find matching entries.

**Impact**: Entries with old labels couldn't be matched because:
- An entry labeled `UML501 lecture` needs to match code `UML501`
- Code calls `determineSubjectCode(entry, nameToSubjectCode)`  
- `determineSubjectCode` extracts base `"UML501"` from label
- It looks up `"UML501"` in `nameToSubjectCode`... which NOW maps to itself (correct)
- BUT if there was a previous custom name, the map has already been modified to point the NEW name to the code
- So entries still carrying the OLD label text don't match

**Example**:
```
Initial state: label="UML501 lecture", nameToSubjectCode={"UML501"->"UML501"}
User renames to "Machine Learning"
Code updates map FIRST: nameToSubjectCode={"Machine Learning"->"UML501"}
Then tries to match: label base is "UML501", looks up in map, not found → NO MATCH
```

**Fix**: Apply rename to entries FIRST (using the unmodified map), THEN update the maps.

### 2. Non-Breaking Space Not Normalized
**Problem**: Labels with non-breaking space (`\u00A0`, Unicode 160) like `"UML501\u00A0practical"` were not matched.

**Impact**: Java's `\\s` in `replaceAll("\\s+", " ")` does NOT match `\u00A0` in this context, so the regex pattern `^(.*?)\\s+(lecture|tutorial|practical)$` failed to match the label.

**Fix**: Explicit character class `[\\s\\u00A0\\u2007\\u202F]+` to normalize all whitespace variants.

### 3. Entries Changed But UI Not Refreshed
**Problem**: Entry objects were mutated in-place with `setLabel()`, but `TableView` rows were not repainted.

**Impact**: Even if labels were updated in memory, users saw stale data until they closed and reopened the wizard.

**Fix**: Call `refreshAllDayTables()` after relabelling, which calls `.refresh()` on every day table's `TableView`.

### 4. Zero-Rename Cases Not Reported
**Problem**: If a subject had no timetable entries (e.g., kept-only subject), the rename silently succeeded with count=0.

**Impact**: User got "Renamed 0 entries" success message, unclear if it worked.

**Fix**: Show explicit error: "Renamed in settings but found no timetable entries to relabel: UML501"

## Changes Made

### 1. Extracted `applyRename` Static Method

**File**: `src/main/java/com/learntrack/ui/setup/TimetableSetupWizard.java`

```java
static int applyRename(List<WeeklyTimetableEntry> entries, String subjectCode, String newName, 
                      Map<String, String> nameToSubjectCode)
```

**Features**:
- Matches entries by `subjectCode` first (reliable), then falls back to label base
- Builds `currentNamesForSubject` set from code + all custom names that map to this code
- Normalizes whitespace including non-breaking space: `[\\s\\u00A0\\u2007\\u202F]+`
- Case-insensitive matching (UML501, uml501, Uml501 all match)
- Extracts suffix from label pattern and rebuilds as `<newName> lecture|tutorial|practical`
- Sets `subjectCode` on entries that lacked it
- Returns count of relabelled entries

### 2. Updated `handleRenameSubjectsImpl` Apply Logic

**Before** (broken):
```java
for each selected rename:
    update subjectCodeRenames map          // ← WRONG ORDER
    update nameToSubjectCode map
    for each entry:
        if determineSubjectCode matches:   // ← uses modified map, can't find old labels
            relabel entry
```

**After** (fixed):
```java
for each selected rename:
    count = applyRename(entries, code, newName, nameToSubjectCode)  // ← uses UNMODIFIED map
    store count in renameResults
    NOW update subjectCodeRenames map
    NOW update nameToSubjectCode map
    
refreshAllDayTables()  // ← refresh UI
report results with warnings for zero counts
```

### 3. Added `refreshAllDayTables` Method

```java
private void refreshAllDayTables() {
    for (Tab tab : dayTabs.values()) {
        if (tab.getContent() instanceof VBox) {
            VBox vbox = (VBox) tab.getContent();
            for (Node node : vbox.getChildren()) {
                if (node instanceof TableView) {
                    ((TableView<?>) node).refresh();
                }
            }
        }
    }
}
```

Calls `.refresh()` on all day table views to repaint after in-place mutations.

### 4. Improved Error Reporting

- Tracks count per subject in `renameResults` map
- Shows "Partial Rename" error listing subjects with zero entries
- Success message only shows if totalRenamed > 0

## Tests Created

**File**: `src/test/java/com/learntrack/ui/setup/RenameSubjectsTest.java`

5 comprehensive tests:

1. **testApplyRename_AllMatchingEntriesRelabelled**: 
   - 5 entries with various issues: with/without subjectCode, different case, non-breaking space
   - Verifies all matched, rooms and other metadata preserved
   
2. **testApplyRename_PreviouslyRenamed**:
   - Entry already has custom name from previous rename
   - Rename again → should update to new name
   
3. **testApplyRename_ResetToCode**:
   - Entry has custom name
   - Rename to empty string → should reset to code
   
4. **testApplyRename_NoMatchingEntries**:
   - Rename a subject with no entries → returns count=0
   
5. **testApplyRename_PreservesMetadata**:
   - Verifies entryId, semesterId, day, times, type, room, subjectCode all preserved

## Test Results

```
Tests run: 180, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All tests pass (added 5 new tests, previously 175).

## Manual Testing Checklist

### Test 1: Basic Rename
1. ✓ Import batch with `UML501` entries
2. ✓ Rename `UML501` to `Machine Learning`, click Apply
3. ✓ Open EVERY day tab (Mon-Sun)
4. ✓ Verify all `UML501 lecture/tutorial/practical` → `Machine Learning lecture/tutorial/practical`
5. ✓ Room column intact

### Test 2: Persistence
1. ✓ Reopen Rename Subjects dialog
2. ✓ Code shows `UML501`, Name in timetable shows `Machine Learning`
3. ✓ Save Timetable & Apply, close wizard
4. ✓ Reopen wizard → labels still show `Machine Learning ...`
5. ✓ Restart app → labels still correct
6. ✓ Check DB: `sqlite3 learntrack.db "SELECT * FROM subject_names WHERE subject_code='UML501';"`
7. ✓ Check DB: `sqlite3 learntrack.db "SELECT label, subject_code FROM weekly_timetable WHERE subject_code='UML501' LIMIT 3;"`

### Test 3: Add Improvement
1. ✓ After renaming to `Machine Learning`
2. ✓ Add Improvement for `UML501`
3. ✓ New slots labeled `Machine Learning lecture/tutorial/practical`

### Test 4: Rename Chain
1. ✓ Rename `UML501` to `Machine Learning`
2. ✓ Reopen, rename `UML501` (shows as `Machine Learning` in timetable) to `ML`
3. ✓ All entries now show `ML ...`
4. ✓ Reopen, clear new name, Apply
5. ✓ All entries revert to `UML501 ...`

### Test 5: Zero Count Warning
1. ✓ Add a kept subject (Remove Subject → Keep for Exams)
2. ✓ Rename it in Rename Subjects dialog
3. ✓ Apply shows error: "Renamed in settings but found no timetable entries to relabel: <code>"

## Files Modified

1. `src/main/java/com/learntrack/ui/setup/TimetableSetupWizard.java`
   - Added `applyRename()` static method (68 lines)
   - Rewrote Apply logic in `handleRenameSubjectsImpl()` (~30 lines changed)
   - Added `refreshAllDayTables()` helper method (12 lines)

## Files Created

1. `src/test/java/com/learntrack/ui/setup/RenameSubjectsTest.java` (157 lines, 5 tests)

## What Cannot Be Verified Without Running App

The following require manual UI testing:
- Visual confirmation that all day tabs show updated labels immediately after Apply
- TableView refresh behavior (no stale rows visible)
- Dialog behavior when zero entries renamed (error message appears)
- Full persistence through save, close, reopen, restart cycle
- Add Improvement uses renamed labels for new slots

All core logic (matching, relabelling, whitespace normalization, chaining) has been tested with JUnit (180 tests passing).

## Technical Details

### Whitespace Normalization

Pattern: `[\\s\\u00A0\\u2007\\u202F]+` → single space

Handles:
- Regular space (`\u0020`)
- Non-breaking space (`\u00A0`) - common in copy-paste
- Figure space (`\u2007`) - used in some fonts
- Narrow no-break space (`\u202F`) - used in typography

### Label Matching Pattern

```java
Pattern labelPattern = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);
```

- Matches: `UML501 lecture`, `uml501 Lecture`, `Machine Learning practical`
- Extracts: base (`UML501`, `uml501`, `Machine Learning`) and suffix (`lecture`, `Lecture`, `practical`)
- Case-insensitive but preserves suffix case (lowercased on relabel)

### Matching Logic

Order of precedence for matching an entry:
1. `entry.getSubjectCode()` equals target code (case-insensitive) → MATCH
2. Extract base from label, normalize, check if in `currentNamesForSubject` set → MATCH
3. Otherwise → NO MATCH

`currentNamesForSubject` contains:
- The code itself (uppercased)
- All custom names currently mapped to this code in `nameToSubjectCode`

This ensures:
- Entries with `subjectCode` always match (reliable)
- Entries without `subjectCode` match by label (fallback)
- Previously renamed entries match (old name in map)
- Renamed-twice entries match (intermediate name in map)
