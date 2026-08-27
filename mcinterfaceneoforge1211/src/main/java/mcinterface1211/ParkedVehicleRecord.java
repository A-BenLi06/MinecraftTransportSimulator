package mcinterface1211;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.VehicleParkingState;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup.CollisionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;

/**
 * Complete durable representation of a vehicle while its ticking entity graph is absent.
 * The opaque vehicle tag is produced directly by the normal MTS save path.
 */
final class ParkedVehicleRecord {
    private static final int SCHEMA_VERSION = 1;

    final UUID vehicleId;
    final String entityId;
    final CompoundTag vehicleData;
    final double x;
    final double y;
    final double z;
    final AABB bounds;
    final List<AABB> collisionBoxes;
    final List<UUID> partIds;
    VehicleParkingState state;
    long generation;
    private byte[] compressedRenderSnapshot;

    private ParkedVehicleRecord(UUID vehicleId, String entityId, CompoundTag vehicleData, double x, double y, double z, AABB bounds, List<AABB> collisionBoxes, List<UUID> partIds, VehicleParkingState state, long generation) {
        this.vehicleId = vehicleId;
        this.entityId = entityId;
        this.vehicleData = vehicleData;
        this.x = x;
        this.y = y;
        this.z = z;
        this.bounds = bounds;
        this.collisionBoxes = Collections.unmodifiableList(collisionBoxes);
        this.partIds = Collections.unmodifiableList(partIds);
        this.state = state;
        this.generation = generation;
    }

    static ParkedVehicleRecord capture(EntityVehicleF_Physics vehicle, long generation) {
        CompoundTag vehicleTag = new CompoundTag();
        vehicle.save(new WrapperNBT(vehicleTag));
        List<AABB> collisionBoxes = new ArrayList<>();
        for (BoundingBox box : vehicle.allCollisionBoxes) {
            if (box.collisionTypes.contains(CollisionType.ENTITY)) {
                collisionBoxes.add(WrapperWorld.convert(box));
            }
        }
        List<UUID> partIds = new ArrayList<>();
        vehicle.allParts.forEach(part -> partIds.add(part.uniqueUUID));
        return new ParkedVehicleRecord(
                vehicle.uniqueUUID,
                vehicle.getClass().getSimpleName(),
                vehicleTag,
                vehicle.position.x,
                vehicle.position.y,
                vehicle.position.z,
                WrapperWorld.convert(vehicle.encompassingBox),
                collisionBoxes,
                partIds,
                VehicleParkingState.PARKING_PENDING,
                generation);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA_VERSION);
        tag.putUUID("vehicleUUID", vehicleId);
        tag.putString("entityId", entityId);
        tag.put("vehicleData", vehicleData.copy());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.put("bounds", saveBox(bounds));
        ListTag boxesTag = new ListTag();
        for (AABB box : collisionBoxes) {
            boxesTag.add(saveBox(box));
        }
        tag.put("collisionBoxes", boxesTag);
        ListTag partIdsTag = new ListTag();
        for (UUID partId : partIds) {
            partIdsTag.add(StringTag.valueOf(partId.toString()));
        }
        tag.put("partUUIDs", partIdsTag);
        tag.putString("state", state.name());
        tag.putLong("generation", generation);
        return tag;
    }

    static ParkedVehicleRecord load(CompoundTag tag) {
        int schema = tag.getInt("schema");
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported parked vehicle schema " + schema);
        }
        List<AABB> collisionBoxes = new ArrayList<>();
        ListTag boxesTag = tag.getList("collisionBoxes", Tag.TAG_COMPOUND);
        for (int index = 0; index < boxesTag.size(); ++index) {
            collisionBoxes.add(loadBox(boxesTag.getCompound(index)));
        }
        List<UUID> partIds = new ArrayList<>();
        ListTag partIdsTag = tag.getList("partUUIDs", Tag.TAG_STRING);
        for (int index = 0; index < partIdsTag.size(); ++index) {
            partIds.add(UUID.fromString(partIdsTag.getString(index)));
        }
        return new ParkedVehicleRecord(
                tag.getUUID("vehicleUUID"),
                tag.getString("entityId"),
                tag.getCompound("vehicleData").copy(),
                tag.getDouble("x"),
                tag.getDouble("y"),
                tag.getDouble("z"),
                loadBox(tag.getCompound("bounds")),
                collisionBoxes,
                partIds,
                VehicleParkingState.valueOf(tag.getString("state")),
                tag.getLong("generation"));
    }

    byte[] getCompressedRenderSnapshot() throws IOException {
        if (compressedRenderSnapshot == null) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(vehicleData, output);
            compressedRenderSnapshot = output.toByteArray();
        }
        return compressedRenderSnapshot;
    }

    private static CompoundTag saveBox(AABB box) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("minX", box.minX);
        tag.putDouble("minY", box.minY);
        tag.putDouble("minZ", box.minZ);
        tag.putDouble("maxX", box.maxX);
        tag.putDouble("maxY", box.maxY);
        tag.putDouble("maxZ", box.maxZ);
        return tag;
    }

    private static AABB loadBox(CompoundTag tag) {
        return new AABB(tag.getDouble("minX"), tag.getDouble("minY"), tag.getDouble("minZ"), tag.getDouble("maxX"), tag.getDouble("maxY"), tag.getDouble("maxZ"));
    }
}
