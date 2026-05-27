package name.jurgenei.gradle.antlr.constants;

/**
 * Time unit conversion constants to avoid magic numbers.
 * Centralizes all time-related constants and conversion utilities.
 */
public final class TimeConstants {

    // ════════════════════════════════════════════════════════════════════════════════
    // Time Unit Conversions
    // ════════════════════════════════════════════════════════════════════════════════

    /** Nanoseconds per second */
    public static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Nanoseconds per millisecond */
    public static final long NANOS_PER_MILLI = 1_000_000L;

    /** Seconds per minute */
    public static final long SECONDS_PER_MINUTE = 60L;

    /** Seconds per hour */
    public static final long SECONDS_PER_HOUR = 3600L;

    /** Minutes per hour */
    public static final long MINUTES_PER_HOUR = 60L;

    private TimeConstants() {
        // Prevent instantiation
        throw new AssertionError("Cannot instantiate utility class");
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Conversion Helper Methods
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Converts nanoseconds to seconds, rounding to nearest whole second.
     *
     * @param nanos duration in nanoseconds
     * @return duration in seconds (rounded)
     */
    public static long nanosToSeconds(long nanos) {
        return Math.max(0L, Math.round(nanos / (double) NANOS_PER_SECOND));
    }

    /**
     * Converts nanoseconds to milliseconds.
     *
     * @param nanos duration in nanoseconds
     * @return duration in milliseconds (as double for precision)
     */
    public static double nanosToMillis(long nanos) {
        return nanos / (double) NANOS_PER_MILLI;
    }

    /**
     * Converts total seconds to HH:MM:SS format components.
     * Returns array: [hours, minutes, seconds]
     *
     * @param totalSeconds total duration in seconds
     * @return array of [hours, minutes, seconds]
     */
    public static long[] toHourMinuteSecond(long totalSeconds) {
        long hours = totalSeconds / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        return new long[]{hours, minutes, seconds};
    }
}

