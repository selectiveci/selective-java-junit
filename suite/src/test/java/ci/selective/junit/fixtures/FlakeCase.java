package ci.selective.junit.fixtures;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

public class FlakeCase {
    static int attempt;

    @Test
    void flakes() {
        attempt++;
        if (attempt < 2) {
            fail("flake");
        }
    }
}
