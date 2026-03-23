package net.tompsen.nexuscharacters;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

public class NexusCharactersClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NexusCharactersClientNetwork.register();

		ClientPlayNetworking.registerGlobalReceiver(SkinReloadPayload.ID, (payload, context) -> {
			context.client().execute(() -> {
				net.minecraft.client.network.ClientPlayerEntity player = context.client().player;
				if (player == null) return;

				com.mojang.authlib.properties.Property prop =
						new com.mojang.authlib.properties.Property(
								"textures", payload.skinValue(), payload.skinSignature());

				player.getGameProfile().getProperties().removeAll("textures");
				player.getGameProfile().getProperties().put("textures", prop);

				// Força o SkinProvider a rebuscar com o novo profile
				context.client().getSkinProvider()
						.fetchSkinTextures(player.getGameProfile())
						.thenAccept(textures ->
								NexusCharacters.LOGGER.info("[CharSel] Skin atualizada: {}",
										textures.texture()));
			});
		});

		// Init fake world immediately at launch
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			DummyWorldManager.initAtStartup();
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ClientWorld world = MinecraftClient.getInstance().world;
			if (world != null) DummyWorldManager.captureFromWorld(world);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			NexusCharacters.selectedCharacter = null; // safety net
		});
	}
}