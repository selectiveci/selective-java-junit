package ci.selective.junit.fixtures;

import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class DynamicFactory {
    @TestFactory
    Stream<DynamicTest> children() {
        return Stream.of(
                DynamicTest.dynamicTest("alpha", () -> Counters.add("dynamic-body")),
                DynamicTest.dynamicTest("beta", () -> Counters.add("dynamic-body")));
    }
}
