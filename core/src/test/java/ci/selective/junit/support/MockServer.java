package ci.selective.junit.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MockServer implements AutoCloseable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final BlockingQueue<Conn> connections = new LinkedBlockingQueue<>();
    private final List<String> posts = new CopyOnWriteArrayList<>();
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepts = new AtomicInteger();
    public final AtomicInteger pings = new AtomicInteger();
    public final AtomicBoolean ignorePongs = new AtomicBoolean();
    private boolean sawConnection;
    public volatile boolean reject;

    public MockServer() throws IOException {
        server = new ServerSocket(0);
        Thread thread = new Thread(this::acceptLoop, "selective-mock");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public int accepts() {
        return accepts.get();
    }

    public List<String> posts() {
        return posts;
    }

    public List<byte[]> bodies() {
        return bodies;
    }

    public Conn awaitConnection(long timeoutMs) throws InterruptedException {
        Conn conn = connections.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (conn == null) {
            throw new IllegalStateException("timed out waiting for a connection");
        }
        return conn;
    }

    @Override
    public void close() {
        open.set(false);
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (open.get()) {
            try {
                Socket socket = server.accept();
                accepts.incrementAndGet();
                socket.setTcpNoDelay(true);
                Thread handler = new Thread(() -> handle(socket), "selective-mock-conn");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException exception) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            String head = readHeaders(input);
            if (head.startsWith("POST ")) {
                bodies.add(input.readNBytes(contentLength(head)));
                posts.add(head);
                output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                socket.close();
                return;
            }
            if (reject) {
                socket.close();
                return;
            }
            String key = header(head, "sec-websocket-key");
            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.UTF_8)));
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
            if (sawConnection) {
                ignorePongs.set(false);
            }
            sawConnection = true;
            Conn conn = new Conn(socket, output, head, ignorePongs, pings);
            connections.offer(conn);
            conn.readLoop(input);
        } catch (Exception exception) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            buffer.write(value);
            byte[] bytes = buffer.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') {
                break;
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String header(String head, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : head.split("\\R")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static int contentLength(String head) {
        String value = header(head, "content-length");
        if (value.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(value.trim());
    }

    public static final class Conn {
        private final Socket socket;
        private final OutputStream output;
        private final String request;
        private final AtomicBoolean ignorePongs;
        private final AtomicInteger pings;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        Conn(Socket socket, OutputStream output, String request, AtomicBoolean ignorePongs, AtomicInteger pings) {
            this.socket = socket;
            this.output = output;
            this.request = request;
            this.ignorePongs = ignorePongs;
            this.pings = pings;
        }

        public String request() {
            return request;
        }

        public boolean messagesQuiet() {
            return messages.isEmpty();
        }

        public void send(String text) throws IOException {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            synchronized (output) {
                output.write(0x81);
                if (data.length < 126) {
                    output.write(data.length);
                } else if (data.length < 65536) {
                    output.write(126);
                    output.write((data.length >> 8) & 0xff);
                    output.write(data.length & 0xff);
                } else {
                    output.write(127);
                    long length = data.length;
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        output.write((int) ((length >> shift) & 0xff));
                    }
                }
                output.write(data);
                output.flush();
            }
        }

        public String awaitMessage(long timeoutMs) throws InterruptedException {
            String message = poll(timeoutMs);
            if (message == null) {
                throw new IllegalStateException("timed out waiting for a client frame");
            }
            return message;
        }

        public String poll(long timeoutMs) throws InterruptedException {
            return messages.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        void readLoop(InputStream input) {
            try {
                ByteArrayOutputStream message = new ByteArrayOutputStream();
                int opcode = -1;
                while (true) {
                    int first = input.read();
                    int second = input.read();
                    if (first < 0 || second < 0) {
                        break;
                    }
                    boolean fin = (first & 0x80) != 0;
                    int op = first & 0x0f;
                    if (opcode < 0 || op != 0) {
                        opcode = op;
                        message.reset();
                    }
                    boolean masked = (second & 0x80) != 0;
                    long length = second & 0x7f;
                    if (length == 126) {
                        length = ((input.read() & 0xff) << 8) | (input.read() & 0xff);
                    } else if (length == 127) {
                        length = 0;
                        for (int i = 0; i < 8; i++) {
                            length = (length << 8) | (input.read() & 0xff);
                        }
                    }
                    byte[] mask = masked ? input.readNBytes(4) : new byte[0];
                    byte[] payload = input.readNBytes((int) length);
                    if (masked) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= mask[i & 3];
                        }
                    }
                    if (opcode == 9) {
                        pings.incrementAndGet();
                        if (!ignorePongs.get()) {
                            sendPong(payload);
                        }
                        opcode = -1;
                        continue;
                    }
                    if (opcode == 8) {
                        break;
                    }
                    if (opcode == 1 || opcode == 0) {
                        message.write(payload);
                        if (fin) {
                            messages.offer(message.toString(StandardCharsets.UTF_8));
                            opcode = -1;
                        }
                    }
                }
            } catch (IOException ignored) {
                messages.offer("");
            }
        }

        private void sendPong(byte[] payload) throws IOException {
            synchronized (output) {
                output.write(0x8a);
                output.write(payload.length);
                output.write(payload);
                output.flush();
            }
        }
    }
}
