package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Test;

public class Prints {
    @Test
    void out() {
        System.out.println("user-stdout-marker");
    }
}
