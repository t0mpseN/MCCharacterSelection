package net.tompsen.charsel;

import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.tompsen.charsel.mixin.PlayerManagerAccessor;

import java.util.*;

public class CharacterDataManager {
    public static void loadCharacterToPlayer(ServerPlayerEntity player) {
        CharacterDto character = CharacterSelection.getSelectedCharacter(player);
        if (character == null) return;

        player.getInventory().clear();
        NbtCompound playerNbt = character.playerNbt().copy();

        if (!playerNbt.isEmpty()) {
            playerNbt.remove("Pos");
            playerNbt.remove("Rotation");
            playerNbt.remove("playerGameType");
            playerNbt.remove("previousPlayerGameType");

            UUID uuid = player.getUuid();
            player.readNbt(playerNbt);
            player.setUuid(uuid);

            if (character.playerNbt().contains("playerGameType")) {
                int gameModeId = character.playerNbt().getInt("playerGameType");
                player.changeGameMode(GameMode.byId(gameModeId));
            }
        } else {
            // New character — ensure clean slate
            player.getInventory().clear();
            player.experienceLevel = 0;
            player.experienceProgress = 0f;
            player.totalExperience = 0;
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);
        }

        String worldId = getWorldId(player);
        NbtCompound worldPositions = character.worldPositions();

        if (worldPositions.contains(worldId)) {
            NbtCompound pos = worldPositions.getCompound(worldId);
            player.teleport(player.getServerWorld(),
                    pos.getDouble("x"), pos.getDouble("y"), pos.getDouble("z"),
                    Set.of(), pos.getFloat("yaw"), pos.getFloat("pitch"));
        } else {
            BlockPos spawn = player.getServerWorld().getSpawnPos();
            player.teleport(player.getServerWorld(),
                    spawn.getX(), spawn.getY(), spawn.getZ(),
                    Set.of(), 0f, 0f);
        }

        String skinValue = character.skinValue();
        String skinSig   = character.skinSignature();

        if (skinValue != null && !skinValue.isEmpty()) {
            player.getGameProfile().getProperties().removeAll("textures");
            player.getGameProfile().getProperties().put("textures",
                    new Property("textures", skinValue, skinSig));
            applySkingToAllClients(player);
            if (ServerPlayNetworking.canSend(player.networkHandler, SkinReloadPayload.ID)) {
                ServerPlayNetworking.send(player,
                        new SkinReloadPayload(skinValue, skinSig != null ? skinSig : ""));
            }
        } else {
            // Sem skin custom — captura a skin Mojang real e salva no personagem
            // para o preview ficar consistente com o in-game
            String[] actualSkin = getSkinProperties(player);
            if (!actualSkin[0].isEmpty()) {
                CharacterDto withSkin = new CharacterDto(
                        character.id(), character.name(), character.playerNbt(),
                        character.worldPositions(), actualSkin[0], actualSkin[1],
                        character.skinUsername(), character.modData()
                );
                CharacterSelection.setSelectedCharacter(player, withSkin);
                CharacterSelection.DATA_FILE_MANAGER.updateCharacter(withSkin);
            }
        }

        ModDataScanner.restorePlayerModData(player, character.modData());

        player.sendAbilitiesUpdate();
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    public static void saveCurrentCharacter(ServerPlayerEntity player) {
        CharacterDto current = CharacterSelection.getSelectedCharacter(player);
        if (current == null) return;

        NbtCompound playerNbt = new NbtCompound();
        player.writeNbt(playerNbt);

        String worldId = getWorldId(player);
        NbtCompound worldPositions = current.worldPositions().copy();
        NbtCompound pos = new NbtCompound();
        pos.putDouble("x", player.getX());
        pos.putDouble("y", player.getY());
        pos.putDouble("z", player.getZ());
        pos.putFloat("yaw", player.getYaw());
        pos.putFloat("pitch", player.getPitch());
        worldPositions.put(worldId, pos);

        String[] skin = getSkinProperties(player);
        NbtCompound modData = ModDataScanner.scanPlayerModData(player);

        CharacterDto updated = new CharacterDto(
                current.id(), current.name(), playerNbt, worldPositions,
                skin[0], skin[1], current.skinUsername(), modData
        );

        CharacterSelection.setSelectedCharacter(player, updated);
        CharacterSelection.DATA_FILE_MANAGER.updateCharacter(updated);

        if (!player.server.isDedicated()) {
            CharacterSelection.selectedCharacter = updated;
        }
    }

    public static String[] getSkinProperties(ServerPlayerEntity player) {
        Collection<Property> properties = player.getGameProfile().getProperties().get("textures");
        if (properties.isEmpty()) return new String[]{"", ""};
        Property texture = properties.iterator().next();
        return new String[]{ texture.value(), texture.signature() };
    }

    public static void applySkingToAllClients(ServerPlayerEntity player) {
        MinecraftServer server = player.server;
        PlayerManager playerManager = server.getPlayerManager();

        // Remove from tab list for all clients
        playerManager.sendToAll(new PlayerRemoveS2CPacket(List.of(player.getUuid())));

        // Re-add to tab list with new skin
        PlayerListS2CPacket addPacket = new PlayerListS2CPacket(
                EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.of(player)
        );
        playerManager.sendToAll(addPacket);

        // Force nearby players to respawn the entity with new skin
        for (ServerPlayerEntity other : playerManager.getPlayerList()) {
            if (other == player) continue;
            other.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
            other.networkHandler.sendPacket(new EntitySpawnS2CPacket(player, 0, player.getBlockPos()));
        }

        // Update for the player themselves
        player.networkHandler.sendPacket(addPacket);
    }

    private static String getWorldId(ServerPlayerEntity player) {
        String saveName = player.server.getSaveProperties().getLevelName();
        String serverIp = player.server.isDedicated()
                ? player.server.getServerIp() + ":" + player.server.getServerPort()
                : "singleplayer";
        long seed = player.getServerWorld().getSeed();
        String dimension = player.getServerWorld().getRegistryKey().getValue().toString();
        return serverIp + "|" + saveName + "|" + seed + "|" + dimension;
    }
}
