package com.learntrack.ui.setup;

import com.learntrack.model.WeeklyTimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TimetableSetupWizard.subjectCodesIn helper method.
 */
public class SubjectCodesInTest {

    @Test
    void testExtractsCodesFromLectureTutorialPractical() {
        Map<String, String> nameToSubjectCode = new HashMap<>();
        List<WeeklyTimetableEntry> entries = Arrays.asList(
                entry("UCS503 lecture"),
                entry("UCS503 practical"),
                entry("UML501 tutorial")
        );

        Set<String> codes = TimetableSetupWizard.subjectCodesIn(entries, nameToSubjectCode);

        assertEquals(2, codes.size());
        assertTrue(codes.contains("UCS503"));
        assertTrue(codes.contains("UML501"));
    }

    @Test
    void testIgnoresMealAndNullLabels() {
        Map<String, String> nameToSubjectCode = new HashMap<>();
        List<WeeklyTimetableEntry> entries = Arrays.asList(
                entry("UCS503 lecture"),
                entry("Lunch"),
                entryWithNullLabel(),
                entry("UML501 tutorial")
        );

        Set<String> codes = TimetableSetupWizard.subjectCodesIn(entries, nameToSubjectCode);

        assertEquals(2, codes.size());
        assertTrue(codes.contains("UCS503"));
        assertTrue(codes.contains("UML501"));
    }

    @Test
    void testUsesNameToSubjectCodeMapping() {
        Map<String, String> nameToSubjectCode = new HashMap<>();
        nameToSubjectCode.put("Database Systems", "UCS503");
        nameToSubjectCode.put("Machine Learning", "UML501");

        List<WeeklyTimetableEntry> entries = Arrays.asList(
                entry("Database Systems lecture"),
                entry("Machine Learning practical")
        );

        Set<String> codes = TimetableSetupWizard.subjectCodesIn(entries, nameToSubjectCode);

        assertEquals(2, codes.size());
        assertTrue(codes.contains("UCS503"));
        assertTrue(codes.contains("UML501"));
    }

    @Test
    void testEmptyListReturnsEmptySet() {
        Map<String, String> nameToSubjectCode = new HashMap<>();
        List<WeeklyTimetableEntry> entries = Collections.emptyList();

        Set<String> codes = TimetableSetupWizard.subjectCodesIn(entries, nameToSubjectCode);

        assertTrue(codes.isEmpty());
    }

    @Test
    void testCaseInsensitiveMatching() {
        Map<String, String> nameToSubjectCode = new HashMap<>();
        List<WeeklyTimetableEntry> entries = Arrays.asList(
                entry("UCS503 LECTURE"),
                entry("UML501 Tutorial"),
                entry("UCS510 PRACTICAL")
        );

        Set<String> codes = TimetableSetupWizard.subjectCodesIn(entries, nameToSubjectCode);

        assertEquals(3, codes.size());
        assertTrue(codes.contains("UCS503"));
        assertTrue(codes.contains("UML501"));
        assertTrue(codes.contains("UCS510"));
    }

    @Test
    void testPreservesInsertionOrder() {
        Map<String, String> nameToSubjectCode = new LinkedHashMap<>();
        List<WeeklyTimetableEntry> entries = Arrays.asList(
                entry("UCS503 lecture"),
                entry("UML501 tutorial"),
                entry("UCS510 practical")
        );

        Set<String> codes = TimetableSetupWizard.subjectCodesIn(entries, nameToSubjectCode);

        // LinkedHashSet preserves insertion order
        List<String> codeList = new ArrayList<>(codes);
        assertEquals("UCS503", codeList.get(0));
        assertEquals("UML501", codeList.get(1));
        assertEquals("UCS510", codeList.get(2));
    }

    @Test
    void testDeduplicatesSameSubject() {
        Map<String, String> nameToSubjectCode = new HashMap<>();
        List<WeeklyTimetableEntry> entries = Arrays.asList(
                entry("UCS503 lecture"),
                entry("UCS503 practical"),
                entry("UCS503 tutorial")
        );

        Set<String> codes = TimetableSetupWizard.subjectCodesIn(entries, nameToSubjectCode);

        assertEquals(1, codes.size());
        assertTrue(codes.contains("UCS503"));
    }

    // Helper methods
    private WeeklyTimetableEntry entry(String label) {
        return new WeeklyTimetableEntry(1, 1, "MONDAY", 
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", label, "R101");
    }

    private WeeklyTimetableEntry entryWithNullLabel() {
        return new WeeklyTimetableEntry(1, 1, "MONDAY", 
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", null, "R101");
    }
}
