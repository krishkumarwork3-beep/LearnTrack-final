CREATE TABLE IF NOT EXISTS semesters (
    semester_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    status        TEXT DEFAULT 'ACTIVE'
);

-- Recurring weekly commitments for NORMAL teaching days. Entered once per semester.
-- Also used for MST/EST topics BEFORE a datesheet exists (see section 4).
CREATE TABLE IF NOT EXISTS weekly_timetable (
    entry_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    semester_id     INTEGER NOT NULL,
    day_of_week     TEXT NOT NULL,      -- MONDAY..SUNDAY
    start_time      TEXT NOT NULL,
    end_time        TEXT NOT NULL,
    activity_type   TEXT NOT NULL,      -- CLASS | TUTORIAL | LAB | MEAL | SLEEP | OTHER
    label           TEXT,
    room            TEXT,
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

CREATE TABLE IF NOT EXISTS weekday_study_preference (
    semester_id   INTEGER NOT NULL,
    day_of_week   TEXT NOT NULL,
    study_hours   REAL NOT NULL,
    limit_enabled INTEGER NOT NULL DEFAULT 0,  -- 0 = no limit (use all free time), 1 = cap at study_hours
    PRIMARY KEY (semester_id, day_of_week),
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

CREATE TABLE IF NOT EXISTS weekday_routine (
    semester_id   INTEGER NOT NULL,
    day_of_week   TEXT NOT NULL,
    wake_time     TEXT NOT NULL,
    sleep_time    TEXT NOT NULL,
    PRIMARY KEY (semester_id, day_of_week),
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);


-- Simplified fixed routine used ONLY once a real MST/EST datesheet exists.
-- Entered once, applies to every day inside the resulting exam window.
CREATE TABLE IF NOT EXISTS exam_period_routine (
    routine_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    semester_id     INTEGER NOT NULL,
    start_time      TEXT NOT NULL,
    end_time        TEXT NOT NULL,
    activity_type   TEXT NOT NULL,      -- MEAL | SLEEP | OTHER
    label           TEXT,
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

-- One-off changes to a SPECIFIC date: CANCEL frees a recurring block,
-- ADD blocks new time (e.g. a dated exam sitting + its buffer).
CREATE TABLE IF NOT EXISTS date_overrides (
    override_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    override_date  TEXT NOT NULL,
    entry_id       INTEGER,
    action         TEXT NOT NULL,      -- 'CANCEL' | 'ADD'
    start_time     TEXT,
    end_time       TEXT,
    activity_type  TEXT,               -- EXAM | OTHER
    label          TEXT
);

-- exam_type is also the BATCH key: all exams sharing the same exam_type within
-- an active semester ('MST' or 'EST') are treated as one batch for the
-- negotiation loop and the important_flag reset (sections 4, 9). A semester
-- typically has at most one MST batch and one EST batch.
CREATE TABLE IF NOT EXISTS exams (
    exam_id              INTEGER PRIMARY KEY AUTOINCREMENT,
    semester_id           INTEGER NOT NULL,
    subject               TEXT NOT NULL,         -- display name, kept for compatibility
    subject_code          TEXT,                  -- links to subject list (timetable/kept/names)
    exam_type             TEXT NOT NULL,         -- 'NORMAL' | 'MST' | 'EST'
    eval_name             TEXT,                  -- name for "Other evaluation", NULL for MST/EST
    credits               REAL DEFAULT 1,        -- entered upfront for EVERY exam type
    eval_marks             REAL DEFAULT 0,        -- entered upfront for EVERY exam type
    exam_date             TEXT,                  -- exact date; NULL until datesheet released (MST/EST only)
    exam_date_tentative   INTEGER NOT NULL DEFAULT 0, -- 1 if exam_date is a tentative window start, 0 if confirmed/exact
    exam_start_time        TEXT,                  -- NULL until known (see section 4)
    exam_end_time          TEXT,
    travel_time_minutes    INTEGER DEFAULT 30,
    window_start_date      TEXT,                  -- provisional, MST/EST only; doubles as the effective
                                                   -- deadline for ALL of this exam's topics until a real
                                                   -- exam_date is entered (section 4)
    window_end_date        TEXT,
    pyq_remaining_hours    REAL DEFAULT 2,        -- see section 5a; counts down like a topic's remaining_hours
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

CREATE TABLE IF NOT EXISTS topics (
    topic_id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    exam_id                    INTEGER NOT NULL,
    name                       TEXT NOT NULL,
    importance                 INTEGER NOT NULL,
    difficulty                  INTEGER NOT NULL,
    hours_needed               REAL NOT NULL,
    remaining_hours            REAL NOT NULL,       -- LEARNING phase, overwritten on "Not yet"
    phase                      TEXT DEFAULT 'LEARNING',   -- LEARNING | REVISION
    revision_hours_needed      REAL DEFAULT 0,       -- size of ONE revision cycle
    revision_remaining_hours   REAL DEFAULT 0,       -- left to do in the CURRENT cycle
    revision_interval_days     INTEGER DEFAULT 7,
    last_revised                TEXT,                 -- only updated when a revision cycle is CLOSED (section 10)
    important_flag             INTEGER DEFAULT 1,     -- 0 = temporarily excluded from the queue during an
                                                        -- active MST/EST negotiation (see section 5 and 9)
    subtopic_id                INTEGER,               -- links topic to syllabus subtopic; NULL for manual topics
    FOREIGN KEY (exam_id) REFERENCES exams(exam_id),
    FOREIGN KEY (subtopic_id) REFERENCES syllabus_subtopics(subtopic_id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS skills (
    skill_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT NOT NULL,
    category         TEXT DEFAULT '',
    proficiency      INTEGER DEFAULT 1,
    last_practiced   TEXT DEFAULT '2000-01-01'
    -- no semester_id: never deleted on semester rollover, tracked forever
);

CREATE TABLE IF NOT EXISTS skill_subtopics (
    subtopic_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id               INTEGER NOT NULL,
    name                   TEXT NOT NULL,
    position               INTEGER NOT NULL,
    learn_hours            REAL NOT NULL DEFAULT 1.0,
    learn_remaining_hours  REAL NOT NULL DEFAULT 1.0,
    revise_hours           REAL NOT NULL DEFAULT 0.5,
    revise_remaining_hours REAL NOT NULL DEFAULT 0.5,
    revise_after_days      INTEGER NOT NULL DEFAULT 7,
    learned                INTEGER NOT NULL DEFAULT 0,
    learned_on             TEXT,
    revised                INTEGER NOT NULL DEFAULT 0,
    revised_on             TEXT,
    revision_due_date      TEXT,
    FOREIGN KEY (skill_id) REFERENCES skills(skill_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS daily_log (
    log_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    log_date     TEXT NOT NULL,
    item_type    TEXT NOT NULL,   -- 'TOPIC_LEARN' | 'TOPIC_REVISE' | 'SKILL' | 'SKILL_LEARN' | 'SKILL_REVISE' | 'PYQ_PRACTICE'
    item_id      INTEGER NOT NULL,  -- topic_id / skill_id / subtopic_id, or exam_id for PYQ_PRACTICE
    slot_start   TEXT,
    slot_end     TEXT,
    status       TEXT DEFAULT 'PENDING'   -- DONE | CONTINUING | MISSED
);

-- Subjects removed from timetable but kept for exam/study tracking
CREATE TABLE IF NOT EXISTS kept_subjects (
    semester_id   INTEGER NOT NULL,
    subject_code  TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    PRIMARY KEY (semester_id, subject_code),
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

-- Subject renames: maps subject codes to display names
CREATE TABLE IF NOT EXISTS subject_names (
    semester_id   INTEGER NOT NULL,
    subject_code  TEXT NOT NULL,
    subject_name  TEXT NOT NULL,
    PRIMARY KEY (semester_id, subject_code),
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

-- Syllabus management: units (sections) and subtopics per subject
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
    difficulty    INTEGER NOT NULL DEFAULT 5,
    hours_to_cover REAL NOT NULL DEFAULT 1.0,
    hours_to_revise REAL NOT NULL DEFAULT 0.5,
    FOREIGN KEY (unit_id) REFERENCES syllabus_units(unit_id) ON DELETE CASCADE
);

-- Subject-level credits (source of truth for exams of this subject)
CREATE TABLE IF NOT EXISTS subject_credits (
    semester_id   INTEGER NOT NULL,
    subject_code  TEXT NOT NULL,
    credits       REAL NOT NULL DEFAULT 4.0,
    PRIMARY KEY (semester_id, subject_code),
    FOREIGN KEY (semester_id) REFERENCES semesters(semester_id)
);

-- Exam syllabus scope: tracks which subtopics are selected for each exam (MST/EST/Other)
CREATE TABLE IF NOT EXISTS exam_syllabus_scope (
    exam_id       INTEGER NOT NULL,
    subtopic_id   INTEGER NOT NULL,
    PRIMARY KEY (exam_id, subtopic_id),
    FOREIGN KEY (exam_id) REFERENCES exams(exam_id) ON DELETE CASCADE,
    FOREIGN KEY (subtopic_id) REFERENCES syllabus_subtopics(subtopic_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS settings (
    key    TEXT PRIMARY KEY,
    value  TEXT
);

-- Seeded settings rows
INSERT OR IGNORE INTO settings (key, value) VALUES ('skill_session_length', '1.0');
INSERT OR IGNORE INTO settings (key, value) VALUES ('revision_majority_ratio', '0.8');
INSERT OR IGNORE INTO settings (key, value) VALUES ('pyq_practice_hours', '2');
INSERT OR IGNORE INTO settings (key, value) VALUES ('skill_lookahead_days', '14');
INSERT OR IGNORE INTO settings (key, value) VALUES ('break_minutes', '0');
INSERT OR IGNORE INTO settings (key, value) VALUES ('min_session_minutes', '15');
INSERT OR IGNORE INTO settings (key, value) VALUES ('mst_exam_duration_minutes', '120');
INSERT OR IGNORE INTO settings (key, value) VALUES ('est_exam_duration_minutes', '180');
