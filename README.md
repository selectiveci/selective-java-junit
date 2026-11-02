# selective-java-junit

Selective's JUnit Platform client. A Surefire provider hosts the launcher inside the forked test JVM. The session spawns `transport --stdio` and speaks one JSON object per line. There is no JNI and no Panama binding. If the sidecar fails to start, or never connects, the run exits 1.

The client builds its manifest from JUnit discovery alone, so no constructor, extension callback or test code runs before the server assigns a test. Each `@Test` method is one Selective test case. A `@TestFactory`, a `@ParameterizedTest` (or any other test template, such as `@RepeatedTest`) and, on JUnit 5.13+, a `@ParameterizedClass` are one test case each. Running that case runs every dynamic test or invocation it produces, and it fails when any of them fails, with that one's display name in the message. JUnit 4 tests run through the vintage engine, one case per test, `Parameterized` children included.

The client executes the ids the server assigns, one at a time, in that same JVM and in one JUnit launcher session, so a `LauncherSessionListener` opens once. Each id is its own execution request, which keeps Ruby's per-test group hooks: `@BeforeAll`, `@AfterAll` and class-level extension callbacks run once per assigned test. Three things run once per assigned test for the same reason: resources kept in the root `ExtensionContext` store, Spring's `@DirtiesContext(classMode = AFTER_CLASS)`, and Testcontainers containers held in `static` fields. A store opened with `StoreScope.LAUNCHER_SESSION` (JUnit 5.13+) is shared by every test in the session. The client leaves `junit.jupiter.extensions.autodetection.enabled` as the project sets it. Results are held until `execute` returns, then sent. At `close`, the client reports to Surefire one test set per class, with each test's measured time, so each class gets its own `TEST-<class>.xml`.

This repository is private. Do not publish the jars.

## Artifacts

| Coordinate | Role |
|---|---|
| `ci.selective:selective-junit-provider` | Surefire provider. A dependency of `maven-surefire-plugin`, not of the project. |
| `ci.selective:selective-junit` | The session and the launcher. A `test` dependency. It brings `junit-platform-launcher`. |

The project still depends on its JUnit engines: Jupiter, `junit-vintage-engine`, or both. `selective-junit` brings `junit-platform-launcher`, which has to be the same version as the project's `junit-platform-engine`. Import `org.junit:junit-bom` in `<dependencyManagement>` (Spring Boot's dependency management already does) so every JUnit artifact resolves to one version. If they differ, the run fails with a message that names both versions. Known limitation: the client cannot pick a matching launcher for a project without the BOM. Surefire does that only for its own provider. A JUnit 4 project needs only `junit-vintage-engine`. JUnit 5.8.2 through 6.0.1 and JDK 17 and 21 are covered by CI. The client needs `maven-surefire-plugin` 3.0.0 or newer; under Surefire 2.x the build fails with a message naming Surefire 3, because Surefire 2.x would otherwise ignore the provider and run every test without Selective. Leave `forkCount` at 1 so one warm JVM holds the session. The provider discovers the suite once. It does not shell `mvn -Dtest=` per batch.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.2.5</version>
  <dependencies>
    <dependency>
      <groupId>ci.selective</groupId>
      <artifactId>selective-junit-provider</artifactId>
      <version>0.1.0</version>
    </dependency>
  </dependencies>
</plugin>
```

The provider dependency has to sit on the plugin, next to `<configuration>`. A dependency nested inside `<configuration>` is only added to the test classpath, and Surefire then auto-detects `JUnitPlatformProvider`.

`<groups>`, `<excludedGroups>`, `<includes>`, `<excludes>`, and `-Dtest` are honored. Surefire does not pass `<includeJUnit5Engines>` or `<excludeJUnit5Engines>` to a provider loaded from the plugin classpath, so the client never sees them. Put `includejunit5engines`, `excludejunit5engines`, and `configurationParameters` in the plugin's `<properties>` instead.

## Transport

The first time it runs, the client downloads the transport release it was built against. It checks the archive's SHA-256 against the checksum built into the jar, then caches the binary as `~/.selective/transport/<version>/transport-<OS>-<arch>`. Linux binaries are static, so one binary serves glibc and musl systems such as Alpine. The download goes through the JVM's proxy settings (`https.proxyHost`, `https.proxyPort`). `SELECTIVE_TRANSPORT` names a binary and skips the download. `SELECTIVE_TRANSPORT_RELEASES` replaces `https://github.com/selectiveci/transport/releases/download` with a mirror that has the same layout. The pinned release is `transport.version` in the root pom, with one `transport.sha256.<OS>-<arch>` per target.

The pin is release `v0.2.0` of `selectiveci/transport`, which ships Linux (static) and macOS builds for x86_64 and aarch64. There is no Windows build; there the client exits 1 as an unsupported platform. The URL and API key travel in `SELECTIVE_TRANSPORT_URL`, which the client sets for the child. They are not process arguments.

