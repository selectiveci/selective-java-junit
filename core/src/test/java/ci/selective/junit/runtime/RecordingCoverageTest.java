package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ci.selective.junit.support.ClientRun;
import ci.selective.junit.support.MockServer;

public class RecordingCoverageTest {
    @Test
    void aRecordingRunLeavesTheUsersCoverageInTheExecFile(@TempDir Path dir) throws Exception {
        Path exec = dir.resolve("jacoco.exec");
        try (ClientRun run = ClientRun.startRecording("output=file,destfile=" + exec)) {
            MockServer.Conn conn = run.connection();
            conn.send("{\"command\":\"connected\"}");
            conn.send("{\"command\":\"configure_test_map\",\"tree\":false,\"record\":true}");
            conn.send("{\"command\":\"test_manifest\"}");
            JsonArray cases = next(conn, "test_manifest").getAsJsonObject("data").getAsJsonArray("test_cases");
            conn.send(ClientRun.batch(
                    ClientRun.id(cases, "GroupHooks", "[method:one()]"),
                    ClientRun.id(cases, "ParameterizedHooks", "[test-template:")));
            next(conn, "test_case_result");
            next(conn, "test_case_result");
            conn.send("{\"command\":\"close\",\"exit_status\":null}");
            next(conn, "report_at_finish");
            assertEquals(0, run.awaitExit(), run.stderr());
            assertEquals(1, run.server.posts().size(), run.stderr());
        }
        Set<String> covered = Recorder.hits(Files.readAllBytes(exec)).stream()
                .map(Recorder.Probes::name)
                .collect(Collectors.toSet());
        String fixtures = "ci/selective/junit/fixtures/";
        assertTrue(
                covered.containsAll(Set.of(fixtures + "GroupHooks", fixtures + "ParameterizedHooks", fixtures + "Counters")),
                covered.toString());
    }

    private static JsonObject next(MockServer.Conn conn, String type) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            String text = conn.awaitMessage(20_000);
            if (!text.isEmpty() && type.equals(ClientRun.json(text).get("type").getAsString())) {
                return ClientRun.json(text);
            }
        }
        throw new IllegalStateException("missing " + type);
    }
}
