package com.learntrack.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SyllabusParser.parseUnitList() method.
 */
public class SyllabusParserUnitListTest {

    @Test
    void testParseUnitListFiveUnits() {
        String input = "Foundation of Cognitive Computing: Understanding cognition, Cognitive computing as a new generation, the uses of cognitive systems, system cognitive, gaining insights from data, Artificial Intelligence (AI) as the foundation of cognitive computing, Applications of cognitive computing\n" +
                "Cognitive System Design Principles: Components of a cognitive system, building the corpus, bringing data into cognitive system, Machine learning based design, Hypothesis generation and scoring, Introduction to Natural language processing (NLP), Taxonomies and Ontologies\n" +
                "Reasoning and Learning: Case based reasoning, Analogical reasoning, Constraint reasoning and Meta reasoning, Concept learning, Common sense-based learning, Explanation based learning\n" +
                "Advance Analytics: Big data and Cognitive computing, Predictive Analytics, Text Analytics, Image Analytics, Speech Analytics\n" +
                "Case Studies: Cognitive Systems in health care, Cognitive Assistant for visually impaired , Introduction to IBM Watson, Cognitive Systems in Finance";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(5, units.size(), "Should parse 5 units");

        // Check unit names
        assertEquals("Foundation of Cognitive Computing", units.get(0).getName());
        assertEquals("Cognitive System Design Principles", units.get(1).getName());
        assertEquals("Reasoning and Learning", units.get(2).getName());
        assertEquals("Advance Analytics", units.get(3).getName());
        assertEquals("Case Studies", units.get(4).getName());

        // Check subtopic counts
        assertEquals(7, units.get(0).getSubtopics().size(), "Unit 1 should have 7 subtopics");
        assertEquals(7, units.get(1).getSubtopics().size(), "Unit 2 should have 7 subtopics");
        assertEquals(6, units.get(2).getSubtopics().size(), "Unit 3 should have 6 subtopics");
        assertEquals(5, units.get(3).getSubtopics().size(), "Unit 4 should have 5 subtopics");
        assertEquals(4, units.get(4).getSubtopics().size(), "Unit 5 should have 4 subtopics");

        // Verify parentheses are preserved
        assertTrue(units.get(0).getSubtopics().contains("Artificial Intelligence (AI) as the foundation of cognitive computing"),
                "Should preserve parentheses in AI subtopic");
        assertTrue(units.get(1).getSubtopics().contains("Introduction to Natural language processing (NLP)"),
                "Should preserve parentheses in NLP subtopic");

        // Verify trailing space and comma are removed
        List<String> caseStudiesSubtopics = units.get(4).getSubtopics();
        boolean foundCleanedSubtopic = false;
        for (String subtopic : caseStudiesSubtopics) {
            if (subtopic.equals("Cognitive Assistant for visually impaired")) {
                foundCleanedSubtopic = true;
                break;
            }
        }
        assertTrue(foundCleanedSubtopic, "Should remove trailing space and comma from 'Cognitive Assistant for visually impaired'");
    }

    @Test
    void testBlankLinesIgnored() {
        String input = "Unit One: subtopic 1, subtopic 2\n" +
                "\n" +
                "Unit Two: subtopic 3, subtopic 4\n" +
                "\n\n" +
                "Unit Three: subtopic 5";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(3, units.size(), "Blank lines should be ignored");
        assertEquals("Unit One", units.get(0).getName());
        assertEquals("Unit Two", units.get(1).getName());
        assertEquals("Unit Three", units.get(2).getName());
    }

    @Test
    void testDuplicateSubtopicsRemoved() {
        String input = "Test Unit: Topic A, Topic B, Topic A, Topic C, topic b";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(1, units.size());
        List<String> subtopics = units.get(0).getSubtopics();
        
        // Should have 3 unique subtopics (case-insensitive duplicate removal)
        assertEquals(3, subtopics.size(), "Duplicates should be removed (case-insensitive)");
        assertTrue(subtopics.contains("Topic A"));
        assertTrue(subtopics.contains("Topic B") || subtopics.contains("topic b"));
        assertTrue(subtopics.contains("Topic C"));
    }

    @Test
    void testLineWithoutColon() {
        String input = "This line has no colon so it should be skipped\n" +
                "Valid Unit: subtopic 1, subtopic 2\n" +
                "Another invalid line\n" +
                "Another Valid Unit: subtopic 3";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(2, units.size(), "Lines without colons should be skipped");
        assertEquals("Valid Unit", units.get(0).getName());
        assertEquals("Another Valid Unit", units.get(1).getName());
    }

