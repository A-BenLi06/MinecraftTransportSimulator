package mcinterface1211;

import java.io.IOException;
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
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

/** Owns server-side parked vehicle transitions and the spatial wake index. */
final class ParkedVehicleManager {
    private static final int INITIAL_RECONCILIATION_DELAY = 40;
    private static final int RECONCILIATION_INTERVAL = 200;

    private final WrapperWorld world;
    private final ServerLevel level;
    private final ParkedVehicleSavedData savedData;
    private final Map<Long, Set<UUID>> chunkIndex = new HashMap<>();
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
        return true;
    }

    void tick() {
        ++ticks;
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
        record.state = VehicleParkingState.WAKING;
        ++record.generation;
        savedData.setDirty();
        try {
            savedData.saveDurably(level);
        } catch (IOException exception) {
            record.state = VehicleParkingState.PARKED;
            addToIndex(record);
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
                savedData.remove(record.vehicleId);
                changed = true;
            } else if (record.state == VehicleParkingState.PARKING_PENDING || record.state == VehicleParkingState.WAKING || record.state == VehicleParkingState.ACTIVE) {
                record.state = VehicleParkingState.PARKED;
                ++record.generation;
                addToIndex(record);
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
    }

    private void removeRecord(ParkedVehicleRecord record) {
        removeFromIndex(record);
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
}
