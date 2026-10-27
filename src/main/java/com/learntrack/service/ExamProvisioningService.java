package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.SettingsDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.SubjectInfo;

import java.sql.SQLException;
import java.util.List;

/**
 * Service for automatically provisioning exam records for subjects.
 * Ensures every subject in the timetable has MST and EST exam records.
 */
public class ExamProvisioningService {

    private final ExamDAO examDAO;
    private final SubjectListService subjectListService;
    private final SettingsDAO settingsDAO;
    private final ExamTimelineService timelineService;

    public ExamProvisioningService() {
        TimetableDAO timetableDAO = new TimetableDAO();
        this.examDAO = new ExamDAO();
        this.subjectListService = new SubjectListService(timetableDAO, examDAO);
        this.settingsDAO = new SettingsDAO();
        this.timelineService = new ExamTimelineService();
    }

    public ExamProvisioningService(ExamDAO examDAO, SubjectListService subjectListService, 
                                  SettingsDAO settingsDAO, ExamTimelineService timelineService) {
        this.examDAO = examDAO;
        this.subjectListService = subjectListService;
        this.settingsDAO = settingsDAO;
        this.timelineService = timelineService;
    }

    /**
     * Ensures that every subject in the timetable for the given semester has
     * one MST exam record and one EST exam record.
     * 
     * This method is idempotent: it never creates duplicates and can be called multiple times safely.
     * 
     * - Creates missing MST/EST records with default values (credits=3.0, marks=20.0, travel=30min)
     * - Does not create "NORMAL" evaluations automatically
     * - Does not touch legacy "Unassigned" exams
     * - Does not modify existing exam records
     * 
     * @param semesterId The semester ID
     * @throws SQLException If database error occurs
     */
    public void ensureExamsForSemester(int semesterId) throws SQLException {
        // Get default PYQ hours from settings
        double defaultPyqHours = 2.0;
        try {
            defaultPyqHours = settingsDAO.getDouble("pyq_practice_hours", 2.0);
        } catch (Exception ignored) {
        }

        // Get all subjects in the semester
        List<SubjectInfo> subjects = subjectListService.buildSubjectList(semesterId);

        for (SubjectInfo subject : subjects) {
            // Only provision for subjects in timetable (includes kept_for_study via view logic)
            if (!subject.isInTimetable()) {
                continue;
            }

            String subjectCode = subject.getSubjectCode();
            String displayName = subject.getDisplayName();

            // Skip "Unassigned" or invalid subject codes
            if (subjectCode == null || subjectCode.trim().isEmpty() || 
                "Unassigned".equalsIgnoreCase(subjectCode)) {
                continue;
            }

            // Ensure MST exam exists
            ensureExamExists(semesterId, subjectCode, displayName, "MST", defaultPyqHours);

            // Ensure EST exam exists
            ensureExamExists(semesterId, subjectCode, displayName, "EST", defaultPyqHours);
        }
    }

    /**
     * Ensures a single exam of the given type exists for the subject.
     * If it doesn't exist, creates it with default values.
     * 
     * @param semesterId The semester ID
     * @param subjectCode The subject code
     * @param displayName The subject display name
     * @param examType "MST" or "EST"
     * @param defaultPyqHours Default PYQ hours
     * @throws SQLException If database error occurs
     */
    private void ensureExamExists(int semesterId, String subjectCode, String displayName, 
                                  String examType, double defaultPyqHours) throws SQLException {
        // Check if exam already exists
        List<Exam> existing = examDAO.findBySubject(semesterId, subjectCode);
        boolean hasExam = existing.stream()
            .anyMatch(e -> examType.equals(e.getExamType()));

        if (!hasExam) {
            double subjectCredits = new com.learntrack.dao.SubjectCreditDAO().getExplicitSubjectCredits(semesterId, subjectCode);
            // Create new exam with defaults
            Exam exam = new Exam(
                null,  // examId - will be generated
                semesterId,
                displayName,  // subject name
                subjectCode,
                examType,
                null,  // evalName - null for MST/EST
                subjectCredits > 0 ? subjectCredits : 3.0,   // credits - from subject
                20.0,  // evalMarks - default
                null,  // examDate - not set yet
                null,  // examStartTime - not set yet
                null,  // examEndTime - not set yet
                30,    // travelTimeMinutes - default
                null,  // windowStartDate - will be set by timeline
                null,  // windowEndDate - will be set by timeline
                defaultPyqHours  // pyqRemainingHours
            );

            // Apply tentative window if one exists
            timelineService.applyWindowToNewExam(semesterId, exam);

            examDAO.insert(exam);
        }
    }
}
