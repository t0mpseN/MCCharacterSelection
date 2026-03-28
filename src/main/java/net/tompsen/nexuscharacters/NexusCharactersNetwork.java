package net.tompsen.nexuscharacters;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

public class NexusCharactersNetwork {

    // ── Configuration-phase task ──────────────────────────────────────────────

    /**
     * Blocks the player in the configuration phase until character selection and
     * vault upload are complete.  The server calls completeTask(KEY) once the
     * vault has been installed into the world dir.
     */
    static final class CharacterSelectionTask implements ServerPlayerConfigurationTask {
        static final Key KEY = new Key("nexuscharacters:character_selection");

        private final ServerConfigurationNetworkHandler handler;

        CharacterSelectionTask(ServerConfigurationNetworkHandler handler) {
            this.handler = handler;
        }

        @Override
        public void sendPacket(Consumer<Packet<?>> sender) {
            // Send the "please pick a character" prompt to the client.
            NexusCharacters.LOGGER.info("[Nexus] CharacterSelectionTask started — sending request to client.");
            ServerConfigurationNetworking.send(handler, new CharacterSelectRequestPayload());
        }

        @Override
        public Key getKey() {
            return KEY;
        }
    }

    // ── Respawn helper (play phase, after character switch mid-session) ────────

    /**
     * Respawns the player in-place so Minecraft (and all mods) re-read player data
     * from the world dir files we just placed there.
     * Uses respawnPlayer with alive=true to stay in the same world.
     */
    private static void respawnPlayer(ServerPlayerEntity player) {
        CharacterDto character = NexusCharacters.getSelectedCharacter(player);
        if (character == null) {
            NexusCharacters.LOGGER.warn("[Nexus] respawnPlayer: no character selected for {} — falling back to applyCharacterData", player.getName().getString());
            CharacterDataManager.applyCharacterData(player);
            return;
        }

        NexusCharacters.LOGGER.info("[Nexus] Respawning player {} (uuid={}) to reload mod data for char {} ({})",
                player.getName().getString(), player.getUuid(), character.name(), character.id());

        NexusCharacters.respawningPlayers.add(player.getUuid());
        ServerPlayerEntity newPlayer;
        try {
            newPlayer = player.server.getPlayerManager()
                    .respawnPlayer(player, true, net.minecraft.entity.Entity.RemovalReason.KILLED);
        } finally {
            NexusCharacters.respawningPlayers.remove(player.getUuid());
        }
        NexusCharacters.LOGGER.info("[Nexus] Respawn call complete, new player uuid={}", newPlayer.getUuid());

        // Re-apply character to the NEW player object (respawn creates a new instance)
        NexusCharacters.setSelectedCharacter(newPlayer, character);

        // Respawn resets game mode to default — enforce from DTO
        newPlayer.changeGameMode(net.minecraft.world.GameMode.byId(character.gameMode()));

        // Restore position from vault
        String worldId = CharacterDataManager.getWorldId(newPlayer);
        java.util.Optional<VaultManager.WorldPos> pos = VaultManager.getWorldPosition(character.id(), worldId);
        if (pos.isPresent()) {
            NexusCharacters.LOGGER.info("[Nexus] Teleporting {} to saved pos [{}, {}, {}] in world {}",
                    character.name(), pos.get().x(), pos.get().y(), pos.get().z(), worldId);
            CharacterDataManager.teleportTo(newPlayer, pos.get());
        } else {
            // Fallback: try same host/save but any dimension
            String hostSave = worldId.substring(0, worldId.lastIndexOf('|'));
            java.util.Optional<VaultManager.WorldPos> fallback = VaultManager.getAnyPositionForWorld(character.id(), hostSave);
            if (fallback.isPresent()) {
                NexusCharacters.LOGGER.info("[Nexus] Teleporting {} to fallback pos [{}, {}, {}] in world {}",
                        character.name(), fallback.get().x(), fallback.get().y(), fallback.get().z(), hostSave);
                CharacterDataManager.teleportTo(newPlayer, fallback.get());
            } else {
                net.minecraft.util.math.BlockPos spawn = newPlayer.getServerWorld().getSpawnPos();
                NexusCharacters.LOGGER.info("[Nexus] No saved position for {} in world {} — teleporting to spawn {}",
                        character.name(), worldId, spawn);
                newPlayer.teleport(newPlayer.getServerWorld(), spawn.getX(), spawn.getY() + 1, spawn.getZ(),
                        java.util.Set.of(), 0f, 0f);
            }
        }

        newPlayer.sendAbilitiesUpdate();
        NexusCharacters.LOGGER.info("[Nexus] Respawn complete for {} (char {}).", character.name(), character.id());
    }

