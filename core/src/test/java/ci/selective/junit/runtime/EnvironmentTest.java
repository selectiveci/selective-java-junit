package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class EnvironmentTest {
    private static final Path ROOT = Path.of("/ci/repo");

    @Test
    void reactorRootKeepsTheRunId() {
        assertEquals("run-1", Environment.moduleRunId("run-1", ROOT, ROOT));
    }

    @Test
    void moduleSuffixesTheRunIdWithItsPath() {
        assertEquals("run-1-a", Environment.moduleRunId("run-1", ROOT, ROOT.resolve("a")));
        assertEquals("run-1-services:api", Environment.moduleRunId("run-1", ROOT, ROOT.resolve("services/api")));
    }
}
