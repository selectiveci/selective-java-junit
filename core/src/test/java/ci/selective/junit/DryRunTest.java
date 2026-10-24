package ci.selective.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Test;

import ci.selective.junit.runtime.Session;

public class DryRunTest {
    @Test
    void dryRunPrintsTheManifestWithoutConnecting() {
        PrintStream previous = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            int code = Session.harness("ci.selective.junit.fixtures", true);
            assertEquals(0, code);
        } finally {
            System.setOut(previous);
        }
        String text = buffer.toString();
        assertTrue(text.contains("\"test_cases\""));
        assertTrue(text.contains("GroupHooks"));
        assertTrue(!text.contains("test_map_tree"));
    }

    @Test
    void emptySuiteExitsOne() {
        PrintStream previous = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            assertEquals(1, Session.harness("ci.selective.junit.missing", true));
            assertEquals(1, Session.harness("", false));
        } finally {
            System.setErr(previous);
        }
    }
}
