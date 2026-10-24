package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Test;

public class HangProbe {
    @Test
    void stuck() throws InterruptedException {
        Counters.add("hang-start");
        Thread.sleep(12_000);
    }
}
