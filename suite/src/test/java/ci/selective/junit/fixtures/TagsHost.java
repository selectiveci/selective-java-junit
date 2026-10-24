package ci.selective.junit.fixtures;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class TagsHost {
    @Test
    @Tag("fast")
    void fast() {}

    @Test
    @Tag("slow")
    void slow() {}
}
