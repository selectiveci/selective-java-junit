package ci.selective.junit.fixtures;

import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class ThrowingFactory {
    @TestFactory
    Stream<DynamicTest> boom() {
        throw new IllegalStateException("factory");
    }
}
