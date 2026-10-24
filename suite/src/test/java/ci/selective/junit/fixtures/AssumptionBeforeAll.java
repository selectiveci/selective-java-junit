package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AssumptionBeforeAll {
    @BeforeAll
    static void before() {
        Assumptions.assumeTrue(false, "skip-all");
    }

    @Test
    void holds() {}
}
