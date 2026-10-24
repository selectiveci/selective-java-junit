package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GitHintsTest {
    private static final String CODE = "app/src/main/java/A.java";
    private static final String A_TEST = "app/src/test/java/ATest.java";
    private static final String B_TEST = "app/src/test/java/BTest.java";
    private static final String OTHER_TEST = "lib/src/test/java/LibTest.java";

    @Test
    void countsTestsChangedWithOrRightAfterADiffFile() {
        List<List<String>> commits = List.of(
                List.of(CODE, A_TEST),
                List.of(B_TEST),
                List.of("app/src/main/java/B.java", B_TEST),
                List.of(CODE, A_TEST, OTHER_TEST),
                List.of("app/src/test/resources/data.json"));
        JsonObject hints = GitHints.correlate(commits, Set.of(CODE), "");
        assertEquals(JsonParser.parseString(
                "{\"correlated_files\":{\"" + CODE + "\":{\"" + A_TEST + "\":2,\"" + B_TEST + "\":1,\"" + OTHER_TEST + "\":1}},"
                        + "\"uncorrelated_files\":{\"" + B_TEST + "\":1}}"), hints);
    }

    @Test
    void keepsOnlyThisModulesTestsRelativeToIt() {
        JsonObject hints = GitHints.correlate(List.of(List.of(CODE, A_TEST, OTHER_TEST)), Set.of(CODE), "app/");
        assertEquals(JsonParser.parseString(
                "{\"correlated_files\":{\"" + CODE + "\":{\"src/test/java/ATest.java\":1}},\"uncorrelated_files\":{}}"), hints);
        JsonObject elsewhere = GitHints.correlate(List.of(List.of(CODE, OTHER_TEST)), Set.of(CODE), "app/");
        assertEquals(0, elsewhere.getAsJsonObject("correlated_files").size());
    }

    @Test
    void parsesOneListPerCommit() {
        assertEquals(List.of(List.of("a", "b"), List.of(), List.of("c")),
                GitHints.commits("--commit--\n\na\nb\n--commit--\n--commit--\n\nc\n"));
    }

    @Test
    void diffHintsFromAShallowModuleCheckout(@TempDir Path root) throws Exception {
        Path origin = root.resolve("origin");
        git(root, "init", "-q", "-b", "main", origin.toString());
        write(origin, CODE, "1");
        write(origin, A_TEST, "1");
        commit(origin, "base");
        write(origin, CODE, "2");
        write(origin, A_TEST, "2");
        commit(origin, "together");
        git(origin, "checkout", "-q", "-b", "feature");
        write(origin, CODE, "3");
        write(origin, B_TEST, "1");
        commit(origin, "feature");
        Path clone = root.resolve("clone");
        git(root, "clone", "-q", "--depth", "1", "--branch", "feature", "file://" + origin, clone.toString());
        JsonObject data = new JsonObject();
        GitHints.addDiffHints(data, clone.resolve("app"), "main");
        assertEquals(JsonParser.parseString("[\"src/test/java/BTest.java\"]"), data.get("modified_test_files"));
        JsonObject correlated = data.getAsJsonObject("correlated_files").getAsJsonObject("correlated_files");
        assertEquals(Set.of(CODE), correlated.keySet());
        assertTrue(correlated.getAsJsonObject(CODE).has("src/test/java/ATest.java"), data.toString());
    }

    private static void write(Path repo, String path, String body) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", message);
    }

    private static void git(Path cwd, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = cwd.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), String.join(" ", command) + "\n" + output);
    }
}
