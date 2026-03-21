package net.tompsen.charsel.mixin;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.tompsen.charsel.CharacterSelection;
import net.tompsen.charsel.ModDataScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.UUID;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "loadPlayerData", at = @At("HEAD"))
    private void beforeLoadPlayerData(ServerPlayerEntity player, CallbackInfoReturnable<NbtCompound> cir) {
        UUID playerUuid = player.getUuid();
        UUID lastCharId = CharacterSelection.DATA_FILE_MANAGER.getLastUsed(playerUuid);
        if (lastCharId == null) return;

        CharacterSelection.DATA_FILE_MANAGER.findById(lastCharId).ifPresent(character -> {
            if (character.modData().isEmpty()) return;
            ModDataScanner.restorePlayerModData(player, character.modData());
            CharacterSelection.LOGGER.info("Pre-restored mod data for {} before mods loaded",
                    player.getName().getString());
        });
    }
}