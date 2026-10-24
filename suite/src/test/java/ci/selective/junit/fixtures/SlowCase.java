package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Test;

public class SlowCase {
    @Test
    void sleeps() throws InterruptedException {
        Thread.sleep(400);
    }
}
