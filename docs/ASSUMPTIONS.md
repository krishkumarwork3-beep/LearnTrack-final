# LearnTrack: Assumptions, Deviations, and Decisions

This document records the design decisions, deviations from folder structure, and resolutions between the **Implementation Plan** and **User Guide**.

---

## 1. Deviations from Folder Structure & Schema Constants

- **`com.learntrack.dao.SettingsDAO`**:
  - *Reason*: The database schema (Section 3 of the Implementation Plan) defines the `settings` table with seeded default rows (`skill_session_length`, `revision_majority_ratio`, `pyq_practice_hours`, `skill_lookahead_days`). The class list in Section 2a omitted a DAO for this table. Adding `SettingsDAO` provides clean, layered access to persistence for user-configurable settings without placing raw SQL in UI or Service layers.
- **`com.learntrack.service.TimetableEditService`**:
  - *Reason*: Section 2a omitted a dedicated service for user modifications to the daily schedule. `TimetableEditService` encapsulates slot swapping (swapping item payloads while preserving time intervals) and session resizing with validation against base free slots, enforcing the layer rule `ui → service → dao`.
- **`com.learntrack.service.FeasibilityService`**:
  - *Reason*: Section 2a omitted a service for computing overall semester feasibility. `FeasibilityService` calculates total required study hours vs total available capacity across the term (taking into account weekly preferences, routine routines, date overrides, and exam buffers) to back the `FeasibilityBarView`.
- **`com.learntrack.service.ExamPeriodCapacityService`** :
  - *Reason*: Section 2a omitted a standalone service for evaluating exam period capacity. `ExamPeriodCapacityService` computes required hours (`important_flag = 1` topics and anticipated PYQ load for unfinished exams) versus available routine capacity during dated MST/EST windows, supporting capacity negotiation loops.
- **`com.learntrack.ui.setup.CapacityNegotiationDialogHelper`**:
  - *Reason*: Section 2a omitted a helper for orchestrating the multi-step capacity negotiation loop (requesting extra hours, prompting to trim topics, or accepting the deficit). This helper avoids duplicating dialog logic between `ExamPeriodView` and `ExamFormController`.
- **`com.learntrack.util.SerialExecutor`**:
  - *Reason*: Section 2a omitted a utility for executing asynchronous tasks strictly sequentially on a dedicated background daemon thread. `SerialExecutor` ensures FIFO execution order and guarantees that background replans never execute concurrently or interleave SQLite database transactions, without importing JavaFX (respecting layer rules).
- **`com.learntrack.ui.ReplanRunner`**:
  - *Reason*: Section 2a omitted a UI-level runner coordinating background semester replans with UI state management. `ReplanRunner` wraps `SerialExecutor`, exposes a `busyProperty()` and progress indicator binding to prevent JavaFX thread freezes while disabling triggering buttons, safely handles error alerts without swallowing exceptions, and routes callbacks to the JavaFX Application Thread.
- **Extra `MANUAL` status in `daily_log.status`**:
  - *Reason*: Plan §3 lists `DONE | CONTINUING | MISSED`. When a user resizes or swaps sessions on the Dashboard, marking them `MANUAL` distinguishes them from automatically generated `PENDING` sessions so they survive automatic replanning without being overwritten, while still being distinguishable from `DONE`.

---

## 2. Conflicts & Resolutions (Implementation Plan vs. User Guide)

- **MST/EST Undated Topic Deadline Fallback Chain**:
  - *Observation*: The User Guide states that until a datesheet arrives, the app treats the window start date as the exam date. The Implementation Plan (Section 5) provides a more robust fallback chain:
    1. `exam_date` (if set)
    2. `window_start_date` (if `today <= window_start_date`)
    3. `window_end_date` (if `today <= window_end_date`)
    4. `today` (if the entire rough window elapsed without a datesheet, flooring `daysLeft` at 1 to keep topics maximally urgent).
  - *Resolution*: Implemented the exact fallback chain from the Implementation Plan, which fulfills the User Guide promise that subjects never drop out of scheduling even if the window passes without an official datesheet.

