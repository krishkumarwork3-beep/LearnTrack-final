# LearnTrack: Comprehensive Study Planner

LearnTrack is a JavaFX desktop application that automatically schedules exam preparation and skill development by filling every free slot in your timetable with optimal study sessions.

## Core Features

- **Automated Daily Schedule Generation**: fills all free slots (class breaks, evenings, weekends) with study sessions for exam topics and skills, respecting your weekly timetable, sleep routine, and exam buffers.
- **Multi-Exam Management**: handles NORMAL semester-end exams, MST (Mid-Semester Term), and EST (End-Semester Term) batches with separate datesheets and capacity planning.
- **Syllabus-Driven Topic Planning**: parse `.txt` or `.docx` syllabus files to generate topics with estimated hours (learn + revise) per subtopic, or manually define them.
- **Datesheet Analyser**: paste MST/EST datesheet text, automatically match exams by course code, set end times based on duration, and trigger capacity negotiation if needed.
- **Skills with Subtopics**: manage long-term skills (e.g., "Guitar › Fingerpicking", "Rust › Ownership") with Learn and Revise hours, due-date tracking, and automatic rescheduling.
- **Feasibility Bar**: real-time visualization showing whether remaining work fits within available study capacity before each exam deadline.
- **Interactive Calendar & Day View**: click any day to see its merged schedule (classes, sleep, study sessions); planned sessions auto-fill future days.
- **Manual Adjustments**: resize, swap, or log progress on individual sessions; the planner replans future days automatically.

## Tech Stack

- **Language**: Java 17
- **UI**: JavaFX 21
- **Database**: SQLite via raw JDBC (no ORM)
- **Build**: Maven
- **Testing**: JUnit 5

## Requirements
- Java Development Kit (JDK) 17 or higher
- Apache Maven 3.8+

## How to Build

To compile, run all tests, and package the application:
```bash
mvn clean install
```

## How to Run

To launch the JavaFX desktop application:
```bash
mvn javafx:run
```

Or run the compiled executable JAR directly:
```bash
java -jar target/learntrack-1.0.0.jar
```

On first launch, the **Timetable Setup Wizard** walks you through entering:
1. Weekly class/lab/meal schedule
2. Sleep routine (wake time, sleep time)
3. Weekday study preferences (hours per day)

Once complete, the app auto-generates a full study plan starting from today.

## Key Workflows

### 1. Add a Subject and Exam
- Go to **Exams** tab → **Add Exam**
- Enter subject code (e.g., `UCS503`), type (`NORMAL`, `MST`, or `EST`), credits, marks, and exam date
- If you have a syllabus file, go to **Subjects** tab → **Import Syllabus** to auto-generate topics

### 2. Define Exam Scope and Topic Importance
- Select exam in **Exams** tab → **Edit Scope**
- Mark which syllabus subtopics are covered (hours to learn/revise per subtopic)
- The planner schedules only in-scope content
- In **Subjects**, rate every subtopic's **Importance** (Optional · Low · Normal · High · Must-know),
  one at a time or per unit with the unit's **Importance** menu

### 3. Apply a Datesheet (MST/EST)
- Go to the **Datesheet Analyser** tab
- Paste datesheet text from official PDF
- Click **Analyse** → verify matches and end times → **Apply Datesheet**
- If capacity is tight, a dialog opens to prioritize or trim topics

### 4. Add Skills
- Go to the **Skills** tab → **Add Skill**, choosing a **Priority** (Someday … Top focus) and an optional **Target date**
- Add subtopics with Learn Hours, Revise Hours, and "Revise after N days"
- Tick "Learned" when complete; revision sessions appear automatically after the due date
- Skills are scheduled after exam work; higher priority skills get free time first, a tight target
  date raises a skill's urgency, and overdue revisions climb the queue
- Each skill card shows whether the plan finishes it before its target date

### 5. Daily Use
- Open **Today** to see the day's schedule, what's up next, and how much is done
- Tick a session when done, or double-click it to log partial progress
- Resize or cancel sessions as needed; the planner refills freed time automatically

### 6. Check Coverage
- **Plan insights** (Today page) shows, per exam, how much of the importance-weighted syllabus is
  learned or planned before the exam date, and lists the topics that don't fit
- The sidebar's **Syllabus coverage** card summarises it across all exams
- If important topics don't fit, add free time, trim scope (**Exams → Review topic priorities / capacity**),
  or lower the importance of topics that matter less

## How the Planner Decides

1. **Free time** for every day until the last exam is computed from the timetable, sleep routine and
   exam-period routine.
2. **Coverage optimizer** (`CoverageOptimizer`): each exam is worth `credits × marks`, shared among
   its topics by importance weight (Must-know ≈ 12× Optional). Topics are considered by value per
   hour, and one is accepted only if, for *every* exam date, all accepted work due by then still fits
   in the free time before it (minus a configurable safety buffer). One revision cycle per topic and
   the remaining PYQ hours are reserved.
3. **Day-by-day scheduling**: planned topics go earliest-deadline-first; inside an exam, Must-know
   topics come first (or syllabus order, see **Settings**). PYQ practice follows, then topics that
   didn't fit (if time frees up), then skills.

## Database Schema Highlights

- `semesters`, `exams`, `topics`: core exam and topic entities
- `weekly_timetable`, `exam_period_routine`: fixed commitments
- `daily_log`: scheduled, completed, and missed study sessions
- `skill_subtopics`: skills with learn/revise tracking, learned/revised checkboxes
- `exam_syllabus_scope`: which subtopics are in-scope for each exam
- `settings`: user preferences (skill session length, revision ratio, exam duration, etc.)

## Project Structure

```
src/main/java/com/learntrack/
├── dao/           Data access (JDBC PreparedStatement)
├── model/         Plain data classes
├── service/       Business logic (planning, scoring, parsing)
├── ui/
│   ├── forms/     Exam, topic and skill editors
│   ├── views/     Dashboard, Calendar, Datesheet Analyser
│   ├── setup/     Wizards (Timetable, Exam Period Routine, Topic Trimming)
│   └── charts/    Feasibility bar
└── util/          DBConnection, date utilities

src/main/resources/db/schema.sql: SQLite schema ||| - `SemesterReplanServiceTest`: core planning algorithm
docs/                             Implementation plan, user guide
```

## Testing

Run all tests:
```bash
mvn test
```

Key test suites:
- `SemesterReplanServiceTest`: core planning algorithm
- `DatesheetParserTest`: datesheet text parsing robustness
- `SkillSchedulingTest`: skill subtopic lifecycle and priority tiers
- `FeasibilityServiceTest`: capacity calculation correctness
- `TimetableXlsxReaderTest`: weekly timetable import from Excel

## Documentation

- `docs/LearnTrack_Implementation_Plan.md`: technical design and architecture
- `docs/LearnTrack_User_Guide.md`: user-facing feature walkthrough
- `docs/LearnTrack_Build_Steps.md`: setup and deployment

## License

This project is for educational and personal use.
