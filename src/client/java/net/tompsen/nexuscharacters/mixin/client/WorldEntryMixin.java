package net.tompsen.nexuscharacters.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.tompsen.nexuscharacters.NexusCharacters;
import net.tompsen.nexuscharacters.NexusCharactersScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screen.world.WorldListWidget$WorldEntry")
public class WorldEntryMixin {
    private static long lastPickerOpen = 0;

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void onPlay(CallbackInfo ci) {
        // If character already selected, let play() proceed normally
        if (NexusCharacters.selectedCharacter != null) return;

        // Debounce double-clicks
        long now = System.currentTimeMillis();
        if (now - lastPickerOpen < 1000) return;

        MinecraftClient client = MinecraftClient.getInstance();
        Screen currentScreen = client.currentScreen;
        WorldListWidget.WorldEntry self = (WorldListWidget.WorldEntry)(Object)this;

        lastPickerOpen = now;
        client.setScreen(new NexusCharactersScreen(currentScreen, () -> self.play()));
        ci.cancel();
    }
}
