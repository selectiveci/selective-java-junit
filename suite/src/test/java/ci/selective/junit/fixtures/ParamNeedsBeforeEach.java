package ci.selective.junit.fixtures;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ParamNeedsBeforeEach {
    static String name;

    @BeforeAll
    static void before() {
        name = "ready";
    }

    static Stream<Integer> values() {
        if (name == null) {
            throw new IllegalStateException("missing state");
        }
        return Stream.of(1, 2, 3);
    }

    @ParameterizedTest
    @MethodSource("values")
    void small(int value) {
        if (value == 3) {
            throw new IllegalStateException("too big 3");
        }
    }
}
