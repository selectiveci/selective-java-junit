package ci.selective.junit.engine;

import java.util.List;

public record Outcome(
        String status,
        String message,
        List<String> messageLines,
        List<String> backtrace,
        double seconds,
        boolean assumption,
        Throwable throwable) {}
