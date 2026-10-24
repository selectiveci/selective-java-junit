package ci.selective.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import ci.selective.junit.engine.CaseRef;
import ci.selective.junit.support.ClientRun;
import ci.selective.junit.engine.JUnitSuite;
import ci.selective.junit.engine.Outcome;

public class FailuresTest {
    @Test
    void factoryFailureFollowsTheChild() {
        JUnitSuite suite = collect();
        assertChildFailure(suite, "FactoryNeedsBeforeAll", "[test-factory:");
        assertChildFailure(suite, "FactoryNeedsBeforeEach", "[test-factory:");
        assertChildFailure(suite, "ParamNeedsBeforeEach", "[test-template:");
    }

    @Test
    void everyFailingChildIsNamed() {
        JUnitSuite suite = collect();
        Outcome template = suite.execute(factory(suite, "TwoFailures", "[test-template:"));
        assertEquals(List.of("#2 [2] 2: bad 2", "#3 [3] 3: bad 3"), template.messageLines());
        Outcome dynamic = suite.execute(factory(suite, "TwoFailures", "[test-factory:"));
        assertEquals(List.of("#2 y: bad y", "#3 z: bad z"), dynamic.messageLines());
        assertTrue(dynamic.backtrace().get(0).contains("TwoFailures.java"), dynamic.backtrace().toString());
    }

    @Test
    void libraryFramesStayOutOfTheProject() {
        JUnitSuite suite = collect();
        Outcome outcome = suite.execute(factory(suite, "LibraryFrame", "[method:fails()]"));
        assertEquals("failed", outcome.status());
        assertFalse(outcome.backtrace().isEmpty());
        String first = outcome.backtrace().get(0);
        assertTrue(first.contains("LibraryFrame.java"), first);
        String path = first.substring(0, first.indexOf(':'));
        assertTrue(Files.exists(ClientRun.repo().resolve(path)), path);
        for (String frame : outcome.backtrace()) {
            assertFalse(frame.contains("src/test/java/org/hamcrest"), frame);
            assertFalse(frame.contains("src/test/java/org/assertj"), frame);
            assertFalse(frame.contains("src/test/java/java/"), frame);
        }
    }

    private static void assertChildFailure(JUnitSuite suite, String type, String segment) {
        Outcome outcome = suite.execute(factory(suite, type, segment));
        assertEquals("failed", outcome.status(), type + " " + outcome.message());
        assertTrue(outcome.message().contains("too big 3"), type + " " + outcome.message());
        assertTrue(outcome.messageLines().get(0).matches(".+: too big 3"), outcome.messageLines().toString());
    }

    @Test
    void nullAbortMessageIsEmpty() {
        JUnitSuite suite = collect();
        Outcome outcome = suite.execute(factory(suite, "NullAbort", "[method:blank()]"));
        assertEquals("pending", outcome.status());
        assertEquals("", outcome.message());
    }

    private static CaseRef factory(JUnitSuite suite, String type, String segment) {
        for (CaseRef ref : suite.cases()) {
            if (ref.className().endsWith(type) && ref.fullId().contains(segment)) {
                return ref;
            }
        }
        throw new IllegalStateException("missing " + type + " " + segment);
    }

    private static JUnitSuite collect() {
        JUnitSuite.Spec spec = new JUnitSuite.Spec();
        spec.cwd = ClientRun.repo();
        spec.packageName = "ci.selective.junit.fixtures";
        return JUnitSuite.collect(spec);
    }

}
