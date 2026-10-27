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

public class ScoringServiceTest {

    private ScoringService scoringService;

    @BeforeEach
    void setUp() {
        scoringService = new ScoringService();
    }

    @Test
    void testExamWeightFormulaAndZeroMarksDefault() {
        // Plan Section 5a & 8: credits * max(eval_marks, 1)
        Exam e1 = new Exam();
        e1.setCredits(5);
        e1.setEvalMarks(3.0);
        assertEquals(15.0, scoringService.examWeight(e1), 1e-6);

        Exam e2 = new Exam();
        e2.setCredits(3);
        e2.setEvalMarks(15.0);
        assertEquals(45.0, scoringService.examWeight(e2), 1e-6);

        // Section 8 example: e2 has 3x the weight of e1
        assertEquals(3.0, scoringService.examWeight(e2) / scoringService.examWeight(e1), 1e-6);

        // Edge case: eval_marks left at 0 -> floor of 1 avoids division by zero
        Exam zeroMarks = new Exam();
        zeroMarks.setCredits(4);
        zeroMarks.setEvalMarks(0.0);
        assertEquals(4.0, scoringService.examWeight(zeroMarks), 1e-6);
    }

    @Test
    void testEffectiveDeadlineAndFallbackChain() {
        LocalDate today = LocalDate.of(2026, 10, 10);

        // 1. Exam date set
        Exam dated = new Exam();
        dated.setExamDate(LocalDate.of(2026, 11, 15));
        dated.setWindowStartDate(LocalDate.of(2026, 11, 1));
        assertEquals(LocalDate.of(2026, 11, 15), scoringService.effectiveDeadline(dated, today));

        // 2. Window start in future
        Exam undatedFuture = new Exam();
        undatedFuture.setWindowStartDate(LocalDate.of(2026, 10, 25));
        undatedFuture.setWindowEndDate(LocalDate.of(2026, 11, 5));
        assertEquals(LocalDate.of(2026, 10, 25), scoringService.effectiveDeadline(undatedFuture, today));

        // 3. Window start passed, but window end in future
        Exam undatedMid = new Exam();
        undatedMid.setWindowStartDate(LocalDate.of(2026, 10, 5));
        undatedMid.setWindowEndDate(LocalDate.of(2026, 10, 20));
        assertEquals(LocalDate.of(2026, 10, 20), scoringService.effectiveDeadline(undatedMid, today));

        // 4. Whole window passed -> fallback to today
        Exam undatedLapsed = new Exam();
        undatedLapsed.setWindowStartDate(LocalDate.of(2026, 9, 1));
        undatedLapsed.setWindowEndDate(LocalDate.of(2026, 9, 15));
        assertEquals(today, scoringService.effectiveDeadline(undatedLapsed, today));

        // Edge case: daysLeft = 0 floors to 1
        assertEquals(1, scoringService.daysLeft(undatedLapsed, today));
    }

    @Test
    void testDebtScoreAndDaysLeftFloor() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setExamDate(LocalDate.of(2026, 10, 10)); // exam today -> daysLeft = 0 -> floors to 1

        Topic topic = new Topic();
        topic.setImportance(5);
        topic.setDifficulty(4);
        topic.setRemainingHours(6.0);

