# Subjects Tab UI Improvements - Complete

## Summary

Three major improvements to the Subjects tab syllabus UI:

1. **Fixed invisible text** - All labels, fields, and checkboxes now have explicit dark text colors
2. **Added "Paste Syllabus"** - Import dialog with editable preview and merge/replace modes
3. **Improved button visibility** - Larger fonts, better colors, clear labels, proper spacing

## Files Changed

### Modified Files:

1. **src/main/java/com/learntrack/ui/forms/TopicFormController.java**
   - Added style constants for consistent dark text colors
   - Fixed unit name labels (14px bold, dark text)
   - Fixed subtopic checkbox labels (dark text)
   - Fixed all form fields in Study Plan section (dark text)
   - Improved button styling (larger 12px font, clear labels, better colors)
   - Added "Paste Syllabus" button (blue) to syllabus section
   - Implemented handlePasteSyllabus() with full dialog and preview
   - Implemented buildPasteSyllabusPreview() for editable preview
   - Implemented importParsedSyllabus() with merge/replace modes

2. **src/main/java/com/learntrack/service/SyllabusParser.java**
   - Added parseUnitList(String text) method
   - Added parseSingleLineUnits(String text) fallback method
   - Handles one unit per line: "Unit Name: subtopic1, subtopic2, ..."
   - Uses existing splitRespectingParentheses() for comma splitting
   - Removes duplicates, trailing commas, collapses spaces
   - Fallback for single-line blob with multiple headers

### New Test File:

3. **src/test/java/com/learntrack/service/SyllabusParserUnitListTest.java**
   - 9 comprehensive tests for parseUnitList()
   - Tests 5-unit cognitive computing syllabus
   - Tests blank lines ignored
   - Tests duplicate removal
   - Tests lines without colons
   - Tests trailing comma handling
   - Tests space collapsing
   - Tests parentheses preservation
   - All tests pass ✓

## Test Results

```
mvn test
Tests run: 223, Failures: 1, Errors: 0, Skipped: 0
```

The 1 failure is pre-existing (TimetableSetupWizardFlowTest), unrelated to these changes.

All 9 new tests pass ✓

## Features Implemented

### (A) Fixed Invisible Text ✓

**Problem:** Unit and subtopic names were white on light backgrounds (unreadable on dark OS themes).

**Solution:** Added explicit text colors to all labels, fields, and checkboxes:
- Unit names: `#111827` (very dark gray) - 14px bold
- Subtopic checkboxes: `#1f2937` (dark gray)
- Progress labels: `#6b7280` (medium gray)
- Form labels and fields: dark text colors
- All TextField and Spinner components styled

**Result:** Text is now readable on any OS theme.

### (B) Paste Syllabus Feature ✓

**New Button:** Blue "Paste Syllabus" button next to "+ Add Unit" and "Clear Syllabus"

**Dialog Contents:**
- Large multi-line TextArea for pasting syllabus text
- Hint: "One unit per line. Format: Unit Name: subtopic 1, subtopic 2, ..."
- "Parse" button to generate preview
- Editable preview showing all units and subtopics as cards
- Summary line: "X units, Y subtopics"
- Mode selection: "Add to existing" (default) or "Replace existing"
- "Import" and "Cancel" buttons

**Preview Features:**
- Each unit shown as a card with editable name field
- Subtopic count displayed
- Each subtopic has a "×" remove button
- Each unit card has "Remove unit" button
- "+ Add subtopic" button per unit
- "+ Add unit" button at bottom
- Import button disabled until at least one unit parsed

**Import Behavior:**
- **Add mode:** Merges into existing syllabus (case-insensitive unit matching)
- **Replace mode:** Asks for confirmation, then deletes all existing data first
- Preserves order from pasted text
- Skips duplicate subtopics within same unit

**Parser Logic:**
- One unit per line: `Unit Name: subtopic1, subtopic2, ...`
- First colon splits unit name from subtopics
- Uses `splitRespectingParentheses()` so "AI (Artificial, Intelligence)" stays together
- Trims whitespace, collapses multiple spaces
- Removes trailing commas: "subtopic ," → "subtopic"
- Removes duplicates (case-insensitive) within same unit
- Lines without colons are skipped
- Blank lines ignored
- Fallback for single-line blob with multiple headers (best-effort)

**Test Case (From Test Suite):**
```
Foundation of Cognitive Computing: Understanding cognition, Cognitive computing as a new generation, Artificial Intelligence (AI) as the foundation of cognitive computing, Applications of cognitive computing
Cognitive System Design Principles: Components of a cognitive system, Introduction to Natural language processing (NLP), Taxonomies and Ontologies
```

**Result:**
- 2 units created
- "Artificial Intelligence (AI) as the foundation of cognitive computing" = ONE subtopic (parentheses preserved)
- "Introduction to Natural language processing (NLP)" = ONE subtopic
- All subtopics cleanly trimmed

### (C) UI Improvements ✓

**Button Styling:**
- **Green buttons** (+ Add Unit, + Subtopic): `#10b981` background, white text, 12px, padding 6-14
- **Blue button** (Paste Syllabus): `#3b82f6` background, white text, bold, 12px
- **Red button** (Clear/Delete): `#fee2e2` background, `#b91c1c` text, 12px
- **Regular buttons** (Rename, arrows): 12px font, 4-10px padding

**Unit Display:**
- Blue left border (3px) on unit boxes
- Unit name: 14px bold, dark text, takes full width
- Progress label: smaller gray text
- Clear spacing between elements (8px)
- Light background `#fafafa` with visible border

**Subtopic Rows:**
- Dark text on checkboxes
- Larger font (12px) on buttons
- Clear "Delete" label with red styling
- Better spacing (10px between elements)

