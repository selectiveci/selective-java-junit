package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class Utf8CaptureTest {
    public static void main(String[] args) throws Exception {
        String text = Binary.capture(Path.of(args[0]));
        System.out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void captureUsesUtf8WhenThePlatformCharsetDoesNot() throws Exception {
        Path script = Files.createTempFile("cafe", ".sh");
        Files.writeString(script, "#!/bin/sh\nprintf 'caf\\303\\251\\n'\n");
        script.toFile().setExecutable(true);
        String javaHome = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                        javaHome,
                        "-Dfile.encoding=US-ASCII",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "ci.selective.junit.runtime.Utf8CaptureTest",
                        script.toString())
                .start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertEquals(0, code, err);
        assertEquals("café", text, err);
    }
}
