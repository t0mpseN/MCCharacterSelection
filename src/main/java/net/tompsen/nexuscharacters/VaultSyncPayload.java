package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server → Client: incremental vault sync.
 * Carries a map of vault-relative paths → file bytes for one character.
 * Sent every second during play so the client always has a fresh local copy.
 */
public record VaultSyncPayload(UUID characterId, Map<String, byte[]> files) implements CustomPayload {

    public static final Id<VaultSyncPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_sync"));
    public static final PacketCodec<PacketByteBuf, VaultSyncPayload> CODEC =
            PacketCodec.of(VaultSyncPayload::write, VaultSyncPayload::new);

    public VaultSyncPayload(PacketByteBuf buf) {
        this(buf.readUuid(), readFiles(buf));
    }

    private static Map<String, byte[]> readFiles(PacketByteBuf buf) {
        int count = buf.readVarInt();
        Map<String, byte[]> map = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            String path = buf.readString(4096);
            byte[] data = buf.readByteArray();
            map.put(path, data);
        }
        return map;
    }

    public void write(PacketByteBuf buf) {
        buf.writeUuid(characterId);
        buf.writeVarInt(files.size());
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeByteArray(entry.getValue());
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
