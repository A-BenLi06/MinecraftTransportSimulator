package mcinterface1211;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.VehicleParkingState;
import minecrafttransportsimulator.entities.components.AEntityA_Base;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.entities.components.AEntityF_Multipart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.instances.PacketVehicleParkingChange;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Owns server-side parked vehicle transitions and the spatial wake index. */
final class ParkedVehicleManager {
    private static final int INITIAL_RECONCILIATION_DELAY = 40;
    private static final int RECONCILIATION_INTERVAL = 200;
    private static final int SNAPSHOT_CHUNK_BYTES = 24 * 1024;

    private final WrapperWorld world;
    private final ServerLevel level;
    private final ParkedVehicleSavedData savedData;
    private final Map<Long, Set<UUID>> chunkIndex = new HashMap<>();
    private final Map<UUID, UUID> parkedEntityOwners = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> watchedVehicleChunks = new HashMap<>();
    private final Set<UUID> collisionWakeRequests = new HashSet<>();
    private int ticks;

    ParkedVehicleManager(WrapperWorld world, ServerLevel level) {
        this.world = world;
        this.level = level;
        this.savedData = level.getDataStorage().computeIfAbsent(ParkedVehicleSavedData.FACTORY, ParkedVehicleSavedData.DATA_NAME);
        rebuildIndex();
    }

    boolean park(EntityVehicleF_Physics vehicle) {
        if (!vehicle.isValid || savedData.get(vehicle.uniqueUUID) != null) {
            return false;
        }
        BuilderEntityExisting builder = InterfaceInterface.toExternal(vehicle);
        if (builder == null || builder.isRemoved()) {
            return false;
        }

        ParkedVehicleRecord record;
        try {
            record = ParkedVehicleRecord.capture(vehicle, 1L);
        } catch (Exception exception) {
            InterfaceManager.coreInterface.logError("Could not capture vehicle " + vehicle.uniqueUUID + " for parking: " + exception.getMessage());
            return false;
        }
        savedData.put(record);
        addToIndex(record);
        try {
            savedData.saveDurably(level);
        } catch (IOException exception) {
            savedData.remove(record.vehicleId);
            removeFromIndex(record);
            InterfaceManager.coreInterface.logError("Could not durably prepare parked vehicle " + record.vehicleId + ": " + exception.getMessage());
            return false;
        }

        vehicle.remove();
        builder.discard();
        record.state = VehicleParkingState.PARKED;
        ++record.generation;
        savedData.setDirty();
        try {
            savedData.saveDurably(level);
        } catch (IOException exception) {
            InterfaceManager.coreInterface.logError("Parked vehicle " + record.vehicleId + " remains recoverable in PARKING_PENDING after a final save failure: " + exception.getMessage());
        }
        syncRecordToCurrentWatchers(record);
        return true;
    }

    void onChunkSent(ServerPlayer player, ChunkPos chunkPosition) {
        Set<UUID> indexed = chunkIndex.get(chunkPosition.toLong());
        if (indexed != null) {
            for (UUID vehicleId : indexed) {
                ParkedVehicleRecord record = savedData.get(vehicleId);
                if (record != null && record.state == VehicleParkingState.PARKED) {
                    track(player, record);
                }
            }
        }
    }

    void onChunkUnwatched(ServerPlayer player, ChunkPos chunkPosition) {
        Set<UUID> indexed = chunkIndex.get(chunkPosition.toLong());
        if (indexed != null) {
            for (UUID vehicleId : indexed) {
                untrack(player, vehicleId, 1);
            }
        }
    }

    boolean wakeByEntityId(UUID entityId, String reason) {
        ParkedVehicleRecord record = savedData.get(entityId);
        if (record == null) {
            UUID vehicleId = parkedEntityOwners.get(entityId);
            record = vehicleId != null ? savedData.get(vehicleId) : null;
        }
        return record != null && record.state == VehicleParkingState.PARKED && wake(record, reason);
    }

    void wakeInArea(AABB area, String reason) {
        for (UUID vehicleId : getIndexedVehicleIds(area)) {
            ParkedVehicleRecord record = savedData.get(vehicleId);
            if (record != null && record.state == VehicleParkingState.PARKED && record.bounds.intersects(area)) {
                wake(record, reason);
            }
        }
    }

