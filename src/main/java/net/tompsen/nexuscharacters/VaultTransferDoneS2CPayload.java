package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

public record VaultTransferDoneS2CPayload(UUID characterId) implements CustomPayload {
    public static final Id<VaultTransferDoneS2CPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_done_s2c"));
    public static final PacketCodec<PacketByteBuf, VaultTransferDoneS2CPayload> CODEC =
            PacketCodec.of(VaultTransferDoneS2CPayload::write, VaultTransferDoneS2CPayload::new);

    public VaultTransferDoneS2CPayload(PacketByteBuf buf) { this(buf.readUuid()); }
    public void write(PacketByteBuf buf) { buf.writeUuid(characterId); }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}