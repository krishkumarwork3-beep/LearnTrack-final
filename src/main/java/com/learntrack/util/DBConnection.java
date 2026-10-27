package com.learntrack.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Manages SQLite database connections and schema initialization.
 * Plan Section 3 & 14.
 */
public class DBConnection {

    private static String dbUrl = null;
    private static String schemaPath = "/db/schema.sql";
    private static boolean initialized = false;

    static {
        loadProperties();
    }

    private static void loadProperties() {
        try (InputStream in = DBConnection.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                dbUrl = props.getProperty("db.url", "jdbc:sqlite:learntrack.db");
                schemaPath = props.getProperty("db.schema.path", "/db/schema.sql");
            } else {
                dbUrl = "jdbc:sqlite:learntrack.db";
                schemaPath = "/db/schema.sql";
            }
        } catch (Exception e) {
            dbUrl = "jdbc:sqlite:learntrack.db";
            schemaPath = "/db/schema.sql";
        }
    }

    /**
     * Allows tests to override the DB URL (e.g. in-memory database).
     */
    public static synchronized void setCustomUrl(String customUrl) {
        dbUrl = customUrl;
        initialized = false;
    }

    public static synchronized void resetToDefaultUrl() {
        loadProperties();
        initialized = false;
    }

    public static synchronized Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        }
        if (!initialized) {
            initSchema(conn);
            initialized = true;
        }
        return conn;
    }

    public static synchronized void initSchema(Connection conn) throws SQLException {
        try (InputStream in = DBConnection.class.getResourceAsStream(schemaPath)) {
            if (in == null) {
                throw new IllegalStateException("Schema file not found at " + schemaPath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    int commentIdx = line.indexOf("--");
                    if (commentIdx >= 0) {
                        line = line.substring(0, commentIdx);
                    }
                    line = line.trim();
                    if (!line.isEmpty()) {
                        sb.append(line).append("\n");
                    }
                }
                String[] statements = sb.toString().split(";");
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : statements) {
                        String s = sql.trim();
                        if (!s.isEmpty()) {
                            stmt.execute(s);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException("Failed to execute schema SQL", e);
        }
        migrateWeeklyTimetableRoomColumn(conn);
        migrateWeeklyTimetableSubjectCodeColumn(conn);
        migrateExamsSubjectCodeColumn(conn);
        migrateExamsEvalNameColumn(conn);
        migrateExamSyllabusScopeTable(conn);
        migrateTopicsSubtopicIdColumn(conn);
        migrateSyllabusSubtopicsDifficultyColumn(conn);
        migrateExamsDateTentativeColumn(conn);
        migrateSubjectCreditsTable(conn);
        migrateSyllabusSubtopicsHoursColumns(conn);
        migrateWeekdayStudyPreferenceLimitEnabled(conn);
        migrateSkillSubtopicsTable(conn);
        migrateSkillsLastPracticedDefault(conn);
    }

    /**
     * Older databases declare skills.last_practiced as TEXT NOT NULL with no default, so a skill
     * that was never practiced cannot be inserted. Rebuilds the table with the schema.sql definition
     * (DEFAULT '2000-01-01'), keeping ids and every referencing row. Idempotent: once the column has
     * a default the migration does nothing.
     */
    static void migrateSkillsLastPracticedDefault(Connection conn) throws SQLException {
        if (!skillsLastPracticedIsStrict(conn)) {
            return;
        }

        boolean autoCommit = conn.getAutoCommit();
        try (Statement stmt = conn.createStatement()) {
            // Foreign keys must be off before the rebuild starts: a plain DROP TABLE would otherwise
            // cascade into skill_subtopics. The pragma is a no-op inside a transaction, hence here.
            stmt.execute("PRAGMA foreign_keys = OFF");
            conn.setAutoCommit(false);
            try {
                stmt.execute("CREATE TABLE skills_new (" +
                        "skill_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "category TEXT DEFAULT '', " +
                        "proficiency INTEGER DEFAULT 1, " +
                        "last_practiced TEXT DEFAULT '2000-01-01')");
                stmt.execute("INSERT INTO skills_new (skill_id, name, category, proficiency, last_practiced) " +
                        "SELECT skill_id, name, category, proficiency, COALESCE(last_practiced, '2000-01-01') FROM skills");
                stmt.execute("DROP TABLE skills");
                stmt.execute("ALTER TABLE skills_new RENAME TO skills");
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }

            stmt.execute("PRAGMA foreign_keys = ON");
            try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_check")) {
                if (rs.next()) {
                    throw new SQLException("skills rebuild left a broken foreign key in table "
                            + rs.getString("table") + " (rowid " + rs.getString("rowid") + ")");
                }
            }
        } catch (SQLException e) {
            // Never leave the connection with foreign keys disabled after a failed migration.
            try (Statement restore = conn.createStatement()) {
                restore.execute("PRAGMA foreign_keys = ON");
            } catch (SQLException ignored) {
            }
            throw e;
        }
    }

    /** True when skills.last_practiced is NOT NULL without a default (the legacy declaration). */
    private static boolean skillsLastPracticedIsStrict(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(skills)")) {
            while (rs.next()) {
                if (!"last_practiced".equalsIgnoreCase(rs.getString("name"))) {
                    continue;
                }
                return rs.getInt("notnull") == 1 && rs.getString("dflt_value") == null;
            }
        }
        return false;
    }

    /**
     * Creates skill_subtopics table if missing and seeds mst_exam_duration_minutes setting.
     */
    static void migrateSkillSubtopicsTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS skill_subtopics (" +
                    "subtopic_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "skill_id INTEGER NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, " +
                    "learn_hours REAL NOT NULL DEFAULT 1.0, " +
                    "learn_remaining_hours REAL NOT NULL DEFAULT 1.0, " +
                    "revise_hours REAL NOT NULL DEFAULT 0.5, " +
                    "revise_remaining_hours REAL NOT NULL DEFAULT 0.5, " +
                    "revise_after_days INTEGER NOT NULL DEFAULT 7, " +
                    "learned INTEGER NOT NULL DEFAULT 0, " +
                    "learned_on TEXT, " +
                    "revised INTEGER NOT NULL DEFAULT 0, " +
                    "revised_on TEXT, " +
                    "revision_due_date TEXT, " +
                    "FOREIGN KEY (skill_id) REFERENCES skills(skill_id) ON DELETE CASCADE)");
            stmt.execute("INSERT OR IGNORE INTO settings (key, value) VALUES ('mst_exam_duration_minutes', '120')");
            stmt.execute("INSERT OR IGNORE INTO settings (key, value) VALUES ('est_exam_duration_minutes', '180')");
        }
    }

    /**
     * Creates subject_credits table if missing and backfills from exams.
     */
    static void migrateSubjectCreditsTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='subject_credits'")) {
            if (!rs.next()) {
                stmt.execute("CREATE TABLE subject_credits (" +
                        "semester_id INTEGER NOT NULL, " +
                        "subject_code TEXT NOT NULL, " +
                        "credits REAL NOT NULL DEFAULT 4.0, " +
                        "PRIMARY KEY (semester_id, subject_code), " +
                        "FOREIGN KEY (semester_id) REFERENCES semesters(semester_id))");
            }
        }
        backfillSubjectCreditsFromExams(conn);
    }

    /**
     * Backfills subject credits from existing exams.
     */
    public static void backfillSubjectCreditsFromExams(Connection conn) throws SQLException {
        String sql = "INSERT OR IGNORE INTO subject_credits (semester_id, subject_code, credits) " +
                "SELECT semester_id, UPPER(COALESCE(subject_code, subject)), credits " +
                "FROM exams WHERE credits IS NOT NULL AND credits > 0 AND (subject_code IS NOT NULL OR subject IS NOT NULL)";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Adds hours_to_cover and hours_to_revise columns to syllabus_subtopics if missing.
     */
    static void migrateSyllabusSubtopicsHoursColumns(Connection conn) throws SQLException {
        if (!tableHasColumn(conn, "syllabus_subtopics", "hours_to_cover")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE syllabus_subtopics ADD COLUMN hours_to_cover REAL NOT NULL DEFAULT 1.0");
            }
        }
        if (!tableHasColumn(conn, "syllabus_subtopics", "hours_to_revise")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE syllabus_subtopics ADD COLUMN hours_to_revise REAL NOT NULL DEFAULT 0.5");
            }
        }
    }

    /**
     * Adds exams.exam_date_tentative column if missing.
     */
    static void migrateExamsDateTentativeColumn(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "exams", "exam_date_tentative")) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE exams ADD COLUMN exam_date_tentative INTEGER NOT NULL DEFAULT 0");
        }
    }

    /**
     * Adds syllabus_subtopics.difficulty column if missing.
     */
    static void migrateSyllabusSubtopicsDifficultyColumn(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "syllabus_subtopics", "difficulty")) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE syllabus_subtopics ADD COLUMN difficulty INTEGER NOT NULL DEFAULT 5");
        }
    }

    /**
     * Adds topics.subtopic_id column if missing.
     */
    static void migrateTopicsSubtopicIdColumn(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "topics", "subtopic_id")) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE topics ADD COLUMN subtopic_id INTEGER REFERENCES syllabus_subtopics(subtopic_id) ON DELETE SET NULL");
        }
    }

    /**
     * Creates exam_syllabus_scope table if missing.
     */
    static void migrateExamSyllabusScopeTable(Connection conn) throws SQLException {
        // Check if table exists
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='exam_syllabus_scope'")) {
            if (rs.next()) {
                return; // Table already exists
            }
        }
        
        // Create table
        String createTable = "CREATE TABLE exam_syllabus_scope (" +
                "exam_id INTEGER NOT NULL, " +
                "subtopic_id INTEGER NOT NULL, " +
                "PRIMARY KEY (exam_id, subtopic_id), " +
                "FOREIGN KEY (exam_id) REFERENCES exams(exam_id) ON DELETE CASCADE, " +
                "FOREIGN KEY (subtopic_id) REFERENCES syllabus_subtopics(subtopic_id) ON DELETE CASCADE)";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTable);
        }
    }

    /**
     * Adds weekly_timetable.room when missing. Checks PRAGMA table_info first;
     * never drops or recreates the table.
     */
    static void migrateWeeklyTimetableRoomColumn(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "weekly_timetable", "room")) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE weekly_timetable ADD COLUMN room TEXT");
        }
    }

    /**
     * Adds weekly_timetable.subject_code when missing. Checks PRAGMA table_info first;
     * never drops or recreates the table.
     */
    static void migrateWeeklyTimetableSubjectCodeColumn(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "weekly_timetable", "subject_code")) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE weekly_timetable ADD COLUMN subject_code TEXT");
        }
    }

    /**
     * Adds exams.subject_code when missing. This links exams to the subject list.
     * Backfills by trying to match exams.subject against subject codes or display names.
     */
    static void migrateExamsSubjectCodeColumn(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "exams", "subject_code")) return;
        
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE exams ADD COLUMN subject_code TEXT");
        }
        
        // Backfill: try to match existing exams to subjects
        backfillExamSubjectCodes(conn);
    }
    
    /**
     * Adds exams.eval_name when missing. Used for "Other evaluation" names.
     */
    static void migrateExamsEvalNameColumn(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "exams", "eval_name")) return;
        
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE exams ADD COLUMN eval_name TEXT");
        }
    }
    
    /**
     * Backfill subject_code for existing exams by matching against:
     * 1. Weekly timetable subject codes (case-insensitive)
     * 2. Subject names table (case-insensitive)
     * 3. Kept subjects table (case-insensitive)
     * Never guesses - leaves NULL when no match found.
     * Public for testing.
     */
    public static void backfillExamSubjectCodes(Connection conn) throws SQLException {
        // Get all exams that need backfilling
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT exam_id, semester_id, subject FROM exams WHERE subject_code IS NULL")) {
            
            while (rs.next()) {
                int examId = rs.getInt("exam_id");
                int semesterId = rs.getInt("semester_id");
                String subject = rs.getString("subject");
                
                if (subject == null || subject.trim().isEmpty()) continue;
                
                String subjectCode = findMatchingSubjectCode(conn, semesterId, subject.trim());
                
                if (subjectCode != null) {
                    try (var updateStmt = conn.prepareStatement("UPDATE exams SET subject_code = ? WHERE exam_id = ?")) {
                        updateStmt.setString(1, subjectCode);
                        updateStmt.setInt(2, examId);
                        updateStmt.executeUpdate();
                    }
                }
            }
        }
    }
    
    /**
     * Try to find a matching subject code for a subject name/code string.
     * Returns null if no match found.
     */
    private static String findMatchingSubjectCode(Connection conn, int semesterId, String subject) throws SQLException {
        String upperSubject = subject.toUpperCase();
        
        // Try 1: Match against timetable subject codes
        try (var stmt = conn.prepareStatement(
                "SELECT DISTINCT UPPER(subject_code) as code FROM weekly_timetable " +
                "WHERE semester_id = ? AND subject_code IS NOT NULL AND UPPER(subject_code) = ?")) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, upperSubject);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("code");
                }
            }
        }
        
        // Try 2: Match against subject_names table (both code and name)
        try (var stmt = conn.prepareStatement(
                "SELECT subject_code FROM subject_names " +
                "WHERE semester_id = ? AND (UPPER(subject_code) = ? OR UPPER(subject_name) = ?)")) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, upperSubject);
            stmt.setString(3, upperSubject);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("subject_code").toUpperCase();
                }
            }
        }
        
        // Try 3: Match against kept_subjects table (both code and display_name)
        try (var stmt = conn.prepareStatement(
                "SELECT subject_code FROM kept_subjects " +
                "WHERE semester_id = ? AND (UPPER(subject_code) = ? OR UPPER(display_name) = ?)")) {
            stmt.setInt(1, semesterId);
            stmt.setString(2, upperSubject);
            stmt.setString(3, upperSubject);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("subject_code").toUpperCase();
                }
            }
        }
        
        // No match found
        return null;
    }

    /**
     * Adds weekday_study_preference.limit_enabled column if missing and sets all existing rows to 0 (disabled).
     * This makes existing preferences optional by default.
     */
    static void migrateWeekdayStudyPreferenceLimitEnabled(Connection conn) throws SQLException {
        if (tableHasColumn(conn, "weekday_study_preference", "limit_enabled")) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE weekday_study_preference ADD COLUMN limit_enabled INTEGER NOT NULL DEFAULT 0");
            // All existing preferences are now treated as optional (limit disabled)
            stmt.execute("UPDATE weekday_study_preference SET limit_enabled = 0");
        }
    }

    public static boolean tableHasColumn(Connection conn, String table, String column) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }
}
