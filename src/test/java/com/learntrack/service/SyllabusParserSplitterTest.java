package com.learntrack.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the comma splitter that respects parentheses.
 */
public class SyllabusParserSplitterTest {

    @Test
    void testSplitWithParentheses() {
        String input = "a, b (c, d), e";
        List<String> result = SyllabusParser.splitRespectingParentheses(input, ',');
        
        assertEquals(3, result.size(), "Should split into 3 items");
        assertEquals("a", result.get(0));
        assertEquals("b (c, d)", result.get(1));
        assertEquals("e", result.get(2));
    }

    @Test
    void testSplitSimple() {
        String input = "item1, item2, item3";
        List<String> result = SyllabusParser.splitRespectingParentheses(input, ',');
        
        assertEquals(3, result.size());
        assertEquals("item1", result.get(0));
        assertEquals("item2", result.get(1));
        assertEquals("item3", result.get(2));
    }

    @Test
    void testSplitNestedParentheses() {
        String input = "Topic A, Topic B (with, nested, commas), Topic C";
        List<String> result = SyllabusParser.splitRespectingParentheses(input, ',');
        
        assertEquals(3, result.size());
        assertEquals("Topic A", result.get(0));
        assertEquals("Topic B (with, nested, commas)", result.get(1));
        assertEquals("Topic C", result.get(2));
    }

    @Test
    void testSplitEmpty() {
        String input = "";
        List<String> result = SyllabusParser.splitRespectingParentheses(input, ',');
        
        assertTrue(result.isEmpty());
    }

    @Test
    void testSplitNoCommas() {
        String input = "single item";
        List<String> result = SyllabusParser.splitRespectingParentheses(input, ',');
        
        assertEquals(1, result.size());
        assertEquals("single item", result.get(0));
    }

    @Test
    void testSplitOnlyParentheses() {
        String input = "(a, b, c)";
        List<String> result = SyllabusParser.splitRespectingParentheses(input, ',');
        
        assertEquals(1, result.size());
        assertEquals("(a, b, c)", result.get(0));
    }
}