- **Study Targets vs. Real Free Time**:
  - *Observation*: The User Guide describes setting target study hours; the Plan details slot subtraction and asymmetric buffer exclusion.
  - *Resolution*: Normal teaching days compute the complement of fixed timetable commitments and overrides, and cap daily session allocations at `weekday_study_preference.study_hours`. Dated MST/EST window days cap daily allocations at preference plus negotiated extra hours (`min(dayFree, preference + extraHours)`). In contrast, eve-of-exam days and same-day-clash days deliberately derive study budgets directly from `totalFreeTime` (including the leftover 20% on eve days) and ignore `weekday_study_preference` entirely per Plan §8 and Guide §5.

---

## 3. Ambiguities & Architectural Decisions

- **Cross-Midnight Routine Blocks**:
  - Fixed commitments like night sleep (e.g., 23:00 to 07:00) cross midnight (`startTime > endTime`). `SlotMath.normalizeIntervals` splits these into `[startTime, 23:59:59.999999999]` and `[00:00, endTime]` to prevent invalid interval arithmetic in 24-hour daily slicing.
- **Asymmetric Buffer Clamping**:
  - Before exam: `exam_start_time.minusMinutes(travel_time_minutes)`. If travel time crosses midnight, it clamps to `LocalTime.MIN` (00:00).
  - After exam: `exam_end_time.plusHours(1)`. If the 1-hour buffer crosses midnight, it clamps to `LocalTime.MAX`.
- **Rolling Horizon with No Unfinished Exams**:
  - When all exams are completed or before a new term's exams are registered, the horizon is set to `today + skill_lookahead_days` (default 14 days). `SemesterReplanService` re-offers skill practice daily across the horizon, ensuring continuous skill tracking during study gaps.
- **Eve-of-Exam and Same-Day Clash Precedence & Uncapped Budgets**:
  - On eve days and clash days, the available study budgets derive directly from `totalFreeTime` (after subtracting surviving rows on `fromDate`), and are NOT capped by `weekday_study_preference` (Plan §8, Guide §5). Specifically, the eve revision budget is `totalFreeTime * revision_majority_ratio` and leftover budget is `totalFreeTime - revisionBudgetTotal`. On clash days, `clashBudgetTotal = totalFreeTime`, split among exams by `examWeight` share with unused shares falling through to other items in the priority queue. If a date is both an exam day for A and B and the eve for C, the eve rule takes precedence: Exam C receives up to 80% (`revision_majority_ratio`) of total free time, and the leftover 20% is split between clash exams A and B by weight ratio.
- **Exam Period Daily Budget & Extra Hours Storage**:
  - During dated MST/EST windows, daily budget is `min(freeTime, weekday_study_preference(day) + extraHours)`. The `extraHours` negotiated during capacity resolution is stored in the `settings` key-value table under `exam_period_extra_hours_MST` and `exam_period_extra_hours_EST` (default 0.0), avoiding schema changes.
- **Extra Hours Reset on Batch Window Close and Rollover**:
  - `exam_period_extra_hours_MST` and `exam_period_extra_hours_EST` are reset to `0.0` as soon as their batch window closes (when today is past the latest dated exam in the batch) and during `SemesterService.startNewSemester`. This ensures that negotiated temporary extra hours do not leak into future batches or new semesters.
- **Anticipated PYQ Load in Capacity Negotiation**:
  - `ExamPeriodCapacityService.getRequiredHours` deliberately counts anticipated PYQ practice hours (`pyq_remaining_hours`) for every exam in the batch alongside remaining topic hours (`important_flag = 1`). This models the full syllabus and evaluation load per Plan Section 9, preventing students from underestimating study time requirements.
