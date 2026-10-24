package ci.selective.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ci.selective.junit.support.ClientRun;
import ci.selective.junit.support.MockServer;

public class ForkedSurefireIT {
    @BeforeAll
    static void install() throws Exception {
        assertEquals(0, install("mvn", "-f", ClientRun.repo().resolve("pom.xml").toString(), "-N", "install"));
        assertEquals(0, install("mvn", "-f", ClientRun.repo().resolve("spi/pom.xml").toString(), "-DskipTests", "package", "install:install"));
        assertEquals(0, install("mvn", "-f", ClientRun.repo().resolve("core/pom.xml").toString(), "-DskipTests", "package", "install:install"));
    }

    @Test
    void forkedJvmReportsFromTheClientStore() throws Exception {
        run(Path.of(System.getProperty("java.home")));
        Path java17 = Path.of("/usr/lib/jvm/java-17-openjdk-amd64");
        Path current = Path.of(System.getProperty("java.home")).toRealPath();
        if (Files.isDirectory(java17) && !java17.toRealPath().equals(current)) {
            run(java17);
        }
    }

    private static void run(Path javaHome) throws Exception {
        try (MockServer server = new MockServer()) {
            Path hooks = Files.createTempFile("hooks", ".txt");
            Path finish = Files.createTempFile("finish", ".txt");
            Path log = Files.createTempFile("mvn", ".log");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<JsonObject> report = new AtomicReference<>();
            Thread script = new Thread(() -> {
                try {
                    MockServer.Conn conn = server.awaitConnection(180_000);
                    conn.send("{\"command\":\"connected\"}");
                    conn.send("{\"command\":\"test_manifest\"}");
                    JsonArray cases = ClientRun.json(conn.awaitMessage(60_000)).getAsJsonObject("data").getAsJsonArray("test_cases");
                    String one = ClientRun.id(cases, "GroupHooks", "[method:one()]");
                    String two = ClientRun.id(cases, "GroupHooks", "[method:two()]");
                    String three = ClientRun.id(cases, "GroupHooks", "[method:three()]");
                    String failing = ClientRun.id(cases, "ThrowingBeforeAll");
                    String probe = ClientRun.id(cases, "FinishProbe");
                    conn.send(ClientRun.batch(one, two));
                    conn.awaitMessage(60_000);
                    conn.awaitMessage(60_000);
                    conn.send(ClientRun.batch(three, failing, probe));
                    conn.awaitMessage(60_000);
                    conn.awaitMessage(60_000);
                    conn.awaitMessage(60_000);
                    conn.send("{\"command\":\"close\",\"exit_status\":null}");
                    report.set(ClientRun.json(conn.awaitMessage(30_000)));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "surefire-mock");
            script.setDaemon(true);
            script.start();
            clearReports(ClientRun.repo().resolve("suite/target/surefire-reports"));
            int code = maven(
                    log,
                    javaHome,
                    server.port(),
                    "mvn",
                    "-f",
                    ClientRun.repo().resolve("suite/pom.xml").toString(),
                    "test",
                    "-Dselective.skipSuite=false",
                    "-Dselective.hook.counter=" + hooks,
                    "-Dselective.finish.file=" + finish);
            String output = joined(script, failure, log);
            assertEquals(1, code, output);
            assertTrue(output.contains("SelectiveProvider"), output);
            assertFalse(output.contains("Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider"), output);
            String hookText = Files.readString(hooks);
            assertEquals(3, ClientRun.count(hookText, "before-group"), hookText);
            assertEquals(3, ClientRun.count(hookText, "after-group"), hookText);
            assertTrue(Files.readString(finish).contains("finish"));
            assertEquals(1, ClientRun.count(hookText, "launcher-session-opened"), hookText);
            assertTrue(report.get().getAsJsonObject("data").has("seconds_in_before_all_hooks"));
            Path reportsDir = ClientRun.repo().resolve("suite/target/surefire-reports");
            assertTrue(Files.exists(reportsDir.resolve("TEST-ci.selective.junit.fixtures.GroupHooks.xml")), reportText(reportsDir));
            assertFalse(Files.exists(reportsDir.resolve("TEST-ci.selective.junit.xml")), reportText(reportsDir));
            String reports = reportText(ClientRun.repo().resolve("suite/target/surefire-reports"));
            assertTrue(reports.contains("ThrowingBeforeAll") || reports.contains("before-all"), reports);
        }
    }

    @Test
    void missingConnectedFailsTheBuild() throws Exception {
        try (MockServer server = new MockServer()) {
            Path log = Files.createTempFile("mvn", ".log");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread script = new Thread(() -> {
                try {
                    server.awaitConnection(180_000);
                    Thread.sleep(120_000);
                } catch (InterruptedException ignored) {
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "timeout-mock");
            script.setDaemon(true);
            script.start();
            int code = maven(
                    log,
                    Path.of(System.getProperty("java.home")),
                    server.port(),
                    Map.of("SELECTIVE_CONNECTED_TIMEOUT_MS", "1000"),
                    "mvn",
                    "-f",
                    ClientRun.repo().resolve("suite/pom.xml").toString(),
                    "test",
                    "-Dselective.skipSuite=false");
            script.interrupt();
            String output = joined(script, failure, log);
            assertEquals(1, code, output);
            assertTrue(output.contains("timed out waiting for connected"), output);
        }
    }

    @Test
    void commandedSuccessMatchesTheSessionExit() throws Exception {
        try (MockServer server = new MockServer()) {
            Path log = Files.createTempFile("mvn", ".log");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread script = new Thread(() -> {
                try {
                    MockServer.Conn conn = server.awaitConnection(180_000);
                    conn.send("{\"command\":\"connected\"}");
                    conn.send("{\"command\":\"test_manifest\"}");
                    JsonArray cases = ClientRun.json(conn.awaitMessage(60_000)).getAsJsonObject("data").getAsJsonArray("test_cases");
                    conn.send(ClientRun.batch(ClientRun.id(cases, "ThrowingBeforeAll")));
                    conn.awaitMessage(60_000);
                    conn.send("{\"command\":\"close\",\"exit_status\":0}");
                    conn.awaitMessage(30_000);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "close-zero-mock");
            script.setDaemon(true);
            script.start();
            clearReports(ClientRun.repo().resolve("suite/target/surefire-reports"));
            int code = suite(log, server, "-Dtest=ThrowingBeforeAll");
            String output = joined(script, failure, log);
            assertEquals(0, code, output);
            assertTrue(output.contains("BUILD SUCCESS"), output);
        }
    }

    @Test
    void surefireTwoFailsTheBuild() throws Exception {
        try (MockServer server = new MockServer()) {
            Path log = Files.createTempFile("mvn", ".log");
            int code = maven(
                    log,
                    Path.of(System.getProperty("java.home")),
                    server.port(),
                    "mvn",
                    "-f",
                    ClientRun.repo().resolve("suite/pom.xml").toString(),
                    "test",
                    "-Dselective.skipSuite=false",
                    "-Dsurefire.version=2.22.2",
                    "-Dtest=GroupHooks");
            String output = Files.readString(log);
            assertEquals(1, code, output);
            assertTrue(output.contains("Selective needs maven-surefire-plugin 3.0.0 or newer"), output);
            assertEquals(0, server.accepts(), output);
        }
    }

    @Test
    void testFilterUsesTheClassFileName() throws Exception {
        assertManifest(
                "-Dtest=GroupHooks#one",
                cases -> {
                    assertTrue(containsId(cases, "GroupHooks", "[method:one()]"), cases.toString());
                    assertFalse(containsId(cases, "GroupHooks", "[method:two()]"), cases.toString());
                });
        assertManifest(
                "-Dtest=!GroupHooks#two",
                cases -> {
                    assertTrue(containsId(cases, "GroupHooks", "[method:one()]"), cases.toString());
                    assertFalse(containsId(cases, "GroupHooks", "[method:two()]"), cases.toString());
                });
    }

    @Test
    void capturedOutputReachesTheConsole() throws Exception {
        try (MockServer server = new MockServer()) {
            Path log = Files.createTempFile("mvn", ".log");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread script = new Thread(() -> {
                try {
                    MockServer.Conn conn = server.awaitConnection(180_000);
                    conn.send("{\"command\":\"connected\"}");
                    conn.send("{\"command\":\"test_manifest\"}");
                    JsonArray cases = ClientRun.json(conn.awaitMessage(60_000)).getAsJsonObject("data").getAsJsonArray("test_cases");
                    conn.send(ClientRun.batch(ClientRun.id(cases, "Prints")));
                    conn.awaitMessage(60_000);
                    conn.send("{\"command\":\"print_notice\",\"message\":\"server-notice-marker\"}");
                    conn.send("{\"command\":\"close\",\"exit_status\":null}");
                    conn.awaitMessage(30_000);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "capture-mock");
            script.setDaemon(true);
            script.start();
            int code = suite(log, server, "-Dtest=Prints");
            String output = joined(script, failure, log);
            assertEquals(0, code, output);
            assertTrue(output.contains("user-stdout-marker"), output);
            assertTrue(output.contains("server-notice-marker"), output);
            assertFalse(output.contains("Corrupted channel"), output);
        }
    }

    @Test
    void eachModuleHasItsOwnRun() throws Exception {
        try (MockServer server = new MockServer()) {
            Path log = Files.createTempFile("mvn", ".log");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<String> runs = new ArrayList<>();
            List<String> runners = new ArrayList<>();
            Thread script = new Thread(() -> {
                try {
                    for (int module = 0; module < 2; module++) {
                        MockServer.Conn conn = server.awaitConnection(180_000);
                        String run = query(conn.request(), "run_id");
                        boolean completed = runs.contains(run);
                        runs.add(run);
                        runners.add(query(conn.request(), "runner_id"));
                        conn.send("{\"command\":\"connected\"}");
                        if (completed) {
                            conn.send("{\"command\":\"close\",\"exit_status\":0,\"error_code\":\"run_completed\"}");
                            conn.awaitMessage(30_000);
                            continue;
                        }
                        conn.send("{\"command\":\"test_manifest\"}");
                        JsonArray cases = ClientRun.json(conn.awaitMessage(60_000)).getAsJsonObject("data").getAsJsonArray("test_cases");
                        for (int i = 0; i < cases.size(); i++) {
                            conn.send(ClientRun.batch(cases.get(i).getAsJsonObject().get("id").getAsString()));
                            conn.awaitMessage(60_000);
                        }
                        conn.send("{\"command\":\"close\",\"exit_status\":null}");
                        conn.awaitMessage(30_000);
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "reactor-mock");
            script.setDaemon(true);
            script.start();
            int code = maven(
                    log,
                    Path.of(System.getProperty("java.home")),
                    server.port(),
                    "mvn",
                    "-f",
                    ClientRun.repo().resolve("it/reactor/pom.xml").toString(),
                    "test");
            String output = joined(script, failure, log);
            assertEquals(1, code, output);
            assertTrue(reportText(ClientRun.repo().resolve("it/reactor/b/target/surefire-reports")).contains("b-failed"), output);
            assertEquals(List.of("run-1-a", "run-1-b"), runs, output);
            assertEquals(List.of("runner-1", "runner-1"), runners, output);
        }
    }

    @Test
    void withinBatchSkipDoesNotReplacePass() throws Exception {
        try (MockServer server = new MockServer()) {
            Path log = Files.createTempFile("mvn", ".log");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread script = new Thread(() -> {
                try {
                    MockServer.Conn conn = server.awaitConnection(180_000);
                    conn.send("{\"command\":\"connected\"}");
                    conn.send("{\"command\":\"test_manifest\"}");
                    JsonArray cases = ClientRun.json(conn.awaitMessage(60_000)).getAsJsonObject("data").getAsJsonArray("test_cases");
                    String id = ClientRun.id(cases, "SkipAfterPass");
                    conn.send(ClientRun.batch(id, id));
                    conn.awaitMessage(60_000);
                    conn.awaitMessage(60_000);
                    conn.send("{\"command\":\"close\",\"exit_status\":null}");
                    conn.awaitMessage(30_000);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "skip-mock");
            script.setDaemon(true);
            script.start();
            clearReports(ClientRun.repo().resolve("suite/target/surefire-reports"));
            int code = suite(log, server, "-Dtest=SkipAfterPass");
            String output = joined(script, failure, log);
            assertEquals(0, code, output);
            String reports = reportText(ClientRun.repo().resolve("suite/target/surefire-reports"));
            assertTrue(reports.contains("Skipped: 0"), reports);
            assertTrue(reports.contains("Failures: 0"), reports);
            assertFalse(reports.contains("later skip"), reports);
        }
    }

    private static int suite(Path log, MockServer server, String filter) throws Exception {
        return maven(
                log,
                Path.of(System.getProperty("java.home")),
                server.port(),
                "mvn",
                "-f",
                ClientRun.repo().resolve("suite/pom.xml").toString(),
                "test",
                "-Dselective.skipSuite=false",
                filter);
    }

    private static void assertManifest(String filter, java.util.function.Consumer<JsonArray> check) throws Exception {
        try (MockServer server = new MockServer()) {
            Path log = Files.createTempFile("mvn", ".log");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<JsonArray> cases = new AtomicReference<>();
            Thread script = new Thread(() -> {
                try {
                    MockServer.Conn conn = server.awaitConnection(180_000);
                    conn.send("{\"command\":\"connected\"}");
                    conn.send("{\"command\":\"test_manifest\"}");
                    cases.set(ClientRun.json(conn.awaitMessage(60_000)).getAsJsonObject("data").getAsJsonArray("test_cases"));
                    conn.send("{\"command\":\"close\",\"exit_status\":null}");
                    conn.awaitMessage(30_000);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "filter-mock");
            script.setDaemon(true);
            script.start();
            int code = suite(log, server, filter);
            String output = joined(script, failure, log);
            assertEquals(0, code, output);
            check.accept(cases.get());
        }
    }

    private static boolean containsId(JsonArray cases, String... needles) {
        for (int i = 0; i < cases.size(); i++) {
            String id = cases.get(i).getAsJsonObject().get("id").getAsString();
            boolean match = true;
            for (String needle : needles) {
                if (!id.contains(needle)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static String query(String request, String key) {
        String line = request.split("\\R", 2)[0];
        int start = line.indexOf('?');
        String query = line.substring(start + 1);
        int space = query.indexOf(' ');
        if (space >= 0) {
            query = query.substring(0, space);
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && key.equals(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(request);
    }

    private static String joined(Thread script, AtomicReference<Throwable> failure, Path log) throws Exception {
        script.join(20_000);
        String output = Files.readString(log);
        if (script.isAlive()) {
            script.interrupt();
            throw new AssertionError("mock script still waiting\n" + output);
        }
        if (failure.get() != null) {
            throw new AssertionError(output, failure.get());
        }
        return output;
    }

    private static void clearReports(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String reportText(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith(".txt")) {
                    text.append(Files.readString(file)).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static int install(String... command) throws Exception {
        Path log = Files.createTempFile("install", ".log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(ClientRun.repo().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(Files.readString(log));
        }
        int code = process.exitValue();
        if (code != 0) {
            throw new IllegalStateException(Files.readString(log));
        }
        return code;
    }

    private static int maven(Path log, Path javaHome, int port, String... command) throws Exception {
        return maven(log, javaHome, port, Map.of(), command);
    }

    private static int maven(Path log, Path javaHome, int port, Map<String, String> extra, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(ClientRun.repo().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", javaHome.toString());
        builder.environment().put("PATH", javaHome.resolve("bin") + ":" + System.getenv("PATH"));
        ClientRun.conformanceEnvironment(builder, port);
        builder.environment().putAll(extra);
        Process process = builder.start();
        if (!process.waitFor(240, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("maven timed out\n" + Files.readString(log));
        }
        return process.exitValue();
    }
}