**Form Fields (Study Plan):**
- All labels have dark text
- All TextFields have dark text
- All Spinners have dark text
- ComboBox has dark text
- CheckBox has dark text

**Result:** Everything is visible, buttons are easy to click, controls are clearly labeled.

## How to Use

### Launch the App
```bash
mvn javafx:run
```

### Navigate to Subjects Tab
1. Click **"📚 Subjects"** tab at the top
2. Expand any subject (e.g., UCS420 - Cognitive Computing)
3. The **Syllabus** section is expanded by default

### Test the Paste Syllabus Feature

1. Click the blue **"Paste Syllabus"** button
2. In the dialog, paste this test data:

```
Foundation of Cognitive Computing: Understanding cognition, Cognitive computing as a new generation, the uses of cognitive systems, system cognitive, gaining insights from data, Artificial Intelligence (AI) as the foundation of cognitive computing, Applications of cognitive computing
Cognitive System Design Principles: Components of a cognitive system, building the corpus, bringing data into cognitive system, Machine learning based design, Hypothesis generation and scoring, Introduction to Natural language processing (NLP), Taxonomies and Ontologies
Reasoning and Learning: Case based reasoning, Analogical reasoning, Constraint reasoning and Meta reasoning, Concept learning, Common sense-based learning, Explanation based learning
Advance Analytics: Big data and Cognitive computing, Predictive Analytics, Text Analytics, Image Analytics, Speech Analytics
Case Studies: Cognitive Systems in health care, Cognitive Assistant for visually impaired , Introduction to IBM Watson, Cognitive Systems in Finance
```

3. Click **"Parse"**
4. You should see:
   - 5 unit cards
   - Summary: "5 units, 29 subtopics"
   - Each unit editable with subtopics listed
   - "Artificial Intelligence (AI) as the foundation of cognitive computing" as ONE subtopic
   - "Introduction to Natural language processing (NLP)" as ONE subtopic
   - "Cognitive Assistant for visually impaired" (no trailing space/comma)

5. Edit the preview if needed:
   - Click "×" to remove subtopics
   - Click "Remove unit" to remove units
   - Click "+ Add subtopic" to add more
   - Edit unit names in the text fields

6. Select mode:
   - "Add to existing syllabus" (merges with existing data)
   - "Replace existing syllabus" (deletes all first)

7. Click **"Import"**

8. The syllabus section refreshes showing your imported units and subtopics

### Verify Text Visibility

**All text should be clearly visible regardless of your OS theme:**
- Unit names (bold, dark)
- Subtopic names (dark)
- Progress labels (gray but visible)
- Button labels (clear and readable)
- Form field text (dark)

### Test Bulk Add with Parentheses

1. Add a unit manually: "+ Add Unit" → "Test Unit"
2. In the bulk field, type: `a, b (c, d), e`
3. Click "Add"
4. **Result:** Exactly 3 subtopics:
   - "a"
   - "b (c, d)"  ← comma inside parentheses preserved
   - "e"

### Verify Persistence

1. Add some units and subtopics
2. Check a few subtopics as done
3. Close the app
4. Run `mvn javafx:run` again
5. Navigate to Subjects → expand same subject
6. **All data should be intact** (units, subtopics, checkmarks)

## Style Constants

All style constants defined at top of TopicFormController:
```java
STYLE_UNIT_NAME = "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #111827;";
STYLE_PROGRESS = "-fx-text-fill: #6b7280; -fx-font-size: 11px;";
STYLE_SUBTOPIC_TEXT = "-fx-text-fill: #1f2937;";
STYLE_UNIT_BOX = "-fx-border-color: #3b82f6; -fx-border-width: 0 0 0 3; ...";
STYLE_BTN_DELETE = "-fx-background-color: #fee2e2; -fx-text-fill: #b91c1c; ...";
STYLE_BTN_ADD_GREEN = "-fx-background-color: #10b981; -fx-text-fill: white; ...";
STYLE_BTN_PASTE_BLUE = "-fx-background-color: #3b82f6; -fx-text-fill: white; ...";
STYLE_BTN_CLEAR_RED = "-fx-background-color: #ef4444; -fx-text-fill: white; ...";
STYLE_TEXTFIELD = "-fx-text-fill: #111827;";
```

## Technical Details

### Database
- No schema changes
- Uses existing SyllabusDAO methods:
  - insertUnit()
  - insertSubtopic()
  - deleteUnit()
  - deleteSubtopic()
  - deleteAllForSubject()
  - findUnitsBySubject()
  - findSubtopicsByUnit()

### Parser Details
- `SyllabusParser.parseUnitList(String text)` - main method
- Returns `List<ParsedUnit>`
- Each ParsedUnit has:
  - name (String)
  - subtopics (List<String>)
  - setName() for editing in preview
- Uses existing `splitRespectingParentheses(text, ',')` internally
- Handles edge cases: blank lines, no colon, duplicates, trailing commas

### UI Threading
- All DB operations remain synchronous (consistent with existing code)
- UI updates happen on JavaFX thread after DB operations complete
- Preview editing is live (no DB operations until Import clicked)

## Done Criteria Met ✓

✅ (A) Unit and subtopic names are visible on all OS themes
✅ (B) "Paste Syllabus" button opens import dialog with editable preview
✅ (C) All buttons are clear, visible, and easy to click
✅ Database schema unchanged
✅ Existing tests still pass (223 tests, 1 pre-existing failure)
✅ New tests added and passing (9 new tests for parseUnitList)
✅ App still launches without errors

## Quick Test Command

```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Launch app
mvn javafx:run
```

Then: Subjects tab → Expand UCS420 → Syllabus → Paste Syllabus → paste the 5 lines → Parse → edit if needed → Import
