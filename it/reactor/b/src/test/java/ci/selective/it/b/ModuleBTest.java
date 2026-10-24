package ci.selective.it.b;

import org.junit.jupiter.api.Test;

public class ModuleBTest {
    @Test
    void fails() {
        throw new IllegalStateException("b-failed");
    }
}
