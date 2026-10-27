package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.Semester;

import java.sql.SQLException;

/**
 * Handles semester lifecycle: creation, activation, and rollover with permanent deletion of old semester data.
 * Plan Section 12 & Step 5.
 */
public class SemesterService {

    private final SemesterDAO semesterDAO;
    private final TimetableDAO timetableDAO;
    private final ExamPeriodRoutineDAO routineDAO;
    private final ExamDAO examDAO;
    private final TopicDAO topicDAO;
    private final DailyLogDAO dailyLogDAO;

    private final DateOverrideDAO dateOverrideDAO;
    private final SettingsDAO settingsDAO;
    private final SemesterReplanService replanService;

    public SemesterService(SemesterDAO semesterDAO, TimetableDAO timetableDAO,
                           ExamPeriodRoutineDAO routineDAO, ExamDAO examDAO,
                           TopicDAO topicDAO, DailyLogDAO dailyLogDAO,
                           DateOverrideDAO dateOverrideDAO, SettingsDAO settingsDAO,
                           SemesterReplanService replanService) {
        this.semesterDAO = java.util.Objects.requireNonNull(semesterDAO, "semesterDAO must not be null");
        this.timetableDAO = java.util.Objects.requireNonNull(timetableDAO, "timetableDAO must not be null");
        this.routineDAO = java.util.Objects.requireNonNull(routineDAO, "routineDAO must not be null");
        this.examDAO = java.util.Objects.requireNonNull(examDAO, "examDAO must not be null");
        this.topicDAO = java.util.Objects.requireNonNull(topicDAO, "topicDAO must not be null");
        this.dailyLogDAO = java.util.Objects.requireNonNull(dailyLogDAO, "dailyLogDAO must not be null");
        this.dateOverrideDAO = java.util.Objects.requireNonNull(dateOverrideDAO, "dateOverrideDAO must not be null");
        this.settingsDAO = java.util.Objects.requireNonNull(settingsDAO, "settingsDAO must not be null");
        this.replanService = java.util.Objects.requireNonNull(replanService, "replanService must not be null");
    }

    /**
     * Starts a new semester.
     * Deletes the old semester's daily_log rows (topics/PYQ), topics, exams,
     * date_overrides, weekly_timetable, weekday_study_preference, exam_period_routine,
     * and the semester row, in that order.
     * Resets exam-period extra hours for both MST and EST to 0.0.
     * Skills and SKILL daily_log rows are NEVER touched (Plan Section 12).
     */
    public int startNewSemester(String newSemesterName) throws SQLException {
        Semester active = semesterDAO.getActiveSemester();
        if (active != null && active.getSemesterId() != null) {
            int oldId = active.getSemesterId();
            // 1. DELETE daily_log rows for TOPIC_LEARN / TOPIC_REVISE / PYQ_PRACTICE tied to old semester
            dailyLogDAO.deleteNonSkillBySemester(oldId);

            // 2. DELETE topics
            topicDAO.deleteBySemester(oldId);

            // 3. DELETE exams
            examDAO.deleteBySemester(oldId);

            // 4. DELETE date_overrides tied to old semester's weekly_timetable entries (Item 14)
            dateOverrideDAO.deleteBySemester(oldId);

            // 5. DELETE weekly_timetable & weekday_study_preference
            timetableDAO.deleteBySemester(oldId);

            // 6. DELETE exam_period_routine
            routineDAO.deleteBySemester(oldId);

            // 7. DELETE kept_subjects and subject_names
            KeptSubjectDAO.deleteAllForSemester(oldId);
            SubjectNameDAO.deleteAllForSemester(oldId);

            // 8. DELETE syllabus units and subtopics
            SyllabusDAO syllabusDAO = new SyllabusDAO();
            syllabusDAO.deleteAllForSemester(oldId);

            // 9. DELETE old semester row
            semesterDAO.delete(oldId);
        }

        // Reset exam-period extra hours for both batches to prevent leak across semesters (Fix Pass Item 4)
        settingsDAO.set("exam_period_extra_hours_MST", "0.0");
        settingsDAO.set("exam_period_extra_hours_EST", "0.0");

        // Insert new active semester
        Semester newSem = new Semester(null, newSemesterName, "ACTIVE");
        return semesterDAO.insert(newSem);
    }

    /**
     * Records a class/lab cancellation for a specific date in date_overrides without replanning.
     */
    public void recordCancellation(java.time.LocalDate date, int timetableEntryId) throws SQLException {
        if (!dateOverrideDAO.hasCancellation(date, timetableEntryId)) {
            com.learntrack.model.DateOverride ov = new com.learntrack.model.DateOverride(
                    null, date, timetableEntryId, "CANCEL", null, null, null, "Cancelled"
            );
            dateOverrideDAO.insert(ov);
        }
    }

    /**
     * Handles cancelling a class/lab for a specific date (Plan §11, Guide §3).
     * Prevents duplicate cancel rows and regenerates from today.
     */
    public void handleCancellation(int semesterId, java.time.LocalDate date, int timetableEntryId) throws SQLException {
        recordCancellation(date, timetableEntryId);
        replanService.regenerateSemesterPlan(semesterId, java.time.LocalDate.now());
    }

    /**
     * Removes a class/lab cancellation from date_overrides without replanning.
     */
    public void recordRemoveCancellation(java.time.LocalDate date, int timetableEntryId) throws SQLException {
        dateOverrideDAO.deleteByDateAndEntry(date, timetableEntryId, "CANCEL");
    }

    public void removeCancellation(int semesterId, java.time.LocalDate date, int timetableEntryId) throws SQLException {
        recordRemoveCancellation(date, timetableEntryId);
        replanService.regenerateSemesterPlan(semesterId, java.time.LocalDate.now());
    }
}
