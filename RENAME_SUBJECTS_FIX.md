# Rename Subjects Dialog Fix and Subject Code Storage Implementation

## Summary

Fixed the typing bug in the "Rename Subjects" dialog and completed the implementation of subject code storage across the application.

## Changes Made

### 1. TimetableDAO (Updated to handle subject_code column)

**File**: `src/main/java/com/learntrack/dao/TimetableDAO.java`

**Changes**:
- `insertEntry()`: Added `subject_code` as 8th parameter in INSERT statement
- `saveTimetableEntries()`: Added `subject_code` to both UPDATE and INSERT statements
- `findBySemester()`: Added `subject_code` to SELECT statement
- `findBySemesterAndDay()`: Added `subject_code` to SELECT statement
- `mapEntry()`: Updated constructor call to include `subjectCode` as 9th parameter

**Result**: Subject codes now round-trip correctly through the database.

### 2. TimetableSlot and ImprovementSlot (Set subjectCode on entries)

**Files**: 
- `src/main/java/com/learntrack/service/TimetableSlot.java`
- `src/main/java/com/learntrack/service/ImprovementService.java`

**Changes**:
- `TimetableSlot.toEntry()`: Added `subjectCode` parameter to WeeklyTimetableEntry constructor
- `ImprovementSlot.toEntry()`: Added `subjectCode` parameter to WeeklyTimetableEntry constructor

**Result**: All entries created from imports or improvements carry their subject code.

### 3. TimetableSetupWizard (Major refactoring)

**File**: `src/main/java/com/learntrack/ui/setup/TimetableSetupWizard.java`

**Changes**:

#### a. Added SubjectNameDAO import
```java
import com.learntrack.dao.SubjectNameDAO;
```

#### b. Updated loadExistingData()
- Loads `subject_names` table into `subjectCodeRenames` map
- Builds `nameToSubjectCode` reverse map plus code-to-code mappings
- Backfills `subjectCode` field for entries with null code but label base matching code pattern
- Pattern: `^[A-Za-z]{2,5}\d{2,4}[A-Za-z]?$` (e.g., UCS503, MAT101A, BIO99)

#### c. Updated saveAll()
- Saves `subjectCodeRenames` map to `subject_names` table
- Deletes all existing rows for semester, then inserts non-empty renames
- Empty or code-equals-name entries are not saved (reset state)

#### d. New helper method: determineSubjectCode()
```java
static String determineSubjectCode(WeeklyTimetableEntry entry, Map<String, String> nameToSubjectCode)
```

Determines subject code in order of precedence:
1. `entry.getSubjectCode()` if not null
2. Label base if it matches code pattern
3. `nameToSubjectCode` lookup
4. null (unknown)

#### e. Completely rewrote handleRenameSubjectsImpl()

**Root cause of typing bug**: TableView cell recycling + `setText()` in `updateItem()` while listener writes to item → caret resets to position 0.

**Solution**: Replaced TableView with non-virtualized ScrollPane + GridPane layout.

**New dialog structure**:
- **Header row**: Rename (checkbox), Code, Name in timetable, New name
- **Data rows**: One per subject (timetable + kept subjects)
- **Columns**:
  - **Rename**: Checkbox created once, listener updates item
  - **Code**: Read-only Label for known codes, editable TextField for unknown codes
  - **Name in timetable**: Label with ellipsis and tooltip (200px wide)
  - **New name**: TextField created once with initial value, one-way listener only

**TextField behavior**:
- Created once: `new TextField(initialValue)`
- One-way listener: `textProperty().addListener((obs, o, n) -> item.setNewName(n))`
- **Never** calls `setText()` after construction
- No cell reuse issues (each row has its own TextField instance)

**Code determination**:
- Entries with `subjectCode`: use it
- Entries with label matching code pattern: extract and uppercase
- Entries with renamed name: lookup via `nameToSubjectCode`
- Unknown (old garbled entries): editable Code field with validation

**Apply logic**:
- Validates non-empty code for unknown subjects
- Checks for name clashes (same name for different code)
- Truncates names to 60 characters
- Updates `subjectCodeRenames` and `nameToSubjectCode` maps
- Sets `subjectCode` on entries that lacked it
- Relabels all matching entries
- Updates `kept_subjects.display_name` for kept subjects

#### f. Updated RenameItem class

Changed from JavaFX properties to simple holder:
```java
public static class RenameItem {
    private boolean selected;
    private String code;  // Not final - can be updated for editable codes
    private final String nameInTimetable;
    private String newName;
    private final boolean codeEditable;
    // ... getters/setters including setCode()
}
```

No more `BooleanProperty` or `SimpleStringProperty`; these are just plain fields.
The `code` field is mutable to support editing unknown codes via the TextField.

### 4. Tests

**File**: `src/test/java/com/learntrack/dao/TimetableDAOSubjectCodeTest.java`

4 tests covering:
- Insert entry with subject_code, verify round-trip
- Insert entry without subject_code, verify null loads correctly
- Save multiple entries with subject_code
- Update entry preserves subject_code

