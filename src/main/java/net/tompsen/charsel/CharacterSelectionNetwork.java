package net.tompsen.charsel;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CharacterSelectionNetwork {
    private static final Map<UUID, Integer> pendingSaveTicks = new ConcurrentHashMap<>();
    public static void register() {
        PayloadTypeRegistry.playC2S().register(SelectCharacterPayload.ID, PacketCodec.of(
                SelectCharacterPayload::write, SelectCharacterPayload::new
        ));
        PayloadTypeRegistry.playS2C().register(ModPresentPayload.ID,
                PacketCodec.unit(new ModPresentPayload()));
        PayloadTypeRegistry.playS2C().register(SaveCharacterPayload.ID, PacketCodec.of(
                SaveCharacterPayload::write, SaveCharacterPayload::new
        ));
        PayloadTypeRegistry.playS2C().register(SkinReloadPayload.ID, SkinReloadPayload.CODEC);

        // Client sends selected character → server loads it
        ServerPlayNetworking.registerGlobalReceiver(SelectCharacterPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                CharacterDto character = payload.character();
                CharacterSelection.setSelectedCharacter(context.player(), character);
                CharacterSelection.DATA_FILE_MANAGER.updateCharacter(character);
                context.server().execute(() ->
                        CharacterDataManager.loadCharacterToPlayer(context.player())
                );
            });
        });
    }

    public static Map<UUID, Integer> getPendingSaveTicks() {
        return pendingSaveTicks;
    }
}
