package mcinterface1211;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.VehicleParkingState;
import minecrafttransportsimulator.entities.components.AEntityA_Base;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.entities.components.AEntityF_Multipart;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup.CollisionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Client-only owner of static MTS render proxies. */
final class ParkedVehicleClientManager {
    private final WrapperWorld world;
    private final Map<UUID, ProxyEntry> proxies = new HashMap<>();
    private final Map<UUID, PendingSnapshot> pendingSnapshots = new HashMap<>();
    private final Map<UUID, PendingAssembly> pendingAssemblies = new HashMap<>();
    private final Map<UUID, Long> latestGenerations = new HashMap<>();

    ParkedVehicleClientManager(WrapperWorld world) {
        this.world = world;
    }

    void update(UUID vehicleId, long generation, String entityId, int chunkIndex, int totalChunks, byte[] compressedDataChunk) {
        long latestGeneration = latestGenerations.getOrDefault(vehicleId, Long.MIN_VALUE);
        if (generation < latestGeneration) {
            return;
        }
        latestGenerations.put(vehicleId, generation);
        if (totalChunks == 0) {
            pendingSnapshots.remove(vehicleId);
            pendingAssemblies.remove(vehicleId);
            ProxyEntry proxy = proxies.remove(vehicleId);
            if (proxy != null) {
                proxy.vehicle.remove();
            }
        } else {
            PendingAssembly assembly = pendingAssemblies.get(vehicleId);
            if (assembly == null || assembly.generation != generation || assembly.totalChunks != totalChunks || !assembly.entityId.equals(entityId)) {
                assembly = new PendingAssembly(generation, entityId, totalChunks);
                pendingAssemblies.put(vehicleId, assembly);
            }
            if (assembly.add(chunkIndex, compressedDataChunk)) {
                try {
                    WrapperNBT vehicleData = new WrapperNBT(NbtIo.readCompressed(new ByteArrayInputStream(assembly.join()), NbtAccounter.unlimitedHeap()));
                    pendingSnapshots.put(vehicleId, new PendingSnapshot(generation, entityId, vehicleData));
                } catch (IOException exception) {
                    InterfaceManager.coreInterface.logError("Could not decompress parked vehicle render snapshot " + vehicleId + ": " + exception.getMessage());
                }
                pendingAssemblies.remove(vehicleId);
            }
        }
    }

    void tick() {
        Iterator<Map.Entry<UUID, PendingSnapshot>> iterator = pendingSnapshots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingSnapshot> entry = iterator.next();
            UUID vehicleId = entry.getKey();
            PendingSnapshot snapshot = entry.getValue();
            if (latestGenerations.getOrDefault(vehicleId, Long.MIN_VALUE) != snapshot.generation) {
                iterator.remove();
                continue;
            }

            ProxyEntry currentProxy = proxies.get(vehicleId);
            if (currentProxy != null) {
                if (currentProxy.generation >= snapshot.generation) {
                    iterator.remove();
                    continue;
                }
                currentProxy.vehicle.remove();
                proxies.remove(vehicleId);
            }

            AEntityA_Base existing = world.getEntity(vehicleId);
            if (existing != null) {
                continue;
            }
            if (!BuilderEntityExisting.entityMap.containsKey(snapshot.entityId)) {
                continue;
            }

            EntityVehicleF_Physics vehicle = null;
            try {
                AEntityB_Existing restored = BuilderEntityExisting.entityMap.get(snapshot.entityId).restoreEntityFromData(world, snapshot.vehicleData);
                if (!(restored instanceof EntityVehicleF_Physics)) {
                    throw new IllegalStateException("Factory restored " + restored.getClass().getName() + " instead of a vehicle");
                }
                vehicle = (EntityVehicleF_Physics) restored;
                if (!vehicleId.equals(vehicle.uniqueUUID)) {
                    throw new IllegalStateException("Restored UUID " + vehicle.uniqueUUID + " does not match proxy UUID " + vehicleId);
                }
                vehicle.setParkingState(VehicleParkingState.PARKED);
                world.addEntity(vehicle);
                ((AEntityF_Multipart<?>) vehicle).addPartsPostAddition(null, snapshot.vehicleData);
                initializeStaticState(vehicle);
                proxies.put(vehicleId, new ProxyEntry(snapshot.generation, vehicle));
                iterator.remove();
            } catch (Exception exception) {
                if (vehicle != null) {
                    vehicle.remove();
                }
                InterfaceManager.coreInterface.logError("Could not construct parked vehicle render proxy " + vehicleId + ": " + exception.getMessage());
                iterator.remove();
            }
        }
    }

    private static void initializeStaticState(EntityVehicleF_Physics vehicle) {
        for (APart part : vehicle.allParts) {
            part.ticksExisted = 1;
        }
        for (int index = vehicle.allParts.size() - 1; index >= 0; --index) {
            APart part = vehicle.allParts.get(index);
            part.worldLightValue = part.getWorldLightValue();
            part.doPostUpdateLogic();
        }
        vehicle.ticksExisted = 1;
        vehicle.worldLightValue = vehicle.getWorldLightValue();
        vehicle.doPostUpdateLogic();
    }

    List<AABB> getCollisionBoxes(AABB query) {
        List<AABB> collisions = new ArrayList<>();
        for (ProxyEntry proxy : proxies.values()) {
            if (WrapperWorld.convert(proxy.vehicle.encompassingBox).intersects(query)) {
                for (BoundingBox box : proxy.vehicle.allCollisionBoxes) {
                    if (box.collisionTypes.contains(CollisionType.ENTITY)) {
                        AABB collision = WrapperWorld.convert(box);
                        if (collision.intersects(query)) {
                            collisions.add(collision);
                        }
                    }
                }
            }
        }
        return collisions;
    }

    private static final class PendingSnapshot {
        final long generation;
        final String entityId;
        final IWrapperNBT vehicleData;

        PendingSnapshot(long generation, String entityId, IWrapperNBT vehicleData) {
            this.generation = generation;
            this.entityId = entityId;
            this.vehicleData = vehicleData;
        }
    }

    private static final class PendingAssembly {
        final long generation;
        final String entityId;
        final int totalChunks;
        final byte[][] chunks;
        int chunksReceived;

        PendingAssembly(long generation, String entityId, int totalChunks) {
            this.generation = generation;
            this.entityId = entityId;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }

        boolean add(int chunkIndex, byte[] chunk) {
            if (chunks[chunkIndex] == null) {
                chunks[chunkIndex] = chunk;
                ++chunksReceived;
            }
            return chunksReceived == totalChunks;
        }

        byte[] join() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte[] chunk : chunks) {
                output.write(chunk);
            }
            return output.toByteArray();
        }
    }

    private static final class ProxyEntry {
        final long generation;
        final EntityVehicleF_Physics vehicle;

        ProxyEntry(long generation, EntityVehicleF_Physics vehicle) {
            this.generation = generation;
            this.vehicle = vehicle;
        }
    }
}
