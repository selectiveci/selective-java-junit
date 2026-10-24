package ci.selective.junit.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import sun.misc.Signal;
import sun.misc.SignalHandler;

public final class Signals {
    public static final AtomicReference<String> PENDING = new AtomicReference<>();
    private static final AtomicReference<Termination> TARGET = new AtomicReference<>();
    private static final Map<String, SignalHandler> PREVIOUS = new HashMap<>();

    private Signals() {}

    public static void bind(Termination termination) {
        TARGET.set(termination);
    }

    public static void unbind() {
        TARGET.set(null);
        restore();
    }

    public static void install() {
        try {
            for (String name : List.of("TERM", "INT")) {
                SignalHandler previous = Signal.handle(new Signal(name), signal -> fire(name));
                PREVIOUS.put(name, previous);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("selective could not install termination handlers");
        }
    }

    private static void restore() {
        if (PREVIOUS.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<String, SignalHandler> entry : Map.copyOf(PREVIOUS).entrySet()) {
                Signal.handle(new Signal(entry.getKey()), entry.getValue());
            }
            PREVIOUS.clear();
        } catch (IllegalArgumentException exception) {
            System.err.println("selective could not install termination handlers");
        }
    }

    private static void fire(String name) {
        if (!PENDING.compareAndSet(null, name)) {
            return;
        }
        Termination termination = TARGET.get();
        if (termination != null) {
            termination.terminate(name);
        }
    }

    public interface Termination {
        void terminate(String signal);
    }
}