    List<AABB> getCollisionBoxes(AABB query, boolean wakeOnCollision) {
        List<AABB> collisions = new ArrayList<>();
        if (!ConfigSystem.settings.parking.staticCollision.value) {
            return collisions;
        }
        Set<UUID> candidates = getIndexedVehicleIds(query);
        for (UUID vehicleId : candidates) {
            ParkedVehicleRecord record = savedData.get(vehicleId);
            if (record != null && record.state == VehicleParkingState.PARKED && record.bounds.intersects(query)) {
                boolean intersects = false;
                for (AABB collisionBox : record.collisionBoxes) {
                    if (collisionBox.intersects(query)) {
                        collisions.add(collisionBox);
                        intersects = true;
                    }
                }
                if (intersects && wakeOnCollision) {
                    collisionWakeRequests.add(record.vehicleId);
                }
            }
        }
        return collisions;
    }

    void tick() {
        ++ticks;
        if (!collisionWakeRequests.isEmpty()) {
            Set<UUID> wakeRequests = new HashSet<>(collisionWakeRequests);
            collisionWakeRequests.clear();
            for (UUID vehicleId : wakeRequests) {
                ParkedVehicleRecord record = savedData.get(vehicleId);
                if (record != null && record.state == VehicleParkingState.PARKED) {
                    wake(record, "entity collision");
                }
            }
        }
        if (ticks == INITIAL_RECONCILIATION_DELAY || ticks > INITIAL_RECONCILIATION_DELAY && ticks % RECONCILIATION_INTERVAL == 0) {
            reconcileTransitions();
        }

        int scanInterval = Math.max(1, ConfigSystem.settings.parking.wakeScanIntervalTicks.value);
        if (ticks >= INITIAL_RECONCILIATION_DELAY && ticks % scanInterval == 0 && !chunkIndex.isEmpty()) {
            wakeVehiclesNearPlayers();
        }
    }

    private void wakeVehiclesNearPlayers() {
        double wakeDistance = Math.max(0D, ConfigSystem.settings.parking.wakeDistance.value);
        if (wakeDistance == 0D) {
            return;
        }
        int chunkRadius = (int) Math.ceil(wakeDistance / 16D) + 1;
        double wakeDistanceSquared = wakeDistance * wakeDistance;
        Set<UUID> candidates = new HashSet<>();
        for (Player player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            for (int chunkX = playerChunk.x - chunkRadius; chunkX <= playerChunk.x + chunkRadius; ++chunkX) {
                for (int chunkZ = playerChunk.z - chunkRadius; chunkZ <= playerChunk.z + chunkRadius; ++chunkZ) {
                    Set<UUID> indexed = chunkIndex.get(ChunkPos.asLong(chunkX, chunkZ));
                    if (indexed != null) {
                        candidates.addAll(indexed);
                    }
                }
            }
            for (UUID vehicleId : candidates) {
                ParkedVehicleRecord record = savedData.get(vehicleId);
                if (record != null && record.state == VehicleParkingState.PARKED && player.distanceToSqr(record.x, record.y, record.z) <= wakeDistanceSquared) {
                    wake(record, "player proximity");
                }
            }
            candidates.clear();
        }
    }

