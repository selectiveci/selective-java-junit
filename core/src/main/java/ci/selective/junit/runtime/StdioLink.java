package ci.selective.junit.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class StdioLink implements AutoCloseable {
    private static final Object LOST = new Object();

    private final Process process;
    private final OutputStream stdin;
    private final ReentrantLock stdinLock = new ReentrantLock();
    private final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    private StdioLink(Process process) {
        this.process = process;
        this.stdin = process.getOutputStream();
        Thread reader = new Thread(this::readStdout, "selective-transport-stdout");
        reader.setDaemon(true);
        reader.start();
        Thread errors = new Thread(() -> drain(process.getErrorStream()), "selective-transport-stderr");
        errors.setDaemon(true);
        errors.start();
    }

    public static StdioLink open(Path binary, String url) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), "--stdio");
        builder.environment().put("SELECTIVE_TRANSPORT_URL", url);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        return new StdioLink(builder.start());
    }

    static StdioLink open(Process process) {
        return new StdioLink(process);
    }

    public void send(String json) throws ConnectionLost {
        try {
            stdinLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConnectionLost(exception);
        }
        try {
            stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException exception) {
            throw new ConnectionLost(exception);
        } finally {
            stdinLock.unlock();
        }
    }

    public boolean trySend(String json) {
        if (!stdinLock.tryLock()) {
            return false;
        }
        try {
            stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        } catch (IOException exception) {
            return false;
        } finally {
            stdinLock.unlock();
        }
    }

    public String poll(long timeoutMs) throws InterruptedException, ConnectionLost {
        Object item = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (item == LOST) {
            throw new ConnectionLost();
        }
        return (String) item;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            send("exit");
        } catch (ConnectionLost ignored) {
            process.destroy();
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                inbox.offer(line);
            }
        } catch (IOException ignored) {
        } finally {
            inbox.offer(LOST);
        }
    }

    private static void drain(java.io.InputStream stream) {
        try {
            stream.transferTo(System.err);
        } catch (IOException ignored) {
        }
    }

    public static final class ConnectionLost extends Exception {
        public ConnectionLost(Throwable cause) {
            super(cause);
        }

        public ConnectionLost() {}
    }
}
