package com.learntrack.service;

import com.learntrack.model.FreeSlot;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SlotMathTest {

    @Test
    void testSubtractIntervalsNullOrEmpty() {
        List<FreeSlot> slots = List.of(new FreeSlot(LocalTime.of(10, 0), LocalTime.of(12, 0)));
        assertTrue(SlotMath.subtractIntervals(null, Collections.emptyList()).isEmpty());
        assertTrue(SlotMath.subtractIntervals(Collections.emptyList(), null).isEmpty());

        List<FreeSlot> resultNoOccupied = SlotMath.subtractIntervals(slots, null);
        assertEquals(1, resultNoOccupied.size());
        assertEquals(LocalTime.of(10, 0), resultNoOccupied.get(0).getStart());
        assertEquals(LocalTime.of(12, 0), resultNoOccupied.get(0).getEnd());
    }

    @Test
    void testSubtractIntervalsCompletelyCovered() {
        List<FreeSlot> slots = List.of(new FreeSlot(LocalTime.of(10, 0), LocalTime.of(12, 0)));
        List<SlotMath.TimeInterval> occupied = List.of(new SlotMath.TimeInterval(LocalTime.of(9, 30), LocalTime.of(12, 30)));

        List<FreeSlot> result = SlotMath.subtractIntervals(slots, occupied);
        assertTrue(result.isEmpty(), "Entirely covered slot should result in no free slots");
    }

    @Test
    void testSubtractIntervalsDisjointBeforeAndAfter() {
        List<FreeSlot> slots = List.of(new FreeSlot(LocalTime.of(10, 0), LocalTime.of(12, 0)));
        List<SlotMath.TimeInterval> occupied = List.of(
                new SlotMath.TimeInterval(LocalTime.of(8, 0), LocalTime.of(9, 30)),
                new SlotMath.TimeInterval(LocalTime.of(13, 0), LocalTime.of(15, 0))
        );

        List<FreeSlot> result = SlotMath.subtractIntervals(slots, occupied);
        assertEquals(1, result.size());
        assertEquals(LocalTime.of(10, 0), result.get(0).getStart());
        assertEquals(LocalTime.of(12, 0), result.get(0).getEnd());
    }

    @Test
    void testSubtractIntervalsMiddleSplit() {
        // [10:00 - 14:00] free slot, occupied [11:00 - 12:00]
        List<FreeSlot> slots = List.of(new FreeSlot(LocalTime.of(10, 0), LocalTime.of(14, 0)));
        List<SlotMath.TimeInterval> occupied = List.of(new SlotMath.TimeInterval(LocalTime.of(11, 0), LocalTime.of(12, 0)));

        List<FreeSlot> result = SlotMath.subtractIntervals(slots, occupied);
        assertEquals(2, result.size());
        assertEquals(LocalTime.of(10, 0), result.get(0).getStart());
        assertEquals(LocalTime.of(11, 0), result.get(0).getEnd());
        assertEquals(LocalTime.of(12, 0), result.get(1).getStart());
        assertEquals(LocalTime.of(14, 0), result.get(1).getEnd());
    }

    @Test
    void testSubtractIntervalsOverlapStartAndEnd() {
        // [10:00 - 14:00] free slot
        // Occ1 overlaps start: [9:30 - 11:00] -> frees [11:00 - 14:00]
        // Occ2 overlaps end: [13:00 - 15:00] -> leaves [11:00 - 13:00]
        List<FreeSlot> slots = List.of(new FreeSlot(LocalTime.of(10, 0), LocalTime.of(14, 0)));
        List<SlotMath.TimeInterval> occupied = List.of(
                new SlotMath.TimeInterval(LocalTime.of(9, 30), LocalTime.of(11, 0)),
                new SlotMath.TimeInterval(LocalTime.of(13, 0), LocalTime.of(15, 0))
        );

        List<FreeSlot> result = SlotMath.subtractIntervals(slots, occupied);
        assertEquals(1, result.size());
        assertEquals(LocalTime.of(11, 0), result.get(0).getStart());
        assertEquals(LocalTime.of(13, 0), result.get(0).getEnd());
    }

    @Test
    void testSubtractIntervalsMultipleOccupiedInOneSlot() {
        // [08:00 - 18:00] free slot
        // Occ: [09:00 - 10:00], [12:00 - 13:00], [15:00 - 16:00]
        List<FreeSlot> slots = List.of(new FreeSlot(LocalTime.of(8, 0), LocalTime.of(18, 0)));
        List<SlotMath.TimeInterval> occupied = List.of(
                new SlotMath.TimeInterval(LocalTime.of(9, 0), LocalTime.of(10, 0)),
                new SlotMath.TimeInterval(LocalTime.of(12, 0), LocalTime.of(13, 0)),
                new SlotMath.TimeInterval(LocalTime.of(15, 0), LocalTime.of(16, 0))
        );

        List<FreeSlot> result = SlotMath.subtractIntervals(slots, occupied);
        assertEquals(4, result.size());
        assertEquals(LocalTime.of(8, 0), result.get(0).getStart());
        assertEquals(LocalTime.of(9, 0), result.get(0).getEnd());
        assertEquals(LocalTime.of(10, 0), result.get(1).getStart());
        assertEquals(LocalTime.of(12, 0), result.get(1).getEnd());
        assertEquals(LocalTime.of(13, 0), result.get(2).getStart());
        assertEquals(LocalTime.of(15, 0), result.get(2).getEnd());
        assertEquals(LocalTime.of(16, 0), result.get(3).getStart());
        assertEquals(LocalTime.of(18, 0), result.get(3).getEnd());
    }

    @Test
    void testSubtractIntervalsCrossMidnightOccupied() {
        // Day-long slot [00:00 - 23:59:59], occupied cross-midnight sleep 23:00 to 07:00
        List<FreeSlot> slots = List.of(new FreeSlot(LocalTime.MIN, LocalTime.MAX));
        List<SlotMath.TimeInterval> occupied = List.of(new SlotMath.TimeInterval(LocalTime.of(23, 0), LocalTime.of(7, 0)));

        List<FreeSlot> result = SlotMath.subtractIntervals(slots, occupied);
        assertEquals(1, result.size());
        assertEquals(LocalTime.of(7, 0), result.get(0).getStart());
        assertEquals(LocalTime.of(23, 0), result.get(0).getEnd());
    }
}
