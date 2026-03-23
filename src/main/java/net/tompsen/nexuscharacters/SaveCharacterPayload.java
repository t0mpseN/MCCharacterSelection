package net.tompsen.nexuscharacters;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SaveCharacterPayload(NbtCompound characterNbt) implements CustomPayload {
    public static final CustomPayload.Id<SaveCharacterPayload> ID =
            new CustomPayload.Id<>(Identifier.of("charsel", "save_character"));

    public SaveCharacterPayload(PacketByteBuf buf) {
        this(buf.readNbt());
    }

    public void write(PacketByteBuf buf) {
        buf.writeNbt(characterNbt);
    }

    @Override
    public CustomPayload.Id<SaveCharacterPayload> getId() { return ID; }
}
