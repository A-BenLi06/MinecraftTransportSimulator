package mcinterface1211;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import mcinterface1211.mixin.common.DimensionDataStorageMixin;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;

/** Dimension-scoped storage for authoritative parked vehicle records. */
final class ParkedVehicleSavedData extends SavedData {
    static final String DATA_NAME = "mts_parked_vehicles";
    static final Factory<ParkedVehicleSavedData> FACTORY = new Factory<>(ParkedVehicleSavedData::new, ParkedVehicleSavedData::load);

    private final Map<UUID, ParkedVehicleRecord> records = new LinkedHashMap<>();
    private final Collection<CompoundTag> quarantinedRecords = new ArrayList<>();

    private static ParkedVehicleSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ParkedVehicleSavedData data = new ParkedVehicleSavedData();
        ListTag recordsTag = tag.getList("records", Tag.TAG_COMPOUND);
        for (int index = 0; index < recordsTag.size(); ++index) {
            CompoundTag recordTag = recordsTag.getCompound(index);
            try {
                ParkedVehicleRecord record = ParkedVehicleRecord.load(recordTag);
                if (data.records.containsKey(record.vehicleId)) {
                    throw new IllegalStateException("Duplicate parked vehicle UUID " + record.vehicleId);
                }
                data.records.put(record.vehicleId, record);
            } catch (Exception exception) {
                data.quarantinedRecords.add(recordTag.copy());
                InterfaceManager.coreInterface.logError("Retaining an unreadable parked vehicle record without activating it: " + exception.getMessage());
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag recordsTag = new ListTag();
        for (ParkedVehicleRecord record : records.values()) {
            recordsTag.add(record.save());
        }
        for (CompoundTag quarantinedRecord : quarantinedRecords) {
            recordsTag.add(quarantinedRecord.copy());
        }
        tag.put("records", recordsTag);
        return tag;
    }

    Collection<ParkedVehicleRecord> records() {
        return records.values();
    }

    ParkedVehicleRecord get(UUID vehicleId) {
        return records.get(vehicleId);
    }

    void put(ParkedVehicleRecord record) {
        records.put(record.vehicleId, record);
        setDirty();
    }

    ParkedVehicleRecord remove(UUID vehicleId) {
        ParkedVehicleRecord removed = records.remove(vehicleId);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    /**
     * Writes this data immediately through NeoForge's temporary-file replacement helper.
     * Parking transitions cannot wait for the next periodic world save.
     */
    void saveDurably(ServerLevel level) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("data", save(new CompoundTag(), level.registryAccess()));
        NbtUtils.addCurrentDataVersion(root);
        File dataFolder = ((DimensionDataStorageMixin) level.getDataStorage()).getDataFolder();
        IOUtilities.writeNbtCompressed(root, new File(dataFolder, DATA_NAME + ".dat").toPath());
        setDirty(false);
    }
}
