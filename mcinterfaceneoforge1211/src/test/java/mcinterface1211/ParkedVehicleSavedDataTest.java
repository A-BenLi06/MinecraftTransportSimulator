package mcinterface1211;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import minecrafttransportsimulator.baseclasses.VehicleParkingState;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

class ParkedVehicleSavedDataTest {
    @Test
    void recordRoundTripPreservesAuthoritativeFields() {
        UUID vehicleId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID partId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        CompoundTag original = createRecord(vehicleId, partId, 1);

        ParkedVehicleRecord record = ParkedVehicleRecord.load(original);
        CompoundTag roundTripped = record.save();

        assertEquals(original, roundTripped);
        assertEquals(vehicleId, record.vehicleId);
        assertEquals(partId, record.partIds.get(0));
        assertEquals(VehicleParkingState.PARKED, record.state);
        assertEquals(7L, record.generation);
        assertEquals(1, record.collisionBoxes.size());
        assertEquals("payload-value", record.vehicleData.getString("opaque"));
    }

    @Test
    void unsupportedSchemaIsRejectedByRecordDecoder() {
        CompoundTag unsupported = createRecord(UUID.randomUUID(), UUID.randomUUID(), 99);
        assertThrows(IllegalArgumentException.class, () -> ParkedVehicleRecord.load(unsupported));
    }

    @Test
    void unreadableRecordIsQuarantinedAndWrittenBackUnchanged() {
        CompoundTag unsupported = createRecord(UUID.randomUUID(), UUID.randomUUID(), 99);
        CompoundTag root = new CompoundTag();
        ListTag records = new ListTag();
        records.add(unsupported.copy());
        root.put("records", records);

        ParkedVehicleSavedData data = ParkedVehicleSavedData.FACTORY.deserializer().apply(root, RegistryAccess.EMPTY);
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);

        assertEquals(0, data.records().size());
        assertEquals(1, data.quarantinedCount());
        assertEquals(unsupported, saved.getList("records", Tag.TAG_COMPOUND).getCompound(0));
    }

    @Test
    void durableSaveWritesAReadableDimensionFile(@TempDir Path temporaryDirectory) throws IOException {
        ParkedVehicleSavedData data = new ParkedVehicleSavedData();
        UUID vehicleId = UUID.randomUUID();
        data.put(ParkedVehicleRecord.load(createRecord(vehicleId, UUID.randomUUID(), 1)));

        data.saveDurably(temporaryDirectory.toFile(), RegistryAccess.EMPTY);

        File dataFile = new File(temporaryDirectory.toFile(), ParkedVehicleSavedData.DATA_NAME + ".dat");
        assertTrue(dataFile.isFile());
        CompoundTag root = NbtIo.readCompressed(dataFile.toPath(), NbtAccounter.unlimitedHeap());
        ListTag records = root.getCompound("data").getList("records", Tag.TAG_COMPOUND);
        assertTrue(records.stream().anyMatch(tag -> ((CompoundTag) tag).getUUID("vehicleUUID").equals(vehicleId)));

        data.remove(vehicleId);
        data.saveDurably(temporaryDirectory.toFile(), RegistryAccess.EMPTY);
    }

    private static CompoundTag createRecord(UUID vehicleId, UUID partId, int schema) {
        CompoundTag record = new CompoundTag();
        record.putInt("schema", schema);
        record.putUUID("vehicleUUID", vehicleId);
        record.putString("entityId", "EntityVehicleF_Physics");
        CompoundTag payload = new CompoundTag();
        payload.putString("opaque", "payload-value");
        record.put("vehicleData", payload);
        record.putDouble("x", 12.5D);
        record.putDouble("y", 64D);
        record.putDouble("z", -9.25D);
        record.put("bounds", createBox(10D, 63D, -11D, 15D, 67D, -7D));
        ListTag collisionBoxes = new ListTag();
        collisionBoxes.add(createBox(11D, 63D, -10D, 14D, 66D, -8D));
        record.put("collisionBoxes", collisionBoxes);
        ListTag partIds = new ListTag();
        partIds.add(StringTag.valueOf(partId.toString()));
        record.put("partUUIDs", partIds);
        record.putString("state", VehicleParkingState.PARKED.name());
        record.putLong("generation", 7L);
        return record;
    }

    private static CompoundTag createBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        CompoundTag box = new CompoundTag();
        box.putDouble("minX", minX);
        box.putDouble("minY", minY);
        box.putDouble("minZ", minZ);
        box.putDouble("maxX", maxX);
        box.putDouble("maxY", maxY);
        box.putDouble("maxZ", maxZ);
        return box;
    }
}
