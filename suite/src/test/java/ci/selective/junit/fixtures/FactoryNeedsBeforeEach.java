package ci.selective.junit.fixtures;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class FactoryNeedsBeforeEach {
    String name;

    @BeforeEach
    void before() {
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
