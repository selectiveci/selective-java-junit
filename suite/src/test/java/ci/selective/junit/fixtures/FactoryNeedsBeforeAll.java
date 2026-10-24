package ci.selective.junit.fixtures;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class FactoryNeedsBeforeAll {
    static String name;

    @BeforeAll
    static void before() {
        name = "ready";
    }

    @TestFactory
    Stream<DynamicTest> children() {
        if (name == null) {
            throw new IllegalStateException("missing state");
        }
        return Stream.of(
                DynamicTest.dynamicTest("ok", () -> {}),
                DynamicTest.dynamicTest("bad", () -> {
                    throw new IllegalStateException("too big 3");
                }));
    }
}
