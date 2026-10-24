package ci.selective.junit.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Counters {
    private Counters() {}

    public static void add(String name) {
        String path = System.getProperty("selective.hook.counter");
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            Files.writeString(Path.of(path), name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
