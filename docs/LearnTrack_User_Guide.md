# LearnTrack: User Guide

## 1. First-time setup, each semester

**Step 1: Enter your daily wake/sleep routine and weekly timetable (once).**
Enter your wake-up time and sleep time first (with an option to apply across all days or customize per day, e.g. on weekends). Then add your fixed weekly commitments: classes, labs, meals, and other recurring blocks (multi-day selection supported). End times must be strictly after start times for commitments.

**Step 2: Review the computed free time and set your study targets.**
The app shows your free hours per weekday (calculated from your awake window, taking into account any after-midnight sleep spills from the night before, minus fixed commitments); you decide how much of that you actually want to study.

**Step 3: Add exams, then topics under each.**
For **every** exam, whether a normal quiz or MST/EST, enter its **credits and marks weightage upfront**, since those describe the evaluation itself and don't depend on knowing a date. Credits may be fractional (e.g., 0.5, 1.0, 1.5, 2.0, 2.5, ...).

- **Normal quiz/evaluation**: also enter the exact date, start/end time, and travel time to the venue, all upfront.
- **MST/EST (major exam)**: if the datesheet isn't out yet, just enter a rough window (earliest possible start) instead of a date. You don't need start/end time or travel time yet; those only become relevant once the real date is known. **Until the datesheet arrives, the app treats your window's start date as if it were the real exam date** and schedules those topics into your normal weekly routine right alongside everything else. Nothing special happens on that date itself; it is only used to judge urgency, the same as any other deadline.

Add topics per exam: importance, difficulty, learning hours, revision hours per cycle, and how often to revisit it.

**Step 4: Add your skills.** Set these up once and they stay unaffected by semester changes.

---

## 2. Your daily routine

1. Open the app → **Dashboard** → today's suggested timetable, built from your real free time.
2. Edit it if you like: swap or resize sessions.
3. Log each session honestly:
   - **Learning a topic for the first time:** finished → Yes; not finished → tell the app how many more hours you think you need.
   - **Revising a topic:** fully completed → Yes, and it's freshly revised, ready to come due again after your set interval. Not finished → Not yet with a new estimate, and the app keeps it urgent rather than treating it as freshly revised.
   - **A skill session:** just log that you practiced it, whatever the length. No hours estimate is needed.
   - **Solving PYQs:** works exactly like a topic: finished → Yes; still have more to go → Not yet with a new estimate.
4. **Any of this** (finishing early, falling short, a class getting cancelled) **regenerates your entire remaining semester calendar**, not just today or tomorrow.

---

## 3. If a class or lab gets cancelled

Just mark it cancelled for that date. The freed time is absorbed into the same whole-calendar regeneration, and it reflows to wherever it's needed, automatically.

---

## 4. How PYQ practice works (same rule everywhere, no special day)

There's no fixed "reserve 2 hours the day before" rule. Instead: **once you've fully covered every topic under an exam that's still in scope**, the app starts working 2 hours of previous-year-question practice into your schedule for that exam, fitted in wherever it naturally fits as the exam approaches. If you haven't finished the syllabus yet, PYQ practice simply won't show up, because covering the topics always comes first.

---

## 5. Normal quizzes and evaluations

**Two exams the same day:** split by **credits × marks weightage**: a high-mark test in a lower-credit subject gets more time than a low-mark quiz in a higher-credit subject.

**Exam day:** you can keep studying right up until you actually need to leave for the venue, with no wasted buffer beforehand. Once the exam ends, a 1-hour buffer is kept clear before the next thing gets scheduled, still working around your meals and other fixed times.

**The day before:** the majority (80% by default) of your free time that day goes to revising that exam's topics (and its PYQ practice, once eligible), and the rest to whatever else is most urgent. **If two exams both happen to fall the day after** (so today is the eve for both), that 80% is itself split between them by the same credits × marks weighting used for same-day clashes, so the higher-stakes exam gets the bigger share of the revision time, rather than one exam taking it all.

