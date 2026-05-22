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

    @Test
    void waitAfterIgnoresStaleEvents() throws InterruptedException {
        EventBus bus = new EventBus(8);
        bus.publish("old", "thr_1", Map.of());
        long after = bus.currentSeq();
        bus.publish("new", "thr_1", Map.of());

        EventBus.DebugEvent e = bus.waitAfter(after, 100);

        assertNotNull(e);
        assertEquals("new", e.kind());
    }

    @Test
    void waitAfterCanFilterEvents() throws InterruptedException {
        EventBus bus = new EventBus(8);
        long after = bus.currentSeq();
        bus.publish("breakpoint_hit", "thr_1", Map.of());
        bus.publish("step_completed", "thr_2", Map.of());
        bus.publish("step_completed", "thr_1", Map.of());

        EventBus.DebugEvent e = bus.waitAfter(after, 100,
            ev -> "step_completed".equals(ev.kind()) && "thr_1".equals(ev.threadId()));

        assertNotNull(e);
        assertEquals("step_completed", e.kind());
        assertEquals("thr_1", e.threadId());
    }
}
