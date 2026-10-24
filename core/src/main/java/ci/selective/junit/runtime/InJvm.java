package ci.selective.junit.runtime;

import org.apache.maven.surefire.api.provider.ProviderParameters;
import org.apache.maven.surefire.api.suite.RunResult;

public final class InJvm {
    private InJvm() {}

    public static RunResult invoke(ProviderParameters parameters, Object forkTestSet) {
        return Session.surefire(parameters);
    }
}
