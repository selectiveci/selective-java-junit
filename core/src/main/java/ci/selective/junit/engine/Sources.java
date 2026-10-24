package ci.selective.junit.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Sources {
    private final Path cwd;
    private final Map<String, String> files = new HashMap<>();

    public Sources(Path cwd) {
        this.cwd = cwd;
        index();
    }

    static String outer(String className) {
        int dollar = className.indexOf('$');
        return dollar > 0 ? className.substring(0, dollar) : className;
    }

    public String file(String className) {
        String found = located(className);
        return found == null ? "" : found;
    }

    public String located(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        return files.get(outer(className));
    }

    public Integer line(String binaryName, String method) {
        String path = located(binaryName);
        if (path == null || method == null || method.isEmpty()) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(cwd.resolve(path));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        int from = 0;
        int dollar = binaryName.lastIndexOf('$');
        if (dollar > 0) {
            Pattern nested = Pattern.compile("\\b(class|object)\\s+" + Pattern.quote(binaryName.substring(dollar + 1)) + "\\b");
            while (from < lines.size() && !nested.matcher(lines.get(from)).find()) {
                from++;
            }
        }
        Pattern declaration = Pattern.compile("(\\bvoid|\\bfun|>|\\b[A-Z][\\w.]*(\\[\\])?)\\s+" + Pattern.quote(method) + "\\s*\\(");
        for (int index = from; index < lines.size(); index++) {
            if (declaration.matcher(lines.get(index)).find()) {
                return index + 1;
            }
        }
        return null;
    }

    private void index() {
        if (!Files.isDirectory(cwd)) {
            return;
        }
        try {
            Files.walkFileTree(cwd, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (dir.equals(cwd)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (name.equals("target") || name.equals(".git") || name.equals(".m2") || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java") || name.endsWith(".kt")) {
                        String relative = cwd.relativize(file).toString().replace('\\', '/');
                        String className = className(relative);
                        if (className != null) {
                            files.putIfAbsent(className, relative);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            System.err.println("selective could not index sources (" + exception.getMessage() + ")");
        }
    }

    private static String className(String relative) {
        for (String root : List.of("src/test/java/", "src/main/java/", "src/test/kotlin/", "src/main/kotlin/")) {
            int marker = relative.indexOf(root);
            if (marker >= 0) {
                String body = relative.substring(marker + root.length());
                return body.substring(0, body.lastIndexOf('.')).replace('/', '.');
            }
        }
        return null;
    }
}
