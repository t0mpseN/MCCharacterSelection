package net.tompsen.nexuscharacters;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class NexusCharactersClientNetwork {
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ModPresentPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new NexusCharactersScreen(null, () -> {
                    if (NexusCharacters.selectedCharacter == null) return;
                    ClientPlayNetworking.send(new SelectCharacterPayload(
                            NexusCharacters.selectedCharacter
                    ));
                    context.client().setScreen(null);
                }));
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SaveCharacterPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                CharacterDto updated = CharacterDto.fromNbt(payload.characterNbt());
                NexusCharacters.DATA_FILE_MANAGER.updateCharacter(updated);
            });
        });
    }
}
