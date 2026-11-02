package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

public class BinaryTest {
    @Test
    void downloadsVerifiesAndCachesOncePerVersionAndTarget(@TempDir Path root) throws Exception {
        byte[] archive = archive(root);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve(archive, requests);
        try {
            String releases = "http://127.0.0.1:" + server.getAddress().getPort() + "/releases";
            Binary.Pin pin = new Binary.Pin("9.9.9", Map.of("Linux-x86_64", sha256(archive)));
            Path installed = Binary.install(pin, "Linux-x86_64", root.resolve("cache"), releases);
            assertEquals(root.resolve("cache/9.9.9/transport-Linux-x86_64"), installed);
            assertEquals("installed-transport", Binary.capture(installed));
            assertEquals(installed, Binary.install(pin, "Linux-x86_64", root.resolve("cache"), releases));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesAnArchiveWithTheWrongChecksum(@TempDir Path root) throws Exception {
        byte[] archive = archive(root);
        HttpServer server = serve(archive, new AtomicInteger());
        try {
            String releases = "http://127.0.0.1:" + server.getAddress().getPort() + "/releases";
            Binary.Pin pin = new Binary.Pin("9.9.9", Map.of("Linux-x86_64", "0".repeat(64)));
            Binary.MissingBinary refused = assertThrows(Binary.MissingBinary.class,
                    () -> Binary.install(pin, "Linux-x86_64", root.resolve("cache"), releases));
            assertTrue(refused.getMessage().contains("sha256 " + sha256(archive)), refused.getMessage());
            assertFalse(Files.exists(root.resolve("cache/9.9.9/transport-Linux-x86_64")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void namesTheReleaseTargets() {
        assertEquals("Linux-x86_64", Binary.target("Linux", "amd64"));
        assertEquals("Linux-aarch64", Binary.target("Linux", "aarch64"));
        assertEquals("Darwin-aarch64", Binary.target("Mac OS X", "aarch64"));
        assertEquals("Darwin-x86_64", Binary.target("Mac OS X", "x86_64"));
        assertThrows(Binary.MissingBinary.class, () -> Binary.target("Windows 11", "amd64"));
    }

    @Test
    void thePinShipsEveryReleaseTarget() {
        Binary.Pin pin = Binary.Pin.load();
        assertEquals("0.2.0", pin.version());
        assertEquals(Set.of("Darwin-aarch64", "Darwin-x86_64", "Linux-aarch64", "Linux-x86_64"), pin.sha256().keySet());
        pin.sha256().values().forEach(hash -> assertTrue(hash.matches("[0-9a-f]{64}"), hash));
    }

    @Test
    void aTargetWithoutABuildNamesTheOverride(@TempDir Path root) {
        Binary.Pin pin = new Binary.Pin("0.0.0-test", Map.of("Linux-x86_64", "0".repeat(64)));
        Binary.MissingBinary missing = assertThrows(
                Binary.MissingBinary.class, () -> Binary.install(pin, "Darwin-aarch64", root, "http://127.0.0.1:9"));
        assertEquals(
                "selective has no transport for Darwin-aarch64 in release 0.0.0-test. Set SELECTIVE_TRANSPORT to a transport binary.",
                missing.getMessage());
    }

    private static byte[] archive(Path root) throws Exception {
        Path stage = Files.createDirectories(root.resolve("stage"));
        Path transport = stage.resolve("transport");
        Files.writeString(transport, "#!/bin/sh\necho installed-transport\n");
        transport.toFile().setExecutable(true);
        Path archive = root.resolve("transport.tar.gz");
        Binary.capture(Path.of("tar"), "-czf", archive.toString(), "-C", stage.toString(), "transport");
        return Files.readAllBytes(archive);
    }

    private static HttpServer serve(byte[] archive, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/v9.9.9/transport-Linux-x86_64.tar.gz", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
