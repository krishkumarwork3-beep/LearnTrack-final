# LearnTrack planner fix plan

## Rules (always)

No test weakening. Never touch the real learntrack.db from tests. Show evidence. Read docs/PLANNER_FIX_PLAN.md at the start of each session. One phase at a time, then stop.

## User requirements

- Subjects have credits. Each subtopic has hours_to_cover and hours_to_revise (no difficulty).

- Dashboard and Calendar day view: the full timetable of that day + sleep + all remaining free slots filled with study sessions of subtopics from different subjects.

- NO daily study limit. Fill every free slot (minimum fragment 15 min, setting min_session_minutes). A subtopic is split across slots, and any remainder carries to the next day (example: a 4 h topic with free slots of 1 h and 2 h gets 3 h that day, 1 h next day). Subjects interleave by urgency (deadline, then credits*marks). Breaks off by default (break_minutes = 0).

- Session shortening: unfinished hours go back to the topic and later days are replanned.

- Calendar: clicking any day shows that day's complete schedule; future days are pre-planned (PLANNED rows).

- If a subtopic was covered for an earlier exam (MST etc.), later exams use hours_to_revise only (already implemented in ExamScopeService).

- PYQ only after all learning for that exam is done, and only for exams that have syllabus scope.

## Status

- Phase 1 DONE: pyqEligible needs topics; undated exam deadline = today+60; PriorityItem tiers (topics/skills before PYQ); regressions undone.

- Phase 2 DONE: topic hours resync, hasBeenStudied counts only DONE/CONTINUING, rebuildPlan, startup repair, debounced replan.

- Phase 3 DONE: All tests strict and green (369 tests, 0 failures). Gaps filled, no study limit, 15 min minimum fragment respected, interleave within day verified, DB hash unchanged.

- Phase 4 DONE: DayScheduleService and DayScheduleView created; merged schedule with classes, sleep, and study sessions integrated on Dashboard with date picker and navigation; verified on copy of learntrack.db for 2026-10-22 (374 tests green, 0 failures).

- Phase 5 DONE:
  - CalendarGridView and DayScheduleView integration complete with cell badges and click-to-view day schedule.
  - Scope warnings: banner on Dashboard for exams without scope, indicators on exams.
  - Undated exams marked as tentative with "Date TBD (planned to +60 days)" badge/chips.
  - Dashboard actions audit: selectedDate vs today verified; guard against marking future-dated sessions done added and tested.
  - Replan triggers: audited and tested (replan covers every day from today to last exam deadline); 3rd argument in resyncHoursForSubject clarified and callers fixed.
  - Feasibility tests: 3 tests added and passing (PYQ only for exams with scope; never 0.0/0.0 when topics exist; revision-only topics count revise hours).
  - Subjects tab: text colors fixed (#1e40af, #1e293b) and tooltips added.
  - VerifyPhase4/5ManualTest updated to skip cleanly when learntrack.db is missing.
  - All 398 tests pass, 0 failures, 0 errors, DB hash unchanged.

## Phase 4: merged day schedule on the Dashboard

1. Create service/DayScheduleService with `List<DayRow> build(int semesterId, LocalDate date)`. DayRow: type (CLASS, LAB, TUTORIAL, MEAL, OTHER, EXAM, SLEEP, TOPIC_LEARN, TOPIC_REVISE, PYQ_PRACTICE, SKILL), start, end, label, DailyLogEntry (nullable), boolean fixed. Sources: weekly_timetable via TimetableDAO.findBySemesterAndDay, date_overrides (CANCEL and ADD), exam entries, wake/sleep from TimetableDAO.findRoutinesBySemester (sleep = sleep_time to midnight on this day, plus midnight to wake_time), and daily_log rows for the date. Sort by start time.

2. Create ui/views/DayScheduleView, a reusable TableView of DayRow. Fixed rows are muted and read-only. Study rows show "UCS615: Java Platform: JVM (Learn)" or "(Revise)" plus hours, and keep Log Progress / Resize / Swap only for study rows. Header: fixed hours, sleep hours, planned study hours, done hours.

3. Replace the table in DashboardView with DayScheduleView. Add previous/next day buttons and a date picker.

4. Tests: a day with classes, a CANCEL override and sleep gives ordered rows with no overlaps; free gaps are filled with study rows.

## Phase 5: calendar and warnings

1. CalendarGridView: clicking any day (past, today, future) shows that day via DayScheduleView/DayScheduleService. Future days come from stored PLANNED rows. Show a planned-study-hours badge in each day cell.

2. Exams tab and Dashboard: warn for each exam with no syllabus scope; mark undated exams as tentative.

3. Feasibility bar: needed = remaining learn + due revision (+ PYQ only for exams with scope); capacity = free study time from today to each deadline. It must not show 0.0 / 0.0 when topics exist.

4. Subjects tab: the "In timetable", "Credits:" and "Multi-select" labels are white on a light background; fix the text colour. Add tooltips to the two unlabeled checkboxes on each subtopic row (select-for-bulk vs done).
