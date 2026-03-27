package net.tompsen.nexuscharacters;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server → Client: "I'm ready, start sending your vault." */
public record VaultReceiveReadyPayload() implements CustomPayload {
    public static final Id<VaultReceiveReadyPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_receive_ready"));
    public static final PacketCodec<net.minecraft.network.PacketByteBuf, VaultReceiveReadyPayload> CODEC =
            PacketCodec.unit(new VaultReceiveReadyPayload());
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}