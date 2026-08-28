package minecrafttransportsimulator.baseclasses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SpatialIndexTest {
    @Test
    void queriesDistinctNearbyCandidatesAndTracksMovement() {
        SpatialIndex<Object> index = new SpatialIndex<>(16D, 16);
        Object near = new Object();
        Object spanning = new Object();
        Object far = new Object();
        index.update(near, 1, 1, 2, 2);
        index.update(spanning, 15, 15, 17, 17);
        index.update(far, 100, 100, 101, 101);

        List<Object> results = new ArrayList<>();
        index.query(0, 0, 20, 20, results);
        assertEquals(2, results.size());
        assertTrue(results.contains(near));
        assertTrue(results.contains(spanning));
        assertFalse(results.contains(far));

        index.update(near, 64, 64, 65, 65);
        results.clear();
        index.query(0, 0, 10, 10, results);
        assertFalse(results.contains(near));
        index.query(60, 60, 70, 70, results);
        assertTrue(results.contains(near));
    }

    @Test
    void oversizedObjectsRemainCandidatesWithoutHugeBucketExpansion() {
        SpatialIndex<Object> index = new SpatialIndex<>(16D, 4);
        Object oversized = new Object();
        index.update(oversized, -1000, -1000, 1000, 1000);

        List<Object> results = new ArrayList<>();
        index.query(400, 400, 401, 401, results);
        assertEquals(1, results.size());
        assertTrue(results.contains(oversized));

        index.remove(oversized);
        results.clear();
        index.query(400, 400, 401, 401, results);
        assertTrue(results.isEmpty());
        assertEquals(0, index.size());
    }
}
