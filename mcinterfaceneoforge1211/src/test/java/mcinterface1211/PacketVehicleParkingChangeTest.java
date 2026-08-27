package mcinterface1211;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import minecrafttransportsimulator.packets.instances.PacketVehicleParkingChange;

class PacketVehicleParkingChangeTest {
    private static final UUID VEHICLE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void outgoingPacketRejectsOversizedAssembly() {
        assertThrows(IllegalArgumentException.class, () -> new PacketVehicleParkingChange(
                VEHICLE_ID,
                1L,
                "EntityVehicleF_Physics",
                0,
                PacketVehicleParkingChange.MAX_TOTAL_CHUNKS + 1,
                new byte[1]));
    }

    @Test
    void outgoingPacketRejectsOversizedChunk() {
        assertThrows(IllegalArgumentException.class, () -> new PacketVehicleParkingChange(
                VEHICLE_ID,
                1L,
                "EntityVehicleF_Physics",
                0,
                1,
                new byte[PacketVehicleParkingChange.MAX_CHUNK_BYTES + 1]));
    }

    @Test
    void decoderRejectsInvalidChunkCountBeforeReadingPayload() {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeLong(VEHICLE_ID.getMostSignificantBits());
        buffer.writeLong(VEHICLE_ID.getLeastSignificantBits());
        buffer.writeLong(1L);
        buffer.writeInt(-1);
        try {
            assertThrows(IllegalArgumentException.class, () -> new PacketVehicleParkingChange(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void removalPacketRemainsValid() {
        assertDoesNotThrow(() -> new PacketVehicleParkingChange(VEHICLE_ID, 2L));
    }
}
