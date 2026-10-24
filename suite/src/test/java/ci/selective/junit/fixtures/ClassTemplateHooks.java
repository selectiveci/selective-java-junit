package ci.selective.junit.fixtures;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

@ParameterizedClass
@ValueSource(strings = {"a", "b"})
public class ClassTemplateHooks {
    @Parameter
    String value;

    @BeforeAll
    static void before() {
        Counters.add("before-class-template");
    }

    @AfterAll
    static void after() {
        Counters.add("after-class-template");
    }

    @Test
    void holds() {}
}
