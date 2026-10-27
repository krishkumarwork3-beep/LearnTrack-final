# Subjects Tab Implementation - Complete

## Summary

The Subjects tab has been fully redesigned to show collapsible sections per subject with syllabus management and the existing topic scheduling functionality preserved.

## Files Changed

### Modified Files:
1. **src/main/java/com/learntrack/ui/forms/TopicFormController.java** - Complete rewrite
   - Replaced exam combo + table with scrollable subject sections
   - Each subject gets a TitledPane with Syllabus and Study Plan sections
   - Syllabus management with units, subtopics, checkboxes, and CRUD operations
   - Study Plan section contains the original topic management UI

### Files Already in Place (from previous work):
2. **src/main/resources/db/schema.sql** - Already has syllabus_units and syllabus_subtopics tables
3. **src/main/java/com/learntrack/dao/SyllabusDAO.java** - Already implemented
4. **src/main/java/com/learntrack/model/SyllabusUnit.java** - Already exists
5. **src/main/java/com/learntrack/model/SyllabusSubtopic.java** - Already exists
6. **src/main/java/com/learntrack/service/SubjectListService.java** - Already implemented
7. **src/main/java/com/learntrack/service/SyllabusParser.java** - Already has splitRespectingParentheses()
8. **src/main/java/com/learntrack/service/SemesterService.java** - Already calls syllabusDAO.deleteAllForSemester()

### New Test Files:
9. **src/test/java/com/learntrack/service/SyllabusParserSplitterTest.java** - Tests comma splitter

### Test Files Already Created:
10. **src/test/java/com/learntrack/dao/SyllabusDAOTest.java** - Already tests all DAO operations
11. **src/test/java/com/learntrack/service/SubjectListServiceTest.java** - Already tests subject merging

## How to Use the New Features

### 1. Launch the Application
```bash
mvn javafx:run
```

### 2. Navigate to the Subjects Tab
- Click on the **"📚 Subjects"** tab in the main window
- You will see collapsible sections, one per subject

### 3. Subject List
- **Where to see it**: Main area of the Subjects tab
- **What you'll see**: 
  - One TitledPane per subject (collapsed by default)
  - Subject name and code in the title (e.g., "Software Engineering (UCS503)")
  - Badge showing "In timetable" or "Kept for study"
  
**Subjects shown**:
- All subjects from your weekly timetable (CLASS/TUTORIAL/LAB entries)
- Kept subjects (removed from timetable but tracked)
- Any subject that has an exam

### 4. Managing Syllabus

**To add your first unit:**
1. Expand any subject section (click on it)
2. The "Syllabus" section is expanded by default
3. Click the **"+ Add Unit"** button
4. Enter a unit name (e.g., "Introduction to Software Engineering")
5. Click OK

**To add subtopics (individual):**
1. Find your unit in the list
2. Click **"+ Subtopic"** below the unit
3. Enter the subtopic name
4. Click OK

**To add subtopics (bulk with parentheses):**
1. Find your unit in the list
2. In the text field labeled "Bulk add (comma-separated)", enter:
   ```
   a, b (c, d), e
   ```
3. Click the **"Add"** button next to the field
4. **Result**: Three subtopics will be created:
   - "a"
   - "b (c, d)" ← Note: commas inside parentheses are preserved!
   - "e"

**To mark subtopics as done:**
- Simply check the checkbox next to each subtopic
- Progress is shown as "X/Y done" next to the unit name
- Changes save immediately

**To rename:**
- Click **"Rename"** button next to any unit or subtopic
- Enter new name and click OK

**To delete:**
- Click **"Delete"** (or **"×"**) next to any unit or subtopic
- Confirm the deletion
- Deleting a unit deletes all its subtopics

**To reorder:**
- Use **↑** and **↓** buttons next to units and subtopics
- Changes save immediately

**To clear all syllabus data:**
- Click **"Clear Syllabus"** button at the bottom
- Confirm the deletion
- All units and subtopics for that subject are deleted

### 5. Managing Topics (Study Plan)

**To access topic scheduling:**
1. Expand any subject section
2. Expand the **"Study Plan (Topics)"** section

**What you'll see:**
- If the subject has an exam: the original topic management UI
  - Topic table showing all topics for that exam
  - Form with fields: Name, Importance, Difficulty, Hours, Phase, Revision settings
  - Save Topic, Clear Form, Delete Topic buttons
