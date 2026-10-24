package ci.selective.junit.fixtures;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class GroupHooks {
    @BeforeAll
    static void before() {
        Counters.add("before-group");
    }

    @AfterAll
    static void after() {
        Counters.add("after-group");
    }

    @Test
    void one() {}

    @Test
    void two() {}

    @Test
    void three() {}
}
