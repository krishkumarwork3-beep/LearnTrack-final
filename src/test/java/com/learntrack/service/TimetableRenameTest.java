package com.learntrack.service;

import com.learntrack.model.WeeklyTimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class TimetableRenameTest {

    private static final Pattern LABEL_PATTERN = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical)$", Pattern.CASE_INSENSITIVE);

    @Test
    public void testSubjectRenameFlow() {
        List<WeeklyTimetableEntry> entries = new ArrayList<>();
        entries.add(new WeeklyTimetableEntry(null, 0, "MONDAY", LocalTime.of(8, 0), LocalTime.of(8, 50), "CLASS", "UCS503 lecture"));
        entries.add(new WeeklyTimetableEntry(null, 0, "MONDAY", LocalTime.of(11, 20), LocalTime.of(13, 0), "LAB", "UCS503 practical"));
        entries.add(new WeeklyTimetableEntry(null, 0, "TUESDAY", LocalTime.of(9, 0), LocalTime.of(9, 50), "TUTORIAL", "UPH013 tutorial"));

        Map<String, String> codeToName = new LinkedHashMap<>();
        Map<String, String> nameToCode = new LinkedHashMap<>();

        // 1. Find distinct bases
        Set<String> distinctBases = new LinkedHashSet<>();
        for (WeeklyTimetableEntry e : entries) {
            if (e.getLabel() != null) {
                Matcher m = LABEL_PATTERN.matcher(e.getLabel().trim());
                if (m.matches()) {
                    distinctBases.add(m.group(1));
                }
            }
        }
        assertEquals(2, distinctBases.size());
        assertTrue(distinctBases.contains("UCS503"));
        assertTrue(distinctBases.contains("UPH013"));

        // 2. Rename UCS503 -> Software Engineering
        String oldBase = "UCS503";
        String newBase = "Software Engineering";
        String origCode = nameToCode.getOrDefault(oldBase, oldBase);
        codeToName.put(origCode, newBase);
        nameToCode.put(newBase, origCode);

        for (WeeklyTimetableEntry e : entries) {
            if (e.getLabel() != null) {
                Matcher m = LABEL_PATTERN.matcher(e.getLabel().trim());
                if (m.matches() && m.group(1).equalsIgnoreCase(oldBase)) {
                    e.setLabel(newBase + " " + m.group(2).toLowerCase());
                }
            }
        }

        assertEquals("Software Engineering lecture", entries.get(0).getLabel());
        assertEquals("Software Engineering practical", entries.get(1).getLabel());
        assertEquals("UPH013 tutorial", entries.get(2).getLabel());

        // 3. Rename again: Software Engineering -> Advanced SE
        oldBase = "Software Engineering";
        newBase = "Advanced SE";
        origCode = nameToCode.getOrDefault(oldBase, oldBase);
        assertEquals("UCS503", origCode);
        codeToName.put(origCode, newBase);
        nameToCode.put(newBase, origCode);

        for (WeeklyTimetableEntry e : entries) {
            if (e.getLabel() != null) {
                Matcher m = LABEL_PATTERN.matcher(e.getLabel().trim());
                if (m.matches() && m.group(1).equalsIgnoreCase(oldBase)) {
                    e.setLabel(newBase + " " + m.group(2).toLowerCase());
                }
            }
        }

        assertEquals("Advanced SE lecture", entries.get(0).getLabel());
        assertEquals("Advanced SE practical", entries.get(1).getLabel());
        assertEquals("Advanced SE", codeToName.get("UCS503"));
    }
}
