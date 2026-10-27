# LearnTrack Planner Fix - Final Report

## Summary

All five phases of the LearnTrack Planner Fix plan (Phases 1 through 5) are complete.
All 398 automated tests pass with 0 failures and 0 errors. Database hash integrity for `learntrack.db` was strictly maintained across all runs.

- **Final DB SHA-256 Hash**: `BC6B304B55AA586F54B1E091B984351E27B9B6FEF620DD0FFA4EDE9546624BF1`
- **Final Test Suite Run**: `Tests run: 398, Failures: 0, Errors: 0, Skipped: 0` (BUILD SUCCESS)

---

## Phase Overview & Results

### Phase 1: Foundations and Urgency Scoring
- Fixed `pyqEligible` to require syllabus topics.
- Set undated exam deadline fallback to `today + 60 days` (`Exam.java:117`).
- Implemented `PriorityItem` tier comparator (`TIER_COMPARATOR`), ensuring syllabus topics and skills precede PYQs.
- All Phase 1 tests passing.

### Phase 2: Syllabus Scope, Hours Sync, and Replan Robustness
- Implemented `ExamScopeService.syncTopicPhasesForSubject` and `resyncHoursForSubject`.
- Updated `TopicDAO.hasBeenStudied` to consider only `DONE` and `CONTINUING` logs.
- Added `rebuildPlan`, startup plan repair, and debounced replan mechanisms.
- All Phase 2 tests passing.

### Phase 3: Strict Scheduling and Capacity Rules
- Strict assertions on scheduling: no over-allocation of remaining hours.
- Topic splitting across multiple slots with zero hours lost.
- 15-minute minimum session fragment respected (`min_session_minutes`).
- Urgency interleaving: subjects with the same deadline interleave across the day.
- Uncapped free time on normal days and exam eve majority revision allocation.

### Phase 4: Merged Day Schedule and Dashboard Navigation
- Created `DayScheduleService` and `DayScheduleView`:
  - Merges weekly classes, timetable overrides (CANCEL/ADD), exam slots with travel buffers, sleep routines, and daily log study sessions into ordered `DayRow` items.
- Added previous/next day navigation and date picker to Dashboard.
- Verified on copy of `learntrack.db` for 2026-10-22.

### Phase 5: Calendar, Scope Warnings, Replan Triggers, and Feasibility
- **Calendar Day Schedule**:
  - `CalendarGridView.java` integrated with `DayScheduleService` and `DayScheduleView`.
  - Day cells display planned study hours badge (`%.1fh`), `EXAM`, or `EVE`. Clicking any day opens its full schedule.
- **Scope & Tentative Warnings**:
  - `ExamScopeService.findExamsWithoutScope` detects exams lacking syllabus scope.
  - Dashboard banner (`scopeWarningBanner`) alerts user when exams lack scope.
  - Undated exams marked with `"Date TBD (planned to +60 days)"` badges.
- **Dashboard Action Auditing & Future Session Guard**:
  - Verified `selectedDate` vs `today` across all user actions.
  - Added `DashboardView.canLogProgress(sessionDate, today)` guard blocking completion of future-dated sessions (`DashboardView.java:264-275`), verified via `DashboardLogProgressGuardTest`.
- **Replan Triggers & Subject Resync**:
  - Audited all replan triggers (log, resize, swap, cancel, timetable/sleep, exam, scope, credits).
  - Added test in `SemesterReplanServiceTest#testReplanCoversEveryDayFromTodayToLastExamDeadline`.
  - Clarified 3rd argument in `ExamScopeService.resyncHoursForSubject` (matches `subject_code` OR `subject`), added 2-arg overload, and fixed inconsistent callers across `TopicFormController.java`.
- **Feasibility Invariants**:
  - Added 3 feasibility tests in `FeasibilityServiceTest.java`:
    1. PYQ only for exams with syllabus scope.
    2. Never 0.0/0.0 when topics exist.
    3. Revision-only topics count due revision hours in backlog needed.
- **Subjects Tab UI**:
  - Labels styled with dark text (`#1e40af`, `#1e293b`).
  - Tooltips added to selection and completion checkboxes.
- **Manual Verification Tests**:
  - `VerifyPhase4ManualTest` and `VerifyPhase5ManualTest` skip gracefully using `assumeTrue(orig.exists(), ...)` when `learntrack.db` is missing.
