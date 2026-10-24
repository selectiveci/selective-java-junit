package ci.selective.junit.fixtures;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

public class ThrowingAfterAll {
    @AfterAll
    static void after() {
        throw new IllegalStateException("after-all");
    }

    @Test
    void holds() {}
}
