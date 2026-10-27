package com.learntrack.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public class DatesheetEntry {
    private final int slNo;
    private final LocalDate date;
    private final LocalTime start;
    private final LocalTime end;
    private final String courseCode;
    private final String courseName;
    /** Candidates printed for this course ({@code CODE (30)}); -1 when the sheet does not state one. */
    private final int candidateCount;

    public DatesheetEntry(int slNo, LocalDate date, LocalTime start, String courseCode, String courseName) {
        this(slNo, date, start, null, courseCode, courseName, -1);
    }

    public DatesheetEntry(int slNo, LocalDate date, LocalTime start, LocalTime end, String courseCode, String courseName) {
        this(slNo, date, start, end, courseCode, courseName, -1);
    }

    public DatesheetEntry(int slNo, LocalDate date, LocalTime start, LocalTime end, String courseCode,
                          String courseName, int candidateCount) {
        this.slNo = slNo;
        this.date = date;
        this.start = start;
        this.end = end;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.candidateCount = candidateCount;
    }

    public int getSlNo() {
        return slNo;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    /**
     * Number of candidates the sheet prints next to this course, or -1 when it prints none.
     * Metadata only: it never takes part in matching, identity or scheduling.
     */
    public int getCandidateCount() {
        return candidateCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatesheetEntry that = (DatesheetEntry) o;
        return slNo == that.slNo &&
                Objects.equals(date, that.date) &&
                Objects.equals(start, that.start) &&
                Objects.equals(end, that.end) &&
                Objects.equals(courseCode, that.courseCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slNo, date, start, end, courseCode);
    }

    @Override
    public String toString() {
        return "DatesheetEntry{" +
                "slNo=" + slNo +
                ", date=" + date +
                ", start=" + start +
                ", end=" + end +
                ", courseCode='" + courseCode + '\'' +
                ", courseName='" + courseName + '\'' +
                '}';
    }
}
