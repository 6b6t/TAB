package me.neznamy.tab.shared.features.proxy;

import com.saicone.delivery4j.MessageChannel;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class WireCacheTest {
    @Test void legacyCollisionDropsForeignPayloadBeforeConsumer() throws Exception {
        MessageChannel legacy = new MessageChannel("test").cache(true);
        byte[] own = legacy.encode("origin-a");
        int cached = ByteBuffer.wrap(own).getInt();
        MessageChannel other = new MessageChannel("test").cache(true);
        byte[] foreign = other.encode("origin-b");
        ByteBuffer.wrap(foreign).putInt(cached);
        assertNull(legacy.decode(foreign));
        MessageChannel fixed = new MessageChannel("test").cache(new ProxyMessengerSupport.WireCache());
        assertArrayEquals(new String[]{"origin-b"}, fixed.decode(foreign));
    }

    @Test void rollingUpgradeBothDirectionsAndEqualFixedIds() throws Exception {
        MessageChannel old = new MessageChannel("test").cache(true);
        MessageChannel a = new MessageChannel("test").cache(new ProxyMessengerSupport.WireCache());
        MessageChannel b = new MessageChannel("test").cache(new ProxyMessengerSupport.WireCache());
        for (int i = 0; i < 10000; i++) {
            byte[] wire = a.encode("a");
            b.encode("b");
            assertTrue(ByteBuffer.wrap(wire).getInt() < 0);
            assertArrayEquals(new String[]{"a"}, old.decode(wire));
            assertArrayEquals(new String[]{"a"}, b.decode(wire));
            assertArrayEquals(new String[]{"legacy"}, a.decode(old.encode("legacy")));
        }
    }
}