- **Queue Scores Raw Comparison**:
  - Queue scores (`debtScore`, `revisionScore`, `pyqScore`) are compared raw in the unified priority queue. While Plan §5 mentions normalization, it does not define a normalization formula across heterogeneous scoring dimensions (debt hours vs eval marks). The scores are therefore ranked directly without artificial rescaling.
- **Manual Session Status & Item Swapping vs Time Swapping**:
  - When swapping two sessions on the dashboard, `TimetableEditService` swaps the item payloads (`item_type` and `item_id`) between the two existing slot rows rather than swapping the time intervals. This guarantees that each slot's exact start and end times, duration, and visual layout remain intact with zero risk of interval distortion, gap fragmentation, or collisions with fixed commitments and buffers.
  - Swapping and resizing are restricted strictly to `PENDING` or `MANUAL` sessions (rejecting `DONE`, `CONTINUING`, or `MISSED`). Resizing validates non-overlap with other sessions and containment within available base free slots. Both actions persist rows with `status = 'MANUAL'`. `clearFutureRows` preserves `MANUAL` rows for today while removing future pending sessions.
- **Semester Lifecycle Deletion Sequence**:
  - As required by Section 12 and Step 5, rollover permanently deletes data in order: `date_overrides` (tied to old timetable entries) -> `daily_log` non-skill rows -> `topics` -> `exams` -> `weekly_timetable` & `weekday_study_preference` -> `exam_period_routine` -> `semesters`. Skills and `SKILL` daily log rows are permanently preserved.
- **Batch Window Closed Rule & Reset Timing (Fix 5)**:
  - Plan §6 step 6 / §9 specifies that once a batch window closes, all topics in that batch reset to `important_flag = 1`. To ensure that previously trimmed topics are scheduled without requiring an extra replanning cycle, `checkAndResetBatchImportantFlags` runs *before* active topics are loaded from the database during `regenerateSemesterPlan`.
  - The window for an MST/EST batch is considered closed when: (a) the latest known dated exam in the batch is in the past (`today.isAfter(latestExamDate)`), and (b) no undated exam in the batch has a future unlapsed rough window (`windowEndDate != null && !today.isAfter(windowEndDate)`). Undated exams whose rough windows have already elapsed without a datesheet, or exams with no window dates at all, do not block the window-closed reset.
- **Skills with Subtopics**:
  - Skills are broken into subtopics stored in the `skill_subtopics` table with fields for learning and revision tracking (`learned`, `learned_on`, `revised`, `revision_due_date`).
  - Skill scheduling uses `SKILL_LEARN` and `SKILL_REVISE` daily log types. Skills are a lower priority tier than exam topics and PYQ. Missed skill rows roll over if not completed.
  - The `purgeLegacySkillRows()` method removes old skill rows that don't have associated subtopics, ensuring clean separation between the new subtopic-based system and legacy approaches.
- **Timetable Entry ID Preservation & Date Overrides (Fix 2)**:
  - `TimetableDAO.saveTimetableEntries` updates existing entries by `entry_id`, inserts new rows, and deletes only removed rows. This preserves `entry_id` values so that existing `date_overrides.CANCEL` rows remain linked to their recurring classes. Removed entry IDs are deleted alongside their orphaned overrides.
  - In `ExamPeriodFreeSlotService`, `CANCEL` overrides are ignored entirely because they reference weekly timetable entries, not `exam_period_routine` entries. Consequently, `ExamPeriodRoutineDAO.saveRoutineEntries` does not need to preserve routine IDs or remap date overrides.
- **`DailyLogDAO.insertBatch` Per-Row Key Assignment in Single Transaction (Issue 1)**:
  - Because `sqlite-jdbc` returns only the last inserted rowid for batch executions, `insertBatch` executes each row individually via `executeUpdate()` and reads `getGeneratedKeys()` per row immediately within a single transaction (`setAutoCommit(false)` ... `commit()`). If any row fails, the entire batch is rolled back atomically. This maintains single-commit disk efficiency while ensuring every `DailyLogEntry` receives its distinct `logId`.
