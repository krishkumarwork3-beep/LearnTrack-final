package com.learntrack.util;

import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for formatting and parsing durations between hours and human-readable strings.
 * Examples:
 *   format(1.5)  -> "1h 30m"
 *   format(0.75) -> "45m"
 *   format(2.0)  -> "2h"
 *   format(0.0)  -> "0m"
 *   format(1.8)  -> "1h 48m"
 *   format(0.9999) -> "1h 0m"
 */
public final class DurationFormat {

    private DurationFormat() {}

    /**
     * Formats duration in hours to a concise string (e.g. "1h 30m", "45m", "2h", "0m").
     * Rounds to the nearest minute.
     */
    public static String format(double hours) {
        long totalMinutes = Math.round(hours * 60.0);
        if (totalMinutes <= 0) {
            return "0m";
        }
        long h = totalMinutes / 60;
        long m = totalMinutes % 60;

        if (h > 0 && m > 0) {
            return h + "h " + m + "m";
        } else if (h > 0) {
            if (hours > 0.99 && hours < 1.0) {
                return h + "h 0m";
            }
            return h + "h";
        } else {
            return m + "m";
        }
    }

    /**
     * Formats integer minutes into a display string for spinners or tables (e.g. "1h 00m", "1h 30m", "45m").
     */
    public static String formatMinutes(int totalMinutes) {
        if (totalMinutes <= 0) {
            return "0m";
        }
        int h = totalMinutes / 60;
        int m = totalMinutes % 60;
        if (h > 0 && m > 0) {
            return String.format("%dh %02dm", h, m);
        } else if (h > 0) {
            return String.format("%dh 00m", h);
        } else {
            return m + "m";
        }
    }

    /**
     * Parses a string duration into hours (double).
     * Accepts formats: "1h 30m", "1h30m", "1:30", "90m", "1.5", "1.5h", "2h", "45m".
     */
    public static double parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0.0;
        }
        String s = text.trim().toLowerCase();

        // 1. Colon format "H:MM" or "HH:MM" e.g. "1:30"
        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length == 2) {
                try {
                    double h = Double.parseDouble(parts[0].trim());
                    double m = Double.parseDouble(parts[1].trim());
                    return h + (m / 60.0);
                } catch (NumberFormatException ignored) {}
            }
        }

        boolean hasH = s.contains("h");
        boolean hasM = s.contains("m");

        // 2. Both 'h' and 'm' e.g. "1h 30m" or "1h30m"
        if (hasH && hasM) {
            int hIdx = s.indexOf('h');
            int mIdx = s.indexOf('m');
            if (hIdx < mIdx) {
                try {
                    String hStr = s.substring(0, hIdx).trim();
                    String mStr = s.substring(hIdx + 1, mIdx).replace("in", "").trim();
                    double h = hStr.isEmpty() ? 0 : Double.parseDouble(hStr);
                    double m = mStr.isEmpty() ? 0 : Double.parseDouble(mStr);
                    return h + (m / 60.0);
                } catch (NumberFormatException ignored) {}
            }
        }

        // 3. Only 'h' e.g. "2h" or "1.5h"
        if (hasH && !hasM) {
            try {
                String num = s.replace("h", "").trim();
                return Double.parseDouble(num);
            } catch (NumberFormatException ignored) {}
        }

        // 4. Only 'm' e.g. "90m", "45min", "90mins"
        if (!hasH && hasM) {
            try {
                String num = s.replaceAll("[^0-9.]", "").trim();
                double m = Double.parseDouble(num);
                return m / 60.0;
            } catch (NumberFormatException ignored) {}
        }

        // 5. Plain decimal or integer e.g. "1.5", "2"
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Smallest editable duration: 15 minutes. */
    public static final double MIN_EDITABLE_HOURS = 0.25;

    /** Largest editable duration: 40 hours. */
    public static final double MAX_EDITABLE_HOURS = 40.0;

    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2})\\s*:\\s*(\\d{1,2})$");
    private static final Pattern HOURS_MINUTES = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(?:h|hr|hrs|hour|hours)"
                    + "(?:\\s*(\\d+(?:\\.\\d+)?)\\s*(?:m|min|mins|minute|minutes))?$");
    private static final Pattern MINUTES_ONLY = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(?:m|min|mins|minute|minutes)$");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("^\\d+(?:\\.\\d+)?$");

    /**
     * Parses a user-typed duration into hours, rejecting anything outside the editable range.
     * Accepts the shapes the hours editor advertises: "1.5", "1h 30m", "90m", "1:30", "2h".
     * Returns empty for unparseable input, for zero/negative values and for values outside
     * {@link #MIN_EDITABLE_HOURS} … {@link #MAX_EDITABLE_HOURS}, so callers never save junk.
     */
    public static OptionalDouble parseHours(String text) {
        if (text == null) {
            return OptionalDouble.empty();
        }
        String s = text.trim().toLowerCase();
        if (s.isEmpty()) {
            return OptionalDouble.empty();
        }

        double hours;
        Matcher clock = CLOCK.matcher(s);
        Matcher hoursMinutes = HOURS_MINUTES.matcher(s);
        Matcher minutesOnly = MINUTES_ONLY.matcher(s);
        if (clock.matches()) {
            int h = Integer.parseInt(clock.group(1));
            int m = Integer.parseInt(clock.group(2));
            if (m > 59) {
                return OptionalDouble.empty();
            }
            hours = h + m / 60.0;
        } else if (hoursMinutes.matches()) {
            hours = Double.parseDouble(hoursMinutes.group(1));
            if (hoursMinutes.group(2) != null) {
                double m = Double.parseDouble(hoursMinutes.group(2));
                if (m > 59) {
                    return OptionalDouble.empty();
                }
                hours += m / 60.0;
            }
        } else if (minutesOnly.matches()) {
            hours = Double.parseDouble(minutesOnly.group(1)) / 60.0;
        } else if (PLAIN_NUMBER.matcher(s).matches()) {
            hours = Double.parseDouble(s);
        } else {
            return OptionalDouble.empty();
        }

        if (hours < MIN_EDITABLE_HOURS || hours > MAX_EDITABLE_HOURS) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(hours);
    }

    /**
     * Parses a string duration into total rounded minutes.
     */
    public static int parseMinutes(String text) {
        double hours = parse(text);
        return (int) Math.round(hours * 60.0);
    }
}