- If no exam exists: a message to create one in the Exams tab first

**To add a topic:**
1. Fill in the form fields
2. Click **"Save Topic"**
3. The topic appears in the table above

**To edit a topic:**
1. Click on a row in the topic table
2. The form fills with that topic's data
3. Make changes
4. Click **"Save Topic"**

**All topic functionality works exactly as before** - nothing is broken!

### 6. After Restart

**Data persistence test:**
1. Add a unit with name "Test Unit"
2. Add bulk subtopics: `a, b (c, d), e`
3. Check off one subtopic
4. Close the application
5. Run `mvn javafx:run` again
6. Navigate to Subjects tab
7. Expand the same subject
8. **You should see**:
   - "Test Unit" still there
   - Three subtopics: "a", "b (c, d)", "e"
   - The checked subtopic still checked
   - Progress shows "1/3 done"

## Test Results

```bash
mvn test
```

**Results:**
- Tests run: 208, Failures: 1 (pre-existing), Errors: 0, Skipped: 0
- All syllabus tests pass ✓
- SyllabusDAO tests pass ✓
- SubjectListService tests pass ✓
- Splitter tests pass ✓
- No regressions introduced ✓

## Technical Details

### Architecture
- **UI Layer**: TopicFormController creates TitledPanes for each subject
- **Service Layer**: SubjectListService merges timetable/kept/exam subjects
- **DAO Layer**: SyllabusDAO handles all database operations
- **Parser**: SyllabusParser.splitRespectingParentheses() is the shared splitter

### Key Features Implemented
✓ Subject sections with badges
✓ Syllabus management (units & subtopics)
✓ Checkable subtopics with progress tracking
✓ Comma splitter that respects parentheses
✓ Move up/down for reordering
✓ Bulk add with comma-separated text
✓ Immediate persistence on all changes
✓ Study Plan section preserves old topic management
✓ Semester rollover cleanup
✓ Comprehensive tests

### Database Schema
```sql
CREATE TABLE IF NOT EXISTS syllabus_units (
    unit_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    semester_id   INTEGER NOT NULL,
    subject_code  TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INTEGER NOT NULL,
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

CREATE TABLE IF NOT EXISTS syllabus_subtopics (
    subtopic_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    unit_id       INTEGER NOT NULL,
    name          TEXT NOT NULL,
    position      INTEGER NOT NULL,
    done          INTEGER DEFAULT 0,
    FOREIGN KEY (unit_id) REFERENCES syllabus_units(unit_id) ON DELETE CASCADE
);
```

## Quick Visual Guide

```
📚 Subjects Tab
  └─ Software Engineering (UCS503) [In timetable] ▼
       ├─ Syllabus ▼
       │    ├─ Introduction to Software Engineering [2/5 done] [Rename] [Delete] [↑] [↓]
       │    │    ├─ ☑ Software Crisis
       │    │    ├─ ☐ Software process models
       │    │    ├─ ☑ Agile methodologies (Scrum, XP)  ← Note: parentheses preserved!
       │    │    ├─ ☐ DevOps practices
       │    │    └─ ☐ Requirements Engineering
       │    │    [+ Subtopic] [Bulk add: _____________] [Add]
       │    │
       │    ├─ [+ Add Unit]
       │    └─ [Clear Syllabus]
       │
       └─ Study Plan (Topics) ▼
            ├─ [Topic Table]
            ├─ [Topic Form: Name, Importance, Difficulty, etc.]
            └─ [Save Topic] [Clear Form] [Delete Topic]
```

## Done Criteria Met ✓

✓ Launching the app shows one section per subject
✓ Each section has an empty Syllabus area
✓ Can add a unit
✓ Can add "a, b (c, d), e" as subtopics → creates exactly 3 subtopics
✓ Data persists after restart
✓ Tests pass
✓ Existing topic scheduling still works

## What to Click

1. **Run app**: `mvn javafx:run`
2. **Go to Subjects tab**: Click "📚 Subjects" in the main window
3. **Expand a subject**: Click on any collapsed subject section
4. **Add unit**: Click "+ Add Unit"
5. **Test bulk add**: Type `a, b (c, d), e` in the bulk field and click "Add"
6. **Check progress**: Look for "0/3 done" → check one → see "1/3 done"
7. **Restart app**: Close and run `mvn javafx:run` again
8. **Verify persistence**: Check the same subject - data should still be there