- **Asynchronous Replanning via ReplanRunner & Intentional Synchronous Call Sites (Issue 3)**:
  - UI replans triggered after log actions, form saves, cancellations, and settings updates are dispatched asynchronously via `ReplanRunner` backed by `SerialExecutor` to prevent freezing the JavaFX application thread. `DBConnection` sets `PRAGMA busy_timeout = 5000;` to prevent `SQLITE_BUSY` errors during concurrent UI reads.
  - Call sites that require synchronous completion before immediate follow-up logic intentionally remain synchronous:
    - App open replan in `MainApp.start()` (views not yet rendered; missed-day marking precedes it).
    - Modal setup wizards (`TimetableSetupWizard`, `ExamPeriodRoutineWizard`) which run inside modal stages that close upon completion.
    - `TimetableEditService` (swaps and resizes validate and return result directly).
    - `CapacityNegotiationDialogHelper`'s "Increase Hours" path which immediately recurses to re-evaluate capacity post-replan.
    - `SemesterService.startNewSemester` rollover follow-up.
- **Safe Replanning against App Shutdown (Atomic DB Write, Survivor Filter, Graceful Shutdown)**:
  - *Atomic Database Write*: `SemesterReplanService.regenerateSemesterPlan` computes the complete replanned schedule in memory across the entire horizon before making any database modification. Instead of deleting future rows early and inserting entries day-by-day in multiple commits, a single atomic method `DailyLogDAO.replaceFutureRows(fromDate, allEntries)` executes the future-row deletion (`clearFutureRows` equivalent) and all row insertions with individual generated key retrieval within a single database transaction (`setAutoCommit(false)` ... `commit()`). If any failure occurs, the transaction rolls back completely and rethrows, leaving the previous database state intact.
  - *Survivor-Filter Rule*: Because `clearFutureRows` is no longer called early, reading `dailyLogDAO.findByDate(fromDate)` on `fromDate` returns all rows currently present for that date. To prevent `PENDING` or `CONTINUING` rows scheduled for replacement from wrongly consuming today's study budget or subtracting from today's free slots, the rows read on `fromDate` are filtered in Java to exclude any row with status `PENDING` or `CONTINUING`. This survivor-filter rule strictly preserves only `DONE`, `MISSED`, and `MANUAL` sessions on `fromDate`, matching the exact survivor semantics previously enforced by the early deletion.
  - *Graceful Worker Shutdown*: `SerialExecutor.shutdownGracefully(timeoutMillis)` stops accepting new tasks, clears unstarted tasks from the queue, and allows the actively executing background replan to finish without thread interruption, waiting up to `timeoutMillis` before falling back to `shutdownNow()`. `ReplanRunner.shutdown()` invokes `shutdownGracefully(10_000)` with a 10-second limit. UI callbacks (`onSuccess`/`onError`) check `executor.isShutdown()` to guarantee that no UI dialogs or updates execute after the application window has started closing. With atomic database writes in place, even if the 10-second limit is hit and the JVM is killed mid-replan, the uncommitted database transaction rolls back and the old plan survives.
