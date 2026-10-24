package ci.selective.junit.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import ci.selective.junit.runtime.Texts;
import ci.selective.junit.runtime.WireIds;

public final class JUnitSuite implements AutoCloseable {
    private static final Set<String> TEMPLATES = Set.of(
            "test-factory", "test-template", "class-template", "nested-class-template");

    private final LauncherSession session;
    private final Launcher launcher;
    private final Sources sources;
    private final Map<String, String> configuration;
    private final Map<String, CaseRef> cases = new LinkedHashMap<>();
    private final String junitVersion;
    private final ClassSetupClock clock = new ClassSetupClock();

    private JUnitSuite(LauncherSession session, Sources sources, Map<String, String> configuration, String junitVersion) {
        this.session = session;
        this.launcher = session.getLauncher();
        this.sources = sources;
        this.configuration = configuration;
        this.junitVersion = junitVersion;
    }

    public static JUnitSuite collect(Spec spec) {
        String launcher = Launcher.class.getPackage().getImplementationVersion();
        String platform = TestEngine.class.getPackage().getImplementationVersion();
        if (launcher != null && platform != null && !launcher.equals(platform)) {
            throw new IllegalStateException("selective needs junit-platform-launcher " + platform + " to match junit-platform-engine "
                    + platform + ", but the test classpath has launcher " + launcher
                    + ". Import org.junit:junit-bom in <dependencyManagement> so every JUnit artifact gets one version.");
        }
        Sources sources = new Sources(spec.cwd);
        JUnitSuite suite = new JUnitSuite(LauncherFactory.openSession(), sources, spec.configuration, junitVersion(spec.loader));
        TestPlan plan = suite.launcher.discover(suite.discovery(spec));
        for (TestIdentifier node : nodes(plan)) {
            if (suite.isCase(node) && suite.wanted(spec, node)) {
                suite.add(node);
            }
        }
        return suite;
    }

    @Override
    public void close() {
        session.close();
    }

    public double beforeAllSeconds() {
        return clock.beforeAll / 1_000_000_000.0d;
    }

    public double afterAllSeconds() {
        return clock.afterAll / 1_000_000_000.0d;
    }

    public boolean isEmpty() {
        return cases.isEmpty();
    }

    public Collection<CaseRef> cases() {
        return cases.values();
    }

    public CaseRef find(String wireId) {
        return cases.get(wireId);
    }

    public String junitVersion() {
        return junitVersion;
    }

    public Outcome execute(CaseRef ref) {
        Capture capture = new Capture();
        long start = System.nanoTime();
        launcher.execute(execution(ref.fullId()), capture, clock);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0d;
        return capture.outcome(ref, sources, seconds);
    }

    public Outcome missing(String wireId) {
        return new Outcome(
                "failed",
                "not collected",
                List.of("not collected"),
                List.of(),
                0.0d,
                false,
                new IllegalStateException("not collected: " + wireId));
    }

    static void remember(Map<String, CaseRef> cases, CaseRef ref) {
        CaseRef existing = cases.get(ref.wireId());
        if (existing != null) {
            if (!existing.fullId().equals(ref.fullId())) {
                throw new IllegalStateException("duplicate test id " + ref.wireId());
            }
            return;
        }
        cases.put(ref.wireId(), ref);
    }

    private void add(TestIdentifier node) {
        String full = node.getUniqueId();
        String wire = WireIds.wire(full);
        Names names = names(node);
        String className = names.className() == null ? "" : names.className();
        String method = names.method() == null ? node.getLegacyReportingName() : names.method();
        Integer line = sources.line(className, method);
        String display = Texts.clean(node.getDisplayName());
        remember(cases, new CaseRef(full, wire, className, method, display, sources.file(className), line));
    }

    private boolean isCase(TestIdentifier node) {
        List<UniqueId.Segment> segments = UniqueId.parse(node.getUniqueId()).getSegments();
        for (UniqueId.Segment segment : segments.subList(0, segments.size() - 1)) {
            if (TEMPLATES.contains(segment.getType())) {
                return false;
            }
        }
        return node.isTest() || template(node);
    }