    private boolean wake(ParkedVehicleRecord record, String reason) {
        AEntityA_Base existing = world.getEntity(record.vehicleId);
        if (existing != null) {
            removeRecord(record);
            return true;
        }
        if (!BuilderEntityExisting.entityMap.containsKey(record.entityId)) {
            InterfaceManager.coreInterface.logError("Cannot wake parked vehicle " + record.vehicleId + " because entity factory " + record.entityId + " is unavailable. The parked record was retained.");
            return false;
        }

        removeFromIndex(record);
        untrackRecord(record);
        record.state = VehicleParkingState.WAKING;
        ++record.generation;
        savedData.setDirty();
        try {
            savedData.saveDurably(level);
        } catch (IOException exception) {
            record.state = VehicleParkingState.PARKED;
            addToIndex(record);
            syncRecordToCurrentWatchers(record);
            InterfaceManager.coreInterface.logError("Could not begin waking parked vehicle " + record.vehicleId + ": " + exception.getMessage());
            return false;
        }

        EntityVehicleF_Physics vehicle = null;
        BuilderEntityExisting builder = null;
        try {
            WrapperNBT data = new WrapperNBT(record.vehicleData.copy());
            AEntityB_Existing restored = BuilderEntityExisting.entityMap.get(record.entityId).restoreEntityFromData(world, data);
            if (!(restored instanceof EntityVehicleF_Physics)) {
                throw new IllegalStateException("Factory restored " + restored.getClass().getName() + " instead of a vehicle");
            }
            vehicle = (EntityVehicleF_Physics) restored;
            if (!record.vehicleId.equals(vehicle.uniqueUUID)) {
                throw new IllegalStateException("Restored UUID " + vehicle.uniqueUUID + " does not match parked UUID " + record.vehicleId);
            }
            vehicle.setParkingState(VehicleParkingState.WAKING);
            builder = world.spawnEntityInternal(vehicle);
            if (vehicle instanceof AEntityF_Multipart) {
                ((AEntityF_Multipart<?>) vehicle).addPartsPostAddition(null, data);
            }
            vehicle.setParkingState(VehicleParkingState.ACTIVE);
            removeRecord(record);
            InterfaceManager.coreInterface.logError("Woke parked vehicle " + record.vehicleId + " due to " + reason + ".");
            return true;
        } catch (Exception exception) {
            if (vehicle != null) {
                vehicle.remove();
            }
            if (builder != null) {
                builder.discard();
            }
            record.state = VehicleParkingState.PARKED;
            addToIndex(record);
            syncRecordToCurrentWatchers(record);
            savedData.setDirty();
            try {
                savedData.saveDurably(level);
            } catch (IOException saveException) {
                InterfaceManager.coreInterface.logError("Could not persist recovery after wake failure for " + record.vehicleId + ": " + saveException.getMessage());
            }
            InterfaceManager.coreInterface.logError("Failed to wake parked vehicle " + record.vehicleId + "; its record was retained: " + exception.getMessage());
            return false;
        }
    }

    private void reconcileTransitions() {
        List<ParkedVehicleRecord> snapshot = new ArrayList<>(savedData.records());
        boolean changed = false;
        for (ParkedVehicleRecord record : snapshot) {
            boolean hasLiveEntity = world.getEntity(record.vehicleId) != null;
            if (hasLiveEntity) {
                removeFromIndex(record);
                untrackRecord(record);
                savedData.remove(record.vehicleId);
                changed = true;
            } else if (record.state == VehicleParkingState.PARKING_PENDING || record.state == VehicleParkingState.WAKING || record.state == VehicleParkingState.ACTIVE) {
                record.state = VehicleParkingState.PARKED;
                ++record.generation;
                addToIndex(record);
                syncRecordToCurrentWatchers(record);
                savedData.setDirty();
                changed = true;
            }
        }
        if (changed) {
            try {
                savedData.saveDurably(level);
            } catch (IOException exception) {
                InterfaceManager.coreInterface.logError("Could not persist parked vehicle transition reconciliation: " + exception.getMessage());
            }
        }
        Set<UUID> onlinePlayers = new HashSet<>();
        for (Player player : level.players()) {
            onlinePlayers.add(player.getUUID());
        }
        watchedVehicleChunks.keySet().retainAll(onlinePlayers);
    }

    private void removeRecord(ParkedVehicleRecord record) {
        removeFromIndex(record);
        untrackRecord(record);
        savedData.remove(record.vehicleId);
        try {
            savedData.saveDurably(level);
        } catch (IOException exception) {
            InterfaceManager.coreInterface.logError("Could not persist removal of parked vehicle record " + record.vehicleId + "; startup reconciliation will resolve it: " + exception.getMessage());
        }
    }

    private void rebuildIndex() {
        chunkIndex.clear();
        for (ParkedVehicleRecord record : savedData.records()) {
            if (record.state == VehicleParkingState.PARKED || record.state == VehicleParkingState.PARKING_PENDING) {
                addToIndex(record);
            }
        }
    }

    private void addToIndex(ParkedVehicleRecord record) {
        parkedEntityOwners.put(record.vehicleId, record.vehicleId);
        for (UUID partId : record.partIds) {
            parkedEntityOwners.put(partId, record.vehicleId);
        }
        int minChunkX = ((int) Math.floor(record.bounds.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(record.bounds.maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(record.bounds.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(record.bounds.maxZ)) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                chunkIndex.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> new HashSet<>()).add(record.vehicleId);
            }
        }
    }