- **Wake-Up & Sleep Routine Model (`weekday_routine` Table)**:
  - *Reason for Schema Addition*: Implementation Plan §3 originally treated sleep as an ad-hoc timetable entry of type `SLEEP` typed manually in `weekly_timetable` for each of the 7 weekdays. If a user only entered sleep for Monday (or omitted it for certain days), the remaining days were treated as having 24.0 free hours, and cross-midnight sleep on Monday night failed to block Tuesday morning.
  - *Storage & Model*: Table `weekday_routine (semester_id INTEGER NOT NULL, day_of_week TEXT NOT NULL, wake_time TEXT NOT NULL, sleep_time TEXT NOT NULL, PRIMARY KEY (semester_id, day_of_week), FOREIGN KEY (semester_id) REFERENCES semesters(semester_id))` added with `CREATE TABLE IF NOT EXISTS` in `schema.sql`. Model `WeekdayRoutine` encapsulates per-day routine settings. `TimetableDAO` implements `saveRoutines`, `findRoutinesBySemester`, `findRoutinesMapBySemester`, and `deleteRoutinesBySemester`. `TimetableDAO.deleteBySemester` automatically removes routines, ensuring `SemesterService.startNewSemester` clears routine data without touching skills.
  - *Awake-Window Math*: For date D, the awake window is defined as `[wake(D), sleep(D))`, clipped to the 24-hour day. If `sleep(D)` is after midnight (`sleep(D) <= wake(D)`), the portion `[00:00, sleep(D))` belongs to date D+1 (so D+1's awake window is `[00:00, sleep(D)) ∪ [wake(D+1), ...)`). Everything outside the awake window is non-free sleep time. Free time for date D is the awake window minus that date's commitments, minus date cancellations, plus exam buffers.
  - *Cross-Midnight Weekly Commitments vs. Daily Exam Routine*: For recurring weekly commitments in `weekly_timetable`, any entry with `end <= start` on day D blocks `[start, 24:00)` on day D and spills `[00:00, end)` into day D+1. This is handled by pre-splitting in `FreeSlotService` (reading the previous day's recurring entries for morning spill) before calling `SlotMath`. Crucially, `SlotMath.normalizeIntervals` same-day wrap semantics remain unchanged because `ExamPeriodFreeSlotService` relies on it for the daily exam-period routine which repeats identically every day.
  - *Legacy Fallback*: If a semester has no rows in `weekday_routine`, `FreeSlotService` falls back to the legacy model: only explicit timetable entries (including existing `SLEEP` entries) are subtracted from 24 hours, with the cross-midnight weekly fix applied. On application startup, if the active semester has no timetable entries or no `weekday_routine` configured, `MainApp` opens `TimetableSetupWizard` so the user is prompted once.
- **Fractional Credits Support & `ScoringService.examWeight` Update**:
  - *Field & Schema*: Exam credits are represented as `double` end-to-end: `Exam.credits` (`double`), `ExamDAO` (`stmt.setDouble`, `rs.getDouble`), and `schema.sql` (`credits REAL DEFAULT 1`). In `ExamFormController`, credits use `Spinner<Double>` (min 0.5, max 10.0, default 3.0, step 0.5, editable) with validation requiring `credits > 0.0`. Table display formats without trailing noise (e.g. `1.5` and `3`).
  - *Scoring Weight Formula*: In `ScoringService.examWeight`, the formula remains `credits * max(eval_marks, 1.0)`, but the integer floor `Math.max(credits, 1)` was removed so fractional credits such as 0.5 do not round up to 1.0 (e.g., 1.5 credits × 20 marks = 30.0; 0.5 credits × 20 marks = 10.0). A fallback of 1.0 is used only when `credits <= 0.0` or `exam == null`.
  - *Old-DDL Compatibility*: Verified with an automated test (`testOldDDLCompatibilityWithFractionalCredits`) that SQLite's dynamic type affinity safely stores and retrieves `REAL` values (such as `1.5`) in existing databases where the column was declared `INTEGER`, preserving all data without requiring a disruptive database table migration.



---

## 4. Datesheet Parsing, Exam Windows, and the Exam-Period Routine

- **Column-block pairing is deliberately conservative**:
  - *Rule*: `ColumnBlockFormat` assigns dates/times to course codes only when the page is unambiguous: the number of code groups equals the number of date/time slots, dates are chronologically non-decreasing, and times on the same date strictly increase. "Code groups" come from alphabetical runs in the extracted code column, which is how the PDF's row order survives a column-order copy.
  - *Why*: a column-order paste has no per-row link between a date and a course. One mis-paired course is worse than a flagged one, because the user would never notice it after Apply.
  - *Measured result on the EST datesheet*: 546 unique courses total, **481 assigned and 65 ambiguous (481 + 65 = 546)**. The same numbers are asserted by `GeneralDatesheetParserTest.testColumnBlockFormat`, together with the rule that a code never appears in both the assigned and the ambiguous sets. When one page's times are scrambled, all codes on that page move to the ambiguous set and a warning is recorded, and none is silently assigned a wrong date.
  - *Flagged courses are never applied silently*: they surface in the "Needs Your Decision" panel, one row per user subject, and `apply(...)` writes them only from an explicit resolution. A code present in the `skipped` set is ignored even if a candidate is also supplied (this guard was added after `DatesheetAnalyserServiceTest.testSkippedAmbiguousCourseIsNotApplied` reproduced the original leak), and an unresolved course keeps its previous date and tentative flag.
- **Exam window = the whole datesheet range, with a fallback to the user's dated exams**:
  - *Rule*: `ExamWindowService.windowFor` returns the stored window (written by `DatesheetAnalyserService.apply` or `ExamTimelineService.saveTentativeWindow`) when the datesheet is announced; otherwise it returns the min/max of that type's non-tentative dated exams. Both endpoints are inclusive, so weekends and days with no exam of the user's fall inside the window and are planned as exam-period days.
  - *Fallback start is the first exam date, not today*: if the only information available is the user's dated exams, the window starts on the earliest such date. Starting it "today" would silently convert ordinary teaching days into exam-period days (no classes) and inflate capacity.
  - *Retained lapsed semantics*: a window whose stored range has already passed simply stops matching dates; `Exam.windowStartDate`/`windowEndDate` still feed `effectiveDeadline`, so an exam whose datesheet never arrived keeps its urgency and does not fall out of the plan (see section 2).
- **Default routine when `exam_period_routine` is empty**:
  - *Rule*: `DefaultExamRoutineProvider` derives the exam-period day from `weekday_routine` (wake/sleep for the date and the previous date, falling back to **07:00 / 23:00** when no routine rows exist) plus the weekly timetable entries whose `activityType` is `MEAL`, `SLEEP` or `OTHER`. Entries typed `CLASS`, `TUTORIAL` or `LAB` are excluded, because weekly classes are not followed inside a dated exam window.
  - *Shared projection*: the same provider feeds `ExamPeriodFreeSlotService.deriveBlockedIntervals` (planning/capacity) and `DayScheduleService.deriveRoutineBlocks` (display), so a MEAL row is never shown as free time by the planner or vice versa.
  - *Exam sittings are not part of the provider*: each consumer adds `[exam_start - travel_time_minutes, exam_end + 1h]` for the exams dated on that day, keeping the same asymmetric buffer used on normal days.
  - *Explicit routine wins*: as soon as one `exam_period_routine` row exists for the semester, it is used as-is and no weekly MEAL/SLEEP/OTHER entry is imported. This is intentional: the wizard's routine is the user's deliberate exam-period day.
- **`CANCEL` overrides are ignored inside the window**:
  - *Rule*: `date_overrides.CANCEL` rows reference `weekly_timetable.entry_id`, a different ID space from `exam_period_routine.routine_id`. Applying them to exam-period blocks could silently cancel an unrelated meal or sleep block whenever the two tables happen to share a numeric id. `ExamPeriodFreeSlotService` therefore applies only `ADD` overrides on window days, and `DayScheduleService`'s exam-period branch likewise adds only `ADD` overrides. Classes are absent inside the window anyway, so there is nothing for a class cancellation to remove there.
- **`clearWindow` when a batch is reset to tentative**:
  - *Rule*: unticking the datesheet checkbox for a batch (and confirming) runs `setDatesheetAnnounced(..., false)`, `clearWindow(...)`, `ExamTimelineService.resetToTentative(...)` and a replan. Clearing both the announced flag and the stored start/end prevents the old sheet range from continuing to mark days as exam-period days after the user has explicitly gone back to tentative planning; the effective deadline then falls back through `window_start_date` → `window_end_date` → "due immediately" as described in section 2.

- **PDF import was removed to keep dependencies to JavaFX and a JDBC driver; datesheets are pasted as text.**
