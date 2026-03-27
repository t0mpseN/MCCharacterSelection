package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

public record VaultTransferDoneC2SPayload(UUID characterId) implements CustomPayload {
    public static final Id<VaultTransferDoneC2SPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_done_c2s"));
    public static final PacketCodec<PacketByteBuf, VaultTransferDoneC2SPayload> CODEC =
            PacketCodec.of(VaultTransferDoneC2SPayload::write, VaultTransferDoneC2SPayload::new);

    public VaultTransferDoneC2SPayload(PacketByteBuf buf) { this(buf.readUuid()); }
    public void write(PacketByteBuf buf) { buf.writeUuid(characterId); }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}