`transport --build-env` supplies the run identity. These must be set and non-empty, or the client exits 1 and names the missing variables:

- `SELECTIVE_API_KEY`
- `SELECTIVE_PLATFORM`
- `SELECTIVE_RUN_ID`
- `SELECTIVE_RUN_ATTEMPT`
- `SELECTIVE_BRANCH`
- `SELECTIVE_SHA`
- `SELECTIVE_GIT_REPO`
- `SELECTIVE_GIT_PROVIDER`

`SELECTIVE_HOST` is optional. It must start with `ws://` or `wss://` when set. The default host is `wss://app.selective.ci`. `SELECTIVE_RUNNER_ID` is optional.

Each Maven module is its own Selective run. Under Surefire the client appends the module's path from the reactor root to `SELECTIVE_RUN_ID`, with `:` between directories (`1234-a`, `1234-services:api`). Tests in the reactor root keep the run id unchanged. The reactor root is `MAVEN_PROJECTBASEDIR`, which `mvn` and `mvnw` export; without it the run fails. `SELECTIVE_RUNNER_ID` reaches the server unchanged, so every module's session on one CI shard uses the same runner id. A build with N modules shows up as N runs, and as N builds in the build list and trends, which group by run id.

The client advertises `test_map=select`. `SELECTIVE_TEST_MAP_DISABLE` of `1`, `true`, or `yes` forces `0`. When `SELECTIVE_TARGET_BRANCH` is set, the manifest carries Ruby's ordering hints. The client fetches the last 1,000 commits of `origin/<target branch>`, which changes the checkout's refs. It sends the changed test files under the module as `modified_test_files`, and the test files that changed together with each changed non-test file as `correlated_files`. Paths are relative to the module, like `file_path`. If the fetch or diff fails, the client warns and sends neither.

When the JaCoCo agent is attached to the test JVM, for example through `jacoco-maven-plugin`'s `prepare-agent`, the client advertises `test_map=record`. If the server then asks it to record, it works test by test. Before each assigned test it resets JaCoCo's counters, and afterwards it reads them. It maps every class that ran to its tracked source file, using the class's `SourceFile` attribute and the module the class was compiled in. Classes from jars, the client's own classes and untracked files are left out. At `close` it uploads the fragment through `transport --upload-fragment`. The counters are reset around each test, so the client keeps every reading it takes and, when the session ends, hands the merged probes back to the agent. The agent's own `jacoco.exec` (or whatever output it is configured with) then holds the run's full coverage, and a coverage report or `jacoco:check` sees the same numbers as on a run that doesn't record. This uses the agent's runtime data, which is not part of JaCoCo's public `IAgent` interface; it is tested with JaCoCo 0.8.12. An agent that doesn't offer it is treated as not attached: the client advertises `select` and records nothing. A run stopped by a signal never reaches the agent's exit dump, with or without Selective.

In a multi-module build every module is its own run in the same suite, and the server keeps one map per suite and environment. Only the module that recorded most recently selects with it; the other modules' tests count as new and run in full. Nothing is wrongly skipped. Per-module maps are a server change. Without the agent, a record request gives one warning and no upload.

The client sends `remove_failed=true` on every connect, reconnects included. It applies a `remove_failed_test_case_result` in arrival order with the other commands and ignores an unknown id, keeps its failures across a reconnect until a remove clears them, and still lets a rerun replace an earlier result without waiting for a remove.

## Harness

`ci.selective.junit.Main` is the same session, fed a classpath instead of Surefire. That is how the mock-server tests run.

```
java -cp <classes-and-deps> ci.selective.junit.Main --package com.example.tests
java -cp <classes-and-deps> ci.selective.junit.Main --dry-run --package com.example.tests
```

`--package` overrides `SELECTIVE_PACKAGE`. `--dry-run` discovers and prints the manifest. It does not open a socket. An empty suite prints `selective found no tests` and exits 1.

## Tests

Build `transport` and `transport-conformance` at the pinned tag:

```
cargo install --locked --git https://github.com/selectiveci/transport --tag v0.2.0 \
  --bin transport --bin transport-conformance --root ~/transport-v0.2.0
```

Use a fresh `CARGO_TARGET_DIR` for each tag, since a reused one can install stale binaries. Then, with `SELECTIVE_TRANSPORT` pointing at `~/transport-v0.2.0/bin/transport`:

```
mvn -B verify
mvn -B verify -Djunit.version=5.12.2 -Dselective.noClassTemplate
mvn -B verify -Djunit.version=5.11.4 -Dselective.noClassTemplate
scripts/conformance.sh ~/transport-v0.2.0/bin/transport-conformance "$SELECTIVE_TRANSPORT"
```

`scripts/conformance.sh` runs the transport's own conformance suite against the client twice: once as a plain runner, and once with the JaCoCo agent so the recording scenarios run.

`5.13.4` is the default and includes `@ParameterizedClass`. The `noClassTemplate` profile drops that fixture on 5.11 and 5.12.
