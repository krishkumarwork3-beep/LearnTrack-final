package com.learntrack.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurationFormatTest {

    @Test
    void testFormatRequiredCases() {
        assertEquals("1h 30m", DurationFormat.format(1.5));
        assertEquals("45m", DurationFormat.format(0.75));
        assertEquals("2h", DurationFormat.format(2.0));
        assertEquals("0m", DurationFormat.format(0.0));
        assertEquals("1h 48m", DurationFormat.format(1.8));
        assertEquals("1h 0m", DurationFormat.format(0.9999));
        assertEquals("6h 42m", DurationFormat.format(6.7));
        assertEquals("50m", DurationFormat.format(0.83));
        assertEquals("4h 48m", DurationFormat.format(4.8));
    }

    @Test
    void testParseRequiredCases() {
        assertEquals(1.5, DurationFormat.parse("1h 30m"), 1e-6);
        assertEquals(1.5, DurationFormat.parse("1:30"), 1e-6);
        assertEquals(1.5, DurationFormat.parse("90m"), 1e-6);
        assertEquals(1.5, DurationFormat.parse("1.5"), 1e-6);
        assertEquals(1.5, DurationFormat.parse("1.5h"), 1e-6);
        assertEquals(0.75, DurationFormat.parse("45m"), 1e-6);
        assertEquals(2.0, DurationFormat.parse("2h"), 1e-6);
        assertEquals(0.0, DurationFormat.parse("0m"), 1e-6);
        assertEquals(1.0, DurationFormat.parse("1h 0m"), 1e-6);
    }

    @Test
    void testSpinnerRoundTrip() {
        double originalHours = 0.5;
        int minutes = (int) Math.round(originalHours * 60.0);
        assertEquals(30, minutes);

        String display = DurationFormat.formatMinutes(minutes);
        assertEquals("30m", display);

        int parsedMinutes = DurationFormat.parseMinutes(display);
        assertEquals(30, parsedMinutes);

        double roundTripHours = parsedMinutes / 60.0;
        assertEquals(0.5, roundTripHours, 1e-9);
    }
}