    // ── Install vault into world dir and complete configuration task ──────────

    /**
     * Called on the server thread once a vault zip is assembled during the
     * configuration phase.  Installs files into the world dir then releases
     * the configuration task so the player can enter the world.
     */
    private static void installVaultAndCompleteTask(
            ServerConfigurationNetworkHandler handler,
            UUID playerUuid,
            CharacterDto dto,
            byte[] zip) {

        net.minecraft.server.MinecraftServer server = ServerConfigurationNetworking.getServer(handler);
        Path worldDir = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();

        try {
            VaultManager.unzipToVault(dto.id(), zip, true); // preserveServerFiles=true keeps world_positions.json
            // Import pre-existing player data for a matching username if this is a fresh vault
            VaultManager.importLegacyDataIfNeeded(dto.id(), dto.name(), worldDir);
            VaultManager.clearWorldFiles(worldDir, playerUuid);
            VaultManager.copyVaultToWorld(dto.id(), worldDir, playerUuid);
            NexusCharacters.LOGGER.info("[Server] Config-phase vault installed for player {} char {}.", playerUuid, dto.id());
        } catch (Exception e) {
            NexusCharacters.LOGGER.error("[Server] Failed to install vault during config phase:", e);
            // Even on failure, complete the task so the player isn't stuck forever.
        }

        handler.completeTask(CharacterSelectionTask.KEY);
    }

    // ── Registration ──────────────────────────────────────────────────────────

