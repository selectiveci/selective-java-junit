package ci.selective.junit.fixtures;

import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TwoFailures {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void value(int value) {
        if (value > 1) {
            throw new IllegalStateException("bad " + value);
        }
    }

    @TestFactory
    Stream<DynamicTest> children() {
        return Stream.of("x", "y", "z").map(name -> DynamicTest.dynamicTest(name, () -> {
            if (!"x".equals(name)) {
                throw new IllegalStateException("bad " + name);
            }
        }));
    }
}
