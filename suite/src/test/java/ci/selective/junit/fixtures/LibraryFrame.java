package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Test;

public class LibraryFrame {
    @Test
    void fails() {
        RuntimeException error = new RuntimeException("nope");
        error.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("org.hamcrest.MatcherAssert", "assertThat", "MatcherAssert.java", 20),
            new StackTraceElement("org.assertj.core.api.Assertions", "assertThat", "Assertions.java", 10),
            new StackTraceElement("java.lang.Thrower", "fail", "Thrower.java", 4),
            new StackTraceElement("ci.selective.junit.fixtures.LibraryFrame", "fails", "LibraryFrame.java", 12)
        });
        throw error;
    }
}
