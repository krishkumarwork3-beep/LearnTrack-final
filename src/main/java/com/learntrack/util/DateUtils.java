package com.learntrack.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Utility functions for date/time parsing, calculations, and floors.
 * Plan Section 5 & 16.
 */
public class DateUtils {

    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_TIME;

    public static LocalDate parseDate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return LocalDate.parse(text.trim(), DATE_FORMATTER);
    }

    public static String formatDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DATE_FORMATTER);
    }

    public static LocalTime parseTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return LocalTime.parse(text.trim(), TIME_FORMATTER);
    }

    public static String formatTime(LocalTime time) {
        if (time == null) {
            return null;
        }
        return time.format(TIME_FORMATTER);
    }

    /**
     * Calculates the number of days between two dates: to - from.
     */
    public static long daysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(from, to);
    }

    /**
     * Calculates daysLeft between today and target date with a minimum floor of 1.
     * Plan Section 5 & 16: max(daysLeft, 1).
     */
    public static long daysLeftWithFloor(LocalDate today, LocalDate target) {
        if (target == null) {
            return 1;
        }
        long days = daysBetween(today, target);
        return Math.max(days, 1);
    }
}
