package com.learntrack.ui.forms;

import com.learntrack.model.Exam;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamRowHelper (ordering comparator, validation, formatting).
 */
public class ExamRowHelperTest {

    @Test
    public void testExamOrderingComparator_ExactDatesFirst() {
        Exam exact1 = createExam("SubjectA", LocalDate.of(2026, 11, 15), LocalTime.of(10, 0), false);
        Exam exact2 = createExam("SubjectB", LocalDate.of(2026, 11, 12), LocalTime.of(14, 0), false);
        Exam tentative1 = createExam("SubjectC", LocalDate.of(2026, 11, 10), LocalTime.of(10, 0), true);
        Exam tentative2 = createExam("SubjectD", LocalDate.of(2026, 11, 20), LocalTime.of(10, 0), true);

        List<Exam> exams = new ArrayList<>();
        exams.add(tentative1);
        exams.add(exact1);
        exams.add(tentative2);
        exams.add(exact2);

        exams.sort(ExamRowHelper.getExamOrderingComparator());

        // Exact dates should come first, sorted by date
        assertEquals("SubjectB", exams.get(0).getSubject());
        assertEquals("SubjectA", exams.get(1).getSubject());
        // Then tentative, sorted alphabetically
        assertEquals("SubjectC", exams.get(2).getSubject());
        assertEquals("SubjectD", exams.get(3).getSubject());
    }

    @Test
    public void testExamOrderingComparator_SameDateByTime() {
        Exam exam1 = createExam("SubjectA", LocalDate.of(2026, 11, 15), LocalTime.of(14, 0), false);
        Exam exam2 = createExam("SubjectB", LocalDate.of(2026, 11, 15), LocalTime.of(10, 0), false);
        Exam exam3 = createExam("SubjectC", LocalDate.of(2026, 11, 15), LocalTime.of(12, 0), false);

        List<Exam> exams = new ArrayList<>();
        exams.add(exam1);
        exams.add(exam2);
        exams.add(exam3);

        exams.sort(ExamRowHelper.getExamOrderingComparator());

        // Same date: sorted by start time
        assertEquals("SubjectB", exams.get(0).getSubject()); // 10:00
        assertEquals("SubjectC", exams.get(1).getSubject()); // 12:00
        assertEquals("SubjectA", exams.get(2).getSubject()); // 14:00
    }

    @Test
    public void testExamOrderingComparator_TentativeAlphabetical() {
        Exam tentative1 = createExam("Zebra", LocalDate.of(2026, 11, 10), LocalTime.of(10, 0), true);
        Exam tentative2 = createExam("Apple", LocalDate.of(2026, 11, 20), LocalTime.of(10, 0), true);
        Exam tentative3 = createExam("Banana", LocalDate.of(2026, 11, 15), LocalTime.of(10, 0), true);

        List<Exam> exams = new ArrayList<>();
        exams.add(tentative1);
        exams.add(tentative2);
        exams.add(tentative3);

        exams.sort(ExamRowHelper.getExamOrderingComparator());

        // Tentative sorted alphabetically, date ignored
        assertEquals("Apple", exams.get(0).getSubject());
        assertEquals("Banana", exams.get(1).getSubject());
        assertEquals("Zebra", exams.get(2).getSubject());
    }

    @Test
    public void testValidateDateFields_Valid() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(12, 0);

        String error = ExamRowHelper.validateDateFields(date, start, end);
        assertNull(error, "Should be valid");
    }

    @Test
    public void testValidateDateFields_MissingDate() {
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(12, 0);

        String error = ExamRowHelper.validateDateFields(null, start, end);
        assertNotNull(error, "Should have error for missing date");
        assertTrue(error.contains("required"), "Error should mention date is required");
    }

    @Test
    public void testValidateDateFields_EndBeforeStart() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        LocalTime start = LocalTime.of(14, 0);
        LocalTime end = LocalTime.of(12, 0);

        String error = ExamRowHelper.validateDateFields(date, start, end);
        assertNotNull(error, "Should have error for end before start");
        assertTrue(error.contains("after"), "Error should mention end must be after start");
    }

    @Test
    public void testFormatDateChip_Exact() {
        Exam exam = createExam("SubjectA", LocalDate.of(2026, 11, 15), LocalTime.of(10, 0), false);
        String chip = ExamRowHelper.formatDateChip(exam);
        assertEquals("15 Nov, 10:00", chip);
    }

    @Test
    public void testFormatDateChip_Tentative() {
        Exam exam = createExam("SubjectA", LocalDate.of(2026, 11, 15), LocalTime.of(10, 0), true);
        String chip = ExamRowHelper.formatDateChip(exam);
        assertEquals("Tentative", chip);
    }

    @Test
    public void testFormatDateChip_NoDate() {
        Exam exam = createExam("SubjectA", null, null, false);
        String chip = ExamRowHelper.formatDateChip(exam);
        assertEquals("No date", chip);
    }

    @Test
    public void testFormatTentativeDisplay() {
        LocalDate date = LocalDate.of(2026, 11, 10);
        String display = ExamRowHelper.formatTentativeDisplay(date);
        assertEquals("Planned as: 10 Nov (tentative)", display);
    }

    @Test
    public void testFormatTentativeDisplay_NullDate() {
        String display = ExamRowHelper.formatTentativeDisplay(null);
        assertEquals("Set the timeline above", display);
    }

    // Helper to create exam with tentative flag
    private Exam createExam(String subject, LocalDate examDate, LocalTime startTime, boolean tentative) {
        Exam exam = new Exam(1, 1, subject, subject.toUpperCase(), "MST", null,
                3.0, 20.0, examDate, startTime, LocalTime.of(12, 0), 30,
                null, null, 10.0);
        exam.setExamDateTentative(tentative);
        return exam;
    }
}
