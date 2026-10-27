package com.learntrack.service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Communication skills (one stray blank line must not merge units; "Category 1:" parts stay inside "Reading")
 * and C programming ("Title- items" headings that use a hyphen instead of a colon).
 */
public class SyllabusParserCommunicationAndCTest {

    private static final String COMMUNICATION =
            "Fundamentals of Communication: Meaning, Types and Characteristics of\n" +
            "communication, Applicability of Transactional Analysis and Johari Window for enhancing\n" +
            "interpersonal communication skills. Seven Cs of Effective Communication, Barriers to\n" +
            "Effective Communication.\n" +
            "Effective Oral Communication: Understanding Principles of Oral communication,\n" +
            "Formal and Informal Oral Communication, Oral Communication and Behavioral Patterns,\n" +
            "Advantages and Disadvantages of Oral Communication.\n" +
            "Effective Listening: Listening vs Hearing, Active Listening techniques, Barriers to\n" +
            "Listening.\n" +
            "Effective non-verbal communication: Meaning and Importance of Non-Verbal\n" +
            "Communication, Different Types of Non-verbal Communication, Interpretation of Non-\n" +
            "verbal Cues.\n" +
            "Effective written Communication: Characteristics of Good Writing, Choice of Words,\n" +
            "Sentence Construction, Paragraph development, Forms of writing.\n" +
            "Business Communication: Technical Report Writing, Designing Resumes and Cover\n" +
            "Letters for effective job application, E-mail writing and e-mail etiquette.\n" +
            "Organizational Communication: Directional communication: Downward, Upward and\n" +
            "Horizontal Communication, Grapevine.\n" +
            "Reading: The following texts (one from each of the two categories listed below) are\n" +
            "required to be read by the students in the semester:\n" +
            "Category 1: Animal Farm by George Orwell, Lord of the Flies by William Golding, Life\n" +
            "of Pi by Yann Martel\n" +
            "Category 2: The Namesake by Jhumpa Lahiri, The God of Small Things by Arundhati\n" +
            "Roy, Q&A by Vikas Swarup\n";

    private static final String C_PROGRAMMING =
            "Introduction to Computer Fundamentals- Computer Memory Hierarchy, Types of\n" +
            "Software Binary number system, Algorithm, Flowchart, Formulate simple algorithms\n" +
            "for logical and arithmetic problems.\n" +
            "Basics of C Programming: Structure and Life cycle of a C Program, Data types,\n" +
            "Identifiers, Variables, Keywords, Constants, input/output statements, Operators, Type\n" +
            "conversion and type casting. Translate the algorithms to code snippets.\n" +
            "Decision Making and Iterative Statements- Decision making- if, if-else, Nested if-\n" +
            "else, Multiple if, else if, switch, Ternary Operator, Loops- (while, do-while, for),\n" +
            "Nesting of Loops, break, continue and goto. Implement the switch () to solve the basic\n" +
            "functions of scientific calculator.\n" +
            "Functions: Function prototype, Definition and Call, Type of Functions, Scope of\n" +
            "variables in (Block, Function, Program, File), Storage classes (Auto, Register, Static\n" +
            "and Extern), Recursion (with the introduction of Stack), Implementation of recursion to\n" +
            "solve the problem of Tower of Hanoi.\n" +
            "Arrays and Strings- One-dimensional array its operations (Traversal, Linear Search,\n" +
            "Insertion, Deletion, Bubble Sort), Two-dimensional and its operations (Addition,\n" +
            "Transpose and Multiplication), Passing of array into a function (row and entire array),\n" +
            "Input and output of a string, string inbuilt functions, 2-D Character array.\n" +
            "Pointers: Introduction to Pointers, Pointer arithmetic, Passing arguments to a function\n" +
            "using pointer (understanding of call by value and call by reference), Accessing arrays\n" +
            "using pointers Dynamic memory allocation (malloc(), calloc(), realloc() and free()),\n" +
            "Pointer and Functions.\n" +
            "Structures and Union: Structure declaration, Initialization of structures, Structure\n" +
            "variables, Accessing structure elements using (.) operator, Array of structure variables,\n" +
            "Passing structure variable to a function (individual and entire structure), Structure\n" +
            "pointer, Comparison of Structure and Union.\n" +
            "File Handling: Introduction of Files (streams in C), using File (Declaring, Opening and\n" +
            "Closing), Operations on File (Reading, Writing and appending), and Random Access of\n" +
            "a file, command line argument.\n";

    private static void assertCommunication(List<ParsedUnit> u) {
        assertEquals(List.of("Fundamentals of Communication", "Effective Oral Communication", "Effective Listening",
                "Effective non-verbal communication", "Effective written Communication", "Business Communication",
                "Organizational Communication", "Reading"), u.stream().map(ParsedUnit::getName).toList());
        assertEquals(5, u.get(0).getSubtopics().size());
        assertTrue(u.get(2).getSubtopics().contains("Listening vs Hearing"));
        assertTrue(u.get(3).getSubtopics().contains("Interpretation of Non-verbal Cues"));
        // "Category 1/2" belong to Reading and keep their label
        List<String> reading = u.get(7).getSubtopics();
        assertEquals(7, reading.size());
        assertTrue(reading.contains("Category 1: Animal Farm by George Orwell"));
        assertTrue(reading.contains("Category 2: Q&A by Vikas Swarup"));
    }

    @Test
    void communicationWithoutBlankLines() {
        assertCommunication(SyllabusParser.parseUnitList(COMMUNICATION));
    }

    @Test
    void communicationWithOneStrayBlankLineStillGivesEightUnits() {
        assertCommunication(SyllabusParser.parseUnitList(COMMUNICATION.replace("Reading: The", "\nReading: The")));
    }

    @Test
    void communicationWithBlankLinesBetweenEveryUnit() {
        assertCommunication(SyllabusParser.parseUnitList(COMMUNICATION.replace("\nEffective", "\n\nEffective")
                .replace("\nBusiness", "\n\nBusiness").replace("\nOrganizational", "\n\nOrganizational")
                .replace("\nReading", "\n\nReading")));
    }

    @Test
    void hyphenHeadingsAreUnitsAndFirstUnitIsNotLost() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(C_PROGRAMMING);
        assertEquals(List.of("Introduction to Computer Fundamentals", "Basics of C Programming",
                "Decision Making and Iterative Statements", "Functions", "Arrays and Strings", "Pointers",
                "Structures and Union", "File Handling"), u.stream().map(ParsedUnit::getName).toList());
        assertTrue(u.get(0).getSubtopics().contains("Computer Memory Hierarchy"));
        assertTrue(u.get(2).getSubtopics().contains("Nested if-else"));
        assertTrue(u.get(4).getSubtopics().contains("Two-dimensional and its operations (Addition, Transpose and Multiplication)"));
        assertTrue(u.get(7).getSubtopics().contains("Random Access of a file"));
    }
}