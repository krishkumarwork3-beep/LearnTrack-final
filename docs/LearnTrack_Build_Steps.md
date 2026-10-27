# LearnTrack Build Steps

Derived from `docs/LearnTrack_Implementation_Plan.md` Section 15.
This document records each build phase and its implementation status in LearnTrack.

---

### Phase 1: Skeleton, DB Initialization & Models
- **Status:** [x] Implemented
- SQLite connection management in `DBConnection`, initial table creation from `schema.sql` (foreign keys enabled).
- Core domain model objects: `Semester`, `WeeklyTimetableEntry`, `WeekdayStudyPreference`, `ExamPeriodRoutineEntry`, `DateOverride`, `Exam`, `Topic`, `Skill`, `DailyLogEntry`, `FreeSlot`, `PriorityItem`.

### Phase 2: DAO Layer
- **Status:** [x] Implemented
- Full CRUD implementations with raw JDBC SQLite:
  - `SemesterDAO`, `TimetableDAO`, `ExamPeriodRoutineDAO`, `DateOverrideDAO`
  - `ExamDAO` (transactional deletion cascade for topics and daily logs)
  - `TopicDAO` (transactional deletion removing topic daily logs)
  - `SkillDAO`, `DailyLogDAO`, `SettingsDAO`

### Phase 3: Timetable Setup Wizard & FreeSlotService
- **Status:** [x] Implemented
- `TimetableSetupWizard`: recurring commitments entry (classes, labs, sleep, meals) + review free time with dynamic target-exceeds-capacity warnings.
- `FreeSlotService`: complement arithmetic (`SlotMath`), accounting for recurring commitments, date overrides, and exam buffers.
- `ExamPeriodRoutineWizard` deferred until first dated MST/EST.

### Phase 4: Exam, Topic & Skill Forms
- **Status:** [x] Implemented
- Credits and evaluation marks collected upfront for every exam.
- Window start/end dates validated for undated MST/EST. Start and end times validated (`endTime > startTime`) for dated exams.
- `TopicFormController` with revision cycle fields and `important_flag`.
- `SkillFormController` with permanent, semester-independent persistence.

### Phase 5: Scoring Services & Unified Priority Queue
- **Status:** [x] Implemented
- `ScoringService`: `debtScore`, `revisionScore`, `pyqScore`, and syllabus completion check for `pyqEligible`.
- `PriorityQueueService`: priority queue construction with priority items filtered by `important_flag = 1`.

### Phase 6: Semester Regeneration (`regenerateSemesterPlan`) & Daily Allocation
- **Status:** [x] Implemented
- Unconditional regeneration on app open and after schedule changes.
- Day-by-day simulated state progression: working copies advance through learning completion, revision cycles, PYQ decrements, and skill practice.
- Rolling horizon handling when no exams remain (skills lookahead).
- Preservation of `DONE`, `MISSED`, and `MANUAL` rows today without overlaps.

### Phase 7: Dashboard & Calendar Views
- **Status:** [x] Implemented
- `DashboardView`: today's slot timetable, progress bar for done hours vs planned hours, session resize and swap actions (`status = 'MANUAL'`).
- `CalendarGridView`: full semester grid view showing daily sessions and exam days.

### Phase 8: Done / Not-Yet Progress Dialogs
- **Status:** [x] Implemented
- Interactive logging dialog handling all four item types:
  - `TOPIC_LEARN`: Finished -> phase becomes REVISION; Not Yet -> user enters estimated hours left.
  - `TOPIC_REVISE`: Completed -> cycle resets; Not Yet -> cycle hours left.
  - `SKILL`: Practiced -> updates `lastPracticed` date.
  - `PYQ_PRACTICE`: Completed -> decrements/clears PYQ hours; Not Yet -> updates hours left.

### Phase 9: Cancellation Handling
- **Status:** [x] Implemented
- Class/lab cancellation on today or any future date via DatePicker.
- Inserts `CANCEL` override row in `date_overrides` and regenerates whole semester plan from today.
- Supports un-cancelling (restoring).

### Phase 10: Weightage-based Same-Day Exam Split & Eve Rule
- **Status:** [x] Implemented
- Eve majority revision rule: 80% (`revision_majority_ratio`) of free time allocated to upcoming exams by `examWeight`.
- Same-day clash split: free time partitioned by `examWeight(E) / sum(examWeight)` with fallback to general queue.
- Combined clash + eve precedence: eve takes 80% majority, leftover 20% splits across clash exams by weight.

### Phase 11: Datesheet Mode Switch & Capacity Negotiation Loop
- **Status:** [x] Implemented
- Switches to `ExamPeriodFreeSlotService` for dates in dated MST/EST windows (with fallback to `FreeSlotService` if routine empty).
- `ExamPeriodCapacityService`: evaluates available capacity vs required hours (`important_flag = 1` topics + anticipated PYQ load).
- Automated negotiation loop dialog in `ExamPeriodView` and upon saving datesheets in `ExamFormController`.
- Resets `important_flag = 1` after batch window closes.

### Phase 12: Semester Lifecycle & Rollover
- **Status:** [x] Implemented
- `SemesterService.startNewSemester`: deletes old semester data (exams, topics, timetable, and timetable date overrides; previous semester is not archived).
- Permanent preservation of all skills and `SKILL` daily logs across rollovers.

### Phase 13: Charts & Feasibility Bar
- **Status:** [x] Implemented
- `FeasibilityBarView` backed by `FeasibilityService`: calculates real capacity `min(freeTime, preference)` honoring cancellations and buffers.
- `SkillDashboardView`: displays skills with subtopics, learning and revision tracking.
- `DatesheetAnalyserView`: exam matching and topic trimming UI backed by `DatesheetParser` and `DatesheetAnalyserService`.

### Phase 14: Edge Cases & Polish
- **Status:** [x] Implemented
- All edge cases from Plan §16 handled (floor `daysLeft = 1`, zero eval marks safe weight, empty routine fallback, etc.).
