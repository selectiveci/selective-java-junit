package ci.selective.junit.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.apache.maven.surefire.api.provider.ProviderParameters;
import org.apache.maven.surefire.api.report.ConsoleOutputCapture;
import org.apache.maven.surefire.api.report.OutputReportEntry;
import org.apache.maven.surefire.api.report.RunListener;
import org.apache.maven.surefire.api.report.TestOutputReceiver;
import org.apache.maven.surefire.api.suite.RunResult;
import org.apache.maven.surefire.api.testset.TestListResolver;

import ci.selective.junit.engine.CaseRef;
import ci.selective.junit.engine.JUnitSuite;
import ci.selective.junit.engine.Outcome;

public final class Session {
    private Session() {}

    public static int harness(String packageName, boolean dryRun) {
        if (packageName == null || packageName.isEmpty()) {
            System.err.println("selective found no tests");
            return 1;
        }
        JUnitSuite.Spec spec = new JUnitSuite.Spec();
        spec.packageName = packageName;
        try (JUnitSuite suite = JUnitSuite.collect(spec)) {
            if (suite.isEmpty()) {
                System.err.println("selective found no tests");
                return 1;
            }
            if (dryRun) {
                System.out.println(new com.google.gson.Gson().toJson(manifestData(suite)));
                return 0;
            }
            return new Runner(suite, null, null).run();
        }
    }

    @SuppressWarnings("unchecked")
    public static RunResult surefire(ProviderParameters parameters) {
        try (JUnitSuite suite = JUnitSuite.collect(spec(parameters))) {
            if (suite.isEmpty()) {
                throw new IllegalStateException("selective found no tests");
            }
            RunListener listener = parameters.getReporterFactory().createTestReportListener();
            ConsoleOutputCapture.startCapture((TestOutputReceiver<OutputReportEntry>) listener);
            Runner runner = new Runner(suite, parameters, listener);
            int status = runner.run();
            return runner.toRunResult(status);
        }
    }