    public static void register() {
        // ── Payload registration ───────────────────────────────────────────────
        // Config-phase C2S (client → server during configuration)
        PayloadTypeRegistry.configurationC2S().register(SelectCharacterPayload.ID,   SelectCharacterPayload.CODEC);
        PayloadTypeRegistry.configurationC2S().register(VaultChunkC2SPayload.ID,     VaultChunkC2SPayload.CODEC);
        PayloadTypeRegistry.configurationC2S().register(VaultTransferDoneC2SPayload.ID, VaultTransferDoneC2SPayload.CODEC);

        // Config-phase S2C (server → client during configuration)
        PayloadTypeRegistry.configurationS2C().register(CharacterSelectRequestPayload.ID, CharacterSelectRequestPayload.CODEC);
        PayloadTypeRegistry.configurationS2C().register(VaultReceiveReadyPayload.ID,      VaultReceiveReadyPayload.CODEC);

        // Play-phase C2S (kept for singleplayer/LAN; dedicated uses config phase above)
        PayloadTypeRegistry.playC2S().register(SelectCharacterPayload.ID,   SelectCharacterPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(VaultChunkC2SPayload.ID,     VaultChunkC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(VaultTransferDoneC2SPayload.ID, VaultTransferDoneC2SPayload.CODEC);

        // Play-phase S2C
        PayloadTypeRegistry.playS2C().register(ModPresentPayload.ID,              PacketCodec.unit(new ModPresentPayload()));
        PayloadTypeRegistry.playS2C().register(SkinReloadPayload.ID,              SkinReloadPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VaultReceiveReadyPayload.ID,       VaultReceiveReadyPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VaultChunkS2CPayload.ID,           VaultChunkS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VaultTransferDoneS2CPayload.ID,    VaultTransferDoneS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VaultSyncPayload.ID,               VaultSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CharacterDeletedPayload.ID,        CharacterDeletedPayload.CODEC);

        // ── Configuration-phase server event ──────────────────────────────────
        // On dedicated servers AND LAN non-host clients: add a task that blocks the
        // player from entering the world until character selection + vault upload are done.
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            // Always apply to dedicated servers
            boolean isDedicated = server.isDedicated();
            // For integrated (LAN) server: apply to non-host players only
            if (!isDedicated) {
                com.mojang.authlib.GameProfile profile =
                        ((net.tompsen.nexuscharacters.mixin.ServerConfigurationHandlerAccessor) handler).getProfile();
                if (profile == null || server.isHost(profile)) return;
            }

            if (ServerConfigurationNetworking.canSend(handler, CharacterSelectRequestPayload.ID)) {
                NexusCharacters.LOGGER.info("[Nexus] Adding CharacterSelectionTask for connecting player (dedicated={}).", isDedicated);
                handler.addTask(new CharacterSelectionTask(handler));
            } else {
                NexusCharacters.LOGGER.warn("[Nexus] Client does not support CharacterSelectRequest — skipping config-phase selection.");
            }
        });

        // ── Configuration-phase C2S handlers ──────────────────────────────────

        // 1a. (Config phase) Client chose a character → always request client upload.
        // The client's vault is always the source of truth for character data.
        // Server progress is synced back to the client every second via VaultSyncPayload,
        // so the client vault is always up to date and safe to trust on join.
        ServerConfigurationNetworking.registerGlobalReceiver(SelectCharacterPayload.ID, (payload, ctx) -> {
            ServerConfigurationNetworkHandler handler = ctx.networkHandler();
            CharacterDto dto = payload.character();

            UUID playerUuid = ((net.tompsen.nexuscharacters.mixin.ServerConfigurationHandlerAccessor) handler).getProfile().getId();
            NexusCharacters.LOGGER.info("[Server] Config SelectCharacter: char={} ({}) for uuid={}",
                    dto.name(), dto.id(), playerUuid);

            // Store selection so that when the player entity is created it's already known
            NexusCharacters.pendingCharacters.put(playerUuid, dto);

            // Always request upload — client vault has the freshest data
            NexusCharacters.LOGGER.info("[Server] Config: requesting vault upload from client for char {}.", dto.id());
            ServerConfigurationNetworking.send(handler, new VaultReceiveReadyPayload());
            // Task completed after VaultTransferDoneC2SPayload arrives.
        });

        // 1b. (Config phase) Receive one vault chunk from client
        ServerConfigurationNetworking.registerGlobalReceiver(VaultChunkC2SPayload.ID, (payload, ctx) -> {
            UUID playerUuid = ((net.tompsen.nexuscharacters.mixin.ServerConfigurationHandlerAccessor) ctx.networkHandler()).getProfile().getId();
            CharacterTransferManager.serverReceiveChunk(playerUuid, payload.index(), payload.total(), payload.data());
            if (payload.index() == 0 || payload.index() == payload.total() - 1) {
                NexusCharacters.LOGGER.info("[Server] Config vault chunk {}/{} received from uuid={}.",
                        payload.index() + 1, payload.total(), playerUuid);
            }
        });

        // 1c. (Config phase) Client signals upload complete → install vault, complete task
        ServerConfigurationNetworking.registerGlobalReceiver(VaultTransferDoneC2SPayload.ID, (payload, ctx) -> {
            ServerConfigurationNetworkHandler handler = ctx.networkHandler();
            net.minecraft.server.MinecraftServer server = ctx.server();
            UUID playerUuid = ((net.tompsen.nexuscharacters.mixin.ServerConfigurationHandlerAccessor) handler).getProfile().getId();
            NexusCharacters.LOGGER.info("[Server] Config VaultTransferDone from uuid={}.", playerUuid);

            byte[] zip = CharacterTransferManager.serverAssemble(playerUuid);
            NexusCharacters.LOGGER.info("[Server] Config: assembled {} bytes for uuid={}.", zip.length, playerUuid);

            CharacterDto dto = NexusCharacters.pendingCharacters.get(playerUuid);
            if (dto == null) {
                NexusCharacters.LOGGER.error("[Server] Config: no pending character for uuid={} — cannot install vault.", playerUuid);
                handler.completeTask(CharacterSelectionTask.KEY);
                return;
            }

            server.execute(() -> installVaultAndCompleteTask(handler, playerUuid, dto, zip));
        });

        // ── Play-phase handlers (singleplayer / LAN only) ─────────────────────

        // 2. (Play phase, SP/LAN) Client chose a character → apply data
        ServerPlayNetworking.registerGlobalReceiver(SelectCharacterPayload.ID, (payload, ctx) -> {
            ctx.server().execute(() -> {
                ServerPlayerEntity player = ctx.player();
                CharacterDto dto = payload.character();
                NexusCharacters.setSelectedCharacter(player, dto);
                NexusCharacters.LOGGER.info("[Server] Play SelectCharacter: char={} ({}) for player={} (singleplayer/LAN)",
                        dto.name(), dto.id(), player.getName().getString());

                if (!ctx.server().isDedicated() && !ctx.server().isHost(player.getGameProfile())) {
                    // LAN guest: request vault upload from client (just like dedicated does in config phase)
                    NexusCharacters.LOGGER.info("[Server] Play: requesting vault upload from LAN guest {}.", player.getName().getString());
                    ServerPlayNetworking.send(player, new VaultReceiveReadyPayload());
                } else {
                    // Singleplayer / LAN host: vault was already copied to world dir by the mixin.
                    CharacterDataManager.applyCharacterData(player);
                }
            });
        });

        // 3. (Play phase) Receive one vault chunk from client
        ServerPlayNetworking.registerGlobalReceiver(VaultChunkC2SPayload.ID, (payload, ctx) -> {
            UUID chunkPlayerUuid = ctx.player().getUuid();
            CharacterTransferManager.serverReceiveChunk(chunkPlayerUuid, payload.index(), payload.total(), payload.data());
            if (payload.index() == 0 || payload.index() == payload.total() - 1) {
                NexusCharacters.LOGGER.info("[Server] Play vault chunk {}/{} received from {}.",
                        payload.index() + 1, payload.total(), ctx.player().getName().getString());
            }
        });

        // 4. (Play phase) Client signals upload complete → assemble, write to world, respawn player
        ServerPlayNetworking.registerGlobalReceiver(VaultTransferDoneC2SPayload.ID, (payload, ctx) -> {
            ctx.server().execute(() -> {
                ServerPlayerEntity player = ctx.player();
                UUID playerUuid = player.getUuid();
                NexusCharacters.LOGGER.info("[Server] Play VaultTransferDone received from {}.", player.getName().getString());

                byte[] zip = CharacterTransferManager.serverAssemble(playerUuid);
                NexusCharacters.LOGGER.info("[Server] Play: assembled {} bytes for player {}.", zip.length, player.getName().getString());

                CharacterDto dto = NexusCharacters.getSelectedCharacter(player);
                if (dto == null) {
                    NexusCharacters.LOGGER.error("[Server] Play: no selected character for {} — cannot install vault.", player.getName().getString());
                    return;
                }

                NexusCharacters.LOGGER.info("[Server] Play: installing vault for char {} ({}).", dto.name(), dto.id());
                Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();

                try {
                    VaultManager.unzipToVault(dto.id(), zip, true); // preserveServerFiles=true keeps world_positions.json
                    VaultManager.importLegacyDataIfNeeded(dto.id(), dto.name(), worldDir);
                    VaultManager.clearWorldFiles(worldDir, playerUuid);
                    VaultManager.copyVaultToWorld(dto.id(), worldDir, playerUuid);
                    NexusCharacters.LOGGER.info("[Server] Play: vault installed for {} (char {}).", player.getName().getString(), dto.id());
                } catch (Exception e) {
                    NexusCharacters.LOGGER.error("[Server] Play: failed to install vault for {}:", player.getName().getString(), e);
                    return;
                }

                respawnPlayer(player);
            });
        });
    }
}
