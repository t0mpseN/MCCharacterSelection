package net.tompsen.charsel;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SkinReloadPayload(String skinValue, String skinSignature) implements CustomPayload {
    public static final Id<SkinReloadPayload> ID =
            new Id<>(Identifier.of(CharacterSelection.MOD_ID, "skin_reload"));
    public static final PacketCodec<PacketByteBuf, SkinReloadPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING, SkinReloadPayload::skinValue,
                    PacketCodecs.STRING, SkinReloadPayload::skinSignature,
                    SkinReloadPayload::new
            );
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}