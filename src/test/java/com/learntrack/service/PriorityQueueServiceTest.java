package com.learntrack.service;

import com.learntrack.model.Exam;
import com.learntrack.model.PriorityItem;
import com.learntrack.model.Skill;
import com.learntrack.model.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

public class PriorityQueueServiceTest {

    private PriorityQueueService queueService;
    private ScoringService scoringService;

    @BeforeEach
    void setUp() {
        scoringService = new ScoringService();
        queueService = new PriorityQueueService(scoringService);
    }

    @Test
    void testImportantFlagZeroFilteredOutBeforeScoring() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setExamId(1);
        exam.setCredits(4);
        exam.setEvalMarks(20.0);
        exam.setExamDate(LocalDate.of(2026, 10, 20));

        Topic importantTopic = new Topic(1, 1, "Sorting", 5, 4, 6.0, 6.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        Topic excludedTopic = new Topic(2, 1, "Optional Trees", 5, 5, 10.0, 10.0, "LEARNING", 2.0, 2.0, 7, null, 0);

        PriorityQueue<PriorityItem> q = queueService.build(
                List.of(importantTopic, excludedTopic),
                List.of(),
                List.of(exam),
                today,
                1.0
        );

        // Only importantTopic must be in queue; excludedTopic (flag=0) filtered out completely
        assertEquals(1, q.size());
        PriorityItem item = q.poll();
        assertEquals("Sorting", item.getName());
    }

    @Test
    void testUnifiedQueueOrdering() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setExamId(1);
        exam.setCredits(4);
        exam.setEvalMarks(20.0);
        exam.setExamDate(LocalDate.of(2026, 10, 12)); // 2 days left

        Topic urgentTopic = new Topic(1, 1, "Urgent", 5, 5, 5.0, 5.0, "LEARNING", 2.0, 2.0, 7, null, 1);
        // debtScore = (5 * 5 * 5) / 2 = 62.5

        Skill skill = new Skill(1, "Piano", "Music", 1, LocalDate.of(2026, 9, 20)); // 20 days ago, prof=1 -> 10.0

        PriorityQueue<PriorityItem> q = queueService.build(
                List.of(urgentTopic),
                List.of(skill),
                List.of(exam),
                today,
                1.0
        );

        assertEquals(2, q.size());
        PriorityItem first = q.poll();
        PriorityItem second = q.poll();

        assertEquals("Urgent", first.getName());
        assertEquals("Skill: Piano", second.getName());
        assertTrue(first.getScore() > second.getScore());
    }

    @Test
    void testTopicAndPyqOfFinishedExamExcludedFromQueue() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam finishedExam = new Exam();
        finishedExam.setExamId(1);
        finishedExam.setCredits(4);
        finishedExam.setEvalMarks(20.0);
        finishedExam.setExamDate(today.minusDays(1)); // Exam was yesterday!
        finishedExam.setPyqRemainingHours(2.0);

        Topic topic = new Topic(1, 1, "Past Topic", 5, 4, 6.0, 6.0, "LEARNING", 2.0, 2.0, 7, null, 1);

        Exam futureExam = new Exam();
        futureExam.setExamId(2);
        futureExam.setCredits(4);
        futureExam.setEvalMarks(20.0);
        futureExam.setExamDate(today.plusDays(5));
        futureExam.setPyqRemainingHours(2.0);

        Topic futureTopic = new Topic(2, 2, "Future Topic", 5, 4, 4.0, 4.0, "LEARNING", 2.0, 2.0, 7, null, 1);

        PriorityQueue<PriorityItem> q = queueService.build(
                List.of(topic, futureTopic),
                List.of(),
                List.of(finishedExam, futureExam),
                today,
                1.0
        );

        // Past topic and finished exam's PYQ must NOT be in queue
        for (PriorityItem item : q) {
            assertNotEquals(1, item.getId(), "Topic or PYQ of finished exam must not be in queue");
        }
        assertEquals(1, q.size());
        assertEquals("Future Topic", q.peek().getName());
    }
}
