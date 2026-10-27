package com.learntrack.ui.setup;

import com.learntrack.model.WeeklyTimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for subject renaming logic in TimetableSetupWizard.
 */
class RenameSubjectsTest {

    @Test
    void testApplyRename_AllMatchingEntriesRelabelled() {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        
        // Entry with subjectCode
        WeeklyTimetableEntry e1 = new WeeklyTimetableEntry(1, 1, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "UML501 lecture", "CR101", "UML501");
        entries.add(e1);
        
        // Entry with subjectCode
        WeeklyTimetableEntry e2 = new WeeklyTimetableEntry(2, 1, "TUESDAY",
                LocalTime.of(11, 0), LocalTime.of(12, 0), "LAB", "UML501 practical", "Lab2", "UML501");
        entries.add(e2);
        
        // Entry without subjectCode (should still match by label base)
        WeeklyTimetableEntry e3 = new WeeklyTimetableEntry(3, 1, "WEDNESDAY",
                LocalTime.of(14, 0), LocalTime.of(15, 0), "TUTORIAL", "UML501 tutorial", "CR102", null);
        entries.add(e3);
        
        // Different case
        WeeklyTimetableEntry e4 = new WeeklyTimetableEntry(4, 1, "THURSDAY",
                LocalTime.of(10, 0), LocalTime.of(11, 0), "CLASS", "uml501 Lecture", "CR103", "UML501");
        entries.add(e4);
        
        // Non-breaking space - this is tricky as it gets normalized
        WeeklyTimetableEntry e5 = new WeeklyTimetableEntry(5, 1, "FRIDAY",
                LocalTime.of(15, 0), LocalTime.of(16, 0), "LAB", "UML501\u00A0practical", "Lab3", "UML501");
        entries.add(e5);
        
        // Different subject (should not change)
        WeeklyTimetableEntry e6 = new WeeklyTimetableEntry(6, 1, "MONDAY",
                LocalTime.of(11, 0), LocalTime.of(12, 0), "CLASS", "UCS503 lecture", "CR104", "UCS503");
        entries.add(e6);

        Map<String, String> nameToSubjectCode = new HashMap<>();
        nameToSubjectCode.put("UML501", "UML501");
        nameToSubjectCode.put("UCS503", "UCS503");

        int count = TimetableSetupWizard.applyRename(entries, "UML501", "Machine Learning", nameToSubjectCode);

        // All 5 should match: e1, e2, e3, e4 by subjectCode or label; e5 has subjectCode so matches
        assertEquals(5, count, "Should relabel 5 UML501 entries. Results: " +
                "e1=" + e1.getLabel() + ", " +
                "e2=" + e2.getLabel() + ", " +
                "e3=" + e3.getLabel() + ", " +
                "e4=" + e4.getLabel() + ", " +
                "e5=" + e5.getLabel());
        assertEquals("Machine Learning lecture", e1.getLabel());
        assertEquals("Machine Learning practical", e2.getLabel());
        assertEquals("Machine Learning tutorial", e3.getLabel());
        assertEquals("Machine Learning lecture", e4.getLabel());
        assertEquals("Machine Learning practical", e5.getLabel());
        assertEquals("UCS503 lecture", e6.getLabel(), "Other subject should not change");
        
        // Verify subjectCode is set for entry that didn't have it
        assertEquals("UML501", e3.getSubjectCode());
        
        // Verify rooms unchanged
        assertEquals("CR101", e1.getRoom());
        assertEquals("Lab2", e2.getRoom());
    }

    @Test
    void testApplyRename_PreviouslyRenamed() {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        
        // Entry with previous custom name in label
        WeeklyTimetableEntry e1 = new WeeklyTimetableEntry(1, 1, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "Neural Networks lecture", "CR101", "UML501");
        entries.add(e1);

        Map<String, String> nameToSubjectCode = new HashMap<>();
        nameToSubjectCode.put("UML501", "UML501");
        nameToSubjectCode.put("Neural Networks", "UML501"); // Previous rename

        int count = TimetableSetupWizard.applyRename(entries, "UML501", "Machine Learning", nameToSubjectCode);

        assertEquals(1, count);
        assertEquals("Machine Learning lecture", e1.getLabel());
    }

    @Test
    void testApplyRename_ResetToCode() {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        
        WeeklyTimetableEntry e1 = new WeeklyTimetableEntry(1, 1, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "Machine Learning lecture", "CR101", "UML501");
        entries.add(e1);

        Map<String, String> nameToSubjectCode = new HashMap<>();
        nameToSubjectCode.put("UML501", "UML501");
        nameToSubjectCode.put("Machine Learning", "UML501");

        int count = TimetableSetupWizard.applyRename(entries, "UML501", "", nameToSubjectCode);

        assertEquals(1, count);
        assertEquals("UML501 lecture", e1.getLabel());
    }

    @Test
    void testApplyRename_NoMatchingEntries() {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        
        WeeklyTimetableEntry e1 = new WeeklyTimetableEntry(1, 1, "MONDAY",
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "UCS503 lecture", "CR101", "UCS503");
        entries.add(e1);

        Map<String, String> nameToSubjectCode = new HashMap<>();
        nameToSubjectCode.put("UML501", "UML501");
        nameToSubjectCode.put("UCS503", "UCS503");

        int count = TimetableSetupWizard.applyRename(entries, "UML501", "Machine Learning", nameToSubjectCode);

        assertEquals(0, count, "Should find no entries to relabel");
        assertEquals("UCS503 lecture", e1.getLabel(), "Other subject unchanged");
    }

    @Test
    void testApplyRename_PreservesMetadata() {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        
        WeeklyTimetableEntry e1 = new WeeklyTimetableEntry(42, 1, "MONDAY",
                LocalTime.of(9, 30), LocalTime.of(11, 10), "CLASS", "UML501 lecture", "CR201", "UML501");
        entries.add(e1);

        Map<String, String> nameToSubjectCode = new HashMap<>();
        nameToSubjectCode.put("UML501", "UML501");

        TimetableSetupWizard.applyRename(entries, "UML501", "ML", nameToSubjectCode);

        assertEquals(42, e1.getEntryId(), "Entry ID preserved");
        assertEquals(1, e1.getSemesterId(), "Semester ID preserved");
        assertEquals("MONDAY", e1.getDayOfWeek(), "Day preserved");
        assertEquals(LocalTime.of(9, 30), e1.getStartTime(), "Start time preserved");
        assertEquals(LocalTime.of(11, 10), e1.getEndTime(), "End time preserved");
        assertEquals("CLASS", e1.getActivityType(), "Activity type preserved");
        assertEquals("CR201", e1.getRoom(), "Room preserved");
        assertEquals("UML501", e1.getSubjectCode(), "Subject code preserved");
    }
}
