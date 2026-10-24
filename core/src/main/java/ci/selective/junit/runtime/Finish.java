package ci.selective.junit.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Finish {
    private static final List<Runnable> HOOKS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean RAN = new AtomicBoolean();

    private Finish() {}

    public static void add(Runnable hook) {
        HOOKS.add(hook);
    }

    public static void runOnce() {
        if (!RAN.compareAndSet(false, true)) {
            return;
        }
        for (Runnable hook : HOOKS) {
            hook.run();
        }
    }
}
