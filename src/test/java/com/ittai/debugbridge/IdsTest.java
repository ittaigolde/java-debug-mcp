package com.ittai.debugbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdsTest {

    @Test
    void issuesStableIdsPerCategoryAndKey() {
        Ids ids = new Ids();
        Object refA = new Object();
        Object refB = new Object();

        String a1 = ids.idFor("obj", "100", refA);
        String a2 = ids.idFor("obj", "100", refA);
        String b1 = ids.idFor("obj", "101", refB);
        String t1 = ids.idFor("thr", "100", refA);

        assertEquals(a1, a2, "same (category,key) returns same id");
        assertNotEquals(a1, b1, "different keys → different ids");
        assertNotEquals(a1, t1, "different categories → different ids even if key matches");
        assertEquals("obj_1", a1);
        assertEquals("obj_2", b1);
        assertEquals("thr_1", t1);
    }

    @Test
    void resolvesIdsToOriginalRefs() {
        Ids ids = new Ids();
        Object ref = new Object();
        String id = ids.idFor("obj", "1", ref);
        assertSame(ref, ids.resolveAny(id));
        assertSame(ref, ids.resolve(id, Object.class));
    }

    @Test
    void unknownIdThrows() {
        Ids ids = new Ids();
        assertThrows(IllegalArgumentException.class, () -> ids.resolveAny("obj_999"));
    }

    @Test
    void clearResetsCountersAndMapping() {
        Ids ids = new Ids();
        String a = ids.idFor("obj", "x", new Object());
        ids.clear();
        assertFalse(ids.has(a));
        String b = ids.idFor("obj", "x", new Object());
        assertEquals("obj_1", b, "counter reset after clear");
    }

    @Test
    void removeDropsIdAndKeyMapping() {
        Ids ids = new Ids();
        Object first = new Object();
        Object second = new Object();

        String a = ids.idFor("obj", "x", first);
        ids.remove(a);

        assertFalse(ids.has(a));
        assertThrows(IllegalArgumentException.class, () -> ids.resolveAny(a));
        String b = ids.idFor("obj", "x", second);
        assertNotEquals(a, b);
        assertSame(second, ids.resolveAny(b));
    }
}
