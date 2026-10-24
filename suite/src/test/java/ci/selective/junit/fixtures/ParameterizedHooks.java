package ci.selective.junit.fixtures;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class ParameterizedHooks {
    @BeforeAll
    static void before() {
        Counters.add("before-param");
    }

    @AfterAll
    static void after() {
        Counters.add("after-param");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "b", "c"})
    void value(String value) {}
}