    private static JUnitSuite.Spec spec(ProviderParameters parameters) {
        JUnitSuite.Spec spec = new JUnitSuite.Spec();
        spec.loader = parameters.getTestClassLoader();
        if (parameters.getDirectoryScannerParameters() != null) {
            if (parameters.getDirectoryScannerParameters().getTestClassesDirectory() != null) {
                spec.classesDirectory = parameters.getDirectoryScannerParameters().getTestClassesDirectory().toPath();
            }
        }
        Set<String> scanned = new LinkedHashSet<>();
        for (int i = 0; i < parameters.getScanResult().size(); i++) {
            scanned.add(parameters.getScanResult().getClassName(i));
        }
        spec.scannedClasses = scanned;
        Map<String, String> properties = parameters.getProviderProperties();
        spec.includeTags = split(properties.get("groups"));
        spec.excludeTags = split(properties.get("excludegroups"));
        spec.includeEngines = split(properties.get("includejunit5engines"));
        spec.excludeEngines = split(properties.get("excludejunit5engines"));
        spec.configuration = configuration(properties.get("configurationParameters"));
        if (parameters.getTestRequest() != null && parameters.getTestRequest().getTestListResolver() != null) {
            TestListResolver resolver = parameters.getTestRequest().getTestListResolver();
            if (!resolver.isEmpty()) {
                spec.methodFilter = (className, methodName) ->
                        resolver.shouldRun(TestListResolver.toClassFileName(className), methodName);
            }
        }
        return spec;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    private static Map<String, String> configuration(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return values;
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException exception) {
            return values;
        }
        for (String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name));
        }
        return values;
    }

    static JsonObject manifestData(JUnitSuite suite) {
        JsonObject data = new JsonObject();
        JsonArray tests = new JsonArray();
        for (CaseRef ref : suite.cases()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", ref.wireId());
            item.addProperty("file_path", ref.filePath());
            if (ref.lineNumber() != null) {
                item.addProperty("line_number", ref.lineNumber());
            }
            item.addProperty("description", ref.displayName());
            String owner = ref.className();
            item.addProperty("full_description", Texts.clean(owner.isEmpty() ? ref.displayName() : owner + " " + ref.displayName()));
            item.addProperty("run_time", 0.0d);
            tests.add(item);
        }
        data.add("test_cases", tests);
        return data;
    }

    static long retryMillis(int failures, boolean fast) {
        if (fast) {
            return 0L;
        }
        return Math.min(failures, 4) * 1000L;
    }

    private static final class Runner implements Signals.Termination {
        private final JUnitSuite suite;
        private final ProviderParameters parameters;
        private final RunListener listener;
        private final com.google.gson.Gson gson = new com.google.gson.Gson();
        private final Map<String, Stored> store = new LinkedHashMap<>();
        private final boolean fast = "1".equals(System.getenv("SELECTIVE_CONFORMANCE_FAST"));
        private final AtomicBoolean signaled = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();
        private volatile StdioLink active;
        private volatile String failureReason;
        private Environment environment;
        private Path binary;
        private boolean tree;
        private boolean recordWarned;
        private Recorder recorder;
        private int connectionRetries;
        private volatile Integer forcedStatus;
        private boolean hookFailed;
        private RunResult publishedResult;

        Runner(JUnitSuite suite, ProviderParameters parameters, RunListener listener) {
            this.suite = suite;
            this.parameters = parameters;
            this.listener = listener;
        }

        int run() {
            Signals.bind(this);
            Signals.install();
            try {
                binary = Binary.resolve();
                environment = Environment.load(binary, parameters != null);
                int failures = 0;
                boolean reconnect = false;
                while (true) {
                    StdioLink link = null;
                    try {
                        link = StdioLink.open(binary, environment.websocketUrl(suite.junitVersion(), reconnect));
                        active = link;
                        return drive(link);
                    } catch (StdioLink.ConnectionLost | IOException exception) {
                        failures++;
                        connectionRetries = failures;
                        if (failures > 10) {
                            failureReason = "selective gave up after " + failures + " connection failures";
                            System.err.println(failureReason);
                            return 1;
                        }
                        sleep(retryMillis(failures, fast));
                        reconnect = true;
                    } catch (Reconnect ignored) {
                        reconnect = true;
                    } catch (Unparseable unparseable) {
                        failureReason = "selective received a frame it cannot parse: " + unparseable.getMessage();
                        System.err.println(failureReason);
                        return 1;
                    } finally {
                        active = null;
                        if (link != null) {
                            link.close();
                        }
                    }
                }
            } finally {
                if (recorder != null) {
                    recorder.restore();
                }
                Signals.unbind();
            }
        }

        @Override
        public void terminate(String signal) {
            int status = 1;
            try {
                status = status();
                notifySignal(active, signal);
                publish(status);
            } finally {
                Runtime.getRuntime().halt(status);
            }
        }

        RunResult toRunResult(int status) {
            publish(status);
            return publishedResult;
        }

        private synchronized void publish(int status) {
            if (parameters == null || listener == null || !published.compareAndSet(false, true)) {
                return;
            }
            boolean commandedSuccess = forcedStatus != null && forcedStatus == 0;
            String reason = failureReason == null ? "selective failed" : failureReason;
            List<Stored> reported;
            synchronized (store) {
                if (status != 0 && !failedStoredHoldingLock()) {
                    Outcome outcome = new Outcome(
                            "failed",
                            reason,
                            List.of(reason),
                            List.of(),
                            0,
                            false,
                            new IllegalStateException(reason));
                    store.put(
                            "selective-session",
                            new Stored("selective-session", "ci.selective.junit", "session", "session", outcome));
                }
                reported = store.values().stream()
                        .filter(stored -> !(commandedSuccess && "failed".equals(stored.outcome().status())))
                        .toList();
            }
            SurefireEvents.emit(listener, reported);
            RunResult result = parameters.getReporterFactory().close();
            if (status != 0 && result.isErrorFree()) {
                publishedResult = new RunResult(0, 1, 0, 0, reason, false);
                return;
            }
            if (commandedSuccess && !result.isErrorFree()) {
                publishedResult = new RunResult(result.getCompletedCount(), 0, 0, result.getSkipped());
                return;
            }
            publishedResult = result;
        }

        private boolean failedStored() {
            synchronized (store) {
                return failedStoredHoldingLock();
            }
        }

        private boolean failedStoredHoldingLock() {
            for (Stored stored : store.values()) {
                if ("failed".equals(stored.outcome().status())) {
                    return true;
                }
            }
            return false;
        }

        private int drive(StdioLink link) throws StdioLink.ConnectionLost, IOException, Reconnect, Unparseable {
            ArrayDeque<JsonObject> queued = new ArrayDeque<>();
            Integer early = awaitConnected(link, queued);
            if (early != null) {
                return early;
            }
            while (true) {
                JsonObject command = queued.poll();
                if (command == null) {
                    try {
                        command = next(link, 200);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        String pending = Signals.PENDING.get();
                        return onSignal(link, pending == null ? "INT" : pending);
                    }
                    if (command == null) {
                        continue;
                    }
                }
                switch (commandName(command)) {
                    case "connected" -> {
                    }
                    case "configure_test_map" -> configure(command);
                    case "test_manifest" -> send(link, manifest());
                    case "run_test_cases" -> runBatch(link, command);
                    case "remove_failed_test_case_result" -> remove(command);
                    case "reconnect" -> throw new Reconnect();
                    case "close" -> {
                        return close(link, command);
                    }
                    case "print_notice", "print_message" -> print(command);
                    default -> {
                        if (Texts.on(System.getenv("SELECTIVE_DEBUG"))) {
                            throw new IllegalStateException("unknown command " + commandName(command));
                        }
                    }
                }
            }
        }

        private Integer awaitConnected(StdioLink link, ArrayDeque<JsonObject> queued)
                throws StdioLink.ConnectionLost, Unparseable {
            long timeout = connectedTimeout();
            long deadline = System.nanoTime() + timeout * 1_000_000L;
            while (true) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    failureReason = "timed out waiting for connected";
                    System.err.println(failureReason);
                    return 1;
                }
                JsonObject command;
                try {
                    command = next(link, Math.min(remainingMs, 200));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    String pending = Signals.PENDING.get();
                    return onSignal(link, pending == null ? "INT" : pending);
                }
                if (command == null) {
                    continue;
                }
                if ("connected".equals(commandName(command))) {
                    return null;
                }
                queued.add(command);
            }
        }

        private void runBatch(StdioLink link, JsonObject command) throws StdioLink.ConnectionLost {
            JsonArray ids = command.getAsJsonArray("test_case_ids");
            if (ids == null) {
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement element : ids) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                if (Signals.PENDING.get() != null) {
                    return;
                }
                String wire = element.getAsString();
                boolean again = !seen.add(wire);
                Stored previous;
                synchronized (store) {
                    previous = store.remove(wire);
                }
                CaseRef ref = suite.find(wire);
                if (recorder != null && ref != null) {
                    recorder.reset();
                }
                Outcome outcome = ref == null ? suite.missing(wire) : suite.execute(ref);
                if (recorder != null && ref != null) {
                    recorder.record(wire);
                }
                boolean keepPass = again
                        && "passed".equals(previous.outcome().status())
                        && "pending".equals(outcome.status());
                synchronized (store) {
                    store.put(wire, keepPass ? previous : Stored.of(wire, ref, outcome));
                }
                try {
                    send(link, result(wire, ref, outcome));
                } catch (StdioLink.ConnectionLost lost) {
                    synchronized (store) {
                        store.remove(wire);
                    }
                    throw lost;
                }
            }
        }

        private void remove(JsonObject command) {
            if (!command.has("test_case_id") || !command.get("test_case_id").isJsonPrimitive()) {
                return;
            }
            synchronized (store) {
                store.remove(command.get("test_case_id").getAsString());
            }
        }

        private int close(StdioLink link, JsonObject command) throws StdioLink.ConnectionLost {
            String message = string(command, "message");
            if (command.has("exit_status") && !command.get("exit_status").isJsonNull()) {
                forcedStatus = command.get("exit_status").getAsInt();
                if (forcedStatus != 0) {
                    failureReason = message.isEmpty() ? "selective close " + forcedStatus : message;
                }
            }
            send(link, report());
            if (forcedStatus == null) {
                try {
                    Finish.runOnce();
                } catch (Throwable throwable) {
                    hookFailed = true;
                    failureReason = "selective finish hook failed (" + throwable.getMessage() + ")";
                    System.err.println(failureReason);
                }
            }
            if (recorder != null && !recorder.empty()) {
                upload();
            }
            return status();
        }

        private int onSignal(StdioLink link, String signal) {
            notifySignal(link, signal);
            return status();
        }

        private void notifySignal(StdioLink link, String signal) {
            if (!signaled.compareAndSet(false, true) || link == null) {
                return;
            }
            JsonObject data = new JsonObject();
            data.addProperty("signal", signal);
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "terminating");
            frame.add("data", data);
            if (!link.trySend(gson.toJson(frame))) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private void configure(JsonObject command) {
            if (flag(command, "record") && recorder == null && !recordWarned) {
                recordWarned = true;
                if (!Recorder.attached()) {
                    System.err.println("Selective was asked to record a test map, but no supported JaCoCo agent is attached. Tests will not be traced.");
                } else {
                    try {
                        recorder = Recorder.start(Thread.currentThread().getContextClassLoader(), Path.of("").toAbsolutePath());
                    } catch (IOException | InterruptedException exception) {
                        if (exception instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        System.err.println("Selective was asked to record a test map, but cannot read the git checkout ("
                                + exception.getMessage() + "). Tests will not be traced.");
                    }
                }
            }
            if (flag(command, "tree") && !"0".equals(environment.testMap)) {
                tree = true;
            }
        }

        private JsonObject manifest() {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "test_manifest");
            JsonObject data = manifestData(suite);
            Path cwd = Path.of("").toAbsolutePath();
            if (tree) {
                JsonObject snapshot = GitHints.snapshot(binary, cwd);
                if (snapshot != null) {
                    data.add("test_map_tree", snapshot);
                }
            }
            GitHints.addDiffHints(data, cwd, Environment.text(environment.build, "target_branch"));
            frame.add("data", data);
            return frame;
        }

        private JsonObject result(String wire, CaseRef ref, Outcome outcome) {
            JsonObject data = new JsonObject();
            data.addProperty("id", Texts.clean(wire));
            String description = ref == null ? "not collected" : ref.displayName();
            String full = ref == null ? "not collected" : ref.className() + " " + ref.displayName();
            data.addProperty("description", Texts.clean(description));
            data.addProperty("full_description", Texts.clean(full));
            data.addProperty("status", outcome.status());
            data.addProperty("file_path", ref == null ? "" : ref.filePath());
            if (ref != null && ref.lineNumber() != null) {
                data.addProperty("line_number", ref.lineNumber());
            }
            data.addProperty("run_time", outcome.seconds());
            if ("pending".equals(outcome.status())) {
                data.addProperty("pending_message", Texts.clean(outcome.message()));
            } else {
                data.add("pending_message", JsonNull.INSTANCE);
            }
            if ("failed".equals(outcome.status())) {
                JsonArray lines = new JsonArray();
                for (String line : outcome.messageLines()) {
                    lines.add(Texts.clean(line));
                }
                JsonArray frames = new JsonArray();
                for (String frame : outcome.backtrace()) {
                    frames.add(Texts.clean(frame));
                }
                data.add("failure_message_lines", lines);
                data.add("failure_formatted_backtrace", frames);
            }
            JsonObject message = new JsonObject();
            message.addProperty("type", "test_case_result");
            message.add("data", data);
            return message;
        }

        private JsonObject report() {
            JsonObject data = new JsonObject();
            data.addProperty("connection_retries", connectionRetries);
            data.addProperty("seconds_in_before_all_hooks", suite.beforeAllSeconds());
            data.addProperty("seconds_in_after_all_hooks", suite.afterAllSeconds());
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "report_at_finish");
            frame.add("data", data);
            return frame;
        }

        private void upload() {
            String error;
            try {
                ProcessBuilder builder = new ProcessBuilder(
                                binary.toString(),
                                "--upload-fragment",
                                "--run-id", Environment.text(environment.build, "run_id"),
                                "--run-attempt", Environment.text(environment.build, "run_attempt"),
                                "--runner-id", environment.runnerId)
                        .redirectErrorStream(true);
                builder.environment().put("SELECTIVE_TEST_MAP_UPLOAD_KEY", Environment.text(environment.build, "api_key"));
                Process process = builder.start();
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(recorder.fragment());
                }
                error = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.waitFor() == 0) {
                    return;
                }
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                error = exception.getMessage();
            }
            System.err.println("Selective could not upload this runner's test map (" + error
                    + "). Its tests will run on the next build rather than be skipped.");
        }

        private void send(StdioLink link, JsonObject frame) throws StdioLink.ConnectionLost {
            link.send(gson.toJson(frame));
        }

        private void print(JsonObject command) {
            String message = string(command, "message");
            if (!message.isEmpty()) {
                System.out.println(message);
            }
        }

        private int framework() {
            return hookFailed || failedStored() ? 1 : 0;
        }

        private int status() {
            return forcedStatus != null ? forcedStatus : framework();
        }

        private static void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Reconnect extends Exception {}

    private static final class Unparseable extends Exception {
        Unparseable(String line) {
            super(Texts.clean(line.length() > 200 ? line.substring(0, 200) + "..." : line));
        }
    }

    private static long connectedTimeout() {
        String value = System.getenv("SELECTIVE_CONNECTED_TIMEOUT_MS");
        if (value == null || value.isBlank()) {
            return 30_000L;
        }
        return Long.parseLong(value.trim());
    }

    private static JsonObject next(StdioLink link, long timeoutMs)
            throws StdioLink.ConnectionLost, InterruptedException, Unparseable {
        String line = link.poll(timeoutMs);
        return line == null ? null : parse(line);
    }

    private static JsonObject parse(String line) throws Unparseable {
        if (line.isBlank()) {
            return null;
        }
        JsonElement element;
        try {
            element = JsonParser.parseString(line);
        } catch (JsonParseException exception) {
            throw new Unparseable(line);
        }
        if (!element.isJsonObject()) {
            throw new Unparseable(line);
        }
        return element.getAsJsonObject();
    }

    private static String commandName(JsonObject object) {
        return string(object, "command");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean flag(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }
}
