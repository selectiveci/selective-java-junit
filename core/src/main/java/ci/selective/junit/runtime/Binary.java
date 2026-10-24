package ci.selective.junit.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class Binary {
    private static final String RELEASES = "https://github.com/selectiveci/transport/releases/download";

    private Binary() {}

    public static Path resolve() {
        String override = System.getenv("SELECTIVE_TRANSPORT");
        if (override != null && !override.isEmpty()) {
            Path path = Path.of(override);
            if (!Files.isExecutable(path)) {
                throw new MissingBinary("SELECTIVE_TRANSPORT is not an executable file: " + path);
            }
            return path;
        }
        String releases = System.getenv("SELECTIVE_TRANSPORT_RELEASES");
        return install(
                Pin.load(),
                target(System.getProperty("os.name"), System.getProperty("os.arch")),
                Path.of(System.getProperty("user.home"), ".selective", "transport"),
                releases == null || releases.isEmpty() ? RELEASES : releases);
    }

    static Path install(Pin pin, String target, Path cacheRoot, String releases) {
        Path binary = cacheRoot.resolve(pin.version()).resolve("transport-" + target);
        if (Files.isExecutable(binary)) {
            return binary;
        }
        String expected = pin.sha256().get(target);
        if (expected == null) {
            throw new MissingBinary("selective has no transport for " + target + " in release " + pin.version()
                    + ". Set SELECTIVE_TRANSPORT to a transport binary.");
        }
        String url = releases + "/v" + pin.version() + "/transport-" + target + ".tar.gz";
        try {
            Files.createDirectories(binary.getParent());
            Path work = Files.createTempDirectory(binary.getParent(), "download-");
            try {
                Path archive = work.resolve("transport.tar.gz");
                download(url, archive);
                String actual = sha256(archive);
                if (!actual.equals(expected)) {
                    throw new MissingBinary("selective refused " + url + ": sha256 " + actual + ", expected " + expected);
                }
                capture(Path.of("tar"), "-xzf", archive.toString(), "-C", work.toString());
                Path extracted = work.resolve("transport");
                extracted.toFile().setExecutable(true);
                Files.move(extracted, binary, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try (var files = Files.walk(work)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MissingBinary("selective could not install the transport from " + url + " (" + exception.getMessage()
                    + "). Set SELECTIVE_TRANSPORT to a transport binary to skip the download.");
        }
        return binary;
    }

    static String target(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = "aarch64".equals(osArch) || "arm64".equals(osArch) ? "aarch64" : "x86_64";
        if (os.contains("win")) {
            throw new MissingBinary("Windows is not a supported platform for selective");
        }
        return (os.contains("mac") ? "Darwin-" : "Linux-") + arch;
    }

    private static void download(String url, Path archive) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<Path> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofFile(archive));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Pin(String version, Map<String, String> sha256) {
        static Pin load() {
            Properties properties = new Properties();
            try (InputStream input = Binary.class.getResourceAsStream("/ci/selective/junit/transport.properties")) {
                properties.load(input);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            Map<String, String> sha256 = new HashMap<>();
            for (String name : properties.stringPropertyNames()) {
                if (name.startsWith("sha256.")) {
                    sha256.put(name.substring("sha256.".length()), properties.getProperty(name));
                }
            }
            return new Pin(properties.getProperty("version"), sha256);
        }
    }

    public static final class MissingBinary extends RuntimeException {
        public MissingBinary(String message) {
            super(message);
        }
    }

    public static String capture(Path binary, String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command(binary, args)).redirectErrorStream(false).start();
        StringBuilder stderr = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                stderr.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }, "selective-capture-stderr");
        drain.setDaemon(true);
        drain.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        drain.join(2000);
        if (code != 0) {
            throw new IOException(String.join(" ", args) + " exited " + code + " " + stderr.toString().trim());
        }
        return stdout.trim();
    }

    private static String[] command(Path binary, String[] args) {
        String[] command = new String[args.length + 1];
        command[0] = binary.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        return command;
    }
}
