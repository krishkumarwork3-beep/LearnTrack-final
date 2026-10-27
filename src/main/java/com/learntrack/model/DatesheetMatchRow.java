package com.learntrack.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Represents a matched exam row between the parsed datesheet and user subjects.
 */
public class DatesheetMatchRow {

    private String courseCode;
    private String datesheetCourseName;
    private String userSubjectName;
    private String examType;
    private LocalDate examDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String status;

    public DatesheetMatchRow() {
    }

    public DatesheetMatchRow(String courseCode, String datesheetCourseName, String userSubjectName,
                             String examType, LocalDate examDate, LocalTime startTime, LocalTime endTime,
                             String status) {
        this.courseCode = courseCode;
        this.datesheetCourseName = datesheetCourseName;
        this.userSubjectName = userSubjectName;
        this.examType = examType;
        this.examDate = examDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getDatesheetCourseName() {
        return datesheetCourseName;
    }

    public void setDatesheetCourseName(String datesheetCourseName) {
        this.datesheetCourseName = datesheetCourseName;
    }

    public String getUserSubjectName() {
        return userSubjectName;
    }

    public void setUserSubjectName(String userSubjectName) {
        this.userSubjectName = userSubjectName;
    }

    public String getExamType() {
        return examType;
    }

    public void setExamType(String examType) {
        this.examType = examType;
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
    }

    public String getWeekday() {
        if (examDate == null) return "";
        return examDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "DatesheetMatchRow{" +
                "courseCode='" + courseCode + '\'' +
                ", examType='" + examType + '\'' +
                ", examDate=" + examDate +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", status='" + status + '\'' +
                '}';
    }
}
