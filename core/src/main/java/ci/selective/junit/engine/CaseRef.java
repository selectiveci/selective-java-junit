package ci.selective.junit.engine;

public record CaseRef(
        String fullId,
        String wireId,
        String className,
        String methodName,
        String displayName,
        String filePath,
        Integer lineNumber) {}
