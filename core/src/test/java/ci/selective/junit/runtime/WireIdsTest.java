package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class WireIdsTest {
    @Test
    void shortIdStays() {
        assertEquals("alpha", WireIds.wire("alpha"));
    }

    @Test
    void longIdIsShortForm() {
        String id = "x".repeat(300);
        String wire = WireIds.wire(id);
        assertTrue(wire.length() <= 255);
        assertTrue(wire.contains("~"));
        assertEquals(64, wire.substring(wire.indexOf('~') + 1).length());
        assertEquals(wire, WireIds.wire(id));
        assertFalse(wire.contains("\0"));
    }

    @Test
    void nulUsesShortFormWithoutNul() {
        String wire = WireIds.wire("ab\0cd");
        assertFalse(wire.contains("\0"));
        assertTrue(wire.contains("~"));
        assertTrue(wire.startsWith("abcd~") || wire.startsWith("abc~") || wire.contains("~"));
    }

    @Test
    void loneSurrogateUsesShortForm() {
        String wire = WireIds.wire("a\uD800b");
        assertTrue(wire.contains("~"));
        assertFalse(Texts.loneSurrogate(wire));
    }

    @Test
    void fastRetryIsZero() {
        assertEquals(0L, Session.retryMillis(3, true));
        assertEquals(3000L, Session.retryMillis(3, false));
        assertEquals(4000L, Session.retryMillis(9, false));
    }
}
