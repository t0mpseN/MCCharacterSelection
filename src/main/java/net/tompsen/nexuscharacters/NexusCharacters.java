package net.tompsen.nexuscharacters;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
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

			if (server.isDedicated() || !server.isHost(player.getGameProfile())) {
				// Use a scheduled task to ensure the client is ready to receive the packet
				// This is more reliable for LAN and fast-joining clients
				server.execute(() -> {
					if (ServerPlayNetworking.canSend(handler, ModPresentPayload.ID)) {
						ServerPlayNetworking.send(player, new ModPresentPayload());
					} else {
						// If still not ready, try one more time a second later
						new Timer().schedule(new TimerTask() {
							@Override
							public void run() {
								server.execute(() -> {
									if (ServerPlayNetworking.canSend(handler, ModPresentPayload.ID)) {
										ServerPlayNetworking.send(player, new ModPresentPayload());
									} else {
										// Final fallback
										CharacterDataManager.loadCharacterToPlayer(player);
									}
								});
							}
						}, 1000);
					}
				});
			} else {
				// Host of LAN / Singleplayer
				if (NexusCharacters.selectedCharacter != null) {
					NexusCharacters.setSelectedCharacter(player, NexusCharacters.selectedCharacter);
				}
				server.execute(() -> CharacterDataManager.loadCharacterToPlayer(player));
			}
		});

		// Ensure data is saved when player disconnects
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			server.execute(() -> CharacterDataManager.saveCurrentCharacter(handler.player, true));
		});

		// Hardcore Death Logic
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayerEntity player) {
				CharacterDto character = getSelectedCharacter(player);
				if (character != null && character.playerNbt().getBoolean("hardcore")) {
					LOGGER.info("[CharSel] Character {} died in Hardcore mode! Deleting...", character.name());
					deadHardcorePlayers.add(player.getUuid());
					DATA_FILE_MANAGER.deleteCharacter(character.id());
					clearSelectedCharacter(player);
				}
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int tick = server.getTicks();

			// Auto-save every 20 ticks for all players (partial save)
			if (tick % 20 == 0) {
				for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
					CharacterDataManager.saveCurrentCharacter(player, false);
				}
			}
		});

		ServerWorldEvents.UNLOAD.register((server, world) -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				CharacterDataManager.saveCurrentCharacter(player, true);
			}
		});
	}
}