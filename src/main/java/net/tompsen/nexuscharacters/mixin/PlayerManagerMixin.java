package net.tompsen.nexuscharacters.mixin;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.tompsen.nexuscharacters.CharacterDataManager;
import net.tompsen.nexuscharacters.NexusCharacters;
import net.tompsen.nexuscharacters.ModDataScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void beforeRemove(ServerPlayerEntity player, CallbackInfo ci) {
        // Force trackers save so CharacterDataManager can scan them
        player.getAdvancementTracker().save();
        player.getStatHandler().save();

        // Runs on Server thread BEFORE player is removed - ensures data is saved to our character file
        // We pass 'true' to ensure a final full save of advancements/stats/mods
        CharacterDataManager.saveCurrentCharacter(player, true);

        NexusCharacters.clearSelectedCharacter(player);
        NexusCharacters.playerJoinTick.remove(player.getUuid());
        // Fix bug 1: reset selectedCharacter so picker shows again next world
        if (!player.server.isDedicated()) {
            NexusCharacters.selectedCharacter = null;
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
        if (NexusCharacters.deadHardcorePlayers.remove(newPlayer.getUuid())) {
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
        UUID charId = NexusCharacters.selectedCharacter != null
                ? NexusCharacters.selectedCharacter.id()
                : NexusCharacters.DATA_FILE_MANAGER.getLastUsed(player.getUuid());

        if (charId == null) return;

        NexusCharacters.DATA_FILE_MANAGER.findById(charId).ifPresent(character -> {
            // 1. Clear all stale files for this UUID first to ensure absolute isolation
            ModDataScanner.clearPlayerModData(player);

            // 2. Restore character data
            // We restore EVERYTHING in modData to the world folder. 
            // If it's a legacy prefixed key, we strip the prefix if it matches the current world.
            // If it's a global key (like advancements/stats), we restore it as-is.
            String worldId = CharacterDataManager.getWorldId(player);
            String prefix = worldId + "::";
            NbtCompound toRestore = new NbtCompound();

            for (String key : character.modData().getKeys()) {
                if (key.startsWith("_charsel:")) continue; // Skip internal cache

                if (key.contains("::")) {
                    if (key.startsWith(prefix)) {
                        toRestore.put(key.substring(prefix.length()), character.modData().get(key));
                    }
                } else {
                    toRestore.put(key, character.modData().get(key));
                }
            }

            if (!toRestore.isEmpty()) {
                ModDataScanner.restorePlayerModData(player, toRestore);
                NexusCharacters.LOGGER.info("[Nexus] Restored mod data for {} in {}",
                        player.getName().getString(), worldId);
            }
        });
    }
}