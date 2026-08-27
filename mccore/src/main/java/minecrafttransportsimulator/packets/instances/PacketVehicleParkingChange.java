package minecrafttransportsimulator.packets.instances;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.packets.components.APacketBase;

/** Synchronizes a generation-ordered parked render snapshot to one client. */
public class PacketVehicleParkingChange extends APacketBase {
    public static final int MAX_CHUNK_BYTES = 24 * 1024;
    public static final int MAX_TOTAL_CHUNKS = 512;
    public static final int MAX_COMPRESSED_BYTES = MAX_CHUNK_BYTES * MAX_TOTAL_CHUNKS;

    private final UUID vehicleId;
    private final long generation;
    private final String entityId;
    private final int chunkIndex;
    private final int totalChunks;
    private final byte[] compressedDataChunk;

    public PacketVehicleParkingChange(UUID vehicleId, long generation, String entityId, int chunkIndex, int totalChunks, byte[] compressedDataChunk) {
        super(null);
        this.vehicleId = vehicleId;
        this.generation = generation;
        this.entityId = entityId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.compressedDataChunk = compressedDataChunk;
        validateChunkMetadata();
    }

    public PacketVehicleParkingChange(UUID vehicleId, long generation) {
        this(vehicleId, generation, "", 0, 0, new byte[0]);
    }

    public PacketVehicleParkingChange(ByteBuf buf) {
        super(buf);
        this.vehicleId = readUUIDFromBuffer(buf);
        this.generation = buf.readLong();
        this.totalChunks = buf.readInt();
        if (totalChunks < 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("Invalid parked vehicle snapshot chunk count");
        }
        if (totalChunks > 0) {
            this.entityId = readStringFromBuffer(buf);
            this.chunkIndex = buf.readInt();
            int chunkLength = buf.readInt();
            if (totalChunks > MAX_TOTAL_CHUNKS || chunkIndex < 0 || chunkIndex >= totalChunks || chunkLength < 0 || chunkLength > MAX_CHUNK_BYTES || chunkLength > buf.readableBytes()) {
                throw new IllegalArgumentException("Invalid parked vehicle snapshot chunk metadata");
            }
            this.compressedDataChunk = new byte[chunkLength];
            buf.readBytes(compressedDataChunk);
        } else {
            this.entityId = "";
            this.chunkIndex = 0;
            this.compressedDataChunk = new byte[0];
        }
        validateChunkMetadata();
    }

    @Override
    public void writeToBuffer(ByteBuf buf) {
        super.writeToBuffer(buf);
        writeUUIDToBuffer(vehicleId, buf);
        buf.writeLong(generation);
        buf.writeInt(totalChunks);
        if (totalChunks > 0) {
            writeStringToBuffer(entityId, buf);
            buf.writeInt(chunkIndex);
            buf.writeInt(compressedDataChunk.length);
            buf.writeBytes(compressedDataChunk);
        }
    }

    @Override
    public void handle(AWrapperWorld world) {
        world.updateParkedVehicleProxy(vehicleId, generation, entityId, chunkIndex, totalChunks, compressedDataChunk);
    }

    private void validateChunkMetadata() {
        if (totalChunks == 0) {
            if (chunkIndex != 0 || compressedDataChunk.length != 0) {
                throw new IllegalArgumentException("Removal packets cannot contain parked vehicle snapshot data");
            }
        } else if (totalChunks < 0 || totalChunks > MAX_TOTAL_CHUNKS || chunkIndex < 0 || chunkIndex >= totalChunks || compressedDataChunk.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Invalid parked vehicle snapshot chunk metadata");
        }
    }
}