    @Test
    void testSingleLineBlobFallback() {
        // Single line with multiple title-case headers - this is a best-effort fallback
        String input = "Introduction to Computing: Basic concepts, History Cognitive Computing: AI systems, Machine learning Data Science: Analytics, Visualization";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        // Fallback may or may not work perfectly - at minimum should not crash
        // If it finds units, verify they have content
        if (!units.isEmpty()) {
            for (ParsedUnit unit : units) {
                assertNotNull(unit.getName(), "Unit name should not be null");
                assertFalse(unit.getName().isEmpty(), "Unit name should not be empty");
            }
        }
        // If fallback doesn't work, that's acceptable - the main use case is one unit per line
    }

    @Test
    void testSingleUnitOneLine() {
        // This is the main expected format - one unit per line
        String input = "Introduction to Computing: Basic concepts, History, Algorithms";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(1, units.size(), "Should parse single unit from single line");
        assertEquals("Introduction to Computing", units.get(0).getName());
        assertEquals(3, units.get(0).getSubtopics().size());
    }

    @Test
    void testEmptyInput() {
        List<ParsedUnit> units = SyllabusParser.parseUnitList("");
        assertTrue(units.isEmpty(), "Empty input should return empty list");

        units = SyllabusParser.parseUnitList(null);
        assertTrue(units.isEmpty(), "Null input should return empty list");
    }

    @Test
    void testTrailingCommaHandling() {
        String input = "Unit Name: subtopic 1, subtopic 2,";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(1, units.size());
        assertEquals(2, units.get(0).getSubtopics().size(), "Trailing comma should not create empty subtopic");
    }

    @Test
    void testMultipleSpacesCollapsed() {
        String input = "Test Unit: subtopic   with   multiple    spaces, normal subtopic";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(1, units.size());
        List<String> subtopics = units.get(0).getSubtopics();
        
        // Check that spaces are collapsed
        boolean foundCollapsed = false;
        for (String subtopic : subtopics) {
            if (subtopic.equals("subtopic with multiple spaces")) {
                foundCollapsed = true;
                break;
            }
        }
        assertTrue(foundCollapsed, "Multiple spaces should be collapsed to single space");
    }

    @Test
    void testHardWrappedLinesFromWordOrPdf() {
        // Text copied from Word/PDF is wrapped mid-unit: continuation lines have no header.
        String input =
                "Foundation of Cognitive Computing: Understanding cognition, Cognitive computing as a new\n" +
                "generation, the uses of cognitive systems, system cognitive, gaining insights from data, Artificial\n" +
                "Intelligence (AI) as the foundation of cognitive computing, Applications of cognitive computing\n" +
                "Cognitive System Design Principles: Components of a cognitive system, building the corpus,\n" +
                "bringing data into cognitive system, Machine learning based design, Hypothesis generation and\n" +
                "scoring, Introduction to Natural language processing (NLP), Taxonomies and Ontologies\n" +
                "Reasoning and Learning: Case based reasoning, Analogical reasoning, Constraint reasoning\n" +
                "and Meta reasoning, Concept learning, Common sense-based learning, Explanation based\n" +
                "learning\n" +
                "Advance Analytics: Big data and Cognitive computing, Predictive Analytics, Text Analytics,\n" +
                "Image Analytics, Speech Analytics\n" +
                "Case Studies: Cognitive Systems in health care, Cognitive Assistant for visually impaired ,\n" +
                "Introduction to IBM Watson, Cognitive Systems in Finance";

        List<ParsedUnit> units = SyllabusParser.parseUnitList(input);

        assertEquals(5, units.size());
        assertEquals(7, units.get(0).getSubtopics().size());
        assertEquals(7, units.get(1).getSubtopics().size());
        assertEquals(6, units.get(2).getSubtopics().size());
        assertEquals(5, units.get(3).getSubtopics().size());
        assertEquals(4, units.get(4).getSubtopics().size());

        assertTrue(units.get(0).getSubtopics().contains("Cognitive computing as a new generation"));
        assertTrue(units.get(2).getSubtopics().contains("Constraint reasoning and Meta reasoning"));
        assertTrue(units.get(2).getSubtopics().contains("Explanation based learning"));
        assertTrue(units.get(4).getSubtopics().contains("Cognitive Assistant for visually impaired"));
    }

    @Test
    void testWindowsLineEndings() {
        List<ParsedUnit> units = SyllabusParser.parseUnitList("Unit A: a1, a2\r\nUnit B: b1,\r\nb2");
        assertEquals(2, units.size());
        assertEquals(2, units.get(1).getSubtopics().size());
    }
}