    private static boolean template(TestIdentifier node) {
        return TEMPLATES.contains(UniqueId.parse(node.getUniqueId()).getLastSegment().getType());
    }

    private boolean wanted(Spec spec, TestIdentifier node) {
        Names names = names(node);
        if (names.className() == null) {
            return !template(node);
        }
        if (!classAllowed(spec, names.className())) {
            return false;
        }
        if (spec.methodFilter != null) {
            String method = names.method() == null ? "" : names.method();
            return spec.methodFilter.test(names.className(), method);
        }
        return true;
    }

    private boolean classAllowed(Spec spec, String className) {
        if (spec.scannedClasses == null) {
            return true;
        }
        return spec.scannedClasses.contains(className) || spec.scannedClasses.contains(Sources.outer(className));
    }

    private LauncherDiscoveryRequest discovery(Spec spec) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
        if (spec.classesDirectory != null) {
            builder.selectors(DiscoverySelectors.selectClasspathRoots(Set.of(spec.classesDirectory)));
        } else if (spec.packageName != null && !spec.packageName.isEmpty()) {
            builder.selectors(DiscoverySelectors.selectPackage(spec.packageName));
        }
        if (!spec.includeEngines.isEmpty()) {
            builder.filters(EngineFilter.includeEngines(spec.includeEngines));
        }
        if (!spec.excludeEngines.isEmpty()) {
            builder.filters(EngineFilter.excludeEngines(spec.excludeEngines));
        }
        if (!spec.includeTags.isEmpty()) {
            builder.filters(TagFilter.includeTags(spec.includeTags));
        }
        if (!spec.excludeTags.isEmpty()) {
            builder.filters(TagFilter.excludeTags(spec.excludeTags));
        }
        return build(builder);
    }

    private LauncherDiscoveryRequest execution(String uniqueId) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
        builder.selectors(DiscoverySelectors.selectUniqueId(uniqueId));
        return build(builder);
    }

    private LauncherDiscoveryRequest build(LauncherDiscoveryRequestBuilder builder) {
        configuration.forEach(builder::configurationParameter);
        return builder.build();
    }

    private static List<TestIdentifier> nodes(TestPlan plan) {
        List<TestIdentifier> nodes = new ArrayList<>();
        for (TestIdentifier root : plan.getRoots()) {
            nodes.add(root);
            nodes.addAll(plan.getDescendants(root));
        }
        return nodes;
    }

    private static Names names(TestIdentifier node) {
        String className = null;
        String method = null;
        Optional<org.junit.platform.engine.TestSource> source = node.getSource();
        if (source.orElse(null) instanceof MethodSource methodSource) {
            className = methodSource.getClassName();
            method = methodSource.getMethodName();
        } else if (source.orElse(null) instanceof ClassSource classSource) {
            className = classSource.getClassName();
        }
        UniqueId uniqueId = UniqueId.parse(node.getUniqueId());
        if (className == null) {
            StringBuilder binary = new StringBuilder();
            for (UniqueId.Segment segment : uniqueId.getSegments()) {
                if ("class".equals(segment.getType()) || "class-template".equals(segment.getType())) {
                    binary.setLength(0);
                    binary.append(segment.getValue());
                } else if ("nested-class".equals(segment.getType())
                        || "nested-class-template".equals(segment.getType())) {
                    if (binary.length() > 0) {
                        binary.append('$');
                    }
                    binary.append(segment.getValue());
                }
            }
            if (binary.length() > 0) {
                className = binary.toString();
            }
        }
        if (method == null) {
            for (UniqueId.Segment segment : uniqueId.getSegments()) {
                String type = segment.getType();
                if ("method".equals(type) || "test".equals(type) || "test-template".equals(type)
                        || "test-factory".equals(type)) {
                    method = strip(segment.getValue());
                }
            }
        }
        return new Names(className, method);
    }

    private static String strip(String value) {
        int paren = value.indexOf('(');
        return paren < 0 ? value : value.substring(0, paren);
    }

    private static String junitVersion(ClassLoader loader) {
        Map<String, String> versions = new HashMap<>();
        for (TestEngine engine : ServiceLoader.load(TestEngine.class, loader)) {
            engine.getVersion().ifPresent(version -> versions.put(engine.getId(), version));
        }
        return versions.getOrDefault("junit-jupiter", versions.getOrDefault("junit-vintage", "unknown"));
    }

    private record Names(String className, String method) {}

    private static final class Capture implements TestExecutionListener {
        private final Map<String, Node> nodes = new LinkedHashMap<>();

        @Override
        public void executionSkipped(TestIdentifier testIdentifier, String reason) {
            nodes.put(testIdentifier.getUniqueId(), Node.skip(reason));
        }

        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
            String index = UniqueId.parse(testIdentifier.getUniqueId()).getLastSegment().getValue();
            nodes.put(testIdentifier.getUniqueId(), Node.finish(result, index + " " + testIdentifier.getDisplayName()));
        }

        Outcome outcome(CaseRef ref, Sources sources, double seconds) {
            Node own = nodes.get(ref.fullId());
            Node failed = own != null && own.failed() ? own : failedAncestor(ref.fullId());
            if (failed != null) {
                return failed(ref, sources, seconds, failed.throwable());
            }
            List<Node> children = failedDescendants(ref.fullId());
            if (!children.isEmpty()) {
                return failed(ref, sources, seconds, children);
            }
            boolean assumption = aborted(ref.fullId()) || (own != null && own.aborted());
            if (own == null) {
                if (assumption || skipped(ref.fullId())) {
                    return pending(ref, seconds, messageOf(ref.fullId()), assumption);
                }
                return failed(ref, sources, seconds, new IllegalStateException("no outcome"));
            }
            if (own.skipped() || own.aborted()) {
                return pending(ref, seconds, own.message(), own.aborted() || assumption);
            }
            return new Outcome("passed", "", List.of(), List.of(), seconds, false, null);
        }

        private List<Node> failedDescendants(String id) {
            String prefix = id + "/";
            List<Node> failed = new ArrayList<>();
            for (Map.Entry<String, Node> entry : nodes.entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getValue().failed()) {
                    failed.add(entry.getValue());
                }
            }
            return failed;
        }

        private Node ancestor(String id, Predicate<Node> matches) {
            for (String cursor = parent(id); cursor != null; cursor = parent(cursor)) {
                Node node = nodes.get(cursor);
                if (node != null && matches.test(node)) {
                    return node;
                }
            }
            return null;
        }

        private Node failedAncestor(String id) {
            return ancestor(id, Node::failed);
        }

        private boolean aborted(String id) {
            return ancestor(id, Node::aborted) != null;
        }

        private boolean skipped(String id) {
            return ancestor(id, Node::skipped) != null;
        }

        private String messageOf(String id) {
            Node node = ancestor(id, candidate -> candidate.message() != null && !candidate.message().isEmpty());
            return node == null ? "" : node.message();
        }

        private static String parent(String id) {
            UniqueId parsed = UniqueId.parse(id);
            if (parsed.getSegments().size() <= 1) {
                return null;
            }
            return parsed.removeLastSegment().toString();
        }

        private static Outcome pending(CaseRef ref, double seconds, String message, boolean assumption) {
            return new Outcome("pending", Texts.clean(message), List.of(), List.of(), seconds, assumption, null);
        }

        private static Outcome failed(CaseRef ref, Sources sources, double seconds, Throwable throwable) {
            Throwable error = throwable == null ? new IllegalStateException("failed") : throwable;
            return failed(ref, sources, seconds, text(error), error);
        }

        private static Outcome failed(CaseRef ref, Sources sources, double seconds, List<Node> children) {
            List<String> parts = new ArrayList<>();
            for (Node child : children) {
                parts.add(Texts.clean(child.label()) + ": " + text(child.throwable()));
            }
            return failed(ref, sources, seconds, String.join("\n", parts), children.get(0).throwable());
        }

        private static String text(Throwable error) {
            String message = Texts.clean(String.valueOf(error.getMessage() == null ? error : error.getMessage()));
            return message.isEmpty() ? Texts.clean(error.toString()) : message;
        }

        private static Outcome failed(CaseRef ref, Sources sources, double seconds, String message, Throwable error) {
            List<String> lines = new ArrayList<>();
            for (String line : message.split("\\R", -1)) {
                if (!line.isEmpty()) {
                    lines.add(Texts.clean(line));
                }
            }
            if (lines.isEmpty()) {
                lines.add("failed");
            }
            return new Outcome("failed", message, lines, frames(ref, sources, error), seconds, false, error);
        }

        private static List<String> frames(CaseRef ref, Sources sources, Throwable error) {
            List<String> project = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            Throwable cursor = error;
            while (cursor != null) {
                for (StackTraceElement element : cursor.getStackTrace()) {
                    String path = sources.located(element.getClassName());
                    String frame = Texts.clean(format(path, element));
                    if (path == null) {
                        rest.add(frame);
                    } else {
                        project.add(frame);
                    }
                }
                if (!project.isEmpty()) {
                    break;
                }
                cursor = cursor.getCause();
            }
            if (project.isEmpty()) {
                String path = sources.located(ref.className());
                if (path != null) {
                    int line = ref.lineNumber() == null ? 1 : ref.lineNumber();
                    String method = ref.methodName() == null || ref.methodName().isEmpty() ? "test" : ref.methodName();
                    project.add(Texts.clean(path + ":" + line + ": in `" + method + "`"));
                }
            }
            project.addAll(rest);
            return project;
        }

        private static String format(String path, StackTraceElement element) {
            int line = element.getLineNumber();
            if (line < 0) {
                line = 1;
            }
            String where = path == null ? element.getClassName() : path;
            return where + ":" + line + ": in `" + element.getMethodName() + "`";
        }
    }

    private record Node(boolean failed, boolean aborted, boolean skipped, String message, Throwable throwable, String label) {
        static Node skip(String reason) {
            return new Node(false, false, true, reason, null, "");
        }

        static Node finish(TestExecutionResult result, String label) {
            Throwable throwable = result.getThrowable().orElse(null);
            String message = throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
            return switch (result.getStatus()) {
                case FAILED -> new Node(true, false, false, message, throwable, label);
                case ABORTED -> new Node(false, true, false, message, throwable, label);
                case SUCCESSFUL -> new Node(false, false, false, "", null, label);
            };
        }
    }

    private static final class ClassSetupClock implements TestExecutionListener {
        private final Map<String, Long> settingUp = new HashMap<>();
        private final Map<String, Long> lastChild = new HashMap<>();
        private long beforeAll;
        private long afterAll;

        @Override
        public void executionStarted(TestIdentifier testIdentifier) {
            long now = System.nanoTime();
            testIdentifier.getParentId().map(settingUp::remove).ifPresent(start -> beforeAll += now - start);
            if (classContainer(testIdentifier)) {
                settingUp.put(testIdentifier.getUniqueId(), now);
            }
        }

        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
            long now = System.nanoTime();
            testIdentifier.getParentId().ifPresent(parent -> lastChild.put(parent, now));
            if (!classContainer(testIdentifier)) {
                return;
            }
            Long start = settingUp.remove(testIdentifier.getUniqueId());
            if (start != null) {
                beforeAll += now - start;
            } else {
                afterAll += now - lastChild.remove(testIdentifier.getUniqueId());
            }
        }

        private static boolean classContainer(TestIdentifier testIdentifier) {
            return testIdentifier.isContainer() && testIdentifier.getSource().orElse(null) instanceof ClassSource;
        }
    }

    public static final class Spec {
        public Path cwd = Path.of("").toAbsolutePath();
        public ClassLoader loader = Thread.currentThread().getContextClassLoader();
        public String packageName;
        public Path classesDirectory;
        public Set<String> scannedClasses;
        public List<String> includeTags = List.of();
        public List<String> excludeTags = List.of();
        public List<String> includeEngines = List.of();
        public List<String> excludeEngines = List.of();
        public Map<String, String> configuration = Map.of();
        public BiPredicate<String, String> methodFilter;
    }
}
