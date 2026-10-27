package com.learntrack.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class SkillTest {

    @Test
    void testIsNeverPracticed() {
        Skill skillNull = new Skill(1, "Typing", "Tech", 3, null);
        assertTrue(skillNull.isNeverPracticed(), "Null lastPracticed must be considered never practiced");

        Skill skillSentinel = new Skill(2, "Guitar", "Music", 3, Skill.NEVER_PRACTICED);
        assertTrue(skillSentinel.isNeverPracticed(), "Sentinel 2000-01-01 must be considered never practiced");

        Skill skillBeforeSentinel = new Skill(3, "Flute", "Music", 3, LocalDate.of(1999, 12, 31));
        assertTrue(skillBeforeSentinel.isNeverPracticed(), "Date on or before sentinel must be considered never practiced");

        Skill skill2001 = new Skill(4, "Chess", "Games", 3, LocalDate.of(2001, 1, 1));
        assertFalse(skill2001.isNeverPracticed(), "Date in 2001 or later must not be considered never practiced");

        Skill skillRecent = new Skill(5, "Java", "Tech", 4, LocalDate.of(2026, 10, 1));
        assertFalse(skillRecent.isNeverPracticed(), "Recent date must not be considered never practiced");
    }
}
