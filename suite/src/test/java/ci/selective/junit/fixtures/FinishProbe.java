package ci.selective.junit.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;

import ci.selective.junit.runtime.Finish;

public class FinishProbe {
    static {
        String path = System.getProperty("selective.finish.file");
        if (path != null && !path.isEmpty()) {
            Finish.add(() -> {
                try {
                    Files.writeString(Path.of(path), "finish\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        }
    }

    @Test
    void ping() {}
}
