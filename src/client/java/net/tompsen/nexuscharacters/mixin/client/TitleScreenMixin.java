package net.tompsen.nexuscharacters.mixin.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.tompsen.nexuscharacters.CharacterListScreen;
import net.tompsen.nexuscharacters.IconButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {

    protected TitleScreenMixin() { super(Text.empty()); }

    @Inject(method = "init", at = @At("TAIL"))
    private void addButton(CallbackInfo ci) {
        addDrawableChild(new IconButtonWidget(10, 10, btn -> openCharacterList()));
    }

    private void openCharacterList() {
        client.setScreen(new CharacterListScreen(client.currentScreen));
    }
}