package net.tompsen.charsel.mixin;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.entity.player.PlayerEntity;
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

    @Inject(method = "createStatHandler", at = @At("HEAD"))
    private void beforeCreateStatHandler(PlayerEntity player, CallbackInfoReturnable<net.minecraft.stat.ServerStatHandler> cir) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            prepareCharacterData(serverPlayer);
        }
    }

    @Inject(method = "getAdvancementTracker", at = @At("HEAD"))
    private void beforeGetAdvancementTracker(ServerPlayerEntity player, CallbackInfoReturnable<PlayerAdvancementTracker> cir) {
        prepareCharacterData(player);
    }

    @Inject(method = "respawnPlayer", at = @At("RETURN"))
    private void onRespawn(ServerPlayerEntity player, boolean alive, net.minecraft.entity.Entity.RemovalReason removalReason, CallbackInfoReturnable<ServerPlayerEntity> cir) {
        ServerPlayerEntity newPlayer = cir.getReturnValue();
        if (CharacterSelection.deadHardcorePlayers.remove(newPlayer.getUuid())) {
            // Hardcore character died - it's already removed from the list in AFTER_DEATH.
            // Switch the NEW player instance to spectator and notify.
            newPlayer.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
            newPlayer.sendMessage(net.minecraft.text.Text.literal("§cYour character has perished and was removed from the list. §7You are now spectating."));
        }
    }

    private void prepareCharacterData(ServerPlayerEntity player) {
        Map<UUID, PlayerAdvancementTracker> trackers = ((PlayerManagerAccessor)(Object)this).getAdvancementTrackers();
        // If advancement tracker is already created, we likely already ran this logic via getStatsHandler or another call
        if (trackers.containsKey(player.getUuid())) return;

        // Use selectedCharacter if set (Singleplayer/Integrated), otherwise fallback to last used
        UUID charId = CharacterSelection.selectedCharacter != null
                ? CharacterSelection.selectedCharacter.id()
                : CharacterSelection.DATA_FILE_MANAGER.getLastUsed(player.getUuid());

        if (charId == null) return;

        CharacterSelection.DATA_FILE_MANAGER.findById(charId).ifPresent(character -> {
            // 1. Clear all stale files for this UUID first to ensure absolute isolation
            ModDataScanner.clearPlayerModData(player);

            // 2. Prepare ALL character-specific files to restore (Global + World-specific)
            String worldId = CharacterDataManager.getWorldId(player);
            String prefix = worldId + "::";
            NbtCompound toRestore = new NbtCompound();

            for (String key : character.modData().getKeys()) {
                if (key.startsWith("_charsel:")) continue; // Skip internal cache

                if (key.contains("::")) {
                    // Restore world-specific data if it matches the current world
                    if (key.startsWith(prefix)) {
                        toRestore.put(key.substring(prefix.length()), character.modData().get(key));
                    }
                } else {
                    // Restore character-global data (e.g., advancements, stats)
                    toRestore.put(key, character.modData().get(key));
                }
            }

            if (!toRestore.isEmpty()) {
                ModDataScanner.restorePlayerModData(player, toRestore);
                CharacterSelection.LOGGER.info("[CharSel] Restored character data for {} in world {}",
                        player.getName().getString(), worldId);
            }
        });
    }
}