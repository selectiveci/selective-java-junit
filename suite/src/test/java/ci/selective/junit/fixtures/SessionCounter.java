package ci.selective.junit.fixtures;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public class SessionCounter implements LauncherSessionListener {
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Counters.add("launcher-session-opened");
    }
}
