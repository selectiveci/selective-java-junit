package ci.selective.junit.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ClientRun implements AutoCloseable {
    public final MockServer server;
    public final Process process;
    public final Path hooks;
    public final Path finish;
    public final Path stderr;
    public final Path stdout;

    private ClientRun(MockServer server, Process process, Path hooks, Path finish, Path stderr, Path stdout) {
        this.server = server;
        this.process = process;
        this.hooks = hooks;
        this.finish = finish;
        this.stderr = stderr;
        this.stdout = stdout;
    }

    public static ClientRun start(String... extraEnv) throws IOException {
        return start(false, List.of(), extraEnv);
    }

    public static ClientRun startRejected() throws IOException {
        return start(true, List.of());
    }

    public static ClientRun startRecording() throws IOException {
        return startRecording("output=none");
    }

    public static ClientRun startRecording(String agentOptions) throws IOException {
        return start(false, List.of("-javaagent:" + System.getProperty("jacoco.agent") + "=" + agentOptions));
    }

    private static ClientRun start(boolean reject, List<String> jvm, String... extraEnv) throws IOException {
        MockServer server = new MockServer();
        server.reject = reject;
        Path hooks = Files.createTempFile("hooks", ".txt");
        Path finish = Files.createTempFile("finish", ".txt");
        Path stderr = Files.createTempFile("err", ".txt");
        Path stdout = Files.createTempFile("out", ".txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java));
        command.addAll(jvm);
        command.addAll(List.of(
                "-Dselective.hook.counter=" + hooks,
                "-Dselective.finish.file=" + finish,
                "-cp",
                System.getProperty("java.class.path"),
                "ci.selective.junit.Main",
                "--package",
                "ci.selective.junit.fixtures"));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo().toFile());
        builder.redirectError(ProcessBuilder.Redirect.to(stderr.toFile()));
        builder.redirectOutput(ProcessBuilder.Redirect.to(stdout.toFile()));
        conformanceEnvironment(builder, server.port());
        for (int i = 0; i + 1 < extraEnv.length; i += 2) {
            builder.environment().put(extraEnv[i], extraEnv[i + 1]);
        }
        return new ClientRun(server, builder.start(), hooks, finish, stderr, stdout);
    }

    public MockServer.Conn connection() throws InterruptedException {
        return server.awaitConnection(30_000);
    }

    public int awaitExit() throws InterruptedException {
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("client did not exit\n" + stderr());
        }
        return process.exitValue();
    }

    public String stderr() {
        try {
            return Files.readString(stderr);
        } catch (IOException exception) {
            return exception.toString();
        }
    }

    public String stdout() {
        try {
            return Files.readString(stdout);
        } catch (IOException exception) {
            return exception.toString();
        }
    }

    public String hooks() {
        try {
            return Files.exists(hooks) ? Files.readString(hooks) : "";
        } catch (IOException exception) {
            return "";
        }
    }

    public String finish() {
        try {
            return Files.exists(finish) ? Files.readString(finish) : "";
        } catch (IOException exception) {
            return "";
        }
    }

    public static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    public static String id(JsonArray cases, String... needles) {
        for (JsonElement element : cases) {
            String id = element.getAsJsonObject().get("id").getAsString();
            boolean match = true;
            for (String needle : needles) {
                if (!id.contains(needle)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return id;
            }
        }
        throw new IllegalStateException("missing id " + String.join(" ", needles) + " in " + cases);
    }

    public static Path repo() {
        Path cwd = Path.of("").toAbsolutePath();
        if ("core".equals(String.valueOf(cwd.getFileName()))) {
            return cwd.getParent();
        }
        return cwd;
    }

    public static void conformanceEnvironment(ProcessBuilder builder, int port) {
        String transport = System.getenv("SELECTIVE_TRANSPORT");
        if (transport == null || transport.isEmpty()) {
            transport = "/tmp/transport/target/release/transport";
        }
        builder.environment().put("SELECTIVE_TRANSPORT", transport);
        builder.environment().put("SELECTIVE_CONFORMANCE_FAST", "1");
        builder.environment().put("SELECTIVE_API_KEY", "conformance-key");
        builder.environment().put("SELECTIVE_PLATFORM", "github");
        builder.environment().put("SELECTIVE_RUN_ID", "run-1");
        builder.environment().put("SELECTIVE_RUN_ATTEMPT", "1");
        builder.environment().put("SELECTIVE_BRANCH", "main");
        builder.environment().put("SELECTIVE_SHA", "abc");
        builder.environment().put("SELECTIVE_GIT_REPO", "org/repo");
        builder.environment().put("SELECTIVE_GIT_PROVIDER", "github");
        builder.environment().put("SELECTIVE_RUNNER_ID", "runner-1");
        builder.environment().put("SELECTIVE_HOST", "ws://127.0.0.1:" + port);
    }

    public static String batch(String... ids) {
        StringBuilder body = new StringBuilder("{\"command\":\"run_test_cases\",\"test_case_ids\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('"').append(ids[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return body.append("]}").toString();
    }

    public static int count(String text, String token) {
        int found = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            found++;
            index += token.length();
        }
        return found;
    }

    @Override
    public void close() {
        process.destroyForcibly();
        server.close();
    }
}
