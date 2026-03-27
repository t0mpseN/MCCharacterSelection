package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record VaultChunkC2SPayload(int index, int total, byte[] data) implements CustomPayload {
    public static final Id<VaultChunkC2SPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_chunk_c2s"));
    public static final PacketCodec<PacketByteBuf, VaultChunkC2SPayload> CODEC =
            PacketCodec.of(VaultChunkC2SPayload::write, VaultChunkC2SPayload::new);

    public VaultChunkC2SPayload(PacketByteBuf buf) {
        this(buf.readInt(), buf.readInt(), buf.readByteArray());
    }
    public void write(PacketByteBuf buf) {
        buf.writeInt(index); buf.writeInt(total); buf.writeByteArray(data);
    }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}