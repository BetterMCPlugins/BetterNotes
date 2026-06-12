package dev.nikhey.betternotes.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class TimeText {

    private static final DateTimeFormatter ABSOLUTE =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private TimeText() {
    }

    public static String absolute(long epochMillis) {
        return ABSOLUTE.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String ago(long epochMillis) {
        return duration(System.currentTimeMillis() - epochMillis) + " ago";
    }

    public static String in(long epochMillis) {
        return "in " + duration(epochMillis - System.currentTimeMillis());
    }

    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return (hours / 24) + "d " + (hours % 24) + "h";
    }

    /**
     * Parses a compact duration like "30m", "12h", "7d" into milliseconds.
     * Returns -1 when the input is not a duration (so callers can treat the
     * token as something else, e.g. the start of a free-text reason).
     */
    public static long parseDuration(String token) {
        if (token == null || token.length() < 2) {
            return -1;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        char unit = lower.charAt(lower.length() - 1);
        long perUnit = switch (unit) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 7 * 86_400_000L;
            default -> -1;
        };
        if (perUnit < 0) {
            return -1;
        }
        try {
            long amount = Long.parseLong(lower.substring(0, lower.length() - 1));
            return amount > 0 ? amount * perUnit : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
