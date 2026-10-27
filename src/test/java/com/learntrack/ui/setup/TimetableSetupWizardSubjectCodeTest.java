package com.learntrack.ui.setup;

import com.learntrack.model.WeeklyTimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TimetableSetupWizard subject code determination logic.
 */
class TimetableSetupWizardSubjectCodeTest {

    @Test
    void testDetermineSubjectCode_FromEntryField() {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, 1, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "Data Structures lecture", "CR101", "UCS503");
        
        String code = TimetableSetupWizard.determineSubjectCode(entry, null);
        assertEquals("UCS503", code);
    }

    @Test
    void testDetermineSubjectCode_FromLabelBase_LooksLikeCode() {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, 1, "TUESDAY",
                LocalTime.of(10, 0), LocalTime.of(11, 0), "TUTORIAL", "UCS503 tutorial", "Lab1", null);
        
        String code = TimetableSetupWizard.determineSubjectCode(entry, null);
        assertEquals("UCS503", code);
    }

    @Test
    void testDetermineSubjectCode_FromNameToSubjectCodeMap() {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, 1, "WEDNESDAY",
                LocalTime.of(14, 0), LocalTime.of(15, 0), "LAB", "Corporate Finance practical", "LR2", null);
        
        Map<String, String> nameToCode = new HashMap<>();
        nameToCode.put("Corporate Finance", "UCS421");
        
        String code = TimetableSetupWizard.determineSubjectCode(entry, nameToCode);
        assertEquals("UCS421", code);
    }

    @Test
    void testDetermineSubjectCode_Unknown() {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, 1, "THURSDAY",
                LocalTime.of(11, 0), LocalTime.of(12, 0), "CLASS", "sirksk2psk lecture", null, null);
        
        String code = TimetableSetupWizard.determineSubjectCode(entry, null);
        assertNull(code);
    }

    @Test
    void testDetermineSubjectCode_NonAcademicEntry() {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, 1, "FRIDAY",
                LocalTime.of(12, 0), LocalTime.of(13, 0), "MEAL", "Lunch", null, null);
        
        String code = TimetableSetupWizard.determineSubjectCode(entry, null);
        assertNull(code);
    }

    @Test
    void testDetermineSubjectCode_VariousCodePatterns() {
        // Valid patterns
        assertCodeFromLabel("CS101 lecture", "CS101");
        assertCodeFromLabel("UCS503 tutorial", "UCS503");
        assertCodeFromLabel("MAT2001 practical", "MAT2001");
        assertCodeFromLabel("PHYS101A lecture", "PHYS101A");
        assertCodeFromLabel("BIO99 tutorial", "BIO99");
        
        // Invalid patterns (too short, too long, wrong format)
        assertCodeFromLabel("A1 lecture", null); // too short
        assertCodeFromLabel("TOOLONG12345 tutorial", null); // code part too long
        assertCodeFromLabel("123ABC lecture", null); // starts with number
    }

    private void assertCodeFromLabel(String label, String expectedCode) {
        WeeklyTimetableEntry entry = new WeeklyTimetableEntry(null, 1, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", label, null, null);
        String code = TimetableSetupWizard.determineSubjectCode(entry, null);
        assertEquals(expectedCode, code);
    }
}
