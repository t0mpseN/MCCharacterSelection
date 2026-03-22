package net.tompsen.charsel.mixin;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.tompsen.charsel.CharacterDataManager;
import net.tompsen.charsel.CharacterDto;
import net.tompsen.charsel.CharacterSelection;
import net.tompsen.charsel.ModDataScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "remove", at = @At("TAIL"))
    private void afterRemove(ServerPlayerEntity player, CallbackInfo ci) {
        // Runs on Server thread AFTER savePlayerData() → advancements/stats are already written
        CharacterSelection.LOGGER.info("[CharSel] afterRemove fired on thread: {}",
                Thread.currentThread().getName());
        CharacterDataManager.saveCurrentCharacter(player);
        CharacterSelection.clearSelectedCharacter(player);
        CharacterSelection.playerJoinTick.remove(player.getUuid());
        // Fix bug 1: reset selectedCharacter so picker shows again next world
        if (!player.server.isDedicated()) {
            CharacterSelection.selectedCharacter = null;
        }
    }

    @Inject(method = "getAdvancementTracker", at = @At("HEAD"))
    private void beforeGetAdvancementTracker(ServerPlayerEntity player, CallbackInfoReturnable<PlayerAdvancementTracker> cir) {
        Map<UUID, PlayerAdvancementTracker> trackers = ((PlayerManagerAccessor)(Object)this).getAdvancementTrackers();
        if (trackers.containsKey(player.getUuid())) return;

        UUID lastCharId = CharacterSelection.DATA_FILE_MANAGER.getLastUsed(player.getUuid());
        if (lastCharId == null) return;

        CharacterSelection.DATA_FILE_MANAGER.findById(lastCharId).ifPresent(character -> {
            Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            String uuidStr = player.getUuid().toString();
            Path advFile = worldDir.resolve("advancements/" + uuidStr + ".json");

            if (!character.modData().isEmpty()) {
                // Existing character — restore saved advancement data
                NbtCompound advOnly = new NbtCompound();
                for (String key : character.modData().getKeys()) {
                    if (key.startsWith("advancements/") || key.startsWith("stats/")) {
                        advOnly.put(key, character.modData().get(key));
                    }
                }
                if (!advOnly.isEmpty()) {
                    ModDataScanner.restorePlayerModData(player, advOnly);
                    CharacterSelection.LOGGER.info("[CharSel] Pre-wrote advancements for {}", player.getName().getString());
                }
            } else {
                // New character — delete any existing advancement file so it starts fresh
                try {
                    if (Files.exists(advFile)) {
                        Files.delete(advFile);
                        CharacterSelection.LOGGER.info("[CharSel] Cleared stale advancements for new character {}", player.getName().getString());
                    }
                } catch (IOException e) {
                    CharacterSelection.LOGGER.warn("[CharSel] Failed to clear advancements: {}", e.getMessage());
                }
            }
        });
    }
}