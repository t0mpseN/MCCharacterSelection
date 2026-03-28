package net.tompsen.nexuscharacters.mixin;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.tompsen.nexuscharacters.CharacterDataManager;
import net.tompsen.nexuscharacters.CharacterDto;
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
        // LAN guests use the DISCONNECT event handler for their vault save — skip here.
        boolean isLanGuest = !player.server.isDedicated() && !player.server.isHost(player.getGameProfile());
        if (!isLanGuest) CharacterDataManager.saveCurrentCharacter(player);

        NexusCharacters.clearSelectedCharacter(player);
        NexusCharacters.playerJoinTick.remove(uuid);
        // Only clear the static selectedCharacter for singleplayer/LAN host (not LAN guests)
        if (!player.server.isDedicated() && !isLanGuest) {
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

        boolean isDedicated = player.server.isDedicated();
        boolean isHost = player.server.isHost(player.getGameProfile());
        boolean isLanGuest = !isDedicated && !isHost;

        // LAN guests and dedicated server players go through the config-phase flow.
        // installVaultAndCompleteTask already copied vault files to world dir before the player entered.
        // Just mark the character as selected so trackers pick up the right files.
        if (isDedicated || isLanGuest) {
            CharacterDto pending = NexusCharacters.pendingCharacters.get(player.getUuid());
            if (pending != null) {
                NexusCharacters.setSelectedCharacter(player, pending);
                NexusCharacters.LOGGER.info("[Nexus] prepareCharacterData: pending character {} for {}.",
                        pending.name(), player.getName().getString());
            } else {
                NexusCharacters.LOGGER.debug("[Nexus] prepareCharacterData: no pending character for {} — config-phase may still be in progress.", player.getName().getString());
            }
            return;
        }

        // Singleplayer / LAN host: use selectedCharacter or last-used
        UUID charId = NexusCharacters.selectedCharacter != null
                ? NexusCharacters.selectedCharacter.id()
                : NexusCharacters.DATA_FILE_MANAGER.getLastUsed(player.getUuid());

        NexusCharacters.LOGGER.info("[Nexus] prepareCharacterData: player={} singleplayer/host charId={}",
                player.getName().getString(), charId);

        if (charId == null) return;

        NexusCharacters.DATA_FILE_MANAGER.findById(charId).ifPresent(character -> {
            NexusCharacters.setSelectedCharacter(player, character);
            Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            // Import pre-existing player data for a matching username if this is a fresh vault
            VaultManager.importLegacyDataIfNeeded(character.id(), character.name(), worldDir);
            // Singleplayer / LAN host: copy vault files into world dir right now,
            // before Minecraft's PlayerManager reads player.dat, advancements, stats.
            VaultManager.clearWorldFiles(worldDir, player.getUuid());
            VaultManager.copyVaultToWorld(character.id(), worldDir, player.getUuid());
            NexusCharacters.LOGGER.info("[Nexus] Vault installed for singleplayer/host join: {}", character.name());
        });
    }
}