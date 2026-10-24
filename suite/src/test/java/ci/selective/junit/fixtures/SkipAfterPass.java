package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class SkipAfterPass {
    private static int calls;

    @Test
    void once() {
        calls++;
        Assumptions.assumeTrue(calls == 1, "later skip");
    }
}
