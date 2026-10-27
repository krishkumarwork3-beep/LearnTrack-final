package com.learntrack.ui.views;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class DashboardLogProgressGuardTest {

    @Test
    public void testCannotLogProgressOnFutureSession() {
        LocalDate today = LocalDate.of(2026, 10, 22);
        LocalDate tomorrow = LocalDate.of(2026, 10, 23);

        assertFalse(DashboardView.canLogProgress(tomorrow, today),
                "Should not allow logging progress on a session dated after today");
    }

    @Test
    public void testCanLogProgressOnTodaySession() {
        LocalDate today = LocalDate.of(2026, 10, 22);

        assertTrue(DashboardView.canLogProgress(today, today),
                "Should allow logging progress on today's session");
    }

    @Test
    public void testCanLogProgressOnPastSession() {
        LocalDate today = LocalDate.of(2026, 10, 22);
        LocalDate yesterday = LocalDate.of(2026, 10, 21);

        assertTrue(DashboardView.canLogProgress(yesterday, today),
                "Should allow logging progress on a past session");
    }

    @Test
    public void testNullDateGuards() {
        LocalDate today = LocalDate.of(2026, 10, 22);
        assertFalse(DashboardView.canLogProgress(null, today));
        assertFalse(DashboardView.canLogProgress(today, null));
        assertFalse(DashboardView.canLogProgress(null, null));
    }
}