---

## 6. MST / EST season (major exams)

All the MST exams in a semester count as one "batch," and all the EST exams count as another; they are planned and negotiated together as a unit.

**Before the datesheet:** nothing special happens: those topics are simply part of your normal Dashboard, scored as if the exam were on your entered window's start date, and scheduled around your regular weekly timetable like everything else. Credits and marks weightage are still in effect from setup even at this stage. **If the datesheet is delayed past even your rough window**, the app doesn't break or drop the subject from your plan; it just keeps treating it as increasingly urgent (falling back to your window's end date, and then to "due immediately" if that passes too) until a real date finally arrives.

**Once the datesheet is released:** apply it, either through the Datesheet Analyser or by entering the dates yourself. From that moment the whole span covered by that batch's datesheet (its first date to its last date, weekends and exam-free days included) becomes exam-mode: your weekly class timetable stops applying on those days, and instead the app uses a simple exam-period routine (meals, sleep, other fixed times) across the whole window, plus the travel/after-exam buffers around your own sittings. Any exams in that batch still without a date keep using their window dates as deadlines. Section 12 describes the window in detail.

**On the day of a dated exam:** study right up until you need to leave, resume 1 hour after it ends, meals and other fixed times always respected.

**If everything doesn't fit** in the time you actually have once dated:
- The app asks if you can add more study hours per day.
- If not, it asks you to mark your most important topics; the rest are set aside and stop competing for time until the window closes.
- This repeats until the plan genuinely fits.
- **Once the exam window ends**, any topics set aside during this trimming automatically become eligible again.

---

## 7. If you miss a day entirely

Open the app the next day: it detects the miss and regenerates the whole remaining calendar automatically.

---

## 8. Starting a new semester

Starting a new semester **permanently deletes** the old semester's exams, topics, and timetable, and you'll be asked to confirm first. **Your Skills tab is completely unaffected** and keeps tracking across every semester. Even after all your exams for a term are done, the app keeps generating a rolling schedule (checked every time you open the app) so your skills don't go unattended in the gap before your next semester.

---

## 9. Rules of thumb

- **Log every session honestly**: every log triggers a full recalculation of your whole remaining term.
- **Enter credits and marks weightage for every exam upfront**, even MST/EST ones you don't have a date for yet, because it is what the app uses to weigh same-day clashes and PYQ urgency correctly.
- **Don't worry about entering MST/EST exam times early**: the rough window is enough until the datesheet actually exists.
- **Mark cancellations as soon as you know**: the sooner it is marked, the more the rest of your semester benefits.
- **Update the real date the moment a datesheet is released**: that is what switches the exam into its proper exam-mode planning.
- **Trust the PYQ timing**: it only shows up once you've genuinely finished the syllabus for that exam, so if you don't see it yet, that's a sign there's still topic work to do.
- **Treat "not enough time" prompts as useful signals**, not failures.
- **Before starting a new semester, be sure you're done with the old one**: deletion is permanent (skills are the only thing that always survives).


---

## 10. Syllabus Management (Subjects Tab)

The **Subjects** tab is a master-detail screen. The left column lists every subject of the active semester (those in your timetable, kept subjects that were removed from the timetable but are still tracked, and any subject that has an exam), with its code, name, a slim progress bar and a status line (No syllabus, In progress, Done). Type in **Search by code or name…** to filter the list; the subject you last opened stays selected while the app is running.

The right side shows one subject at a time, with a header card (name, code, credits, exam dates, overall progress) and its syllabus, with the unit list underneath.

Units appear as collapsible cards under a toolbar with **+ Add Unit**, **Paste Syllabus** and **Clear Syllabus**. Each subtopic row has a *Studied* checkbox, its name, a status chip, its learn and revise hours and icon buttons to move it up or down, rename it, set its hours or delete it. When a subject has no syllabus you get an empty state that offers **+ Add Unit** and **Paste Syllabus** right there.

**Hours are edited in the row.** Click a learn or revise time to turn it into a field; type `1h 30m`, `1.5`, `90m` or `1:30` and press **Enter**. Esc or clicking away keeps the old value, and anything outside 15 minutes to 40 hours is refused (the field turns red). Edited hours are kept: re-syncing or rebuilding the study plan copies them onto the topics and never overwrites them.

Study topics themselves are created from each exam's syllabus scope in the **Exams** tab, where you choose which subtopics an exam covers; the planner then plans them from the hours entered here.

**Importing a syllabus:**
1. Choose the subject and click **Paste Syllabus**
2. Paste syllabus text into the dialog, or use **Load .txt / .docx…** to pull the text from a file
3. Click **Parse**. The summary row shows how many units and subtopics were found
4. Edit the preview (rename or remove units and subtopics) before importing
5. Pick **Add to existing** or **Replace** and click **Import**; the button stays disabled until something has been parsed

**Tips for pasting:**
- PDF syllabi: Open the PDF, select all text, copy, and paste into the dialog
- Hard line breaks and hyphenated words are automatically fixed
- Commas inside parentheses are preserved (e.g., "Artificial Intelligence (AI), Machine Learning" stays as two items)

**Managing your syllabus:**
- Check off subtopics as you complete them; progress is shown per unit and for the whole subject
- Add new units or subtopics manually
- Bulk add subtopics by entering comma-separated text
- Reorder units and subtopics with the move up/down buttons
- Delete units (with confirmation naming what will be removed)
- Clear the entire syllabus with one button (with confirmation naming the subject and what it deletes)

Successes (a unit added, a syllabus imported or cleared) appear as a short message at the bottom right instead of a dialog you have to dismiss.

All changes save immediately. Your syllabus data is cleaned up automatically when you start a new semester, just like exams and topics.


---

## 11. Skills Tab: Long-Term Learning Beyond Exams

The **Skills** tab lets you track ongoing skills (coding languages, musical instruments, languages, etc.) that span across semesters. Unlike exam topics, skills never expire when you start a new semester; they are permanent entries that roll forward indefinitely.

### Editing a skill

Click **Edit skill** on a card to change its name or category. Click a Learn, Revise or Every value in the subtopic table to edit it in place: **Enter** saves, **Esc** cancels, and a value outside the allowed range is refused with a message under the table. Double-click a row (or use the pencil icon) to change the name and hours in a dialog.

### Adding a Skill

1. Click **Add Skill**
2. Enter name (e.g., "Guitar"), category (e.g., "Music"), and proficiency (1-5)
3. Add **subtopics** with specific learning goals:
   - **Learn Hours**: time needed to initially learn this subtopic
   - **Revise Hours**: time needed for one revision cycle
   - **Revise after N days**: how many days after learning before revision is due

### Learned and Revised Checkboxes

- **Tick "Learned"** when you've completed the initial learning for a subtopic
  - Sets `learned = true`, `learned_on = today`
  - Automatically calculates `revision_due_date = today + revise_after_days`
  - Remaining learn hours drop to zero
  - The app will schedule a SKILL_REVISE session starting on the due date

- **Tick "Revised"** when you've completed a revision cycle
  - Sets `revised = true`, `revised_on = today`
  - Resets the revision cycle (next revision due after N days again)
  - Remaining revise hours reset to full revise hours needed

### How Skills Are Scheduled

- **Priority tier**: Skills are always scheduled **after** exam topics and PYQ practice
- **Learn before Revise**: For the same skill, Learn subtopics (position order) come before Revise
- **Rollover on miss**: If a SKILL_LEARN or SKILL_REVISE session is PENDING on a past day, it becomes MISSED, the hours roll over, and the subtopic is rescheduled starting from today
- **No revision before due date**: SKILL_REVISE sessions never appear before the `revision_due_date`

### When Skills Get Pushed to Later Days

Skills may not appear on a given day if:
- Exam topics with approaching deadlines need all available capacity
- The exam window is tight (MST/EST period with dated exams)
- You have "Not yet" sessions from topics that consumed extra hours

Once exam pressure eases, skills automatically fill the freed slots.

---

## 12. Datesheet Analyser: Fast MST/EST Setup

The **Datesheet Analyser** tab turns an official datesheet into exact exam dates, start times and end times, so you do not have to retype dozens of rows. It understands the layouts LearnTrack actually receives from university PDFs, and it asks you a question instead of guessing when a layout is ambiguous.

### The three layouts it understands

**Layout A: row-wise MST text.** One exam per line with the date and time next to the course:

```
Date Sheet Code: MST-2026-ODD
1 2026-10-01 09:00 AM UCS503 SOFTWARE ENGINEERING
2 2026-10-02 1:00 PM UCT301 NETWORK THEORY
```

**Layout B: row-wise EST text extracted from the PDF.** The same one-row-per-exam idea, but after a PDF copy/paste: repeated page headers (`Page 1 of 5`, the institute name), weekday-only lines (`Friday`) followed by courses on the next lines, weekday + course on one line (`Monday UEC311 CIRCUIT ANALYSIS`), course names wrapped over two lines, and times such as `09:00 AM`, `1:00 PM`, `4:30 PM`. The exam type is detected from the sheet code (for example `2425ODDEST` is EST) and can still be overridden.

**Layout C: column-block paste.** Some PDF viewers copy a page column by column: every date first, then every time, then every course code, with no line tying them together. LearnTrack pairs dates, times and code groups only when the pairing is unambiguous (matching counts, dates in chronological order, and times on the same date strictly increasing). When it cannot be sure, the affected courses are flagged for your decision instead of being written with a guessed date.

### Paste the datesheet text

- **Paste** the datesheet text into the text area and press **Analyse Datesheet**.
- Copy it from the original document (for a PDF, select all the text and copy). Pasting keeps the exact row or column order of the source, which is what the parser relies on.
- If a pasted PDF arrives with its columns separated, the app asks you to choose the slot for the uncertain courses.

### Exam type

The **Exam Type** ComboBox sits above the text area and starts at **MST**. After an analysis it is set automatically from the detected sheet code (for example `2425ODDEST` → EST). You can override it before applying; Apply stamps the selected type on every preview row, and rows of a different type are skipped. The list also contains **Other** for other evaluation sheets, but only **MST** and **EST** create an exam window.

### Review before applying

The preview table lists each matched course with its code, subject, datesheet name, type, date, weekday, start time, and an **editable End** column. End times are computed as *start + the duration for that exam type* (see below) when the datesheet gives only a start; edit a cell if a particular paper runs longer or shorter. **Subjects Not Found in Datesheet** lists your subjects that the sheet did not mention, and **Warnings & Clashes** lists approximate code matches, name discrepancies, past dates, same-day clashes, and column-block warnings.

### "Needs Your Decision": ambiguous courses

When a column-block paste cannot be paired safely, the affected courses appear in the amber **Needs Your Decision** panel. Each row is one of *your* subjects with a dropdown of its candidate slots (`02/12/2024 at 09:00`, `03/12/2024 at 14:00`, …) plus a first entry, **"(Skip this course)"**.

- Pick a slot and that exact date and start time are applied, with end = start + the type's duration.
- Leave it on **(Skip this course)** and the exam is left exactly as it was; in particular it is **never applied silently** with a guessed date.
- **Apply to Exams** is disabled while the preview table is empty, while a replan is running, or while any decision row has no value. Every decision row starts as a skip, so the panel never blocks you by itself, but nothing is written for a flagged course until you choose a slot.

Flagged courses that are not your subjects are never shown here; only your own subjects need a decision.

### How the exam window works

Applying a datesheet stores a window for that exam type, running from the **first date to the last date of the whole datesheet**, including weekends and days on which none of *your* exams fall. The window is a property of the batch (all MST exams, or all EST exams), not of a single subject.

Inside that window:

- **Weekly classes, labs and tutorials are not followed.** They are dropped from the day, which is why the Dashboard and Calendar day headers read **"MST exam period, no classes"** (or **"EST exam period, no classes"**).
- **Sleep and meals still block time**, taken from your exam-period routine when you have one, or from your normal wake/sleep plus your weekly MEAL/SLEEP/OTHER entries when the exam-period routine is empty.
- **Your own exam sittings still block time**, using the asymmetric buffer: travel time before the sitting, one hour after it ends.
- Study sessions fill whatever free time is left, including the hours your classes used to occupy.

If no datesheet window has been stored yet, the window falls back to the span from your **first to your last dated exam** of that type: the start is the first exam's date, never today. That is what makes "exam period" mode begin on the correct day even when you typed the dates by hand. The Exams tab shows the same window next to **Sheet window**, so the dates you see there are the ones the planner uses.

Switch an exam back to tentative mode (untick its datesheet checkbox in the exam form and confirm) and the stored window is cleared, the exact dates are reset, and planning returns to the normal weekly timetable.

### Exam duration settings

End times come from two separate settings, because MST and EST papers differ:

| Setting | Default | Where |
|---|---|---|
| MST Exam Duration (minutes) | **120** | Settings → Save Settings |
| EST Exam Duration (minutes) | **180** | Settings → Save Settings |

Both spinners allow 30 to 360 minutes in 15-minute steps. Saving settings stores them, replans the semester, and uses the new values for future analyses. Existing exams keep the end times you already applied, so re-analyse (or edit the End column) if a duration changes mid-season.

### Parser robustness

The parser handles:
- Multi-page datesheets with repeated headers (`Page 1`, `Page 2`, …)
- Weekday-alone lines (`Friday`) followed by courses on the next lines
- Weekday + course on the same line (`Monday UEC311 CIRCUIT ANALYSIS`)
- Wrapped course names across two lines
- Time formats: `09:00 AM`, `1:00 PM`, `4:30 PM`, `09:00 to 11:00`
- Garbage lines (skipped gracefully without crashing)

Dates and times are only assigned when they can be paired unambiguously; anything else is flagged for the "Needs Your Decision" panel.

### What "Not found" Means

If a course shows "Not found":
- The course code in the datesheet doesn't match any existing exam (case-insensitive)
- Go to **Exams** tab → **Add Exam** and create it with the exact course code
- Return to Datesheet Analyser and **Analyse** again; it will match now

### Idempotent Apply

Applying the same datesheet twice is safe:
- Each exam keeps exactly one entry per subject and exam type
- If you edit times manually and re-apply, the new times overwrite the old ones
- No duplicate exams are created


---

## 13. Appearance, window and shortcuts

All screens share one look: cards on a warm neutral background, deep ink for primary actions, and colour only for status, always next to a word. Status values (On track, Tight, Behind, Dated, Tentative) appear as a small dot plus text rather than a coloured pill.

- **Theme**: Settings has a **Theme** choice. Light is the default; pick **Dark** for a low-glare palette on all tabs and every dialog you open afterwards. It applies immediately and is remembered on the next launch.
- **Shortcuts**: `Ctrl+1` … `Ctrl+7` jump to Dashboard, Calendar, Exams, Subjects, Skills, Datesheet Analyser and Settings. `Esc` closes every dialog and the setup wizards without saving, and `Enter` confirms the small text dialogs (add unit, rename, add subtopic) and the Add Skill row.
- **Window**: the app opens at 980×640 or larger and remembers its size, position and the last tab you had open.
- **Messages**: successful actions appear as a short message in the bottom right corner. Errors open a dialog with a plain-language heading; the technical detail is folded away under *Show details*.
- **Busy times**: while the plan is being regenerated, a progress line runs under the tabs and the buttons that would start another replan are disabled.
- A course you chose to skip stays untouched on every later apply

---
