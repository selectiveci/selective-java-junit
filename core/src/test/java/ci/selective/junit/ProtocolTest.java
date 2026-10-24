package ci.selective.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ci.selective.junit.support.ClientRun;
import ci.selective.junit.support.MockServer;

public class ProtocolTest {
    @Test
    void batchesRunInOneLauncherSession() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            assertTrue(conn.request().contains("test_map=select"));
            assertTrue(conn.request().contains("framework=junit"));
            assertTrue(conn.request().contains("metadata="));
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"print_notice\",\"message\":\"hello-notice\"}");
            JsonArray cases = manifest(conn);
            String one = ClientRun.id(cases, "GroupHooks", "[method:one()]");
            String two = ClientRun.id(cases, "GroupHooks", "[method:two()]");
            String three = ClientRun.id(cases, "GroupHooks", "[method:three()]");
            String param = ClientRun.id(cases, "ParameterizedHooks", "[test-template:");
            String factory = ClientRun.id(cases, "DynamicFactory", "[test-factory:");
            assertFalse(cases.toString().contains("-invocation:"), cases.toString());
            conn.send(ClientRun.batch(one, two));
            assertEquals("passed", status(conn));
            assertEquals("passed", status(conn));
            conn.send(ClientRun.batch(three, param, factory));
            assertEquals("passed", status(conn));
            assertEquals("passed", status(conn));
            assertEquals("passed", status(conn));
            conn.send(ClientRun.batch(ClientRun.id(cases, "FinishProbe")));
            assertEquals("passed", status(conn));
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            JsonObject report = await(conn, "report_at_finish").getAsJsonObject("data");
            assertEquals(0, report.get("connection_retries").getAsInt());
            assertFalse(report.has("session_fixture_runs"));
            assertTrue(report.get("seconds_in_before_all_hooks").getAsDouble() > 0, report.toString());
            assertTrue(report.get("seconds_in_after_all_hooks").getAsDouble() > 0, report.toString());
            assertEquals(0, run.awaitExit());
            assertEquals(1, ClientRun.count(run.hooks(), "launcher-session-opened"), run.hooks());
            assertEquals(0, ClientRun.count(run.hooks(), "probe-constructor"), run.hooks());
            assertEquals(3, ClientRun.count(run.hooks(), "before-group"));
            assertEquals(3, ClientRun.count(run.hooks(), "after-group"));
            assertEquals(1, ClientRun.count(run.hooks(), "before-param"));
            assertEquals(1, ClientRun.count(run.hooks(), "after-param"));
            assertEquals(2, ClientRun.count(run.hooks(), "dynamic-body"));
            assertTrue(run.finish().contains("finish"));
            assertTrue(run.stdout().contains("hello-notice"));
        }
    }

    @Test
    void conformanceFixtures() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            conn.send(ClientRun.batch(
                    ClientRun.id(cases, "ThrowingFactory"),
                    ClientRun.id(cases, "DisabledClass"),
                    ClientRun.id(cases, "AssumptionBeforeAll"),
                    ClientRun.id(cases, "ThrowingBeforeAll"),
                    ClientRun.id(cases, "ThrowingAfterAll")));
            JsonObject factory = await(conn, "test_case_result");
            JsonObject disabled = await(conn, "test_case_result");
            JsonObject assumption = await(conn, "test_case_result");
            JsonObject before = await(conn, "test_case_result");
            JsonObject after = await(conn, "test_case_result");
            assertEquals("failed", factory.getAsJsonObject("data").get("status").getAsString());
            assertTrue(factory.toString().contains("factory"));
            assertTrue(factory.getAsJsonObject("data").getAsJsonArray("failure_formatted_backtrace").get(0).getAsString().contains("ThrowingFactory"));
            assertEquals("pending", disabled.getAsJsonObject("data").get("status").getAsString());
            assertEquals("pending", assumption.getAsJsonObject("data").get("status").getAsString());
            assertEquals("failed", before.getAsJsonObject("data").get("status").getAsString());
            assertTrue(before.toString().contains("before-all"));
            assertEquals("failed", after.getAsJsonObject("data").get("status").getAsString());
            assertTrue(after.toString().contains("after-all"));
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(1, run.awaitExit());
        }
    }

    @Test
    void removeThenAssignAndCrossRunnerRemove() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            String flake = ClientRun.id(cases, "FlakeCase");
            String before = ClientRun.id(cases, "ThrowingBeforeAll");
            conn.send(ClientRun.batch(flake, before));
            assertEquals("failed", status(conn));
            assertEquals("failed", status(conn));
            conn.send("{\"command\":\"remove_failed_test_case_result\",\"test_case_id\":\"" + flake + "\"}");
            conn.send("{\"command\":\"remove_failed_test_case_result\",\"test_case_id\":\"missing-id\"}");
            conn.send("{\"command\":\"remove_failed_test_case_result\",\"test_case_id\":\"" + before + "\"}");
            conn.send(ClientRun.batch(flake));
            assertEquals("passed", status(conn));
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void failuresSurviveARedialUntilRemoved() throws Exception {
        for (boolean remove : new boolean[] {false, true}) {
            try (ClientRun run = ClientRun.start()) {
                MockServer.Conn first = run.connection();
                assertTrue(first.request().contains("&remove_failed=true"), first.request());
                first.send("{\"command\":\"connected\"}");
                String failing = ClientRun.id(manifest(first), "ThrowingBeforeAll");
                first.send(ClientRun.batch(failing));
                assertEquals("failed", status(first));
                first.send("{\"command\":\"reconnect\"}");
                MockServer.Conn second = run.connection();
                assertTrue(second.request().contains("reconnect=true"), second.request());
                assertTrue(second.request().contains("&remove_failed=true"), second.request());
                second.send("{\"command\":\"connected\"}");
                if (remove) {
                    second.send("{\"command\":\"remove_failed_test_case_result\",\"test_case_id\":\"" + failing + "\"}");
                }
                second.send("{\"command\":\"close\",\"exit_status\":null}");
                await(second, "report_at_finish");
                assertEquals(remove ? 0 : 1, run.awaitExit());
            }
        }
    }

    @Test
    void assignThenAssignReplacesFailure() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            String flake = ClientRun.id(cases, "FlakeCase");
            conn.send(ClientRun.batch(flake));
            assertEquals("failed", status(conn));
            conn.send(ClientRun.batch(flake));
            assertEquals("passed", status(conn));
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void unknownIdFailsAndBatchContinues() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            String one = ClientRun.id(cases, "GroupHooks", "[method:one()]");
            conn.send(ClientRun.batch("not-a-test", one));
            String raw = awaitRaw(conn, "test_case_result");
            assertTrue(raw.contains("\"run_time\":0.0,"), raw);
            JsonObject missing = ClientRun.json(raw);
            JsonObject passed = await(conn, "test_case_result");
            assertEquals("failed", missing.getAsJsonObject("data").get("status").getAsString());
            assertEquals("not-a-test", missing.getAsJsonObject("data").get("id").getAsString());
            assertEquals("", missing.getAsJsonObject("data").get("file_path").getAsString());
            assertEquals("[]", missing.getAsJsonObject("data").getAsJsonArray("failure_formatted_backtrace").toString());
            assertEquals("passed", passed.getAsJsonObject("data").get("status").getAsString());
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(1, run.awaitExit());
        }
    }

    @Test
    void longIdUsesTheSameShortForm() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            String id = ClientRun.id(cases, "LongIdHost", "~");
            assertTrue(id.length() <= 255);
            assertFalse(id.contains("\0"));
            conn.send(ClientRun.batch(id));
            JsonObject result = await(conn, "test_case_result");
            assertEquals(id, result.getAsJsonObject("data").get("id").getAsString());
            assertEquals("passed", result.getAsJsonObject("data").get("status").getAsString());
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void queuedBeforeConnectedSendsNothing() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"test_manifest\"}");
            Thread.sleep(300);
            assertTrue(conn.messagesQuiet());
            conn.send("{\"command\":\"connected\"}");
            await(conn, "test_manifest");
            conn.send("{\"command\":\"close\",\"exit_status\":0,\"message\":\"done\"}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
            assertFalse(run.stdout().contains("done"), run.stdout());
            assertFalse(run.finish().contains("finish"));
        }
    }

    @Test
    void overlapBuffersTheNextBatch() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            String slow = ClientRun.id(cases, "SlowCase");
            String one = ClientRun.id(cases, "GroupHooks", "[method:one()]");
            String fail = ClientRun.id(cases, "ThrowingBeforeAll");
            conn.send(ClientRun.batch(slow));
            conn.send(ClientRun.batch(one));
            conn.send(ClientRun.batch(fail));
            assertEquals("passed", status(conn));
            assertEquals("passed", status(conn));
            assertEquals("failed", status(conn));
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(1, run.awaitExit());
        }
    }

    @Test
    void commandedReconnectDoesNotCount() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn first = run.connection();
            first.send("{\"command\":\"connected\"}");
            manifest(first);
            first.send("{\"command\":\"reconnect\"}");
            MockServer.Conn second = run.connection();
            assertTrue(second.request().contains("reconnect=true"));
            second.send("{\"command\":\"connected\"}");
            second.send("{\"command\":\"close\",\"exit_status\":0}");
            JsonObject report = await(second, "report_at_finish").getAsJsonObject("data");
            assertEquals(0, report.get("connection_retries").getAsInt());
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void socketDropCountsOneRetry() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn first = run.connection();
            first.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(first);
            first.send(ClientRun.batch(ClientRun.id(cases, "GroupHooks", "[method:one()]")));
            assertEquals("passed", status(first));
            first.close();
            MockServer.Conn second = run.connection();
            assertTrue(second.request().contains("reconnect=true"));
            second.send("{\"command\":\"connected\"}");
            second.send("{\"command\":\"close\",\"exit_status\":null}");
            JsonObject report = await(second, "report_at_finish").getAsJsonObject("data");
            assertEquals(1, report.get("connection_retries").getAsInt());
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void runnerIdReachesTheServerUnchanged() throws Exception {
        try (ClientRun run = ClientRun.start("SELECTIVE_RUNNER_ID", "shard 1@ci+x")) {
            MockServer.Conn conn = run.connection();
            assertEquals("shard 1@ci+x", query(conn.request(), "runner_id"), conn.request());
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"close\",\"exit_status\":0}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void closeRejectedSkipsFinishHooks() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"print_notice\",\"message\":\"nope\"}");
            conn.send("{\"command\":\"close\",\"exit_status\":2,\"message\":\"nope\"}");
            await(conn, "report_at_finish");
            assertEquals(2, run.awaitExit());
            assertEquals(1, ClientRun.count(run.stdout(), "nope"), run.stdout());
            assertFalse(run.finish().contains("finish"));
        }
    }

    @Test
    void runCompletedOverridesAFailure() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            conn.send(ClientRun.batch(ClientRun.id(cases, "ThrowingBeforeAll")));
            assertEquals("failed", status(conn));
            conn.send("{\"command\":\"close\",\"exit_status\":0}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
            assertFalse(run.finish().contains("finish"));
        }
    }

    @Test
    void termDuringAHungTestSendsTerminating() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            conn.send(ClientRun.batch(ClientRun.id(cases, "HangProbe")));
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!run.hooks().contains("hang-start")) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("hang did not start\n" + run.stderr());
                }
                Thread.sleep(50);
            }
            run.process.destroy();
            String frame = conn.awaitMessage(4_000);
            assertTrue(frame.contains("terminating"), frame);
            assertTrue(run.process.waitFor(4, TimeUnit.SECONDS), run.stderr());
        }
    }

    @Test
    void sigtermAfterFailureExitsOne() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            JsonArray cases = manifest(conn);
            conn.send(ClientRun.batch(ClientRun.id(cases, "ThrowingBeforeAll")));
            assertEquals("failed", status(conn));
            run.process.destroy();
            assertEquals(1, run.awaitExit());
        }
    }

    @Test
    void unparseableFrameExitsOne() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            manifest(conn);
            conn.send("{this is not json");
            assertEquals(1, run.awaitExit());
            assertTrue(run.stderr().contains("selective received a frame it cannot parse: {this is not json"), run.stderr());
        }
    }

    @Test
    void blankFrameIsSkipped() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            conn.send(" ");
            conn.send("{\"command\":\"close\",\"exit_status\":0}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void unknownCommandIsIgnored() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"nope\"}");
            conn.send("{\"command\":\"close\",\"exit_status\":0}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void debugUnknownCommandExitsOne() throws Exception {
        try (ClientRun run = ClientRun.start("SELECTIVE_DEBUG", "1")) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"nope\"}");
            assertEquals(1, run.awaitExit());
            assertTrue(run.stderr().contains("unknown command"));
        }
    }

    @Test
    void recordRequestWarnsOnceAndUploadsNothing() throws Exception {
        try (ClientRun run = ClientRun.start()) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"configure_test_map\",\"tree\":true,\"record\":true}");
            conn.send("{\"command\":\"configure_test_map\",\"tree\":true,\"record\":true}");
            conn.send("{\"command\":\"test_manifest\"}");
            JsonObject manifest = await(conn, "test_manifest");
            JsonObject data = manifest.getAsJsonObject("data");
            JsonObject tree = data.getAsJsonObject("test_map_tree");
            assertTrue(tree.has("sha"));
            assertTrue(tree.has("prefix"));
            assertTrue(tree.get("files").isJsonObject());
            assertTrue(tree.get("dirty").isJsonArray());
            conn.send(ClientRun.batch(ClientRun.id(data.getAsJsonArray("test_cases"), "GroupHooks", "[method:one()]")));
            assertEquals("passed", status(conn));
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
            assertEquals(1, ClientRun.count(run.stderr(), "no supported JaCoCo agent is attached"), run.stderr());
            assertTrue(run.server.posts().isEmpty(), run.server.posts().toString());
        }
    }

    @Test
    void recordingUploadsEachTestsSourceFiles() throws Exception {
        try (ClientRun run = ClientRun.startRecording()) {
            MockServer.Conn conn = run.connection();
            assertTrue(conn.request().contains("&test_map=record&"), conn.request());
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"configure_test_map\",\"tree\":false,\"record\":true}");
            JsonArray cases = manifest(conn);
            String group = ClientRun.id(cases, "GroupHooks", "[method:one()]");
            String param = ClientRun.id(cases, "ParameterizedHooks", "[test-template:");
            conn.send(ClientRun.batch(group, param));
            assertEquals("passed", status(conn));
            assertEquals("passed", status(conn));
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit(), run.stderr());
            assertEquals(1, run.server.posts().size(), run.stderr());
            assertTrue(run.server.posts().get(0).startsWith("POST /test_maps/fragments?run_id=run-1&run_attempt=1&runner_id=runner-1 "), run.server.posts().get(0));
            JsonObject fragment;
            try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(run.server.bodies().get(0)))) {
                fragment = ClientRun.json(new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            JsonArray files = fragment.getAsJsonArray("files");
            java.util.Map<String, java.util.Set<String>> deps = new java.util.HashMap<>();
            for (com.google.gson.JsonElement test : fragment.getAsJsonArray("tests")) {
                java.util.Set<String> paths = new java.util.HashSet<>();
                test.getAsJsonObject().getAsJsonArray("deps").forEach(index -> paths.add(files.get(index.getAsInt()).getAsString()));
                deps.put(test.getAsJsonObject().get("id").getAsString(), paths);
            }
            String fixtures = "suite/src/test/java/ci/selective/junit/fixtures/";
            assertEquals(java.util.Set.of(group, param), deps.keySet());
            assertEquals(java.util.Set.of(fixtures + "GroupHooks.java", fixtures + "Counters.java"), deps.get(group));
            assertEquals(java.util.Set.of(fixtures + "ParameterizedHooks.java", fixtures + "Counters.java"), deps.get(param));
        }
    }

    @Test
    void disabledTestMapOmitsTheTree() throws Exception {
        try (ClientRun run = ClientRun.start("SELECTIVE_TEST_MAP_DISABLE", "1")) {
            MockServer.Conn conn = run.connection();
            assertTrue(conn.request().contains("test_map=0"));
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"configure_test_map\",\"tree\":true,\"record\":false}");
            conn.send("{\"command\":\"test_manifest\"}");
            JsonObject manifest = await(conn, "test_manifest");
            assertFalse(manifest.getAsJsonObject("data").has("test_map_tree"));
            conn.send("{\"command\":\"close\",\"exit_status\":0}");
            await(conn, "report_at_finish");
            assertEquals(0, run.awaitExit());
        }
    }

    @Test
    void refusedConnectionsGiveUp() throws Exception {
        try (ClientRun run = ClientRun.startRejected()) {
            assertEquals(1, run.awaitExit());
            assertTrue(run.server.accepts() >= 11, "accepts=" + run.server.accepts() + "\n" + run.stderr());
        }
    }

    private static String query(String request, String name) {
        String target = request.split(" ")[1];
        for (String pair : target.substring(target.indexOf('?') + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static JsonArray manifest(MockServer.Conn conn) throws Exception {
        conn.send("{\"command\":\"test_manifest\"}");
        return await(conn, "test_manifest").getAsJsonObject("data").getAsJsonArray("test_cases");
    }

    private static String status(MockServer.Conn conn) throws Exception {
        return await(conn, "test_case_result").getAsJsonObject("data").get("status").getAsString();
    }

    private static JsonObject await(MockServer.Conn conn, String type) throws Exception {
        return ClientRun.json(awaitRaw(conn, type));
    }

    private static String awaitRaw(MockServer.Conn conn, String type) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        List<String> seen = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            String text = conn.awaitMessage(20_000);
            if (text.isEmpty()) {
                continue;
            }
            seen.add(text);
            if (type.equals(ClientRun.json(text).get("type").getAsString())) {
                return text;
            }
        }
        throw new IllegalStateException("missing " + type + " " + seen);
    }

}
