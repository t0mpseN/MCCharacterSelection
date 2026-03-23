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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.tompsen.nexuscharacters.CharacterDto;
import net.tompsen.nexuscharacters.ModDataScanner;
import net.tompsen.nexuscharacters.NexusCharacters;
import net.tompsen.nexuscharacters.SkinReloadPayload;

import java.util.*;

public class CharacterDataManager {
    public static void loadCharacterToPlayer(ServerPlayerEntity player) {
        CharacterDto character = NexusCharacters.getSelectedCharacter(player);
        if (character == null) return;

        String worldId = getWorldId(player);
        NbtCompound playerNbt = character.playerNbt().copy();
        NbtCompound worldPositions = character.worldPositions();

        boolean inherited = false;
        // 1. Handle Inheritance & Stacking for First-Time Join
        if (!worldPositions.contains(worldId)) {
            if (character.name().equalsIgnoreCase(player.getGameProfile().getName())) {
                NbtCompound worldData = ModDataScanner.loadPlayerNbtFromWorld(player);
                if (!worldData.isEmpty()) {
                    playerNbt = mergePlayerNbt(player, playerNbt, worldData);
                    inherited = true;
                    NexusCharacters.LOGGER.info("[Nexus] Character {} inherited existing data for {} in {}",
                            character.name(), player.getName().getString(), worldId);
                }
            }
        }

        player.getInventory().clear();

        if (!playerNbt.isEmpty()) {
            // Strip tags that cause "Invalid player data" or dimension mismatches
            playerNbt.remove("Pos");
            playerNbt.remove("Rotation");
            playerNbt.remove("Dimension");
            playerNbt.remove("SpawnDimension");
            playerNbt.remove("RootVehicle");

            // We use the character's stored gamemode
            int gmId = playerNbt.contains("playerGameType") ? playerNbt.getInt("playerGameType") : 0;

            UUID uuid = player.getUuid();
            player.clearStatusEffects();
            player.readNbt(playerNbt);
            player.setUuid(uuid);

            // Re-apply character-specific gamemode
            player.changeGameMode(GameMode.byId(gmId));

            // Force health/hunger reset for non-inherited first-time join
            if (!inherited && !worldPositions.contains(worldId)) {
                player.setHealth(player.getMaxHealth());
                player.getHungerManager().setFoodLevel(20);
            }
        } else {
            // New character — ensure clean slate
            player.getInventory().clear();
            player.clearStatusEffects();
            player.experienceLevel = 0;
            player.experienceProgress = 0f;
            player.totalExperience = 0;
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);
        }

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
            // capture real Mojang skin
            String[] actualSkin = getSkinProperties(player);
            if (!actualSkin[0].isEmpty()) {
                CharacterDto withSkin = new CharacterDto(
                        character.id(), character.name(), playerNbt,
                        character.worldPositions(), actualSkin[0], actualSkin[1],
                        character.skinUsername(), character.modData()
                );
                NexusCharacters.setSelectedCharacter(player, withSkin);
                NexusCharacters.DATA_FILE_MANAGER.updateCharacter(withSkin);
            }
        }

        player.sendAbilitiesUpdate();
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    private static NbtCompound mergePlayerNbt(ServerPlayerEntity player, NbtCompound base, NbtCompound additional) {
        if (base.isEmpty()) return additional;
        
        NbtCompound merged = base.copy();

        // 1. Merge Inventory
        net.minecraft.nbt.NbtList baseInv = merged.getList("Inventory", 10);
        net.minecraft.nbt.NbtList addInv = additional.getList("Inventory", 10);
        
        Map<Integer, NbtCompound> slots = new HashMap<>();
        for (int i = 0; i < baseInv.size(); i++) {
            NbtCompound item = baseInv.getCompound(i);
            slots.put((int) item.getByte("Slot") & 255, item);
        }

        for (int i = 0; i < addInv.size(); i++) {
            NbtCompound item = addInv.getCompound(i).copy();
            int slot = (int) item.getByte("Slot") & 255;
            
            if (slots.containsKey(slot)) {
                int newSlot = -1;
                for (int s = 0; s < 36; s++) {
                    if (!slots.containsKey(s)) {
                        newSlot = s;
                        break;
                    }
                }
                
                if (newSlot != -1) {
                    item.putByte("Slot", (byte) newSlot);
                    slots.put(newSlot, item);
                } else {
                    net.minecraft.item.ItemStack stack = net.minecraft.item.ItemStack.fromNbtOrEmpty(player.getRegistryManager(), item);
                    player.dropItem(stack, false);
                }
            } else {
                slots.put(slot, item);
            }
        }
        
        net.minecraft.nbt.NbtList finalInv = new net.minecraft.nbt.NbtList();
        slots.values().forEach(finalInv::add);
        merged.put("Inventory", finalInv);

        // 2. Stack XP
        int baseTotal = merged.getInt("XpTotal");
        int addTotal = additional.getInt("XpTotal");
        merged.putInt("XpTotal", baseTotal + addTotal);
        merged.remove("XpLevel");
        merged.remove("XpP");

        // 3. Merge Effects
        if (additional.contains("active_effects", 9)) {
            net.minecraft.nbt.NbtList baseEffects = merged.getList("active_effects", 10);
            net.minecraft.nbt.NbtList addEffects = additional.getList("active_effects", 10);
            Set<String> existingEffects = new HashSet<>();
            for (int i = 0; i < baseEffects.size(); i++) {
                existingEffects.add(baseEffects.getCompound(i).getString("id"));
            }
            for (int i = 0; i < addEffects.size(); i++) {
                NbtCompound effect = addEffects.getCompound(i);
                if (!existingEffects.contains(effect.getString("id"))) {
                    baseEffects.add(effect.copy());
                }
            }
            merged.put("active_effects", baseEffects);
        }

        return merged;
    }

    public static void saveCurrentCharacter(ServerPlayerEntity player) {
        saveCurrentCharacter(player, false);
    }

    public static void saveCurrentCharacter(ServerPlayerEntity player, boolean forceFull) {
        try {
            CharacterDto current = NexusCharacters.getSelectedCharacter(player);
            if (current == null) return;

            // Force save advancements and stats to disk so we can scan them
            player.getAdvancementTracker().save();
            player.getStatHandler().save();

            player.getInventory().markDirty();
            NbtCompound playerNbt = new NbtCompound();
            player.writeNbt(playerNbt);
            
            if (current.playerNbt().getBoolean("hardcore")) {
                playerNbt.putBoolean("hardcore", true);
            }

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
            NbtCompound modData = current.modData().copy();

            // Perform scan of disk files (Advancements, Stats, and optionally other Mod data)
            NbtCompound currentWorldData = ModDataScanner.scanPlayerModData(player, forceFull);
            
            for (String key : currentWorldData.getKeys()) {
                modData.put(key, currentWorldData.get(key));
                modData.remove(worldId + "::" + key);
            }

            // Cache Advancement Display Info for Title Screen UI
            NbtCompound displayCache = modData.getCompound("_charsel:adv_display_cache");
            updateAdvancementDisplayCache(player, displayCache);
            modData.put("_charsel:adv_display_cache", displayCache);

            CharacterDto updated = new CharacterDto(
                    current.id(), current.name(), playerNbt, worldPositions,
                    skin[0], skin[1], current.skinUsername(), modData
            );

            NexusCharacters.setSelectedCharacter(player, updated);
            NexusCharacters.DATA_FILE_MANAGER.updateCharacter(updated);

            // Sync back to client if on a server (Dedicated or LAN Guest)
            if (player.server.isDedicated() || !player.server.isHost(player.getGameProfile())) {
                if (ServerPlayNetworking.canSend(player.networkHandler, SaveCharacterPayload.ID)) {
                    ServerPlayNetworking.send(player, new SaveCharacterPayload(updated.toNbt()));
                }
            }

            if (!player.server.isDedicated()) {
                NexusCharacters.selectedCharacter = updated;
            }
        } catch (Exception e) {
            NexusCharacters.LOGGER.error("[Nexus] Failed to save character for " + player.getName().getString(), e);
        }
    }

    private static void updateAdvancementDisplayCache(ServerPlayerEntity player, NbtCompound cache) {
        net.minecraft.server.ServerAdvancementLoader loader = player.server.getAdvancementLoader();
        net.minecraft.advancement.PlayerAdvancementTracker tracker = player.getAdvancementTracker();
        for (net.minecraft.advancement.AdvancementEntry entry : loader.getAdvancements()) {
            if (tracker.getProgress(entry).isDone()) {
                entry.value().display().ifPresent(display -> {
                    NbtCompound info = new NbtCompound();
                    info.putString("title", display.getTitle().getString());
                    info.putString("desc", display.getDescription().getString());
                    info.put("icon", display.getIcon().encode(player.getRegistryManager()));
                    cache.put(entry.id().toString(), info);
                });
            }
        }
    }

    public static String getWorldId(ServerPlayerEntity player) {
        String saveName = player.server.getSaveProperties().getLevelName();
        String serverId = player.server.isDedicated() ? player.server.getServerIp() + ":" + player.server.getServerPort() : "integrated";
        long seed = player.getServerWorld().getSeed();
        return serverId + "|" + saveName + "|" + seed;
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
        playerManager.sendToAll(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
        PlayerListS2CPacket addPacket = new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.of(player));
        playerManager.sendToAll(addPacket);
        for (ServerPlayerEntity other : playerManager.getPlayerList()) {
            if (other == player) continue;
            other.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
            other.networkHandler.sendPacket(new EntitySpawnS2CPacket(player, 0, player.getBlockPos()));
        }
        player.networkHandler.sendPacket(addPacket);
    }
}
