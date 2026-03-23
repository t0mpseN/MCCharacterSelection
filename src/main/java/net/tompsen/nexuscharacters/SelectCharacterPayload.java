package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

public record SelectCharacterPayload(CharacterDto character) implements CustomPayload {
    public static final CustomPayload.Id<SelectCharacterPayload> ID =
            new CustomPayload.Id<>(Identifier.of("charsel", "select_character"));

    public SelectCharacterPayload(PacketByteBuf buf) {
        this(CharacterDto.fromNbt(buf.readNbt()));
    }

    public void write(PacketByteBuf buf) {
        buf.writeNbt(character.toNbt());
    }

    @Override
    public CustomPayload.Id<SelectCharacterPayload> getId() { return ID; }
}