package ci.selective.junit.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class Recorder {
    private static final String CLIENT = Recorder.class.getProtectionDomain().getCodeSource().getLocation().toString();

    private final Jacoco jacoco;
    private final ClassLoader loader;
    private final Path root;
    private final List<String> tracked;
    private final Map<String, List<String>> sources = new HashMap<>();
    private final Map<String, Set<String>> deps = new LinkedHashMap<>();
    private final Map<Long, Probes> coverage = new LinkedHashMap<>();

    private Recorder(Jacoco jacoco, ClassLoader loader, Path root, List<String> tracked) {
        this.jacoco = jacoco;
        this.loader = loader;
        this.root = root;
        this.tracked = tracked;
    }

    public static boolean attached() {
        return jacoco().isPresent();
    }

    static Recorder start(ClassLoader loader, Path cwd) throws IOException, InterruptedException {
        Jacoco jacoco = jacoco().orElseThrow();
        Path root = Path.of(Binary.capture(Path.of("git"), "-C", cwd.toString(), "rev-parse", "--show-toplevel"));
        List<String> tracked = List.of(Binary.capture(Path.of("git"), "-C", root.toString(), "ls-files", "-z").split("\0"));
        return new Recorder(jacoco, loader, root.toRealPath(), tracked);
    }

    private static Optional<Jacoco> jacoco() {
        Object agent;
        try {
            agent = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
        } catch (ClassNotFoundException | InvocationTargetException absent) {
            return Optional.empty();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        try {
            Method executionData = Class.forName("org.jacoco.agent.rt.IAgent").getMethod("getExecutionData", boolean.class);
            Object runtime = agent.getClass().getMethod("getData").invoke(agent);
            Method live = runtime.getClass().getMethod("getExecutionData", Long.class, String.class, int.class);
            return Optional.of(new Jacoco(agent, executionData, runtime, live, live.getReturnType().getMethod("getProbes")));
        } catch (NoSuchMethodException unsupported) {
            return Optional.empty();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    void reset() {
        dump();
    }

    void restore() {
        try {
            for (Map.Entry<Long, Probes> entry : coverage.entrySet()) {
                boolean[] merged = entry.getValue().hits();
                Object data = jacoco.live().invoke(jacoco.runtime(), entry.getKey(), entry.getValue().name(), merged.length);
                boolean[] target = (boolean[]) jacoco.probes().invoke(data);
                for (int i = 0; i < merged.length; i++) {
                    target[i] |= merged[i];
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("selective could not hand the recorded coverage back to JaCoCo", exception);
        }
    }

    void record(String id) {
        Set<String> files = deps.computeIfAbsent(id, key -> new TreeSet<>());
        for (Probes hit : dump()) {
            files.addAll(sources.computeIfAbsent(hit.name(), this::locate));
        }
    }

    boolean empty() {
        return deps.isEmpty();
    }

    byte[] fragment() throws IOException {
        List<String> files = new ArrayList<>(new TreeSet<>(deps.values().stream().flatMap(Set::stream).toList()));
        JsonArray tests = new JsonArray();
        deps.forEach((id, paths) -> {
            JsonObject test = new JsonObject();
            test.addProperty("id", id);
            JsonArray indexes = new JsonArray();
            paths.forEach(path -> indexes.add(files.indexOf(path)));
            test.add("deps", indexes);
            tests.add(test);
        });
        JsonObject fragment = new JsonObject();
        fragment.addProperty("version", 1);
        fragment.add("files", new Gson().toJsonTree(files));
        fragment.add("tests", tests);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(fragment.toString().getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }

    private List<Probes> dump() {
        List<Probes> hits;
        try {
            hits = hits((byte[]) jacoco.executionData().invoke(jacoco.agent(), true));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        for (Probes hit : hits) {
            boolean[] merged = coverage.computeIfAbsent(hit.id(), id -> new Probes(id, hit.name(), new boolean[hit.hits().length])).hits();
            for (int i = 0; i < merged.length; i++) {
                merged[i] |= hit.hits()[i];
            }
        }
        return hits;
    }

    private List<String> locate(String name) {
        URL url = loader.getResource(name + ".class");
        if (url == null || !"file".equals(url.getProtocol()) || url.toString().startsWith(CLIENT)) {
            return List.of();
        }
        try {
            Path classFile = Path.of(url.toURI()).toRealPath();
            String sourceFile = sourceFile(Files.readAllBytes(classFile));
            String output = classFile.toString();
            int build = Math.max(output.lastIndexOf("/target/"), output.lastIndexOf("/build/"));
            if (sourceFile == null || build < 0 || !Path.of(output.substring(0, build)).startsWith(root)) {
                return List.of();
            }
            String module = root.relativize(Path.of(output.substring(0, build))).toString();
            String prefix = module.isEmpty() ? "" : module + "/";
            String suffix = "/" + name.substring(0, name.lastIndexOf('/') + 1) + sourceFile;
            List<String> everywhere = tracked.stream().filter(path -> ("/" + path).endsWith(suffix)).toList();
            List<String> local = everywhere.stream().filter(path -> path.startsWith(prefix)).toList();
            return local.isEmpty() ? everywhere : local;
        } catch (IOException | URISyntaxException exception) {
            throw new IllegalStateException("selective could not map " + name + " to a source file", exception);
        }
    }

    static List<Probes> hits(byte[] data) {
        List<Probes> hits = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            for (int type = in.read(); type != -1; type = in.read()) {
                switch (type) {
                    case 0x01 -> in.skipBytes(4);
                    case 0x10 -> {
                        in.readUTF();
                        in.skipBytes(16);
                    }
                    case 0x11 -> {
                        long id = in.readLong();
                        String name = in.readUTF();
                        boolean[] probes = new boolean[varInt(in)];
                        byte[] bits = in.readNBytes((probes.length + 7) / 8);
                        boolean any = false;
                        for (int i = 0; i < probes.length; i++) {
                            probes[i] = (bits[i / 8] & (1 << (i % 8))) != 0;
                            any |= probes[i];
                        }
                        if (any) {
                            hits.add(new Probes(id, name, probes));
                        }
                    }
                    default -> throw new IOException("unknown JaCoCo block type " + type);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("selective could not read JaCoCo execution data", exception);
        }
        return hits;
    }

    record Probes(long id, String name, boolean[] hits) {}

    private record Jacoco(Object agent, Method executionData, Object runtime, Method live, Method probes) {}

    private static int varInt(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        return (value & 0x80) == 0 ? value : (value & 0x7f) | (varInt(in) << 7);
    }

    static String sourceFile(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.skipBytes(8);
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int index = 1; index < count; index++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[index] = in.readUTF();
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    index++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.skipBytes(6);
        in.skipBytes(2 * in.readUnsignedShort());
        for (int members = 0; members < 2; members++) {
            for (int count2 = in.readUnsignedShort(); count2 > 0; count2--) {
                in.skipBytes(6);
                skipAttributes(in);
            }
        }
        for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
            String name = utf8[in.readUnsignedShort()];
            int length = in.readInt();
            if ("SourceFile".equals(name)) {
                return utf8[in.readUnsignedShort()];
            }
            in.skipBytes(length);
        }
        return null;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
            in.skipBytes(2);
            in.skipBytes(in.readInt());
        }
    }
}
