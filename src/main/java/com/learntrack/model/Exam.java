package com.learntrack.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Represents an exam or quiz (NORMAL, MST, or EST).
 * Plan Section 3, 4, 5.
 */
public class Exam {

    /** Fallback horizon for exams that have no date, windowStart, or windowEnd at all. */
    public static final int DEFAULT_UNDATED_HORIZON_DAYS = 60;
    private Integer examId;
    private int semesterId;
    private String subject;            // display name
    private String subjectCode;        // links to subject list
    private String examType;           // 'NORMAL' | 'MST' | 'EST'
    private String evalName;           // name for "Other evaluation", NULL for MST/EST
    private double credits = 1.0;
    private double evalMarks = 0.0;
    private LocalDate examDate;
    private boolean examDateTentative = false;  // true if exam_date is a tentative window start
    private LocalTime examStartTime;
    private LocalTime examEndTime;
    private int travelTimeMinutes = 30;
    private LocalDate windowStartDate;
    private LocalDate windowEndDate;
    private double pyqRemainingHours = 2.0;

    public Exam() {
    }

    // Constructor with all fields including examDateTentative
    public Exam(Integer examId, int semesterId, String subject, String subjectCode, String examType, String evalName,
                double credits, double evalMarks, LocalDate examDate, boolean examDateTentative,
                LocalTime examStartTime, LocalTime examEndTime, int travelTimeMinutes,
                LocalDate windowStartDate, LocalDate windowEndDate, double pyqRemainingHours) {
        this.examId = examId;
        this.semesterId = semesterId;
        this.subject = subject;
        this.subjectCode = subjectCode;
        this.examType = examType;
        this.evalName = evalName;
        this.credits = credits;
        this.evalMarks = evalMarks;
        this.examDate = examDate;
        this.examDateTentative = examDateTentative;
        this.examStartTime = examStartTime;
        this.examEndTime = examEndTime;
        this.travelTimeMinutes = travelTimeMinutes;
        this.windowStartDate = windowStartDate;
        this.windowEndDate = windowEndDate;
        this.pyqRemainingHours = pyqRemainingHours;
    }

    // Constructor with all fields (new version with subject_code and eval_name, without examDateTentative)
    public Exam(Integer examId, int semesterId, String subject, String subjectCode, String examType, String evalName,
                double credits, double evalMarks, LocalDate examDate,
                LocalTime examStartTime, LocalTime examEndTime, int travelTimeMinutes,
                LocalDate windowStartDate, LocalDate windowEndDate, double pyqRemainingHours) {
        this.examId = examId;
        this.semesterId = semesterId;
        this.subject = subject;
        this.subjectCode = subjectCode;
        this.examType = examType;
        this.evalName = evalName;
        this.credits = credits;
        this.evalMarks = evalMarks;
        this.examDate = examDate;
        this.examStartTime = examStartTime;
        this.examEndTime = examEndTime;
        this.travelTimeMinutes = travelTimeMinutes;
        this.windowStartDate = windowStartDate;
        this.windowEndDate = windowEndDate;
        this.pyqRemainingHours = pyqRemainingHours;
    }

    // Backward-compatible constructor (old version without subject_code and eval_name)
    @Deprecated
    public Exam(Integer examId, int semesterId, String subject, String examType,
                double credits, double evalMarks, LocalDate examDate,
                LocalTime examStartTime, LocalTime examEndTime, int travelTimeMinutes,
                LocalDate windowStartDate, LocalDate windowEndDate, double pyqRemainingHours) {
        this(examId, semesterId, subject, null, examType, null, credits, evalMarks, examDate,
                examStartTime, examEndTime, travelTimeMinutes, windowStartDate, windowEndDate, pyqRemainingHours);
    }

    /**
     * Display title for this exam: "Subject - Evaluation Name" for other evaluations,
     * or just "Subject" for MST/EST.
     */
    public String getDisplayTitle() {
        if (evalName != null && !evalName.trim().isEmpty()) {
            return subject + " - " + evalName;
        }
        return subject;
    }

    /**
     * Determines the effective deadline based on the fallback chain (Plan Section 5).
     */
    public LocalDate getEffectiveDeadline(LocalDate today) {
        if (examDate != null) {
            return examDate;
        }
        if (windowStartDate != null && !today.isAfter(windowStartDate)) {
            return windowStartDate;
        }
        if (windowEndDate != null && !today.isAfter(windowEndDate)) {
            return windowEndDate;
        }
        // Exam has no date information at all: give a 60-day horizon so it does not
        // artificially inflate pyqScore (daysLeft would be 1 if we returned today).
        // Keep returning today only when a window WAS defined but has now lapsed.
        if (windowStartDate == null && windowEndDate == null) {
            return today.plusDays(DEFAULT_UNDATED_HORIZON_DAYS);
        }
        return today; // window existed but lapsed
    }

    /**
     * An exam is unfinished while examDate is unset or not yet past (examDate >= today).
     * It never drops out of scheduling because a window date passed (Plan Section 5).
     */
    public boolean isUnfinished(LocalDate today) {
        if (examDate == null) {
            return true;
        }
        return !examDate.isBefore(today);
    }

    public boolean isDated() {
        return examDate != null;
    }

    public Integer getExamId() {
        return examId;
    }

    public void setExamId(Integer examId) {
        this.examId = examId;
    }

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
    }

    public String getExamType() {
        return examType;
    }

    public void setExamType(String examType) {
        this.examType = examType;
    }

    public String getEvalName() {
        return evalName;
    }

    public void setEvalName(String evalName) {
        this.evalName = evalName;
    }

    public double getCredits() {
        return credits;
    }

    public void setCredits(double credits) {
        this.credits = credits;
    }

    public double getEvalMarks() {
        return evalMarks;
    }

    public void setEvalMarks(double evalMarks) {
        this.evalMarks = evalMarks;
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
    }

    public boolean isExamDateTentative() {
        return examDateTentative;
    }

    public void setExamDateTentative(boolean examDateTentative) {
        this.examDateTentative = examDateTentative;
    }

    public LocalTime getExamStartTime() {
        return examStartTime;
    }

    public void setExamStartTime(LocalTime examStartTime) {
        this.examStartTime = examStartTime;
    }

    public LocalTime getExamEndTime() {
        return examEndTime;
    }

    public void setExamEndTime(LocalTime examEndTime) {
        this.examEndTime = examEndTime;
    }

    public int getTravelTimeMinutes() {
        return travelTimeMinutes;
    }

    public void setTravelTimeMinutes(int travelTimeMinutes) {
        this.travelTimeMinutes = travelTimeMinutes;
    }

    public LocalDate getWindowStartDate() {
        return windowStartDate;
    }

    public void setWindowStartDate(LocalDate windowStartDate) {
        this.windowStartDate = windowStartDate;
    }

    public LocalDate getWindowEndDate() {
        return windowEndDate;
    }

    public void setWindowEndDate(LocalDate windowEndDate) {
        this.windowEndDate = windowEndDate;
    }

    public double getPyqRemainingHours() {
        return pyqRemainingHours;
    }

    public void setPyqRemainingHours(double pyqRemainingHours) {
        this.pyqRemainingHours = pyqRemainingHours;
    }
}
