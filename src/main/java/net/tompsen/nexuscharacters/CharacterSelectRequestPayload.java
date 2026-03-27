package net.tompsen.nexuscharacters;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → Client (configuration phase):
 * "Please select a character before entering the world."
 */
public record CharacterSelectRequestPayload() implements CustomPayload {
    public static final Id<CharacterSelectRequestPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "char_select_request"));
    public static final PacketCodec<net.minecraft.network.PacketByteBuf, CharacterSelectRequestPayload> CODEC =
            PacketCodec.unit(new CharacterSelectRequestPayload());
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
