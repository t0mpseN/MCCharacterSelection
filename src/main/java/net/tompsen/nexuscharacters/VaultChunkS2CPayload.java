package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record VaultChunkS2CPayload(int index, int total, byte[] data) implements CustomPayload {
    public static final Id<VaultChunkS2CPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_chunk_s2c"));
    public static final PacketCodec<PacketByteBuf, VaultChunkS2CPayload> CODEC =
            PacketCodec.of(VaultChunkS2CPayload::write, VaultChunkS2CPayload::new);

    public VaultChunkS2CPayload(PacketByteBuf buf) {
        this(buf.readInt(), buf.readInt(), buf.readByteArray());
    }
    public void write(PacketByteBuf buf) {
        buf.writeInt(index); buf.writeInt(total); buf.writeByteArray(data);
    }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}