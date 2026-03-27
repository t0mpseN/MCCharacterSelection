package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

/**
 * Client → Server: "I chose this character."
 * Carries the full lightweight DTO so the dedicated server can store the selection
 * without needing its own copy of the client-side characters list.
 */
public record SelectCharacterPayload(CharacterDto character) implements CustomPayload {
    public static final Id<SelectCharacterPayload> ID =
            new Id<>(Identifier.of("nexuscharacters", "select_character"));
    public static final PacketCodec<PacketByteBuf, SelectCharacterPayload> CODEC =
            PacketCodec.of(SelectCharacterPayload::write, SelectCharacterPayload::new);

    public SelectCharacterPayload(PacketByteBuf buf) {
        this(CharacterDto.fromNbt(buf.readNbt()));
    }

    public void write(PacketByteBuf buf) {
        buf.writeNbt(character.toNbt());
    }

    /** Convenience accessor — matches old call sites that used .characterId() */
    public UUID characterId() { return character.id(); }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}