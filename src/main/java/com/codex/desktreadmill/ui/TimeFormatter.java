package com.codex.desktreadmill.ui;

public final class TimeFormatter {
    private TimeFormatter() {
    }

    public static DisplayTime displayTime(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        long days = safeSeconds / 86_400L;
        long remainder = safeSeconds % 86_400L;
        long hours = remainder / 3_600L;
        long minutes = (remainder % 3_600L) / 60L;
        long secs = remainder % 60L;
        String prefix = days > 0 ? days + "d" : "";
        return new DisplayTime(prefix, String.format("%02d:%02d:%02d", hours, minutes, secs));
    }

    public static final class DisplayTime {
        private final String dayPrefix;
        private final String timeText;

        public DisplayTime(String dayPrefix, String timeText) {
            this.dayPrefix = dayPrefix;
            this.timeText = timeText;
        }

        public String getDayPrefix() {
            return dayPrefix;
        }

        public String getTimeText() {
            return timeText;
        }
    }
}
