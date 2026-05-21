package com.ittai.debugbridge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    @Test
    void publishAndPollRoundTrip() throws InterruptedException {
        EventBus bus = new EventBus(8);
        bus.publish("breakpoint_hit", "thr_1", Map.of("loc", "Foo:42"));
        EventBus.DebugEvent e = bus.poll(100);
        assertNotNull(e);
        assertEquals("breakpoint_hit", e.kind());
        assertEquals("thr_1", e.threadId());
        assertEquals(1, e.seq());
    }

    @Test
    void pollTimesOutCleanly() throws InterruptedException {
        EventBus bus = new EventBus(4);
        assertNull(bus.poll(50));
    }

    @Test
    void dropsOldestWhenFull() {
        EventBus bus = new EventBus(2);
        bus.publish("a", "t", Map.of());
        bus.publish("b", "t", Map.of());
        bus.publish("c", "t", Map.of());
        assertEquals(2, bus.size());
        var snap = bus.snapshot(10);
        assertEquals("b", snap.get(0).kind());
        assertEquals("c", snap.get(1).kind());
    }
}
