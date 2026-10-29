package com.learntrack.service;

import com.learntrack.model.PriorityItem;
import com.learntrack.model.Skill;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SkillPriorityScoringTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private static Skill skill(int priority, LocalDate target) {
        Skill s = new Skill(1, "S", "General", 3, null);
        s.setPriority(priority);
        s.setTargetDate(target);
        return s;
    }

    @Test
    void higherPriorityScoresHigher() {
        assertTrue(PriorityQueueService.skillScore(skill(5, null), 4, TODAY)
                > PriorityQueueService.skillScore(skill(1, null), 4, TODAY));
    }

    @Test
    void tightTargetDateRaisesTheScore() {
        double open = PriorityQueueService.skillScore(skill(3, null), 10, TODAY);
        double relaxed = PriorityQueueService.skillScore(skill(3, TODAY.plusDays(60)), 10, TODAY);
        double tight = PriorityQueueService.skillScore(skill(3, TODAY.plusDays(5)), 10, TODAY);
        assertTrue(tight > relaxed);
        assertTrue(relaxed >= open);
    }

    @Test
    void overdueRevisionGainsPriority() {
        Skill s = skill(3, null);
        assertTrue(PriorityQueueService.reviseScore(s, TODAY.minusDays(14), TODAY)
                > PriorityQueueService.reviseScore(s, TODAY, TODAY));
    }

    @Test
    void plannedTopicsOutrankPyqAndUnplannedTopicsOutrankSkills() {
        PriorityItem planned = new PriorityItem(PriorityItem.ItemType.TOPIC_LEARN, 1, "a", 1, 1, 1);
        PriorityItem unplanned = new PriorityItem(PriorityItem.ItemType.TOPIC_LEARN, 2, "b", 1, 1, 1);
        unplanned.setPlanned(false);
        PriorityItem pyq = new PriorityItem(PriorityItem.ItemType.PYQ_PRACTICE, 3, "c", 1, 1, 1);
        PriorityItem skill = new PriorityItem(PriorityItem.ItemType.SKILL_LEARN, 4, "d", 99, 1, 1);

        assertTrue(PriorityItem.TIER_COMPARATOR.compare(planned, pyq) < 0);
        assertTrue(PriorityItem.TIER_COMPARATOR.compare(pyq, unplanned) < 0);
        assertTrue(PriorityItem.TIER_COMPARATOR.compare(unplanned, skill) < 0);
    }
}
