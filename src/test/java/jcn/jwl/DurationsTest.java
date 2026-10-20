package jcn.jwl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DurationsTest {
    @Test void supportsExistingUnits() {
        assertEquals(5000, Durations.parse("5s"));
        assertEquals(300000, Durations.parse("5min"));
        assertEquals(18000000, Durations.parse("5h"));
        assertEquals(432000000, Durations.parse("5d"));
        assertEquals(12960000000L, Durations.parse("5m"));
    }
    @Test void rejectsZeroNegativeMalformedAndOverflow() {
        for (String value : new String[]{"", "0s", "-1h", "abc", "s", "9999999999999999999999999d", "9223372036854775807m"}) {
            assertEquals(-1, Durations.parse(value), value);
        }
        assertEquals(-1, Durations.parse(null));
    }
}
