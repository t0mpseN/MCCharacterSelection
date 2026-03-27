package net.tompsen.nexuscharacters;

import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.tompsen.nexuscharacters.mixin.PlayerManagerAccessor;

import java.nio.file.Path;
import java.util.*;

public class CharacterDataManager {

    /**
     * Full entry-point for singleplayer and LAN.
     * The vault files are already in the world dir (copied by NexusCharactersClient).
     * All we need to do is: refresh trackers, read NBT, apply it, and position the player.
     */
    public static void applyCharacterData(ServerPlayerEntity player) {
        CharacterDto character = NexusCharacters.getSelectedCharacter(player);
        if (character == null) {
            NexusCharacters.LOGGER.warn("[Nexus] applyCharacterData: no character for {}",
                    player.getName().getString());
            return;
        }

        String worldId = getWorldId(player);
        NexusCharacters.LOGGER.info("[Nexus] Applying character {} for {} in {}",
                character.name(), player.getName().getString(), worldId);

        // 1. Force tracker recreation so they pick up the just-restored vault files
        NexusCharacters.LOGGER.info("[Nexus] applyCharacterData: refreshing trackers for {}.", player.getName().getString());
        refreshTrackers(player);

        // 2. Read vanilla player NBT that Minecraft loaded from the world dir
        //    (it was put there by NexusCharactersClient or by VaultTransferDoneC2SPayload handler)
        NbtCompound playerNbt = readPlayerNbtFromWorld(player);
        NexusCharacters.LOGGER.info("[Nexus] applyCharacterData: playerNbt empty={} for {}.", playerNbt.isEmpty(), player.getName().getString());

        player.getInventory().clear();
        player.clearStatusEffects();

        if (!playerNbt.isEmpty()) {
            playerNbt.remove("Pos");
            playerNbt.remove("Rotation");
            playerNbt.remove("Dimension");
            playerNbt.remove("SpawnDimension");
            playerNbt.remove("RootVehicle");

            UUID uuid = player.getUuid();
            player.readNbt(playerNbt);
            player.setUuid(uuid);
        } else {
            // Fresh character — clean slate
            player.experienceLevel = 0;
            player.experienceProgress = 0f;
            player.totalExperience = 0;
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);
        }

        // 3. Always enforce the authoritative game mode from the lightweight DTO
        player.changeGameMode(GameMode.byId(character.gameMode()));

        // 4. Position
        Path vaultDir = VaultManager.getVaultDir(character.id());
        Optional<double[]> pos = VaultManager.getWorldPosition(character.id(), worldId);
        if (pos.isPresent()) {
            double[] c = pos.get();
            player.teleport(player.getServerWorld(), c[0], c[1], c[2], Set.of(), (float)c[3], (float)c[4]);
        } else {
            BlockPos spawn = player.getServerWorld().getSpawnPos();
            player.teleport(player.getServerWorld(), spawn.getX(), spawn.getY() + 1, spawn.getZ(), Set.of(), 0f, 0f);
        }

        // 5. Skin
        applySkin(player, character);

        player.sendAbilitiesUpdate();
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
        NexusCharacters.LOGGER.info("[Nexus] applyCharacterData complete for {}.", character.name());
    }

    /**
     * Called on disconnect. Saves position to vault and initiates file transfer back
     * to client (multiplayer) OR relies on NexusCharactersClient to copy files (singleplayer).
     */
    public static void saveCurrentCharacter(ServerPlayerEntity player) {
        if (player.server.isDedicated()) {
            // Dedicated server: everything is handled by the DISCONNECT event handler
            // in NexusCharacters (flush + vault save). Nothing to do here.
            return;
        }

        CharacterDto current = NexusCharacters.getSelectedCharacter(player);
        if (current == null) return;

        // Singleplayer / LAN host: called from @At("TAIL") of PlayerManager.remove(),
        // so vanilla has already flushed playerdata/advancements/stats to disk.
        String worldId = getWorldId(player);
        VaultManager.saveWorldPosition(current.id(), worldId,
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch());

        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        VaultManager.copyWorldToVault(current.id(), worldDir, player.getUuid());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static NbtCompound readPlayerNbtFromWorld(ServerPlayerEntity player) {
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        Path playerFile = worldDir.resolve("playerdata/" + player.getUuid() + ".dat");
        if (java.nio.file.Files.exists(playerFile)) {
            try {
                return net.minecraft.nbt.NbtIo.readCompressed(playerFile,
                        net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
            } catch (Exception e) {
                NexusCharacters.LOGGER.warn("[Nexus] Could not read player NBT from world: {}", e.getMessage());
            }
        }
        return new NbtCompound();
    }

    private static void refreshTrackers(ServerPlayerEntity player) {
        PlayerManager manager = player.server.getPlayerManager();
        PlayerManagerAccessor acc = (PlayerManagerAccessor) manager;
        NexusPlayerDuck duck = (NexusPlayerDuck) player;
        UUID uuid = player.getUuid();

        acc.getAdvancementTrackers().remove(uuid);
        acc.getStatHandlers().remove(uuid);

        var adv = manager.getAdvancementTracker(player);
        var stat = manager.createStatHandler(player);
        duck.nexus$setAdvancementTracker(adv);
        duck.nexus$setStatHandler(stat);
        if (adv != null) adv.sendUpdate(player);
    }

    private static void applySkin(ServerPlayerEntity player, CharacterDto character) {
        String value = character.skinValue();
        String sig   = character.skinSignature();
        if (value != null && !value.isEmpty()) {
            player.getGameProfile().getProperties().removeAll("textures");
            player.getGameProfile().getProperties().put("textures", new Property("textures", value, sig));
            broadcastSkinUpdate(player);
            if (ServerPlayNetworking.canSend(player.networkHandler, SkinReloadPayload.ID)) {
                ServerPlayNetworking.send(player, new SkinReloadPayload(value, sig != null ? sig : ""));
            }
        }
    }

    private static void broadcastSkinUpdate(ServerPlayerEntity player) {
        MinecraftServer server = player.server;
        PlayerManager pm = server.getPlayerManager();
        pm.sendToAll(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
        pm.sendToAll(new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.of(player)));
        for (ServerPlayerEntity other : pm.getPlayerList()) {
            if (other == player) continue;
            other.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
            other.networkHandler.sendPacket(new EntitySpawnS2CPacket(player, 0, player.getBlockPos()));
        }
        player.networkHandler.sendPacket(
                new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.of(player)));
    }

    public static String getWorldId(ServerPlayerEntity player) {
        String save    = player.server.getSaveProperties().getLevelName();
        String host    = player.server.isDedicated()
                ? player.server.getServerIp() + ":" + player.server.getServerPort()
                : "integrated";
        long seed = player.getServerWorld().getSeed();
        return host + "|" + save + "|" + seed;
    }
}