package minecrafttransportsimulator.baseclasses;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Identity-based, two-dimensional uniform-grid broad phase.  Objects spanning too many
 * cells are retained in a small overflow set, keeping pathological bounds from exploding
 * index memory while preserving correctness.
 */
public final class SpatialIndex<T> {
    private final double cellSize;
    private final int maximumCellsPerObject;
    private final Map<Long, Set<T>> cells = new HashMap<>();
    private final Map<T, long[]> memberships = new IdentityHashMap<>();
    private final Set<T> oversized = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<T> queryScratch = Collections.newSetFromMap(new IdentityHashMap<>());

    public SpatialIndex(double cellSize, int maximumCellsPerObject) {
        if (!(cellSize > 0) || maximumCellsPerObject < 1) {
            throw new IllegalArgumentException("Spatial index dimensions must be positive.");
        }
        this.cellSize = cellSize;
        this.maximumCellsPerObject = maximumCellsPerObject;
    }

    public synchronized void update(T object, double minX, double minZ, double maxX, double maxZ) {
        CellRange range = getRange(minX, minZ, maxX, maxZ);
        long[] newMembership = range.cellCount > maximumCellsPerObject ? new long[0] : range.toKeys();
        long[] oldMembership = memberships.get(object);
        if (oldMembership != null && Arrays.equals(oldMembership, newMembership)) {
            return;
        }

        removeInternal(object, oldMembership);
        memberships.put(object, newMembership);
        if (newMembership.length == 0) {
            oversized.add(object);
        } else {
            for (long key : newMembership) {
                cells.computeIfAbsent(key, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(object);
            }
        }
    }

    public synchronized void remove(T object) {
        removeInternal(object, memberships.remove(object));
    }

    /**Appends distinct broad-phase candidates to the supplied collection.*/
    public synchronized void query(double minX, double minZ, double maxX, double maxZ, Collection<T> output) {
        queryScratch.clear();
        CellRange range = getRange(minX, minZ, maxX, maxZ);
        if (range.cellCount > maximumCellsPerObject) {
            queryScratch.addAll(memberships.keySet());
        } else {
            for (long key : range.toKeys()) {
                Set<T> bucket = cells.get(key);
                if (bucket != null) {
                    queryScratch.addAll(bucket);
                }
            }
            queryScratch.addAll(oversized);
        }
        output.addAll(queryScratch);
    }

    public synchronized int size() {
        return memberships.size();
    }

    private void removeInternal(T object, long[] membership) {
        oversized.remove(object);
        if (membership != null) {
            for (long key : membership) {
                Set<T> bucket = cells.get(key);
                if (bucket != null) {
                    bucket.remove(object);
                    if (bucket.isEmpty()) {
                        cells.remove(key);
                    }
                }
            }
        }
    }

    private CellRange getRange(double minX, double minZ, double maxX, double maxZ) {
        int minCellX = floorToCell(Math.min(minX, maxX));
        int minCellZ = floorToCell(Math.min(minZ, maxZ));
        int maxCellX = floorToCell(Math.max(minX, maxX));
        int maxCellZ = floorToCell(Math.max(minZ, maxZ));
        return new CellRange(minCellX, minCellZ, maxCellX, maxCellZ);
    }

    private int floorToCell(double coordinate) {
        double cell = Math.floor(coordinate / cellSize);
        if (cell <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (cell >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) cell;
    }

    private static long getKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private static final class CellRange {
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final long cellCount;

        private CellRange(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            long width = (long) maxX - minX + 1L;
            long depth = (long) maxZ - minZ + 1L;
            this.cellCount = width > Long.MAX_VALUE / depth ? Long.MAX_VALUE : width * depth;
        }

        private long[] toKeys() {
            long[] keys = new long[(int) cellCount];
            int index = 0;
            for (int x = minX; x <= maxX; ++x) {
                for (int z = minZ; z <= maxZ; ++z) {
                    keys[index++] = getKey(x, z);
                    if (z == Integer.MAX_VALUE) {
                        break;
                    }
                }
                if (x == Integer.MAX_VALUE) {
                    break;
                }
            }
            return keys;
        }
    }
}
