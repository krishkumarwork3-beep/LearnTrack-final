package com.learntrack.service;

/**
 * Maps subtopic difficulty (1-10 scale) to topic difficulty (1-5 scale).
 */
public class DifficultyMapper {

    /**
     * Convert subtopic difficulty (1-10) to topic difficulty (1-5).
     * Uses ceil(d/2) mapping.
     */
    public static int toTopicDifficulty(int subtopicDifficulty) {
        // Clamp input to 1-10
        int d = Math.max(1, Math.min(10, subtopicDifficulty));
        // Map to 1-5 scale: ceil(d/2)
        return (int) Math.ceil(d / 2.0);
    }
}
