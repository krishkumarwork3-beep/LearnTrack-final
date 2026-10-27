package com.learntrack.util;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The subjects tab lets users type hours directly into a subtopic row, so parsing must be
 * strict: anything that is not a duration in the editable range is rejected instead of
 * being silently saved as zero.
 */
class DurationFormatParseHoursTest {

    @Test
    void parsesTheAcceptedShapes() {
        assertEquals(1.5, DurationFormat.parseHours("1.5").orElseThrow(), 1e-9);
        assertEquals(1.5, DurationFormat.parseHours("1h 30m").orElseThrow(), 1e-9);
        assertEquals(1.5, DurationFormat.parseHours("90m").orElseThrow(), 1e-9);
        assertEquals(1.5, DurationFormat.parseHours("1:30").orElseThrow(), 1e-9);
        assertEquals(0.75, DurationFormat.parseHours("45m").orElseThrow(), 1e-9);
        assertEquals(2.0, DurationFormat.parseHours("2h").orElseThrow(), 1e-9);
        assertEquals(2.0, DurationFormat.parseHours("2").orElseThrow(), 1e-9);
        assertEquals(0.25, DurationFormat.parseHours("15m").orElseThrow(), 1e-9);
    }

    @Test
    void rejectsJunkZeroAndOutOfRangeValues() {
        assertTrue(DurationFormat.parseHours("0").isEmpty());
        assertTrue(DurationFormat.parseHours("abc").isEmpty());
        assertTrue(DurationFormat.parseHours("").isEmpty());
        assertTrue(DurationFormat.parseHours(null).isEmpty());
        assertTrue(DurationFormat.parseHours("-2").isEmpty());
        assertTrue(DurationFormat.parseHours("1:75").isEmpty());
        assertTrue(DurationFormat.parseHours("5m").isEmpty(), "below the 15 minute minimum");
        assertTrue(DurationFormat.parseHours("41h").isEmpty(), "above the 40 hour maximum");
    }

    @Test
    void roundsTripWithTheDisplayFormat() {
        for (double hours : new double[]{0.25, 0.75, 1.5, 2.0, 6.7}) {
            String display = DurationFormat.formatMinutes((int) Math.round(hours * 60.0));
            OptionalDouble parsed = DurationFormat.parseHours(display);
            assertTrue(parsed.isPresent(), display);
            assertEquals(hours, parsed.getAsDouble(), 1e-9, display);
        }
    }
}
