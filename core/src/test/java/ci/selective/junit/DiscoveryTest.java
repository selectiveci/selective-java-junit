package ci.selective.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ci.selective.junit.engine.CaseRef;
import ci.selective.junit.support.ClientRun;
import ci.selective.junit.engine.JUnitSuite;

public class DiscoveryTest {
    @Test
    void collectsTemplatesAsCasesWithoutRunningAnything() throws Exception {
        Path hooks = Files.createTempFile("hooks", ".txt");
        System.setProperty("selective.hook.counter", hooks.toString());
        JUnitSuite.Spec spec = new JUnitSuite.Spec();
        spec.cwd = ClientRun.repo();
        spec.packageName = "ci.selective.junit.fixtures";
        try (JUnitSuite suite = JUnitSuite.collect(spec)) {
            List<String> ids = ids(suite);
            boolean shortened = false;
            for (CaseRef ref : suite.cases()) {
                assertTrue(ref.wireId().length() <= 255, ref.wireId());
                assertTrue(ref.filePath() != null && !ref.filePath().isEmpty());
                if (!ref.fullId().equals(ref.wireId()) && ref.wireId().contains("~")) {
                    shortened = true;
                }
            }
            assertTrue(shortened, ids.toString());
            assertTrue(count(ids, "GroupHooks") >= 3, ids.toString());
            assertEquals(1, count(ids, "ParameterizedHooks]/[test-template:"), ids.toString());
            assertEquals(1, count(ids, "DynamicFactory]/[test-factory:"), ids.toString());
            assertEquals(0, count(ids, "-invocation:"), ids.toString());
            assertEquals(0, count(ids, "[dynamic-"), ids.toString());
            assertTrue(contains(ids, "ThrowingFactory"), ids.toString());
            assertTrue(contains(ids, "junit-vintage"), ids.toString());
            assertTrue(contains(ids, "DisabledClass"), ids.toString());
            String text = Files.readString(hooks);
            assertEquals(1, ClientRun.count(text, "launcher-session-opened"), text);
            assertEquals(0, ClientRun.count(text, "probe-constructor"), text);
            assertEquals(0, ClientRun.count(text, "probe-extension"), text);
            assertEquals(0, ClientRun.count(text, "before-param"), text);
            assertEquals(0, ClientRun.count(text, "dynamic-body"), text);
            assertEquals(19, first(suite, "GroupHooks", "[method:one()]").lineNumber());
            assertEquals(21, first(suite, "ParameterizedHooks", "[test-template:").lineNumber());
            suite.execute(first(suite, "GroupHooks", "[method:one()]"));
            assertEquals("passed", suite.execute(first(suite, "ParameterizedHooks", "[test-template:")).status());
            assertEquals("passed", suite.execute(first(suite, "DynamicFactory", "[test-factory:")).status());
            text = Files.readString(hooks);
            assertEquals(1, ClientRun.count(text, "before-group"), text);
            assertEquals(1, ClientRun.count(text, "after-group"), text);
            assertEquals(1, ClientRun.count(text, "before-param"), text);
            assertEquals(2, ClientRun.count(text, "dynamic-body"), text);
            assertEquals(1, ClientRun.count(text, "launcher-session-opened"), text);
        }
    }

    @Test
    void tagFilterKeepsFast() {
        JUnitSuite.Spec spec = new JUnitSuite.Spec();
        spec.packageName = "ci.selective.junit.fixtures";
        spec.includeTags = List.of("fast");
        try (JUnitSuite suite = JUnitSuite.collect(spec)) {
            List<String> ids = ids(suite);
            assertTrue(contains(ids, "TagsHost"));
            assertTrue(contains(ids, "fast"));
            assertTrue(ids.stream().noneMatch(id -> id.contains("[method:slow()]")));
        }
    }

    private static List<String> ids(JUnitSuite suite) {
        List<String> ids = new ArrayList<>();
        for (CaseRef ref : suite.cases()) {
            ids.add(ref.fullId());
        }
        return ids;
    }

    private static CaseRef first(JUnitSuite suite, String type, String method) {
        for (CaseRef ref : suite.cases()) {
            if (ref.fullId().contains(type) && ref.fullId().contains(method)) {
                return ref;
            }
        }
        throw new AssertionError(method);
    }

    private static int count(List<String> ids, String token) {
        int found = 0;
        for (String id : ids) {
            if (id.contains(token)) {
                found++;
            }
        }
        return found;
    }

    private static boolean contains(List<String> ids, String token) {
        return count(ids, token) > 0;
    }
}
