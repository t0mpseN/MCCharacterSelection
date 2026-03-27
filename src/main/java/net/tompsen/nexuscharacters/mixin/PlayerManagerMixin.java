package net.tompsen.nexuscharacters.mixin;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.tompsen.nexuscharacters.CharacterDataManager;
import net.tompsen.nexuscharacters.NexusCharacters;
import net.tompsen.nexuscharacters.VaultManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "remove", at = @At("TAIL"))
    private void afterRemove(ServerPlayerEntity player, CallbackInfo ci) {
        UUID uuid = player.getUuid();

        // Skip vault save if this removal is part of a NexusCharacters-triggered respawn.
        // The respawn is used to reload mod data from disk — not a real disconnect.
        if (NexusCharacters.respawningPlayers.contains(uuid)) {
            NexusCharacters.LOGGER.info("[Nexus] afterRemove: skipping vault save for {} — nexus-triggered respawn.", player.getName().getString());
            return;
        }

        NexusCharacters.LOGGER.info("[Nexus] afterRemove: saving character data for {} (uuid={})", player.getName().getString(), uuid);
        // Runs AFTER PlayerManager.remove() which flushes playerdata/advancements/stats to disk.
        CharacterDataManager.saveCurrentCharacter(player);

        NexusCharacters.clearSelectedCharacter(player);
        NexusCharacters.playerJoinTick.remove(uuid);
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
        if (trackers.containsKey(player.getUuid())) {
            NexusCharacters.LOGGER.debug("[Nexus] prepareCharacterData: tracker already exists for {} — skipping.", player.getName().getString());
            return;
        }

        // If already selected (e.g. during a NexusCharacters-triggered respawn on dedicated),
        // the vault is already in the world dir — nothing to do here.
        if (NexusCharacters.getSelectedCharacter(player) != null) {
            NexusCharacters.LOGGER.debug("[Nexus] prepareCharacterData: character already selected for {} — skipping.", player.getName().getString());
            return;
        }

        UUID charId = NexusCharacters.selectedCharacter != null
                ? NexusCharacters.selectedCharacter.id()
                : NexusCharacters.DATA_FILE_MANAGER.getLastUsed(player.getUuid());

        NexusCharacters.LOGGER.info("[Nexus] prepareCharacterData: player={} dedicated={} charId={}",
                player.getName().getString(), player.server.isDedicated(), charId);

        if (charId == null) return;

        NexusCharacters.DATA_FILE_MANAGER.findById(charId).ifPresent(character -> {
            NexusCharacters.setSelectedCharacter(player, character);
            Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();

            if (player.server.isDedicated()) {
                // Multiplayer: vault transfer happens via packets (VaultReceiveReadyPayload flow).
                // Nothing to do here.
                NexusCharacters.LOGGER.info("[Nexus] prepareCharacterData: dedicated server — vault transfer via packets for {}.", character.name());
                return;
            }

            // Singleplayer / integrated: copy vault files into world dir right now,
            // before Minecraft's PlayerManager reads player.dat, advancements, stats.
            VaultManager.clearWorldFiles(worldDir, player.getUuid());
            VaultManager.copyVaultToWorld(character.id(), worldDir, player.getUuid());
            NexusCharacters.LOGGER.info("[Nexus] Vault installed for singleplayer join: {}", character.name());
        });
    }
}