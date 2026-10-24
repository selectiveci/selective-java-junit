package ci.selective.junit.surefire;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

import org.apache.maven.surefire.api.provider.ProviderParameters;
import org.apache.maven.surefire.api.provider.SurefireProvider;
import org.apache.maven.surefire.api.suite.RunResult;

public final class SelectiveProvider implements SurefireProvider {
    private final ProviderParameters parameters;

    public SelectiveProvider(ProviderParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public Iterable<Class<?>> getSuites() {
        return Collections.singletonList(SelectiveProvider.class);
    }

    @Override
    public RunResult invoke(Object forkTestSet) {
        ClassLoader tests = parameters.getTestClassLoader();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(tests);
        try {
            Class<?> inJvm = Class.forName("ci.selective.junit.runtime.InJvm", true, tests);
            Method run = inJvm.getMethod("invoke", ProviderParameters.class, Object.class);
            return (RunResult) run.invoke(null, parameters, forkTestSet);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "ci.selective:selective-junit is not on the test classpath", exception);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override
    public void cancel() {}
}
