package com.learntrack.service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Wrapped list items that merely contain a colon ("... Heuristics, Reduction: transform-and-conquer",
 * "... Operational Modes, Pipeline: ARM 3 stage") must not become units, and bullet-led syllabi
 * ("\u2022 Verbal Ability & Reading Comprehension: ...") need the bullet treated as the unit marker.
 */
public class SyllabusParserWrappedItemsTest {

    private static final String VERBAL =
            "• Verbal Ability & Reading Comprehension: Introduction to Reading Comprehension:\n" +
            "Strategies for approaching different types of passages, identifying main ideas, and\n" +
            "understanding structure, Advanced reading techniques, dealing with inferences, tone, and\n" +
            "author's purpose, Introduction to Verbal Ability: Grammar Fundamentals, sentence structure,\n" +
            "common errors, Sentence Correction and Completion, Review and Test-Taking Strategies:\n" +
            "Consolidate knowledge from all areas, focusing on test-taking strategies, time management,\n" +
            "and practice tests.\n" +
            "• Quantitative & Logical Reasoning: Introduction to Quantitative Ability - Arithmetic Basics:\n" +
            "arithmetic concepts (numbers, operations, percentages, ratios, averages), Advanced Arithmetic\n" +
            "& Introduction to Algebra: more complex arithmetic (interest, partnerships, mixtures, ratios,\n" +
            "variation) and basics of algebra (equations, inequalities, speed, time and distance), Algebra\n" +
            "Continued & Introduction to Modern Math: quadratic equations, sequences and introduce\n" +
            "concepts of modern math set theory, probability, permutations, combinations, and more\n" +
            "complex probability problems, Introduction to Data Interpretation: Basics of data\n" +
            "interpretation, understanding and interpreting bar charts and pie charts, Data Interpretation -\n" +
            "Line Charts and Tables, interpreting trends, and comparing data, practice session with mixed\n" +
            "problems from quantitative ability and data interpretation, Introduction to Logical Reasoning:\n" +
            "Number Series and Sequences, Family Relationships and Directions.\n" +
            "Course Learning Outcome\n";

    private static final String ALGORITHMS =
            "Syllabus\n" +
            "Basics of Object Oriented Programming (OOP): Introduction to OOP, Classes, Objects\n" +
            "and Methods, Data Abstraction, Polymorphism, Inheritance, Abstract Data Type.\n" +
            "Linear data structures: arrays, linked lists, strategies for choosing the appropriate data\n" +
            "structure, abstract data types and their implementation: stacks, queues, priority queues.\n" +
            "Basic Analysis: Asymptotic analysis, Big O notation, big omega and big theta notation,\n" +
            "Time and space trade-offs in algorithms, Recurrence relations, Analysis of iterative and\n" +
            "recursive algorithms, Linear Search, Binary Search, Bubble Sort, Selection Sort, Insertion\n" +
            "Sort, Quick Sort, Heap Sort, Merge Sort.\n" +
            "Algorithmic Strategies with examples and problem solving: Brute-force algorithms with\n" +
            "examples, Greedy algorithms with examples, Recursive backtracking, Dynamic\n" +
            "Programming with examples, Branch-and-bound with examples, Heuristics, Reduction:\n" +
            "transform-and-conquer with examples.\n" +
            "Non-Linear Data Structures and Sorting Algorithms: Hash tables, including strategies\n" +
            "for avoiding and resolving collisions, Binary search trees, Graphs and graph algorithms,\n" +
            "Representations of graphs, Depth- and breadth-first traversals, Heaps, Shortest-path\n" +
            "algorithms (Dijkstra and Floyd), Minimum spanning tree (Prim and Kruskal).\n" +
            "Laboratory Work:\n";

    private static final String OPAMP =
            "Syllabus\n" +
            "Introduction to Differential Amplifiers: Differential Amplifier, configurations of\n" +
            "differential amplifier, Analysis of single input balanced output, single input unbalanced\n" +
            "output, dual input balanced output and dual input unbalanced output differential amplifiers\n" +
            "Operational amplifier: various characteristics of op-amp, CMRR, PSRR, Internal structure\n" +
            "of Op-amp, Ideal Op-amp.Inverting and Non-Inverting Configuration, Ideal Open-Loop and\n" +
            "CLO ssed-Loop Operation of Op-Amp, Feedback Configurations: Voltage-Series Feedback\n" +
            "Amplifier, Voltage-Shunt Feedback Amplifier, Differential Amplifiers with One & Two Op-\n" +
            "Amps\n" +
            "Frequency Response of an Op-Amp: Introduction to Frequency Response, Compensating\n" +
            "Networks, Frequency Response of Internally Compensated Op-Amp, Frequency response of\n" +
            "Non-compensated Op-Amp, CLO ssed-Loop Frequency Response.\n" +
            "General Applications: DC & AC Amplifiers, Peaking Amplifier, Summing, Scaling and\n" +
            "Averaging amplifier, Instrumentation Amplifier, The Integrator, The Differentiator, Log and\n" +
            "Antilog Amplifier, Comparator, Zero Crossing Detector, Schmitt Trigger, Sample and Hold\n" +
            "Circuit, Clippers and Clampers etc.\n" +
            "Active Filters and Oscillators: Butterworth Filters, Band-Pass Filters, Band Reject Filters,\n" +
            "All Pass Filters, Phase Shift Oscillator, Wien Bridge Oscillator, Voltage-Controlled\n" +
            "Oscillator (VCO), Square Wave Generator.\n" +
            "Specialized IC Applications: Introduction, The 555 Timer, Monostable and Astable\n" +
            "Multivibrator using IC 555, Phase-Locked Loop (PLL), Voltage Regulators.\n" +
            "Familiarization with Standards: IEEE 165-1977.\n" +
            "Laboratory Work: Inverting and Non-Inverting Characteristics of an Op-Amp\n";

