package ci.selective.junit.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConformanceCases {
    static int attempts;

    @BeforeAll
    static void takesOneSecond() throws InterruptedException {
        Thread.sleep(1000);
    }

    @Test
    @Order(1)
    void passes() {}

    @Test
    @Order(2)
    void fails() {
        assertEquals(1, 2, "boom");
    }

    @Test
    @Order(3)
    @Disabled("pending")
    void pends() {}

    @Test
    @Order(4)
    void flakes() {
        attempts++;
        if (attempts < 2) {
            fail("flake");
        }
    }
}
