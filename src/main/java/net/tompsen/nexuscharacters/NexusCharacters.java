package net.tompsen.nexuscharacters;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NexusCharacters implements ModInitializer {
	public static final String MOD_ID = "nexuscharacters";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static DataFileManager DATA_FILE_MANAGER;
	public static CharacterDto selectedCharacter = null;
	// Per-player map instead of single static field
	public static final Map<UUID, CharacterDto> selectedCharacters = new ConcurrentHashMap<>();
	// Track when players joined
	public static final Map<UUID, Long> playerJoinTick = new ConcurrentHashMap<>();
	public static final java.util.Set<UUID> deadHardcorePlayers = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** Players currently being respawned by NexusCharacters — skip vault save in afterRemove. */
	public static final java.util.Set<UUID> respawningPlayers = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
	/**
	 * Characters selected during the configuration phase (before the player entity exists).
	 * Consumed on ServerPlayConnectionEvents.JOIN to set selectedCharacters.
	 */
	public static final Map<UUID, CharacterDto> pendingCharacters = new ConcurrentHashMap<>();
	/** Ticks between per-second incremental vault syncs (20 ticks = 1 second). */
	private static final int VAULT_SYNC_INTERVAL_TICKS = 20;
	/** Ticks between advancements syncs (600 ticks = 30 seconds). */
	private static final int ADVANCEMENTS_SYNC_INTERVAL_TICKS = 600;


	public static CharacterDto getSelectedCharacter(ServerPlayerEntity player) {
		return selectedCharacters.get(player.getUuid());
	}

	public static void setSelectedCharacter(ServerPlayerEntity player, CharacterDto character) {
		selectedCharacters.put(player.getUuid(), character);
	}

	public static void clearSelectedCharacter(ServerPlayerEntity player) {
		selectedCharacters.remove(player.getUuid());
	}

	@Override
	public void onInitialize() {
		DATA_FILE_MANAGER = new DataFileManager();
		NexusCharactersNetwork.register();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID uuid = handler.player.getUuid();
			playerJoinTick.put(uuid, (long) server.getTicks());
			ServerPlayerEntity player = handler.player;
			LOGGER.info("[Nexus] JOIN event for {} (uuid={}) dedicated={}", player.getName().getString(), uuid, server.isDedicated());

			if (server.isDedicated()) {
				// Dedicated server: character was selected during the configuration phase.
				// Consume the pending selection and apply character data.
				CharacterDto pending = pendingCharacters.remove(uuid);
				if (pending != null) {
					setSelectedCharacter(player, pending);
					LOGGER.info("[Nexus] JOIN: applied config-phase character {} ({}) for {}.",
							pending.name(), pending.id(), player.getName().getString());
					// Apply character data (skin, position, stats) — vault is already in world dir.
					server.execute(() -> CharacterDataManager.applyCharacterData(player));
				} else {
					LOGGER.warn("[Nexus] JOIN: no pending character for {} — mod may not be installed on client.", player.getName().getString());
				}
			} else if (!server.isHost(player.getGameProfile())) {
				// LAN non-host client: send ModPresent so they get the picker
				server.execute(() -> {
					boolean canSend = ServerPlayNetworking.canSend(handler, ModPresentPayload.ID);
					LOGGER.info("[Nexus] JOIN: canSend ModPresent to {} (LAN) = {}", player.getName().getString(), canSend);
					if (canSend) {
						ServerPlayNetworking.send(player, new ModPresentPayload());
					} else {
						new Timer().schedule(new TimerTask() {
							@Override
							public void run() {
								server.execute(() -> {
									if (ServerPlayNetworking.canSend(handler, ModPresentPayload.ID)) {
										ServerPlayNetworking.send(player, new ModPresentPayload());
									} else {
										LOGGER.warn("[Nexus] JOIN: ModPresent unavailable for {} after retry.", player.getName().getString());
										CharacterDataManager.applyCharacterData(player);
									}
								});
							}
						}, 1000);
					}
				});
			} else {
				// Singleplayer / LAN host
				LOGGER.info("[Nexus] JOIN: singleplayer/LAN host {}, selectedCharacter={}",
						player.getName().getString(), NexusCharacters.selectedCharacter != null ? NexusCharacters.selectedCharacter.name() : "null");
				if (NexusCharacters.selectedCharacter != null) {
					NexusCharacters.setSelectedCharacter(player, NexusCharacters.selectedCharacter);
				}
				server.execute(() -> CharacterDataManager.applyCharacterData(player));
			}
		});

		// For dedicated servers: on disconnect, serialize player state from memory,
		// save the server-side vault, and send the final state to the client.
		// DISCONNECT fires on the Netty IO thread while the connection is still open.
		// We serialize playerdata directly from the live player object (no disk flush needed),
		// then read mod files (Cobblemon etc.) from disk — those are flushed by the mods themselves.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (!server.isDedicated()) return;

			ServerPlayerEntity player = handler.player;
			UUID disconnectUuid = player.getUuid();
			LOGGER.info("[Nexus] DISCONNECT event for {} (uuid={}) on thread {}",
					player.getName().getString(), disconnectUuid, Thread.currentThread().getName());

			// Clean up any config-phase state that was never consumed
			pendingCharacters.remove(disconnectUuid);

			CharacterDto current = NexusCharacters.getSelectedCharacter(player);
			if (current == null) {
				LOGGER.info("[Nexus] DISCONNECT: no character selected for {} — skipping vault save.", player.getName().getString());
				return;
			}

			final CharacterDto charToSave = current;
			LOGGER.info("[Nexus] DISCONNECT: saving vault for {} char={} ({}).",
					player.getName().getString(), charToSave.name(), charToSave.id());

			// Serialize playerdata directly from the live in-memory player object.
			// This avoids any disk-flush race with PlayerManager.remove() on the server thread.
			byte[] playerNbtBytes = null;
			try {
				playerNbtBytes = VaultManager.serializePlayerNbt(player);
			} catch (Exception e) {
				LOGGER.warn("[Nexus] DISCONNECT: failed to serialize playerdata: {}", e.getMessage());
			}

			// Save advancements and stats to disk (these are safe to call from any thread).
			try { player.getAdvancementTracker().save(); } catch (Exception ignored) {}
			try { player.getStatHandler().save(); } catch (Exception ignored) {}

			// Save position to vault metadata
			String worldId = CharacterDataManager.getWorldId(player);
			VaultManager.saveWorldPosition(charToSave.id(), worldId,
					player.getX(), player.getY(), player.getZ(),
					player.getYaw(), player.getPitch());

			// Schedule the disk-based work on the server thread (vault copy + world files).
			// This runs after the connection closes, so no packets can be sent from here.
			final byte[] finalPlayerNbt = playerNbtBytes;
			server.execute(() -> {
				java.nio.file.Path worldDir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
						.toAbsolutePath().normalize();
				// If we have in-memory playerdata, write it to disk first so copyWorldToVault picks it up
				if (finalPlayerNbt != null) {
					try {
						java.nio.file.Path playerFile = worldDir.resolve("playerdata").resolve(disconnectUuid + ".dat");
						java.nio.file.Files.createDirectories(playerFile.getParent());
						java.nio.file.Files.write(playerFile, finalPlayerNbt);
					} catch (Exception e) {
						LOGGER.warn("[Nexus] DISCONNECT: failed to write playerdata to disk: {}", e.getMessage());
					}
				}
				VaultManager.copyWorldToVault(charToSave.id(), worldDir, disconnectUuid);
				LOGGER.info("[Nexus] Saved server-side vault for {} (char {}).",
						player.getName().getString(), charToSave.id());
			});

			// Send the final state to the client while the connection is still open.
			// playerdata comes from in-memory serialization (fresh); mod files from disk (flushed by mods).
			if (playerNbtBytes != null && ServerPlayNetworking.canSend(player, VaultSyncPayload.ID)) {
				try {
					java.nio.file.Path worldDir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
							.toAbsolutePath().normalize();
					java.util.Map<String, byte[]> allFiles = new java.util.LinkedHashMap<>();
					allFiles.putAll(VaultManager.collectSmallFiles(charToSave.id(), worldDir, disconnectUuid));
					// Override playerdata with the fresh in-memory bytes
					allFiles.put("playerdata/" + VaultManager.PLAYER_TOKEN + ".dat", playerNbtBytes);
					allFiles.putAll(VaultManager.collectAdvancementsFile(worldDir, disconnectUuid));
					ServerPlayNetworking.send(player, new VaultSyncPayload(charToSave.id(), allFiles));
					LOGGER.info("[Nexus] DISCONNECT: sent final VaultSyncPayload ({} files) to {}.",
							allFiles.size(), player.getName().getString());
				} catch (Exception e) {
					LOGGER.warn("[Nexus] DISCONNECT: failed to send final VaultSyncPayload: {}", e.getMessage());
				}
			}
		});

		// Periodic server→client incremental vault sync.
		// Every 20 ticks (1 second): collect playerdata, stats, and mod files (excluding advancements)
		// and send them as a VaultSyncPayload so the client vault is always at most 1 second stale.
		// Every 600 ticks (30 seconds): also sync advancements (they're large but change infrequently).
		// If the server crashes or the player loses connection, their local vault has recent progress.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(tickServer -> {
			if (!tickServer.isDedicated()) return;
			int ticks = tickServer.getTicks();
			if (ticks % VAULT_SYNC_INTERVAL_TICKS != 0) return;

			boolean syncAdvancements = (ticks % ADVANCEMENTS_SYNC_INTERVAL_TICKS == 0);

			for (ServerPlayerEntity player : tickServer.getPlayerManager().getPlayerList()) {
				CharacterDto character = getSelectedCharacter(player);
				if (character == null) continue;
				if (!ServerPlayNetworking.canSend(player, VaultSyncPayload.ID)) continue;

				final UUID playerUuid = player.getUuid();
				final UUID charId = character.id();
				final boolean includeAdvancements = syncAdvancements;

				// Flush all player data to disk now (on server tick thread) so the
				// background thread reads fresh files — including mod data (Cobblemon etc.)
				// that only persists to disk when explicitly saved.
				try {
					player.getAdvancementTracker().save();
					player.getStatHandler().save();
					((net.tompsen.nexuscharacters.mixin.PlayerManagerAccessor) tickServer.getPlayerManager())
							.invokeSavePlayerData(player);
				} catch (Exception e) {
					LOGGER.debug("[Nexus] VaultSync: flush failed for {}: {}", playerUuid, e.getMessage());
				}

				// Serialize playerdata from live in-memory state (most up-to-date).
				final byte[] playerNbtBytes;
				try {
					playerNbtBytes = VaultManager.serializePlayerNbt(player);
				} catch (Exception e) {
					LOGGER.warn("[Nexus] VaultSync: failed to serialize playerdata for {}: {}", playerUuid, e.getMessage());
					continue;
				}
				final String playerNbtKey = "playerdata/" + VaultManager.PLAYER_TOKEN + ".dat";

				new Thread(() -> {
					try {
						java.nio.file.Path worldDir = tickServer.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
								.toAbsolutePath().normalize();
						// Collect mod files (Cobblemon etc.) from disk — freshly flushed above
						java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>(
								VaultManager.collectSmallFiles(charId, worldDir, playerUuid));
						// Override playerdata with live in-memory bytes (fresher than disk)
						files.put(playerNbtKey, playerNbtBytes);
						if (includeAdvancements) {
							files.putAll(VaultManager.collectAdvancementsFile(worldDir, playerUuid));
						}
						final java.util.Map<String, byte[]> snapshot = java.util.Collections.unmodifiableMap(files);
						tickServer.execute(() ->
								ServerPlayNetworking.send(player, new VaultSyncPayload(charId, snapshot)));
					} catch (Exception e) {
						LOGGER.warn("[Nexus] VaultSync failed for {} (char {}): {}", playerUuid, charId, e.getMessage());
					}
				}, "NexusChars-VaultSync").start();
			}
		});

		// Hardcore Death Logic
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, dmg) -> {
			if (entity instanceof ServerPlayerEntity player) {
				CharacterDto ch = getSelectedCharacter(player);
				if (ch != null && ch.hardcore()) {
					deadHardcorePlayers.add(player.getUuid());
					DATA_FILE_MANAGER.deleteCharacter(ch.id());
					clearSelectedCharacter(player);
				}
			}
		});
	}
}