        double score = scoringService.debtScore(topic, exam, today);
        // (5 * 6) / 1 = 30.0 (difficulty is dropped from debt score)
        assertEquals(30.0, score, 1e-6);
    }

    @Test
    void testRevisionScoreAndIntervalGate() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setExamDate(LocalDate.of(2026, 10, 20));

        Topic topic = new Topic();
        topic.setImportance(4);
        topic.setPhase("REVISION");
        topic.setRevisionHoursNeeded(2.0);
        topic.setRevisionRemainingHours(2.0);
        topic.setRevisionIntervalDays(7);

        // Revised 3 days ago: not due yet (< 7)
        topic.setLastRevised(LocalDate.of(2026, 10, 7));
        assertFalse(scoringService.isRevisionDue(topic, exam, today));

        // Revised 7 days ago: due!
        topic.setLastRevised(LocalDate.of(2026, 10, 3));
        assertTrue(scoringService.isRevisionDue(topic, exam, today));

        // score = (4 * 7 * 2) / 7 = 8.0
        assertEquals(8.0, scoringService.revisionScore(topic, today), 1e-6);
    }

    @Test
    void testPyqEligibilityAndScore() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setCredits(4);
        exam.setEvalMarks(25.0);
        exam.setExamDate(LocalDate.of(2026, 10, 15));
        exam.setPyqRemainingHours(2.0);

        Topic t1 = new Topic();
        t1.setImportantFlag(1);
        t1.setRemainingHours(3.0); // syllabus not done

        Topic t2 = new Topic();
        t2.setImportantFlag(1);
        t2.setRemainingHours(0.0);

        assertFalse(scoringService.pyqEligible(exam, List.of(t1, t2), today));

        // When t1 is finished (remainingHours = 0)
        t1.setRemainingHours(0.0);
        assertTrue(scoringService.pyqEligible(exam, List.of(t1, t2), today));

        // pyqScore = (examWeight * pyq_remaining_hours) / daysLeft
        // examWeight = 4 * 25 = 100, daysLeft = 5 -> (100 * 2) / 5 = 40.0
        assertEquals(40.0, scoringService.pyqScore(exam, today), 1e-6);
    }

    @Test
    void testFractionalCreditsAndFallback() {
        // 1.5 credits and 20 marks gives 30.0
        Exam e1 = new Exam();
        e1.setCredits(1.5);
        e1.setEvalMarks(20.0);
        assertEquals(30.0, scoringService.examWeight(e1), 1e-6);

        // 0.5 credits and 20 marks gives 10.0
        Exam e2 = new Exam();
        e2.setCredits(0.5);
        e2.setEvalMarks(20.0);
        assertEquals(10.0, scoringService.examWeight(e2), 1e-6);

        // Null exam gives 1.0
        assertEquals(1.0, scoringService.examWeight(null), 1e-6);

        // Credits <= 0 falls back to 1.0 * marks
        Exam eZeroCredits = new Exam();
        eZeroCredits.setCredits(0.0);
        eZeroCredits.setEvalMarks(25.0);
        assertEquals(25.0, scoringService.examWeight(eZeroCredits), 1e-6);

        Exam eNegCredits = new Exam();
        eNegCredits.setCredits(-1.0);
        eNegCredits.setEvalMarks(15.0);
        assertEquals(15.0, scoringService.examWeight(eNegCredits), 1e-6);
    }

    // ---- Phase 1 regression tests ----

    @Test
    void testPyqEligibleFalseWhenNoTopics() {
        // Exams with no syllabus (21 of 22 in the real DB) must not be PYQ-eligible.
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setExamDate(LocalDate.of(2026, 11, 15));
        exam.setPyqRemainingHours(2.0);

        assertFalse(scoringService.pyqEligible(exam, null, today),
                "null topic list must not be eligible");
        assertFalse(scoringService.pyqEligible(exam, List.of(), today),
                "empty topic list must not be eligible");
    }

    @Test
    void testPyqEligibleFalseWhileImportantTopicHasHours() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setExamDate(LocalDate.of(2026, 11, 15));
        exam.setPyqRemainingHours(2.0);

        Topic unfinished = new Topic();
        unfinished.setImportantFlag(1);
        unfinished.setRemainingHours(2.5);
        unfinished.setPhase("LEARNING");

        Topic done = new Topic();
        done.setImportantFlag(1);
        done.setRemainingHours(0.0);
        done.setPhase("REVISION");

        assertFalse(scoringService.pyqEligible(exam, List.of(unfinished, done), today));
    }

    @Test
    void testPyqEligibleTrueOnlyWhenAllImportantTopicsLearned() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam exam = new Exam();
        exam.setExamDate(LocalDate.of(2026, 11, 15));
        exam.setPyqRemainingHours(2.0);

        Topic t1 = new Topic();
        t1.setImportantFlag(1);
        t1.setRemainingHours(0.0);
        t1.setPhase("REVISION");
        t1.setRevisionRemainingHours(0.0); // revision also done

        Topic t2 = new Topic();
        t2.setImportantFlag(1);
        t2.setRemainingHours(0.0);
        t2.setPhase("REVISION");
        t2.setRevisionRemainingHours(0.0);

        // Non-important topic with hours should not block PYQ
        Topic nonImportant = new Topic();
        nonImportant.setImportantFlag(0);
        nonImportant.setRemainingHours(5.0);

        assertTrue(scoringService.pyqEligible(exam, List.of(t1, t2, nonImportant), today));
    }

    @Test
    void testUndatedExamDeadlineIsToday60() {
        // Exams with no date, windowStart, or windowEnd must get today+60 (not today).
        LocalDate today = LocalDate.of(2026, 10, 10);
        Exam undated = new Exam();
        // All date fields are null (default)

        LocalDate deadline = undated.getEffectiveDeadline(today);
        assertEquals(today.plusDays(60), deadline,
                "Fully undated exam must get today+60 as effective deadline");

        // Lapsed window should still return today
        Exam lapsedWindow = new Exam();
        lapsedWindow.setWindowStartDate(LocalDate.of(2026, 9, 1));
        lapsedWindow.setWindowEndDate(LocalDate.of(2026, 9, 15));
        assertEquals(today, lapsedWindow.getEffectiveDeadline(today),
                "Lapsed window exam must still return today");
    }

    @Test
    void testTierComparatorPutsTopicBeforeHighScorePyq() {
        // A low-score TOPIC_LEARN must be polled before a high-score PYQ_PRACTICE.
        PriorityItem topicItem = new PriorityItem(
                PriorityItem.ItemType.TOPIC_LEARN, 1, "Topic A", 0.5, 2.0, 10);
        PriorityItem pyqItem = new PriorityItem(
                PriorityItem.ItemType.PYQ_PRACTICE, 10, "PYQ: Subject X", 9999.0, 2.0, 10);

        PriorityQueue<PriorityItem> queue = new PriorityQueue<>(PriorityItem.TIER_COMPARATOR);
        queue.offer(pyqItem);
        queue.offer(topicItem);

        PriorityItem first = queue.poll();
        assertEquals(PriorityItem.ItemType.TOPIC_LEARN, first.getItemType(),
                "TOPIC_LEARN must come before PYQ_PRACTICE regardless of score");
    }
}