    private void removeFromIndex(ParkedVehicleRecord record) {
        parkedEntityOwners.remove(record.vehicleId, record.vehicleId);
        for (UUID partId : record.partIds) {
            parkedEntityOwners.remove(partId, record.vehicleId);
        }
        int minChunkX = ((int) Math.floor(record.bounds.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(record.bounds.maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(record.bounds.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(record.bounds.maxZ)) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                Set<UUID> indexed = chunkIndex.get(chunkKey);
                if (indexed != null) {
                    indexed.remove(record.vehicleId);
                    if (indexed.isEmpty()) {
                        chunkIndex.remove(chunkKey);
                    }
                }
            }
        }
    }

    private Set<UUID> getIndexedVehicleIds(AABB query) {
        Set<UUID> vehicleIds = new HashSet<>();
        int minChunkX = ((int) Math.floor(query.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(query.maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(query.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(query.maxZ)) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                Set<UUID> indexed = chunkIndex.get(ChunkPos.asLong(chunkX, chunkZ));
                if (indexed != null) {
                    vehicleIds.addAll(indexed);
                }
            }
        }
        return vehicleIds;
    }

    private void syncRecordToCurrentWatchers(ParkedVehicleRecord record) {
        int minChunkX = ((int) Math.floor(record.bounds.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(record.bounds.maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(record.bounds.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(record.bounds.maxZ)) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunkX, chunkZ), false)) {
                    track(player, record);
                }
            }
        }
    }

    private void track(ServerPlayer player, ParkedVehicleRecord record) {
        double renderDistance = Math.max(0D, ConfigSystem.settings.parking.clientRenderDistance.value);
        if (renderDistance == 0D || player.distanceToSqr(record.x, record.y, record.z) > renderDistance * renderDistance) {
            return;
        }
        Map<UUID, Integer> playerRecords = watchedVehicleChunks.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
        int watchedChunks = playerRecords.getOrDefault(record.vehicleId, 0);
        if (watchedChunks == 0) {
            if (!sendSnapshot(player, record)) {
                if (playerRecords.isEmpty()) {
                    watchedVehicleChunks.remove(player.getUUID());
                }
                return;
            }
        }
        playerRecords.put(record.vehicleId, watchedChunks + 1);
    }

    private boolean sendSnapshot(ServerPlayer player, ParkedVehicleRecord record) {
        try {
            byte[] compressedSnapshot = record.getCompressedRenderSnapshot();
            int totalChunks = Math.max(1, (compressedSnapshot.length + SNAPSHOT_CHUNK_BYTES - 1) / SNAPSHOT_CHUNK_BYTES);
            for (int chunkIndex = 0; chunkIndex < totalChunks; ++chunkIndex) {
                int start = chunkIndex * SNAPSHOT_CHUNK_BYTES;
                int end = Math.min(start + SNAPSHOT_CHUNK_BYTES, compressedSnapshot.length);
                byte[] chunk = Arrays.copyOfRange(compressedSnapshot, start, end);
                WrapperPlayer.getWrapperFor(player).sendPacket(new PacketVehicleParkingChange(record.vehicleId, record.generation, record.entityId, chunkIndex, totalChunks, chunk));
            }
            return true;
        } catch (IOException exception) {
            InterfaceManager.coreInterface.logError("Could not compress parked vehicle render snapshot " + record.vehicleId + ": " + exception.getMessage());
            return false;
        }
    }

    private void untrack(ServerPlayer player, UUID vehicleId, int chunks) {
        Map<UUID, Integer> playerRecords = watchedVehicleChunks.get(player.getUUID());
        if (playerRecords == null) {
            return;
        }
        Integer watchedChunks = playerRecords.get(vehicleId);
        if (watchedChunks == null) {
            return;
        }
        if (watchedChunks <= chunks) {
            playerRecords.remove(vehicleId);
            long generation = savedData.get(vehicleId) != null ? savedData.get(vehicleId).generation : Long.MAX_VALUE;
            WrapperPlayer.getWrapperFor(player).sendPacket(new PacketVehicleParkingChange(vehicleId, generation));
        } else {
            playerRecords.put(vehicleId, watchedChunks - chunks);
        }
        if (playerRecords.isEmpty()) {
            watchedVehicleChunks.remove(player.getUUID());
        }
    }

    private void untrackRecord(ParkedVehicleRecord record) {
        for (Player player : level.players()) {
            Map<UUID, Integer> playerRecords = watchedVehicleChunks.get(player.getUUID());
            if (playerRecords != null) {
                Integer watchedChunks = playerRecords.get(record.vehicleId);
                if (watchedChunks != null) {
                    untrack((ServerPlayer) player, record.vehicleId, watchedChunks);
                }
            }
        }
    }
}
