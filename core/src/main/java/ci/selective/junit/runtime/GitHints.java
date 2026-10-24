package ci.selective.junit.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class GitHints {
    static final int COMMITS = 1000;
    private static final String COMMIT_MARKER = "--commit--";

    private GitHints() {}

    public static JsonObject snapshot(Path binary, Path cwd) {
        try {
            String json = Binary.capture(binary, "--snapshot", cwd.toString());
            if (json.isEmpty()) {
                return null;
            }
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (IOException | InterruptedException exception) {
            interrupted(exception);
            System.err.println("selective omitted the test map tree (" + exception.getMessage() + ")");
            return null;
        }
    }

    public static void addDiffHints(JsonObject data, Path cwd, String branch) {
        if (branch.isEmpty()) {
            return;
        }
        try {
            git(cwd, "fetch", "origin", "+refs/heads/" + branch + ":refs/remotes/origin/" + branch, "--depth=" + COMMITS);
        } catch (IOException | InterruptedException exception) {
            interrupted(exception);
            System.err.println("Selective was unable to fetch the target branch. This may result in a sub-optimal test order. ("
                    + exception.getMessage() + ")");
            return;
        }
        String prefix;
        List<String> diff;
        try {
            prefix = git(cwd, "rev-parse", "--show-prefix");
            diff = git(cwd, "diff", "origin/" + branch, "--name-only").lines().filter(line -> !line.isEmpty()).toList();
        } catch (IOException | InterruptedException exception) {
            interrupted(exception);
            System.err.println("Selective was unable to diff with the target branch. This may result in a sub-optimal test order. ("
                    + exception.getMessage() + ")");
            return;
        }
        JsonArray modified = new JsonArray();
        Set<String> code = new LinkedHashSet<>();
        for (String path : diff) {
            if (!testDirectory(path)) {
                code.add(path);
            } else if (path.startsWith(prefix)) {
                modified.add(path.substring(prefix.length()));
            }
        }
        data.add("modified_test_files", modified);
        try {
            String log = git(cwd, "-c", "log.showRoot=false", "log", "origin/" + branch, "--no-merges", "--reverse",
                    "-n", String.valueOf(COMMITS), "--name-only", "--pretty=tformat:" + COMMIT_MARKER);
            data.add("correlated_files", correlate(commits(log), code, prefix));
        } catch (IOException | InterruptedException exception) {
            interrupted(exception);
            System.err.println("Selective was unable to correlate the diff to test files. This may result in a sub-optimal test order. ("
                    + exception.getMessage() + ")");
            data.add("correlated_files", JsonNull.INSTANCE);
        }
    }

    static List<List<String>> commits(String log) {
        List<List<String>> commits = new ArrayList<>();
        for (String line : log.lines().toList()) {
            if (line.equals(COMMIT_MARKER)) {
                commits.add(new ArrayList<>());
            } else if (!line.isEmpty() && !commits.isEmpty()) {
                commits.get(commits.size() - 1).add(line);
            }
        }
        return commits;
    }

    static JsonObject correlate(List<List<String>> commits, Set<String> code, String prefix) {
        Map<String, Map<String, Integer>> pairs = new LinkedHashMap<>();
        Map<String, Integer> alone = new LinkedHashMap<>();
        List<String> previous = List.of();
        for (List<String> files : commits) {
            Set<String> correlated = new LinkedHashSet<>();
            for (List<String> changed : List.of(previous, files)) {
                for (String file : changed) {
                    if (!code.contains(file)) {
                        continue;
                    }
                    for (String test : files) {
                        if (testSource(test)) {
                            pairs.computeIfAbsent(file, key -> new LinkedHashMap<>()).merge(test, 1, Integer::sum);
                            correlated.add(test);
                        }
                    }
                }
            }
            for (String test : files) {
                if (testSource(test) && !correlated.contains(test)) {
                    alone.merge(test, 1, Integer::sum);
                }
            }
            previous = files;
        }
        JsonObject correlatedFiles = new JsonObject();
        pairs.forEach((file, tests) -> {
            JsonObject local = local(tests, prefix);
            if (local.size() > 0) {
                correlatedFiles.add(file, local);
            }
        });
        JsonObject body = new JsonObject();
        body.add("correlated_files", correlatedFiles);
        body.add("uncorrelated_files", local(alone, prefix));
        return body;
    }

    private static JsonObject local(Map<String, Integer> tests, String prefix) {
        JsonObject local = new JsonObject();
        tests.forEach((test, count) -> {
            if (test.startsWith(prefix)) {
                local.addProperty(test.substring(prefix.length()), count);
            }
        });
        return local;
    }

    private static boolean testDirectory(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    private static boolean testSource(String path) {
        return testDirectory(path) && (path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy"));
    }

    private static void interrupted(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String git(Path cwd, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 2];
        command[0] = "-C";
        command[1] = cwd.toString();
        System.arraycopy(args, 0, command, 2, args.length);
        return Binary.capture(Path.of("git"), command);
    }
}
