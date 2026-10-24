package ci.selective.junit.fixtures;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ThrowingBeforeAll {
    @BeforeAll
    static void before() {
        throw new IllegalStateException("before-all");
    }

    @Test
    void holds() {}
}
