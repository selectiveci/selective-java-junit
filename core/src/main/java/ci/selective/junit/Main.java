package ci.selective.junit;

import ci.selective.junit.runtime.Session;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        boolean dryRun = false;
        String packageName = System.getenv("SELECTIVE_PACKAGE");
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--package".equals(arg) && index + 1 < args.length) {
                packageName = args[++index];
            }
        }
        int code;
        try {
            code = Session.harness(packageName, dryRun);
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            System.err.println(message == null || message.isEmpty() ? throwable.toString() : message);
            code = 1;
        }
        System.exit(code);
    }
}
