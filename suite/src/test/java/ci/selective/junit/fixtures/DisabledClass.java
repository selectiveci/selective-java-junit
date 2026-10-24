package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("off")
public class DisabledClass {
    @Test
    void holds() {}
}