    private static final String ARM =
            "Syllabus\n" +
            "Introduction to Embedded Systems: Definition, Embedded Systems Vs General\n" +
            "Computing Systems, Classification of Embedded Systems, Major application areas. General\n" +
            "purpose processor architecture and organization, Von-Neumann and Harvard architectures,\n" +
            "CISC and RISC architectures, Big- and Little-endian processors, Processor design trade-offs,\n" +
            "Processor cores: soft and hard.\n" +
            "Introduction to ARM Processor: The ARM design philosophy, ARM core data flow model,\n" +
            "Architecture, Register set, ARM7TDMI Interface signals, General Purpose Input Output\n" +
            "Registers, Memory Interface, Bus Cycle types, Operational Modes, Pipeline: ARM 3 stage\n" +
            "Pipeline, ARM family attribute comparison. ARM 5 stage Pipeline, Pipeline Hazards, Data\n" +
            "forwarding.\n" +
            "Programming based on ARM7TDMI: ARM Instruction set, condition codes, Addressing\n" +
            "modes, Interrupts, Exceptions and Vector Table. Instruction Format Assembly Language\n" +
            "Programming, Thumb state, Thumb Programmers model, Thumb Instruction format, Thumb\n" +
            "Applications, ARM coprocessor interface and Instructions.\n" +
            "ARM Tools and Interfacing of Peripherals: ARM Development Environment, Arm\n" +
            "Procedure Call Standard (APCS), Example C/C++ programs, Embedded software\n" +
            "development, Image structure, linker inputs and outputs, Protocols (I2C, SPI), Memory\n" +
            "Protection Unit (MPU). Physical Vs Virtual Memory, Paging, Segmentation. The Advanced\n" +
            "Microcontroller Bus Architecture (AMBA), DMA, Peripherals, Interfacing of peripherals\n" +
            "with ARM.\n" +
            "Familiarization with Standards: IEEE 1275.1-1994 snd IEEE 1754.\n";

    private static List<String> names(List<ParsedUnit> u) {
        return u.stream().map(ParsedUnit::getName).toList();
    }

    @Test
    void bulletLedUnits() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(VERBAL);
        assertEquals(List.of("Verbal Ability & Reading Comprehension", "Quantitative & Logical Reasoning"), names(u));
        assertEquals(15, u.get(0).getSubtopics().size());
        assertTrue(u.get(0).getSubtopics().contains("Introduction to Verbal Ability: Grammar Fundamentals"));
        // "Course Learning Outcome" footer is not content; wrapped "&" fragments are not units
        assertTrue(u.get(1).getSubtopics().contains("Advanced Arithmetic & Introduction to Algebra: more complex arithmetic (interest, partnerships, mixtures, ratios, variation) and basics of algebra (equations, inequalities, speed, time and distance)"));
    }

    @Test
    void wrappedListItemWithColonIsNotAUnit() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(ALGORITHMS);
        assertEquals(List.of("Basics of Object Oriented Programming (OOP)", "Linear data structures", "Basic Analysis",
                "Algorithmic Strategies with examples and problem solving",
                "Non-Linear Data Structures and Sorting Algorithms"), names(u));
        List<String> strat = u.get(3).getSubtopics();
        assertTrue(strat.contains("Dynamic Programming with examples"));
        assertTrue(strat.contains("Branch-and-bound with examples"));
        assertTrue(strat.contains("Reduction: transform-and-conquer with examples"));
    }

    @Test
    void opAmpWrappedLinesKeepSixteenTopicsInRightUnits() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(OPAMP);
        assertEquals(List.of("Introduction to Differential Amplifiers", "Operational amplifier",
                "Frequency Response of an Op-Amp", "General Applications", "Active Filters and Oscillators",
                "Specialized IC Applications", "Familiarization with Standards"), names(u));
        assertTrue(u.get(1).getSubtopics().contains("Differential Amplifiers with One & Two Op-Amps"));
        for (ParsedUnit unit : u) assertFalse(unit.getName().contains(","), unit.getName());
    }

    @Test
    void armWrappedLineStartingWithListItemsIsNotAUnit() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(ARM);
        assertEquals(List.of("Introduction to Embedded Systems", "Introduction to ARM Processor",
                "Programming based on ARM7TDMI", "ARM Tools and Interfacing of Peripherals",
                "Familiarization with Standards"), names(u));
        List<String> arm = u.get(1).getSubtopics();
        assertTrue(arm.contains("General Purpose Input Output Registers"));
        assertTrue(arm.contains("Pipeline: ARM 3 stage Pipeline"));
        List<String> tools = u.get(3).getSubtopics();
        assertTrue(tools.contains("Memory Protection Unit (MPU)"));
        assertTrue(tools.contains("Physical Vs Virtual Memory"));
    }
}