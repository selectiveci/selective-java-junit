package ci.selective.junit.surefire;

import org.apache.maven.surefire.providerapi.ProviderParameters;

public final class LegacySurefire {
    public LegacySurefire(ProviderParameters parameters) {
        throw new IllegalStateException(
                "Selective needs maven-surefire-plugin 3.0.0 or newer. This build uses Surefire 2.x, which would run every test without Selective.");
    }
}
