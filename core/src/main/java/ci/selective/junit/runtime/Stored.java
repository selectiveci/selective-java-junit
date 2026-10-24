package ci.selective.junit.runtime;

import ci.selective.junit.engine.CaseRef;
import ci.selective.junit.engine.Outcome;

public record Stored(String wireId, String className, String methodName, String displayName, Outcome outcome) {
    public static Stored of(String wireId, CaseRef ref, Outcome outcome) {
        if (ref == null) {
            return new Stored(wireId, "", "not collected", "not collected", outcome);
        }
        return new Stored(wireId, ref.className(), ref.methodName(), ref.displayName(), outcome);
    }
}
