package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import ci.selective.junit.runtime.StdioLink.ConnectionLost;

public class StdioLinkTest {
    @Test
    void slowReaderIsWaitedForNotKilled() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 7; cat > /dev/null").start();
        StdioLink link = StdioLink.open(process);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                for (int i = 0; i < 64; i++) {
                    link.send("x".repeat(8192));
                }
            });
            assertTrue(process.isAlive());
        } finally {
            link.close();
            process.destroyForcibly();
        }
    }

    @Test
    void unbindRestoresThePreviousHandler() throws Exception {
        Signal term = new Signal("TERM");
        Signal intr = new Signal("INT");
        SignalHandler hold = signal -> {};
        SignalHandler termBefore = Signal.handle(term, hold);
        Signal.handle(term, termBefore);
        SignalHandler intBefore = Signal.handle(intr, hold);
        Signal.handle(intr, intBefore);
        Signals.PENDING.set(null);
        Signals.install();
        try {
            SignalHandler installed = Signal.handle(term, hold);
            assertNotSame(target(termBefore), target(installed));
            Signal.handle(term, installed);
            Signals.unbind();
            SignalHandler restored = Signal.handle(term, hold);
            assertSame(target(termBefore), target(restored));
            Signal.handle(term, termBefore);
        } finally {
            Signal.handle(term, termBefore);
            Signal.handle(intr, intBefore);
            Signals.PENDING.set(null);
            Signals.unbind();
        }
    }

    private static Object target(SignalHandler handler) throws Exception {
        if (!"sun.misc.Signal$SunMiscHandler".equals(handler.getClass().getName())) {
            return handler;
        }
        Field field = handler.getClass().getDeclaredField("iHandler");
        field.setAccessible(true);
        return field.get(handler);
    }

    @Test
    void trySendDoesNotWaitOnABlockedWrite() throws Exception {
        Process process = new ProcessBuilder("sleep", "30").start();
        StdioLink link = StdioLink.open(process);
        Thread blocked = new Thread(() -> {
            try {
                for (int i = 0; i < 10_000; i++) {
                    link.send("x".repeat(8192));
                }
            } catch (ConnectionLost ignored) {
            }
        });
            blocked.start();
            try {
                boolean sent = true;
                long spent = 0L;
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (System.nanoTime() < deadline) {
                    long attempt = System.nanoTime();
                    sent = link.trySend("{\"type\":\"terminating\"}");
                    spent = System.nanoTime() - attempt;
                    if (!sent) {
                        break;
                    }
                    Thread.sleep(20);
                }
                assertFalse(sent);
                assertTrue(spent < 1_000_000_000L);
            } finally {
            process.destroyForcibly();
            blocked.join(5_000);
            link.close();
        }
    }
}
