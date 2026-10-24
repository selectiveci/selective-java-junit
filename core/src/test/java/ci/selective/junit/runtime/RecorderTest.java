package ci.selective.junit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

public class RecorderTest {
    @Test
    void readsTheSourceFileAttribute() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/ci/selective/junit/fixtures/GroupHooks.class").readAllBytes();
        assertEquals("GroupHooks.java", Recorder.sourceFile(bytes));
    }

    @Test
    void keepsOnlyClassesWithAHitProbe() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(0x01);
        out.writeChar(0xC0C0);
        out.writeChar(0x1007);
        out.writeByte(0x10);
        out.writeUTF("session");
        out.writeLong(1L);
        out.writeLong(2L);
        block(out, "app/Hit", 200, 150);
        block(out, "app/Missed", 9, -1);
        List<Recorder.Probes> hits = Recorder.hits(buffer.toByteArray());
        assertEquals(List.of("app/Hit"), hits.stream().map(Recorder.Probes::name).toList());
        assertEquals("app/Hit".hashCode(), hits.get(0).id());
        assertEquals(200, hits.get(0).hits().length);
        for (int i = 0; i < 200; i++) {
            assertEquals(i == 150, hits.get(0).hits()[i], "probe " + i);
        }
    }

    private static void block(DataOutputStream out, String name, int probes, int hit) throws Exception {
        out.writeByte(0x11);
        out.writeLong(name.hashCode());
        out.writeUTF(name);
        out.writeByte(0x80 | (probes & 0x7f));
        out.writeByte(probes >>> 7);
        byte[] bits = new byte[(probes + 7) / 8];
        if (hit >= 0) {
            bits[hit / 8] = (byte) (1 << (hit % 8));
        }
        out.write(bits);
    }
}
