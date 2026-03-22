package net.tompsen.charsel;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CharacterSelection implements ModInitializer {
	public static final String MOD_ID = "characterselection";
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
		CharacterSelectionNetwork.register();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			playerJoinTick.put(handler.player.getUuid(), (long) server.getTicks());
			ServerPlayerEntity player = handler.player;

			if (server.isDedicated()) {
				if (ServerPlayNetworking.canSend(handler, ModPresentPayload.ID)) {
					ServerPlayNetworking.send(player, new ModPresentPayload());
				} else {
					server.execute(() -> CharacterDataManager.loadCharacterToPlayer(player));
				}
			} else {
				if (CharacterSelection.selectedCharacter != null) {
					CharacterSelection.setSelectedCharacter(player, CharacterSelection.selectedCharacter);
				}
				server.execute(() -> CharacterDataManager.loadCharacterToPlayer(player));
			}
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

			if (tick % 6000 == 100) {
				for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
					Long joinTick = CharacterSelection.playerJoinTick.get(player.getUuid());
					if (joinTick == null || tick - joinTick < 6200) continue;

					CharacterDataManager.saveCurrentCharacter(player);

					CharacterDto current = CharacterSelection.getSelectedCharacter(player);
					if (server.isDedicated() && current != null) {
						if (ServerPlayNetworking.canSend(player.networkHandler, SaveCharacterPayload.ID)) {
							ServerPlayNetworking.send(player, new SaveCharacterPayload(current.toNbt()));
						}
					}
				}
			}
		});

		ServerWorldEvents.UNLOAD.register((server, world) -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				CharacterDataManager.saveCurrentCharacter(player);
			}
		});
	}
}