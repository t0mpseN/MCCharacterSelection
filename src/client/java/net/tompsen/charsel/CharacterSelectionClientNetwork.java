package net.tompsen.charsel;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class CharacterSelectionClientNetwork {
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ModPresentPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new CharacterSelectionScreen(null, () -> {
                    if (CharacterSelection.selectedCharacter == null) return;
                    ClientPlayNetworking.send(new SelectCharacterPayload(
                            CharacterSelection.selectedCharacter
                    ));
                    context.client().setScreen(null);
                }));
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SaveCharacterPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                CharacterDto updated = CharacterDto.fromNbt(payload.characterNbt());
                CharacterSelection.DATA_FILE_MANAGER.updateCharacter(updated);
            });
        });
    }
}
