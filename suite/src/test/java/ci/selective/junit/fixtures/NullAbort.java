package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

public class NullAbort {
    @Test
    void blank() {
        throw new TestAbortedException(null);
    }
}
