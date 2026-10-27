package com.learntrack.ui.forms;

import com.learntrack.model.Exam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamTimelineHelper - pure UI logic without database.
 */
public class ExamTimelineHelperTest {

    private ExamTimelineHelper helper;

    @BeforeEach
    void setUp() {
        helper = new ExamTimelineHelper();
    }

    @Test
    void testComparatorOrdersDatedBeforeUndated() {
        Exam dated = new Exam();
        dated.setExamDate(LocalDate.of(2026, 11, 15));
        dated.setSubject("Math");

        Exam undated = new Exam();
        undated.setWindowStartDate(LocalDate.of(2026, 11, 1));
        undated.setSubject("Physics");

        Comparator<Exam> comp = helper.getExamComparator();
        assertTrue(comp.compare(dated, undated) < 0, "Dated should come before undated");
        assertTrue(comp.compare(undated, dated) > 0, "Undated should come after dated");
    }

    @Test
    void testComparatorSortsDatedByDateThenTime() {
        Exam exam1 = new Exam();
        exam1.setExamDate(LocalDate.of(2026, 11, 10));
        exam1.setExamStartTime(LocalTime.of(10, 0));
        exam1.setSubject("Math");

        Exam exam2 = new Exam();
        exam2.setExamDate(LocalDate.of(2026, 11, 10));
        exam2.setExamStartTime(LocalTime.of(14, 0));
        exam2.setSubject("Physics");

        Exam exam3 = new Exam();
        exam3.setExamDate(LocalDate.of(2026, 11, 15));
        exam3.setExamStartTime(LocalTime.of(9, 0));
        exam3.setSubject("Chemistry");

        List<Exam> exams = new ArrayList<>();
        exams.add(exam3);
        exams.add(exam2);
        exams.add(exam1);

        exams.sort(helper.getExamComparator());

        // Should be ordered: exam1 (10th, 10:00), exam2 (10th, 14:00), exam3 (15th, 9:00)
        assertEquals(exam1, exams.get(0));
        assertEquals(exam2, exams.get(1));
        assertEquals(exam3, exams.get(2));
    }

    @Test
    void testComparatorSortsUndatedAlphabetically() {
        Exam undated1 = new Exam();
        undated1.setWindowStartDate(LocalDate.of(2026, 11, 1));
        undated1.setSubject("Physics");

        Exam undated2 = new Exam();
        undated2.setWindowStartDate(LocalDate.of(2026, 11, 1));
        undated2.setSubject("Chemistry");

        Exam undated3 = new Exam();
        undated3.setWindowStartDate(LocalDate.of(2026, 11, 1));
        undated3.setSubject("Math");

        List<Exam> exams = new ArrayList<>();
        exams.add(undated1);
        exams.add(undated2);
        exams.add(undated3);

        exams.sort(helper.getExamComparator());

        // Should be ordered: Chemistry, Math, Physics
        assertEquals("Chemistry", exams.get(0).getSubject());
        assertEquals("Math", exams.get(1).getSubject());
        assertEquals("Physics", exams.get(2).getSubject());
    }

    @Test
    void testValidWindowAcceptsEqualDates() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        assertTrue(helper.isValidWindow(date, date), "Start == End should be valid");
    }

    @Test
    void testValidWindowRejectsEndBeforeStart() {
        LocalDate start = LocalDate.of(2026, 11, 15);
        LocalDate end = LocalDate.of(2026, 11, 10);
        assertFalse(helper.isValidWindow(start, end), "End < Start should be invalid");
    }

    @Test
    void testValidWindowRejectsNull() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        assertFalse(helper.isValidWindow(null, date));
        assertFalse(helper.isValidWindow(date, null));
        assertFalse(helper.isValidWindow(null, null));
    }

    @Test
    void testFormatTentativeDisplayWithDate() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        String result = helper.formatTentativeDisplay(date);
        assertTrue(result.contains("Planned as:"));
        assertTrue(result.contains("(tentative)"));
    }

    @Test
    void testFormatTentativeDisplayWithoutDate() {
        String result = helper.formatTentativeDisplay(null);
        assertEquals("Set the timeline above", result);
    }

    @Test
    void testFormatDateTimeChipForDatedExam() {
        Exam exam = new Exam();
        exam.setExamDate(LocalDate.of(2026, 11, 15));
        exam.setExamStartTime(LocalTime.of(10, 30));

        String result = helper.formatDateTimeChip(exam);
        assertTrue(result.contains("Nov"));
        assertTrue(result.contains("15"));
        assertTrue(result.contains("10:30"));
    }

    @Test
    void testFormatDateTimeChipForTentativeExam() {
        Exam exam = new Exam();
        exam.setWindowStartDate(LocalDate.of(2026, 11, 1));

        String result = helper.formatDateTimeChip(exam);
        assertEquals("Tentative", result);
    }

    @Test
    void testFormatDateTimeChipForUndatedExam() {
        Exam exam = new Exam();

        String result = helper.formatDateTimeChip(exam);
        assertEquals("No date", result);
    }

    @Test
    void testFullSortScenario() {
        // Mix of dated and undated exams
        Exam dated1 = new Exam();
        dated1.setExamDate(LocalDate.of(2026, 11, 12));
        dated1.setExamStartTime(LocalTime.of(10, 0));
        dated1.setSubject("Dated Early");

        Exam dated2 = new Exam();
        dated2.setExamDate(LocalDate.of(2026, 11, 18));
        dated2.setExamStartTime(LocalTime.of(14, 0));
        dated2.setSubject("Dated Late");

        Exam undated1 = new Exam();
        undated1.setWindowStartDate(LocalDate.of(2026, 11, 1));
        undated1.setSubject("Zebra");

        Exam undated2 = new Exam();
        undated2.setWindowStartDate(LocalDate.of(2026, 11, 1));
        undated2.setSubject("Apple");

        List<Exam> exams = new ArrayList<>();
        exams.add(undated1);
        exams.add(dated2);
        exams.add(undated2);
        exams.add(dated1);

        exams.sort(helper.getExamComparator());

        // Expected order: dated1, dated2, undated2 (Apple), undated1 (Zebra)
        assertEquals("Dated Early", exams.get(0).getSubject());
        assertEquals("Dated Late", exams.get(1).getSubject());
        assertEquals("Apple", exams.get(2).getSubject());
        assertEquals("Zebra", exams.get(3).getSubject());
    }
}
