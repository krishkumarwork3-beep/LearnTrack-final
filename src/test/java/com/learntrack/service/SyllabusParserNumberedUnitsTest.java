package com.learntrack.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Numbered "Unit N." headings are the main units; topics beneath are subunits. */
public class SyllabusParserNumberedUnitsTest {

    @Test
    void numberedUnitsAreMainUnits() {
        String input = "Unit 1. Java Foundations for Application Development:\n" +
                "OOP concepts, Classes and Objects\n" +
                "Inheritance: extends and super\n" +
                "Unit 2. Exception Handling, Collections & Generics (for real application flow):\n" +
                "\u2022 try/catch/finally\n" +
                "\u2022 List, Set, Map (HashMap, TreeMap)\n" +
                "Unit 3. File Handling, Data Persistence & Logging:\n" +
                "Streams\n" +
                "Unit 4. JDBC and Database Connectivity (Core for Projects):\n" +
                "Connection, Statement\n" +
                "Unit 5. JavaFX:\n" +
                "Scenes, Stages";

        List<ParsedUnit> u = SyllabusParser.parseUnitList(input);

        assertEquals(5, u.size());

        assertEquals("Unit 1. Java Foundations for Application Development", u.get(0).getName());
        assertEquals("Unit 2. Exception Handling, Collections & Generics (for real application flow)", u.get(1).getName());
        assertEquals("Unit 3. File Handling, Data Persistence & Logging", u.get(2).getName());
        assertEquals("Unit 4. JDBC and Database Connectivity (Core for Projects)", u.get(3).getName());
        assertEquals("Unit 5. JavaFX", u.get(4).getName());

        assertEquals(List.of("OOP concepts", "Classes and Objects", "Inheritance: extends and super"), u.get(0).getSubtopics());
        assertEquals(List.of("try/catch/finally", "List", "Set", "Map (HashMap, TreeMap)"), u.get(1).getSubtopics());
        assertEquals(List.of("Scenes", "Stages"), u.get(4).getSubtopics());
    }
}
