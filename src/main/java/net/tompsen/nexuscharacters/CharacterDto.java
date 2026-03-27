package net.tompsen.nexuscharacters;

import net.minecraft.nbt.NbtCompound;
import java.util.UUID;

/**
 * Lightweight character metadata only. No player NBT, no mod data, no world positions.
 * All heavy data lives in VaultManager.
 */
public record CharacterDto(
        UUID   id,
        String name,
        String skinValue,
        String skinSignature,
        String skinUsername,
        int    gameMode,   // 0=survival, 1=creative, 2=adventure, 3=spectator
        boolean hardcore
) {
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putUuid("id", id);
        nbt.putString("name", name);
        nbt.putString("skinValue",      skinValue      != null ? skinValue      : "");
        nbt.putString("skinSignature",  skinSignature  != null ? skinSignature  : "");
        nbt.putString("skinUsername",   skinUsername   != null ? skinUsername   : "");
        nbt.putInt("gameMode", gameMode);
        nbt.putBoolean("hardcore", hardcore);
        return nbt;
    }

    public static CharacterDto fromNbt(NbtCompound nbt) {
        return new CharacterDto(
                nbt.getUuid("id"),
                nbt.getString("name"),
                nbt.getString("skinValue"),
                nbt.getString("skinSignature"),
                nbt.getString("skinUsername"),
                nbt.getInt("gameMode"),
                nbt.getBoolean("hardcore")
        );
    }

    // ── Migration helper ────────────────────────────────────────────────────
    // Called once by DataFileManager when it finds a legacy record with playerNbt.
    public static CharacterDto fromLegacyNbt(NbtCompound nbt) {
        int gm = 0;
        boolean hc = false;
        if (nbt.contains("playerNbt")) {
            NbtCompound pnbt = nbt.getCompound("playerNbt");
            gm = pnbt.getInt("playerGameType");
            hc = pnbt.getBoolean("hardcore");
        }
        return new CharacterDto(
                nbt.getUuid("id"),
                nbt.getString("name"),
                nbt.getString("skinValue"),
                nbt.getString("skinSignature"),
                nbt.getString("skinUsername"),
                gm, hc
        );
    }
}