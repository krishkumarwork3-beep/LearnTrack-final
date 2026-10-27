package com.learntrack.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for period-count and end-time rules (synthetic cell text, no workbook).
 */
public class TimetableSlotDurationTest {

    @Test
    void practical_labBelow_isTwoPeriods() {
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "LAB"));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "LAB-1"));
    }

    @Test
    void practical_facultyBelow_isTwoPeriods() {
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "SPP/RSJ"));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "ABC"));
    }

    @Test
    void practical_blankBelow_isTwoPeriods() {
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', null));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', "  "));
        assertEquals(2, TimetableXlsxReader.periodCountFor('P', ""));
    }

    @Test
    void practical_anotherCodeBelow_isOnePeriod() {
        assertEquals(1, TimetableXlsxReader.periodCountFor('P', "UCS510L"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('P', "UCS510 L"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('P', "UMA022T"));
    }

    @Test
    void tutorial_labOrFacultyBelow_isTwoPeriods() {
        assertEquals(2, TimetableXlsxReader.periodCountFor('T', "LAB"));
        assertEquals(2, TimetableXlsxReader.periodCountFor('T', "SPP/RSJ"));
        assertEquals(2, TimetableXlsxReader.periodCountFor('T', "RSJ"));
    }

    @Test
    void tutorial_blankOrAnotherCodeBelow_isOnePeriod() {
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', null));
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', ""));
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', "UCS510L"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('T', "UML501P"));
    }

    @Test
    void lecture_alwaysOnePeriod() {
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', "LAB"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', "SPP"));
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', ""));
        assertEquals(1, TimetableXlsxReader.periodCountFor('L', "UCS510L"));
    }

    @Test
    void lastPeriodOfDay_clampsTo1940() {
        LocalTime start = LocalTime.of(18, 50);
        LocalTime end = TimetableXlsxReader.endTimeFor(start, 2, "TEST", "last period P");
        assertEquals(LocalTime.of(19, 40), end);
        assertFalse(end.isAfter(TimetableXlsxReader.LAST_PERIOD_END));
    }

    @Test
    void uptoPattern_keepsExplicitEnd() {
        LocalTime start = LocalTime.of(15, 30);
        LocalTime end = TimetableXlsxReader.endTimeFromCellText(
                "UCS510P (upto 5:10 PM)", 'P', "UCS510L", start, "TEST", "upto");
        assertEquals(LocalTime.of(17, 10), end);
    }

    @Test
    void endTimeUsesStartPlus50TimesPeriods() {
        LocalTime start = LocalTime.of(15, 30);
        assertEquals(LocalTime.of(17, 10),
                TimetableXlsxReader.endTimeFor(start, 2, "TEST", "P"));
        assertEquals(LocalTime.of(16, 20),
                TimetableXlsxReader.endTimeFor(start, 1, "TEST", "L"));
    }

    @Test
    void mergeBackToBackFiftyMinutePracticals() {
        TimetableSlot a = new TimetableSlot("WEDNESDAY", LocalTime.of(15, 30), LocalTime.of(16, 20),
                "URA521", 'P', "LAB", "practical", "URA521P", "F108", false, null);
        TimetableSlot b = new TimetableSlot("WEDNESDAY", LocalTime.of(16, 20), LocalTime.of(17, 10),
                "URA521", 'P', "LAB", "practical", "URA521P", "F108", false, null);
        List<TimetableSlot> merged = TimetableXlsxReader.mergeBackToBackPtSlots(List.of(a, b));
        assertEquals(1, merged.size());
        assertEquals(LocalTime.of(15, 30), merged.get(0).getStartTime());
        assertEquals(LocalTime.of(17, 10), merged.get(0).getEndTime());
        assertEquals('P', merged.get(0).getSuffix());
    }

    @Test
    void slotToEntryCarriesEndTime() {
        TimetableSlot slot = new TimetableSlot("WEDNESDAY", LocalTime.of(15, 30), LocalTime.of(17, 10),
                "URA521", 'P', "LAB", "practical", "URA521P", "F108", false, null);
        var entry = slot.toEntry(null);
        assertEquals(LocalTime.of(17, 10), entry.getEndTime());
        assertEquals(LocalTime.of(15, 30), entry.getStartTime());
    }

    @Test
    void alreadyCoveredSkipsSecondPeriodOfSameCode() {
        List<TimetableSlot> existing = new ArrayList<>();
        existing.add(new TimetableSlot("WEDNESDAY", LocalTime.of(15, 30), LocalTime.of(17, 10),
                "URA521", 'P', "LAB", "practical", "URA521P", "F108", false, null));
        assertTrue(TimetableXlsxReader.alreadyCoveredByPriorSlot(
                existing, "WEDNESDAY", LocalTime.of(16, 20), "URA521P"));
        assertFalse(TimetableXlsxReader.alreadyCoveredByPriorSlot(
                existing, "WEDNESDAY", LocalTime.of(17, 10), "URA521P"));
    }
}