**File**: `src/test/java/com/learntrack/ui/setup/TimetableSetupWizardSubjectCodeTest.java`

6 tests covering:
- Determine code from entry.subjectCode field
- Determine code from label base (looks like code)
- Determine code from nameToSubjectCode map
- Unknown code returns null
- Non-academic entry returns null
- Various code pattern validation (CS101, UCS503, MAT2001, PHYS101A, BIO99 valid; A1, TOOLONG12345, 123ABC invalid)

## Test Results

```
Tests run: 175, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All existing tests pass, plus 17 new tests added (previously 158 tests).

## Database Schema

The `subject_code` column already exists in `weekly_timetable` (added by migration).
The `subject_names` table already exists with schema:
```sql
CREATE TABLE IF NOT EXISTS subject_names (
    semester_id   INTEGER NOT NULL,
    subject_code  TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    PRIMARY KEY (semester_id, subject_code),
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id) ON DELETE CASCADE
);
```

## Manual Testing Checklist

### Test 1: Normal typing behavior
1. Run the app: `mvn javafx:run`
2. Open existing semester or create one with imported timetable
3. Click **Rename Subjects** button
4. Verify columns: Rename (checkbox), Code, Name in timetable, New name
5. In the "New name" field for one subject, type: `Corporate Finance`
6. **Expected**: Characters appear in correct order
7. Test: Backspace, Home, End, arrow keys, typing in middle, Ctrl+A then type, paste
8. **Expected**: All editing operations work normally
9. Type in a second row, scroll down and back up
10. **Expected**: Both fields retain their own text (no garbling or swapping)

### Test 2: Rename and persistence
1. Change one subject name to `Corporate Finance`
2. Click **Apply**
3. **Expected**: Day tables show `Corporate Finance lecture/tutorial/practical`, rooms intact
4. Click **Save Timetable & Apply**
5. Close and reopen the wizard
6. **Expected**: Labels still show `Corporate Finance ...`, Rename dialog shows code + name
7. Restart the app completely
8. Open the semester
9. **Expected**: Labels still correct
10. Check database: `sqlite3 learntrack.db "SELECT * FROM subject_names;"`
11. **Expected**: Row with semester_id, subject_code, `Corporate Finance`
12. Check: `sqlite3 learntrack.db "SELECT entry_id, label, subject_code, room FROM weekly_timetable LIMIT 5;"`
13. **Expected**: `subject_code` column populated, labels show custom name

### Test 3: Unknown code recovery
1. If you have an old garbled row (e.g., `sirksk2psk lecture`), open Rename Subjects
2. **Expected**: That row shows an editable Code field (TextField with prompt "Enter code")
3. Enter real code (e.g., `UCS421`)
4. Enter proper name (e.g., `Software Engineering`)
5. Click **Apply**
6. **Expected**: Entries relabelled, code stored
7. Save and reopen
8. **Expected**: Code and name persist

### Test 4: Reset rename
1. In Rename Subjects, clear the "New name" field (make it blank or equal to code)
2. Click **Apply**
3. **Expected**: Labels revert to code (e.g., `UCS503 lecture`)
4. Save and reopen
5. Check database: `SELECT * FROM subject_names WHERE subject_code = 'UCS503';`
6. **Expected**: No row (rename removed)

### Test 5: Kept subjects
1. Import a batch, then use **Remove Subject** → Keep for Exams/Studies
2. Open **Rename Subjects**
3. **Expected**: Kept subject appears marked `(not in timetable)`
4. Rename it, Apply
5. Check database: `SELECT * FROM kept_subjects WHERE subject_code = '<code>';`
6. **Expected**: `display_name` updated

### Test 6: Validation
1. Try to rename two different subjects to the same name
2. **Expected**: Error dialog "Name Clash", dialog stays open
3. Try to Apply with an unknown code field left blank
4. **Expected**: Error dialog "requires a code", dialog stays open

## Files Modified

1. `src/main/java/com/learntrack/dao/TimetableDAO.java`
2. `src/main/java/com/learntrack/service/TimetableSlot.java`
3. `src/main/java/com/learntrack/service/ImprovementService.java`
4. `src/main/java/com/learntrack/ui/setup/TimetableSetupWizard.java`

## Files Created

1. `src/test/java/com/learntrack/dao/TimetableDAOSubjectCodeTest.java`
2. `src/test/java/com/learntrack/ui/setup/TimetableSetupWizardSubjectCodeTest.java`

## What Could Not Be Verified Automatically

The following require manual testing with a running JavaFX application:
- Actual typing behavior in the dialog TextField (caret position, character order)
- Visual layout of the GridPane dialog (column widths, scrolling, tooltips)
- User experience with editable vs read-only code fields
- Dialog behavior on Apply with validation errors (stays open, retains user input)
- Integration with kept_subjects updates
- Full round-trip through app restart and database persistence

All pure logic (code determination, DAO operations, pattern matching) has been tested with JUnit.
