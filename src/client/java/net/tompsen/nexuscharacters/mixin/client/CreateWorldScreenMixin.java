package net.tompsen.nexuscharacters.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.tompsen.nexuscharacters.NexusCharacters;
import net.tompsen.nexuscharacters.NexusCharactersScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public class CreateWorldScreenMixin {

    @Inject(method = "createLevel", at = @At("HEAD"), cancellable = true)
    private void onCreateLevel(CallbackInfo ci) {
        if (NexusCharacters.selectedCharacter != null) return; // already picked

        MinecraftClient client = MinecraftClient.getInstance();
        CreateWorldScreen self = (CreateWorldScreen)(Object)this;

        client.setScreen(new NexusCharactersScreen(self, () ->
                ((CreateWorldScreenAccessor) self).invokeCreateLevel()
        ));
        ci.cancel();
    }
}