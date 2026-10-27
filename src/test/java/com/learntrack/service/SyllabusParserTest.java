package com.learntrack.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SyllabusParser.
 */
public class SyllabusParserTest {

    private final SyllabusParser parser = new SyllabusParser();

    @Test
    void testParseUCS420Syllabus() throws IOException {
        Path path = Paths.get("docs/samples/UCS420.txt");
        if (!Files.exists(path)) {
            System.out.println("Skipping testParseUCS420Syllabus: sample file not found");
            return;
        }

        String text = Files.readString(path);
        List<ParsedUnit> units = parser.parse(text);

        assertFalse(units.isEmpty(), "Should parse at least one unit from UCS420");
        
        // Expected units: Introduction to Cognitive Computing, Knowledge Representation and Reasoning,
        // Natural Language Processing, Machine Learning for Cognitive Systems, Computer Vision
        assertTrue(units.size() >= 5, "Expected at least 5 units, got " + units.size());

        // Check first unit
        ParsedUnit firstUnit = units.get(0);
        assertEquals("Introduction to Cognitive Computing", firstUnit.getName());
        assertFalse(firstUnit.getSubtopics().isEmpty());
        
        // Check that parentheses are respected: "Artificial Intelligence (AI)" should be one subtopic
        boolean hasAISubtopic = firstUnit.getSubtopics().stream()
                .anyMatch(s -> s.contains("Artificial Intelligence (AI)"));
        assertTrue(hasAISubtopic, "Should have 'Artificial Intelligence (AI)' as one subtopic");

        // Check another unit has subtopics
        boolean allUnitsHaveSubtopics = units.stream()
                .allMatch(unit -> !unit.getSubtopics().isEmpty());
        assertTrue(allUnitsHaveSubtopics, "All units should have at least one subtopic");
    }

    @Test
    void testParseUCS503Syllabus() throws IOException {
        Path path = Paths.get("docs/samples/UCS503.txt");
        if (!Files.exists(path)) {
            System.out.println("Skipping testParseUCS503Syllabus: sample file not found");
            return;
        }

        String text = Files.readString(path);
        List<ParsedUnit> units = parser.parse(text);

        assertFalse(units.isEmpty(), "Should parse at least one unit from UCS503");
        
        // Expected units: Introduction to Software Engineering, Requirements Engineering,
        // Software Design, Software Testing, Software Project Management, 
        // Software Quality and Maintenance, Agile Software Development
        assertTrue(units.size() >= 6, "Expected at least 6 units, got " + units.size());

        // Check mid-paragraph headers are detected (e.g., "Requirements Engineering:" appears 
        // after "Introduction to Software Engineering" content)
        boolean hasRequirementsEngineering = units.stream()
                .anyMatch(u -> u.getName().contains("Requirements Engineering"));
        assertTrue(hasRequirementsEngineering, "Should detect 'Requirements Engineering' as a unit");

        // Check that "Twelve Practices of eXtreme Programming (XP)" is kept as one item
        boolean hasXPPractices = units.stream()
                .flatMap(u -> u.getSubtopics().stream())
                .anyMatch(s -> s.contains("Twelve Practices of eXtreme Programming (XP)"));
        assertTrue(hasXPPractices, "Should have 'Twelve Practices of eXtreme Programming (XP)' as one subtopic");
    }

    @Test
    void testExtractSubjectCode() {
        String text1 = "UCS420: COGNITIVE COMPUTING\n\nL T P Cr\n3 0 2 4";
        String code1 = parser.extractSubjectCode(text1);
        assertEquals("UCS420", code1);

        String text2 = "ucs503: Software Engineering\n\nSome content...";
        String code2 = parser.extractSubjectCode(text2);
        assertEquals("UCS503", code2);

        String text3 = "No subject code here\nJust regular text";
        String code3 = parser.extractSubjectCode(text3);
        assertNull(code3);
    }

    @Test
    void testSplitRespectingParentheses() {
        String text1 = "Artificial Intelligence (AI), Machine Learning, Deep Learning";
        List<String> parts1 = SyllabusParser.splitRespectingParentheses(text1, ',');
        assertEquals(3, parts1.size());
        assertEquals("Artificial Intelligence (AI)", parts1.get(0));
        assertEquals("Machine Learning", parts1.get(1));
        assertEquals("Deep Learning", parts1.get(2));

        String text2 = "Item 1 (with, comma), Item 2, Item 3 (also, has, commas)";
        List<String> parts2 = SyllabusParser.splitRespectingParentheses(text2, ',');
        assertEquals(3, parts2.size());
        assertEquals("Item 1 (with, comma)", parts2.get(0));
        assertEquals("Item 2", parts2.get(1));
        assertEquals("Item 3 (also, has, commas)", parts2.get(2));

        String text3 = "Simple, List, Without, Parens";
        List<String> parts3 = SyllabusParser.splitRespectingParentheses(text3, ',');
        assertEquals(4, parts3.size());

        String text4 = "";
        List<String> parts4 = SyllabusParser.splitRespectingParentheses(text4, ',');
        assertTrue(parts4.isEmpty());
    }

    @Test
    void testParseWithHardLineBreaks() {
        // Simulate pasted PDF text with hard line breaks mid-sentence
        String text = """
                Course Objectives:
                Some intro text.
                
                Introduction to AI: This is artificial
                intelligence which includes machine
                learning algorithms, Deep neural
                networks, Computer vision
                
                Natural Language Processing: Text
                processing techniques, Sentiment
                analysis
                """;

        List<ParsedUnit> units = parser.parse(text);
        
        assertFalse(units.isEmpty(), "Should parse units even with hard line breaks");
        
        // The parser should join wrapped lines
        ParsedUnit firstUnit = units.get(0);
        assertTrue(firstUnit.getSubtopics().stream()
                .anyMatch(s -> s.contains("intelligence")), 
                "Should have joined 'artificial intelligence' across lines");
    }

    @Test
    void testParseEmptyText() {
        List<ParsedUnit> units = parser.parse("");
        assertTrue(units.isEmpty());

        units = parser.parse(null);
        assertTrue(units.isEmpty());
    }

    @Test
    void testParseWithoutMarkers() {
        String text = """
                Machine Learning: Supervised learning, Unsupervised learning, Reinforcement learning
                
                Deep Learning: Neural networks, CNNs, RNNs
                """;

        List<ParsedUnit> units = parser.parse(text);
        
        assertEquals(2, units.size());
        assertEquals("Machine Learning", units.get(0).getName());
        assertEquals(3, units.get(0).getSubtopics().size());
        assertEquals("Deep Learning", units.get(1).getName());
        assertEquals(3, units.get(1).getSubtopics().size());
    }
}
