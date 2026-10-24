package ci.selective.junit.runtime;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ci.selective.junit.Version;

public final class Environment {
    private static final Map<String, String> REQUIRED = required();

    public final JsonObject build;
    public final String testMap;
    public final String testMapRecord;
    public final String runnerId;

    private Environment(JsonObject build, String testMap, String testMapRecord, String runnerId) {
        this.build = build;
        this.testMap = testMap;
        this.testMapRecord = testMapRecord;
        this.runnerId = runnerId;
    }

    public static Environment load(Path binary, boolean mavenModule) {
        String json;
        try {
            json = Binary.capture(binary, "--build-env");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("selective could not run " + binary + " --build-env (" + exception.getMessage()
                    + "). The client needs a transport with --build-env and --stdio.", exception);
        }
        JsonObject build = JsonParser.parseString(json).getAsJsonObject();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : REQUIRED.entrySet()) {
            if (text(build, entry.getKey()).isEmpty()) {
                missing.add(entry.getValue());
            }
        }
        String host = text(build, "host");
        if (!(host.startsWith("ws://") || host.startsWith("wss://"))) {
            missing.add("SELECTIVE_HOST");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("selective missing " + String.join(", ", missing));
        }
        String runner = text(build, "runner_id");
        if (runner.isEmpty()) {
            runner = "selgen-" + randomId();
        }
        if (mavenModule) {
            build.addProperty("run_id", moduleRunId(text(build, "run_id"), reactorRoot(), real(System.getProperty("basedir"))));
        }
        String disable = System.getenv("SELECTIVE_TEST_MAP_DISABLE");
        String testMap = Texts.on(disable) ? "0" : Recorder.attached() ? "record" : "select";
        String record = System.getenv("SELECTIVE_TEST_MAP_RECORD");
        return new Environment(build, testMap, record == null ? "" : record, runner);
    }

    public String websocketUrl(String junitVersion, boolean reconnect) {
        JsonObject metadata = new JsonObject();
        for (String key : new String[] {
            "platform", "branch", "pr_title", "target_branch", "actor", "sha", "base_sha",
            "commit_message", "committer_name", "committer_email", "git_repo_full_name", "git_provider",
            "run_id", "run_attempt"
        }) {
            metadata.addProperty(key, text(build, key));
        }
        metadata.addProperty("runner_id", runnerId);
        StringBuilder query = new StringBuilder();
        add(query, "api_key", text(build, "api_key"));
        add(query, "run_id", text(build, "run_id"));
        add(query, "run_attempt", text(build, "run_attempt"));
        add(query, "runner_id", runnerId);
        add(query, "language", "java");
        add(query, "core_version", Version.CORE);
        add(query, "framework", "junit");
        add(query, "framework_version", junitVersion);
        add(query, "framework_wrapper_version", Version.CORE);
        add(query, "runtime_version", System.getProperty("java.version", ""));
        add(query, "test_map", testMap);
        add(query, "test_map_record", testMapRecord);
        add(query, "remove_failed", "true");
        add(query, "metadata", metadata.toString());
        if (reconnect) {
            add(query, "reconnect", "true");
        }
        String host = text(build, "host");
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return host + "/transport/websocket?" + query;
    }

    public static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.getAsString();
    }

    private static void add(StringBuilder query, String key, String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(enc(key)).append('=').append(enc(value));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String moduleRunId(String runId, Path reactorRoot, Path module) {
        String relative = reactorRoot.relativize(module).toString();
        return relative.isEmpty() ? runId : runId + "-" + relative.replace(File.separatorChar, ':');
    }

    private static Path reactorRoot() {
        String root = System.getenv("MAVEN_PROJECTBASEDIR");
        if (root == null || root.isEmpty()) {
            throw new IllegalStateException(
                    "selective needs MAVEN_PROJECTBASEDIR to give each Maven module its own run; mvn and mvnw export it");
        }
        return real(root);
    }

    private static Path real(String directory) {
        try {
            return Path.of(directory).toRealPath();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String randomId() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Map<String, String> required() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("api_key", "SELECTIVE_API_KEY");
        map.put("platform", "SELECTIVE_PLATFORM");
        map.put("run_id", "SELECTIVE_RUN_ID");
        map.put("run_attempt", "SELECTIVE_RUN_ATTEMPT");
        map.put("branch", "SELECTIVE_BRANCH");
        map.put("sha", "SELECTIVE_SHA");
        map.put("git_repo_full_name", "SELECTIVE_GIT_REPO");
        map.put("git_provider", "SELECTIVE_GIT_PROVIDER");
        return map;
    }
}
