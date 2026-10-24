package ci.selective.junit.fixtures;

import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(CollectProbe.Callbacks.class)
public class CollectProbe {
    CollectProbe() {
        Counters.add("probe-constructor");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void value(int value) {}

    @TestFactory
    Stream<DynamicTest> children() {
        return Stream.of(DynamicTest.dynamicTest("only", () -> {}));
    }

    static class Callbacks implements BeforeAllCallback, TestInstancePostProcessor {
        @Override
        public void beforeAll(ExtensionContext context) {
            Counters.add("probe-extension");
        }

        @Override
        public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
            Counters.add("probe-extension");
        }
    }
}
