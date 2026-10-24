package ci.selective.junit.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class DuplicateIdTest {
    @Test
    void differentFullIdsThatShareAWireFail() {
        Map<String, CaseRef> cases = new LinkedHashMap<>();
        CaseRef first = new CaseRef("full-a", "same", "A", "a", "a", "A.java", 1);
        CaseRef second = new CaseRef("full-b", "same", "B", "b", "b", "B.java", 2);
        JUnitSuite.remember(cases, first);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> JUnitSuite.remember(cases, second));
        assertTrue(error.getMessage().contains("duplicate test id"));
    }

    @Test
    void sameFullIdIsKeptOnce() {
        Map<String, CaseRef> cases = new LinkedHashMap<>();
        CaseRef first = new CaseRef("full-a", "same", "A", "a", "a", "A.java", 1);
        JUnitSuite.remember(cases, first);
        JUnitSuite.remember(cases, first);
        assertTrue(cases.size() == 1);
    }
}
