package com.learntrack.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real course pages from a scheme booklet (text as PDF copy-paste produces it). Each fixture is a
 * layout the parser used to get wrong; together they pin the expected unit structure.
 */
class SyllabusParserSchemeBookletTest {

    private static List<ParsedUnit> parse(String fixture) throws IOException {
        try (InputStream in = SyllabusParserSchemeBookletTest.class.getResourceAsStream("/syllabi/" + fixture)) {
            assertNotNull(in, "missing fixture " + fixture);
            return SyllabusParser.parseUnitList(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> names(List<ParsedUnit> units) {
        return units.stream().map(ParsedUnit::getName).collect(Collectors.toList());
    }

    private static List<String> all(List<ParsedUnit> units) {
        return units.stream().flatMap(u -> u.getSubtopics().stream()).collect(Collectors.toList());
    }

    @Test
    void headingFollowedByNumberedListBecomesOneUnit() throws IOException {
        List<ParsedUnit> u = parse("UES101_heading_numbered_lists.txt");
        assertEquals(List.of("Engineering Drawing Concepts", "2D Drafting", "3D Modelling"), names(u));
        assertTrue(u.get(0).getSubtopics().contains("Isometric Projections"));
        assertTrue(u.get(1).getSubtopics().contains("Layering: organizing and maintaining the integrity of drawings"));
        assertTrue(all(u).stream().noneMatch(s -> s.contains("Completing the views")), "micro projects are not syllabus");
    }

    @Test
    void headingOnItsOwnLineAbovePlainParagraph() throws IOException {
        List<ParsedUnit> u = parse("UEC734_standalone_headings.txt");
        assertEquals(List.of("Introduction to Compiling", "Lexical Analysis", "Syntax Analysis",
                "Semantics Analysis and Intermediate Code Generation",
                "Code Optimization and Run Time Environments"), names(u));
        assertTrue(u.get(1).getSubtopics().contains("Role of Lexical Analyzer"));
    }

    @Test
    void numberedOutcomesAreNeverUnits() throws IOException {
        List<ParsedUnit> u = parse("UNC303_outcomes_after_syllabus.txt");
        assertEquals(List.of("Overview of Data Communication and Networking", "Data link layer", "Network layer",
                "Transport layer", "Application layer", "Familiarization with standard"), names(u));
        assertTrue(all(u).stream().noneMatch(s -> s.startsWith("Understand") || s.startsWith("Analyze")));
    }

    @Test
    void strayTrailingCommasDoNotMergeUnits() throws IOException {
        List<ParsedUnit> u = parse("UPH064_trailing_commas.txt");
        assertEquals(List.of("Fundamental of Nanoscience", "Quantum Size Effect", "Nano Materials",
                "Synthesis and processing", "Characterization", "Applications"), names(u));
    }

    @Test
    void firstSemicolonClauseOfEachParagraphIsTheUnit() throws IOException {
        List<ParsedUnit> u = parse("UNCXXX_prompt_engineering_lead_clauses.txt");
        assertEquals(List.of("Introduction to Prompt Engineering", "Basic Prompting Techniques",
                "Prompting for Complex Tasks", "Evaluation and Ethical Use", "Familiarization with standard"), names(u));
        assertTrue(u.get(1).getSubtopics().contains("Role-based prompting for different tasks"));
    }

    @Test
    void contentBeforeUnitTwoBecomesUnitOne() throws IOException {
        List<ParsedUnit> u = parse("UHU005_missing_unit_one.txt");
        assertEquals("Unit I", u.get(0).getName());
        assertTrue(u.get(0).getSubtopics().contains("Emotional Intelligence"));
        assertEquals(3, u.size());
    }

    @Test
    void newUnitAfterAFinishedSentenceInsideAParagraph() throws IOException {
        List<ParsedUnit> u = parse("UNC401_units_inside_paragraph.txt");
        assertEquals(List.of("Introduction", "Linear data structures", "Trees and their applications",
                "Familiarization with standard"), names(u));
    }

    @Test
    void repeatedInlineLabelsStayInsideTheirUnit() throws IOException {
        List<ParsedUnit> u = parse("UCS551_repeated_inline_labels.txt");
        assertFalse(names(u).stream().anyMatch(n -> n.toLowerCase().startsWith("rapids")));
        assertTrue(all(u).stream().anyMatch(s -> s.contains("RAPIDS Acceleration: Random Forest")
                || s.contains("RAPIDS acceleration: K-NN")));
    }

    @Test
    void oldMergedCourseHeaderLinesAreNotUnits() {
        String text = "UNC***: MACHINE LEARNING\nL T P\n3 0 2\n"
                + "Introduction: Basics of ML, Applications.\n\n"
                + "Neural Networks: Perceptron, Backpropagation.\n";
        List<ParsedUnit> u = SyllabusParser.parseUnitList(text);
        assertEquals(List.of("Introduction", "Neural Networks"), names(u));
    }
}
