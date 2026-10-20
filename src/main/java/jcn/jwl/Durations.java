package jcn.jwl;

import java.util.Locale;
import java.util.regex.Pattern;

final class Durations {
    private static final Pattern FORMAT = Pattern.compile("([1-9][0-9]*)(min|s|m|h|d)");
    private Durations() { }
    static long parse(String value) {
        if (value == null) return -1;
        var matcher = FORMAT.matcher(value.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return -1;
        try {
            long multiplier = switch (matcher.group(2)) {
                case "s" -> 1000L;
                case "min" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "m" -> 2_592_000_000L;
                default -> throw new IllegalArgumentException();
            };
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
        } catch (IllegalArgumentException | ArithmeticException exception) { return -1; }
    }
}
