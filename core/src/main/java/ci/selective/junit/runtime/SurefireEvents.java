package ci.selective.junit.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.surefire.api.report.LegacyPojoStackTraceWriter;
import org.apache.maven.surefire.api.report.RunListener;
import org.apache.maven.surefire.api.report.RunMode;
import org.apache.maven.surefire.api.report.SimpleReportEntry;

public final class SurefireEvents {
    private static final String SESSION = "ci.selective.junit";

    private SurefireEvents() {}

    public static void emit(RunListener listener, Iterable<Stored> results) {
        Map<String, List<Stored>> classes = new LinkedHashMap<>();
        for (Stored stored : results) {
            String source = stored.className().isEmpty() ? SESSION : stored.className();
            classes.computeIfAbsent(source, key -> new ArrayList<>()).add(stored);
        }
        long runId = 0L;
        for (Map.Entry<String, List<Stored>> entry : classes.entrySet()) {
            runId++;
            String source = entry.getKey();
            int total = 0;
            listener.testSetStarting(new SimpleReportEntry(RunMode.NORMAL_RUN, runId, source, source, null, null));
            for (Stored stored : entry.getValue()) {
                int millis = (int) Math.round(stored.outcome().seconds() * 1000);
                total += millis;
                test(listener, runId, source, stored, millis);
            }
            listener.testSetCompleted(new SimpleReportEntry(RunMode.NORMAL_RUN, runId, source, source, null, null, null, total));
        }
    }

    private static void test(RunListener listener, long runId, String source, Stored stored, int millis) {
        String name = stored.methodName().isEmpty() ? stored.wireId() : stored.methodName();
        String display = stored.displayName().isEmpty() ? name : stored.displayName();
        String message = stored.outcome().message();
        listener.testStarting(new SimpleReportEntry(RunMode.NORMAL_RUN, runId, source, source, name, display));
        switch (stored.outcome().status()) {
            case "passed" -> listener.testSucceeded(entry(runId, source, name, display, null, millis, null));
            case "pending" -> {
                if (stored.outcome().assumption()) {
                    listener.testAssumptionFailure(entry(runId, source, name, display, null, millis, message));
                } else {
                    listener.testSkipped(entry(runId, source, name, display, null, millis, message));
                }
            }
            default -> listener.testFailed(entry(
                    runId,
                    source,
                    name,
                    display,
                    new LegacyPojoStackTraceWriter(source, name, stored.outcome().throwable()),
                    millis,
                    message));
        }
    }

    private static SimpleReportEntry entry(
            long runId, String source, String name, String display, LegacyPojoStackTraceWriter trace, int millis, String message) {
        return new SimpleReportEntry(RunMode.NORMAL_RUN, runId, source, source, name, display, trace, millis, message, Map.of());
    }
}
