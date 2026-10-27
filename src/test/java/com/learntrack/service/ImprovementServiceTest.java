package com.learntrack.service;

import com.learntrack.model.WeeklyTimetableEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImprovementService conflict detection and Best Batch ranking.
 */
public class ImprovementServiceTest {

    // ---------------------------------------------------------------
    // Part 3a: Conflict detection lists all conflicts
    // ---------------------------------------------------------------

    @Test
    void testConflictDetection_AllConflictsListed() {
        ImprovementService.ImprovementSlot slot = new ImprovementService.ImprovementSlot(
                "MONDAY", LocalTime.of(9, 40), LocalTime.of(11, 20),
                "UCS503", 'P', "LAB", "practical", "F108");

        List<WeeklyTimetableEntry> currentEntries = List.of(
                new WeeklyTimetableEntry(1, 1, "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0), "CLASS", "UML501 lecture"),
                new WeeklyTimetableEntry(2, 1, "MONDAY", LocalTime.of(10, 30), LocalTime.of(11, 20), "TUTORIAL", "UCS510 tutorial"),
                new WeeklyTimetableEntry(3, 1, "MONDAY", LocalTime.of(13, 0), LocalTime.of(14, 0), "CLASS", "UMA022 lecture"),
                new WeeklyTimetableEntry(4, 1, "TUESDAY", LocalTime.of(9, 40), LocalTime.of(11, 20), "LAB", "Different day")
        );

        // Build slot list with conflicts
        List<ImprovementService.ImprovementSlot> slots = new ArrayList<>();
        for (WeeklyTimetableEntry cur : currentEntries) {
            if (slot.getDay().equalsIgnoreCase(cur.getDayOfWeek())) {
                if (slot.getStartTime().isBefore(cur.getEndTime()) && cur.getStartTime().isBefore(slot.getEndTime())) {
                    String type = cur.getActivityType() != null ? cur.getActivityType().toUpperCase() : "OTHER";
                    if ("CLASS".equals(type) || "TUTORIAL".equals(type) || "LAB".equals(type)) {
                        slot.getHardConflicts().add(cur);
                    }
                }
            }
        }
        slots.add(slot);

        // Build ComponentOption
        ImprovementService.ComponentOption option = new ImprovementService.ComponentOption(
                "3RD YEAR A", "3C12", 'P', "Practical", slots,
                "status", "conflicts", "slots", slot.getHardConflicts().stream()
                .map(ImprovementService.ConflictInfo::fromEntry).toList());

        // Verify all conflicts are captured
        assertEquals(2, option.getConflictCount());
        List<ImprovementService.ConflictInfo> conflicts = option.getConflicts();
        assertTrue(conflicts.stream().anyMatch(c -> c.getLabel().contains("UML501")));
        assertTrue(conflicts.stream().anyMatch(c -> c.getLabel().contains("UCS510")));
    }

    @Test
    void testConflictDetection_MealOtherNotConflicts() {
        ImprovementService.ImprovementSlot slot = new ImprovementService.ImprovementSlot(
                "MONDAY", LocalTime.of(13, 0), LocalTime.of(14, 0),
                "UCS503", 'L', "CLASS", "lecture", "LT101");

        List<WeeklyTimetableEntry> currentEntries = List.of(
                new WeeklyTimetableEntry(1, 1, "MONDAY", LocalTime.of(13, 0), LocalTime.of(14, 0), "MEAL", "Lunch"),
                new WeeklyTimetableEntry(2, 1, "MONDAY", LocalTime.of(13, 30), LocalTime.of(14, 30), "OTHER", "Club meeting")
        );

        // Apply conflict detection
        for (WeeklyTimetableEntry cur : currentEntries) {
            if (slot.getDay().equalsIgnoreCase(cur.getDayOfWeek())) {
                if (slot.getStartTime().isBefore(cur.getEndTime()) && cur.getStartTime().isBefore(slot.getEndTime())) {
                    String type = cur.getActivityType() != null ? cur.getActivityType().toUpperCase() : "OTHER";
                    if ("CLASS".equals(type) || "TUTORIAL".equals(type) || "LAB".equals(type)) {
                        slot.getHardConflicts().add(cur);
                    } else {
                        slot.getSoftConflicts().add(cur);
                    }
                }
            }
        }

        // Hard conflicts should be empty, soft conflicts should have 2
        assertEquals(0, slot.getHardConflicts().size());
        assertEquals(2, slot.getSoftConflicts().size());
    }

    @Test
    void testBackToBackDoesNotConflict() {
        ImprovementService.ImprovementSlot slot = new ImprovementService.ImprovementSlot(
                "MONDAY", LocalTime.of(10, 30), LocalTime.of(11, 20),
                "UCS503", 'L', "CLASS", "lecture", "LT101");

        WeeklyTimetableEntry backToBack = new WeeklyTimetableEntry(
                1, 1, "MONDAY", LocalTime.of(9, 40), LocalTime.of(10, 30), "CLASS", "Prior lecture");

        // Strict overlap check
        boolean overlaps = slot.getStartTime().isBefore(backToBack.getEndTime())
                && backToBack.getStartTime().isBefore(slot.getEndTime());

        assertFalse(overlaps, "Back-to-back slots should not overlap");
    }

    @Test
    void test100MinPracticalConflictsWithBothPeriods() {
        // 100-minute practical should conflict with a lecture in either of its two 50-min periods
        ImprovementService.ImprovementSlot practical = new ImprovementService.ImprovementSlot(
                "MONDAY", LocalTime.of(9, 40), LocalTime.of(11, 20),
                "UCS503", 'P', "LAB", "practical", "F108");

        WeeklyTimetableEntry lectureFirstPeriod = new WeeklyTimetableEntry(
                1, 1, "MONDAY", LocalTime.of(9, 40), LocalTime.of(10, 30), "CLASS", "First period lecture");

        WeeklyTimetableEntry lectureSecondPeriod = new WeeklyTimetableEntry(
                2, 1, "MONDAY", LocalTime.of(10, 30), LocalTime.of(11, 20), "CLASS", "Second period lecture");

        // Check overlap with first period
        boolean overlaps1 = practical.getStartTime().isBefore(lectureFirstPeriod.getEndTime())
                && lectureFirstPeriod.getStartTime().isBefore(practical.getEndTime());
        assertTrue(overlaps1);

        // Check overlap with second period
        boolean overlaps2 = practical.getStartTime().isBefore(lectureSecondPeriod.getEndTime())
                && lectureSecondPeriod.getStartTime().isBefore(practical.getEndTime());
        assertTrue(overlaps2);
    }

    // ---------------------------------------------------------------
    // Part 3b: Best Batch ordering
    // ---------------------------------------------------------------

    @Test
    void testBestBatchOrdering_ZeroConflictsFirst() {
        ImprovementService.BestBatchOption zero = createBestBatchOption("A", "1A11", 0, 0, 0, 0);
        ImprovementService.BestBatchOption oneConflict = createBestBatchOption("A", "1A12", 1, 0, 0, 0);

        assertTrue(ImprovementService.BEST_BATCH_COMPARATOR.compare(zero, oneConflict) < 0);
    }

    @Test
    void testBestBatchOrdering_PracticalConflictsLast() {
        ImprovementService.BestBatchOption lecConflict = createBestBatchOption("A", "1A11", 0, 2, 0, 0);
        ImprovementService.BestBatchOption tutConflict = createBestBatchOption("A", "1A12", 0, 0, 1, 0);
        ImprovementService.BestBatchOption pracConflict = createBestBatchOption("A", "1A13", 0, 0, 0, 1);

        // Lecture and tutorial conflicts come before practical conflicts
        assertTrue(ImprovementService.BEST_BATCH_COMPARATOR.compare(lecConflict, pracConflict) < 0);
        assertTrue(ImprovementService.BEST_BATCH_COMPARATOR.compare(tutConflict, pracConflict) < 0);
    }

    @Test
    void testBestBatchOrdering_LectureWeightedMoreThanTutorial() {
        ImprovementService.BestBatchOption oneLecture = createBestBatchOption("A", "1A11", 0, 1, 0, 0);
        ImprovementService.BestBatchOption oneTutorial = createBestBatchOption("A", "1A12", 0, 0, 1, 0);

        // One lecture conflict (weight 2) should rank worse than one tutorial conflict (weight 1)
        assertTrue(ImprovementService.BEST_BATCH_COMPARATOR.compare(oneTutorial, oneLecture) < 0);
    }

    @Test
    void testBestBatchOrdering_FewerTotalConflictsBetter() {
        ImprovementService.BestBatchOption twoConflicts = createBestBatchOption("A", "1A11", 0, 1, 1, 0);
        ImprovementService.BestBatchOption threeConflicts = createBestBatchOption("A", "1A12", 0, 2, 1, 0);

        assertTrue(ImprovementService.BEST_BATCH_COMPARATOR.compare(twoConflicts, threeConflicts) < 0);
    }

    @Test
    void testBestBatchOrdering_TieBreakers() {
        ImprovementService.BestBatchOption a = createBestBatchOption("A", "1A11", 1, 1, 0, 0);
        ImprovementService.BestBatchOption b = createBestBatchOption("B", "1A11", 1, 1, 0, 0);

        // Sheet name is tie breaker
        assertTrue(ImprovementService.BEST_BATCH_COMPARATOR.compare(a, b) < 0);

        ImprovementService.BestBatchOption c = createBestBatchOption("A", "1A11", 1, 1, 0, 0);
        ImprovementService.BestBatchOption d = createBestBatchOption("A", "1A12", 1, 1, 0, 0);

        // Batch name is final tie breaker
        assertTrue(ImprovementService.BEST_BATCH_COMPARATOR.compare(c, d) < 0);
    }

    @Test
    void testBestBatchComparatorIsStableSort() {
        // Create a shuffled list and sort it multiple times - should always get same order
        List<ImprovementService.BestBatchOption> options = List.of(
                createBestBatchOption("A", "1A13", 0, 0, 0, 2),
                createBestBatchOption("A", "1A11", 0, 0, 0, 0),
                createBestBatchOption("A", "1A12", 0, 1, 1, 0),
                createBestBatchOption("B", "1A11", 0, 2, 0, 0),
                createBestBatchOption("A", "1A14", 0, 0, 1, 1)
        );

        List<ImprovementService.BestBatchOption> sorted1 = new ArrayList<>(options);
        sorted1.sort(ImprovementService.BEST_BATCH_COMPARATOR);

        List<ImprovementService.BestBatchOption> sorted2 = new ArrayList<>(options);
        java.util.Collections.shuffle(sorted2);
        sorted2.sort(ImprovementService.BEST_BATCH_COMPARATOR);

        // Both should produce the same order
        for (int i = 0; i < sorted1.size(); i++) {
            assertEquals(sorted1.get(i).getBatchName(), sorted2.get(i).getBatchName());
        }

        // Verify expected order: zero conflicts, then lec/tut only, then prac conflicts
        assertEquals("1A11", sorted1.get(0).getBatchName()); // 0 conflicts
        assertEquals("1A12", sorted1.get(1).getBatchName()); // 1 lec + 1 tut
        assertEquals("1A11", sorted1.get(2).getBatchName()); // 2 lec (from sheet B)
        // Last two have practical conflicts
        assertTrue(sorted1.get(3).getPracticalConflicts() > 0);
        assertTrue(sorted1.get(4).getPracticalConflicts() > 0);
    }

    // ---------------------------------------------------------------
    // Part 3b: Add only what fits
    // ---------------------------------------------------------------

    @Test
    void testPlanAdd_SkipsConflictingSlots() {
        // Create a slot with hard conflicts
        ImprovementService.ImprovementSlot conflicting = new ImprovementService.ImprovementSlot(
                "MONDAY", LocalTime.of(9, 40), LocalTime.of(10, 30),
                "UCS503", 'L', "CLASS", "lecture", "LT101");
        conflicting.getHardConflicts().add(new WeeklyTimetableEntry(
                1, 1, "MONDAY", LocalTime.of(9, 40), LocalTime.of(10, 30), "CLASS", "Existing lecture"));

        ImprovementService.ImprovementSlot fitting = new ImprovementService.ImprovementSlot(
                "TUESDAY", LocalTime.of(9, 40), LocalTime.of(10, 30),
                "UCS503", 'L', "CLASS", "lecture", "LT101");

        ImprovementService.ComponentOption option = new ImprovementService.ComponentOption(
                "3RD YEAR A", "3C12", 'L', "Lecture",
                List.of(conflicting, fitting), "status", "conflicts", "slots", List.of());

        ImprovementService.AddPlan plan = ImprovementService.planAdd(List.of(option));

        // Only the fitting slot should be in the plan
        assertEquals(1, plan.getToAdd().size());
        assertEquals("TUESDAY", plan.getToAdd().get(0).getSlot().getDay());
        assertEquals(1, plan.getSkipped().size());
        assertEquals("MONDAY", plan.getSkipped().get(0).getSlot().getDay());
    }

    // ---------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------

    private ImprovementService.BestBatchOption createBestBatchOption(
            String sheet, String batch, int soft, int lec, int tut, int prac) {
        ImprovementService.ComponentOption lecOpt = lec > 0 ? createComponentWithConflicts('L', lec) : null;
        ImprovementService.ComponentOption tutOpt = tut > 0 ? createComponentWithConflicts('T', tut) : null;
        ImprovementService.ComponentOption pracOpt = prac > 0 ? createComponentWithConflicts('P', prac) : null;

        return new ImprovementService.BestBatchOption(
                sheet, batch, lecOpt, tutOpt, pracOpt, true, true, true);
    }

    private ImprovementService.ComponentOption createComponentWithConflicts(char type, int conflictCount) {
        List<ImprovementService.ConflictInfo> conflicts = new ArrayList<>();
        for (int i = 0; i < conflictCount; i++) {
            conflicts.add(new ImprovementService.ConflictInfo(
                    "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0), "Conflict " + i, "CLASS"));
        }
        return new ImprovementService.ComponentOption(
                "TEST", "TEST", type, "Test", List.of(),
                "conflicts", "conflicts", "slots", conflicts);
    }
}
