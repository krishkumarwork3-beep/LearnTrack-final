package com.learntrack.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DifficultyMapper.
 */
public class DifficultyMapperTest {

    @Test
    void testMapping1() {
        assertEquals(1, DifficultyMapper.toTopicDifficulty(1));
        assertEquals(1, DifficultyMapper.toTopicDifficulty(2));
    }

    @Test
    void testMapping5() {
        assertEquals(3, DifficultyMapper.toTopicDifficulty(5));
        assertEquals(3, DifficultyMapper.toTopicDifficulty(6));
    }

    @Test
    void testMapping6() {
        assertEquals(3, DifficultyMapper.toTopicDifficulty(6));
    }

    @Test
    void testMapping10() {
        assertEquals(5, DifficultyMapper.toTopicDifficulty(10));
        assertEquals(5, DifficultyMapper.toTopicDifficulty(9));
    }

    @Test
    void testClampingBelowRange() {
        assertEquals(1, DifficultyMapper.toTopicDifficulty(0));
        assertEquals(1, DifficultyMapper.toTopicDifficulty(-5));
    }

    @Test
    void testClampingAboveRange() {
        assertEquals(5, DifficultyMapper.toTopicDifficulty(11));
        assertEquals(5, DifficultyMapper.toTopicDifficulty(100));
    }

    @Test
    void testAllMappings() {
        // 1-2 -> 1
        assertEquals(1, DifficultyMapper.toTopicDifficulty(1));
        assertEquals(1, DifficultyMapper.toTopicDifficulty(2));
        // 3-4 -> 2
        assertEquals(2, DifficultyMapper.toTopicDifficulty(3));
        assertEquals(2, DifficultyMapper.toTopicDifficulty(4));
        // 5-6 -> 3
        assertEquals(3, DifficultyMapper.toTopicDifficulty(5));
        assertEquals(3, DifficultyMapper.toTopicDifficulty(6));
        // 7-8 -> 4
        assertEquals(4, DifficultyMapper.toTopicDifficulty(7));
        assertEquals(4, DifficultyMapper.toTopicDifficulty(8));
        // 9-10 -> 5
        assertEquals(5, DifficultyMapper.toTopicDifficulty(9));
        assertEquals(5, DifficultyMapper.toTopicDifficulty(10));
    }
}
