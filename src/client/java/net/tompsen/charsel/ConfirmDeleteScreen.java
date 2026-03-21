package net.tompsen.charsel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ConfirmDeleteScreen extends Screen {
    private final Screen parent;
    private final Runnable onConfirm;
    private final String characterName;

    public ConfirmDeleteScreen(Screen parent, String characterName, Runnable onConfirm) {
        super(Text.literal("Confirm Delete"));
        this.parent = parent;
        this.characterName = characterName;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Delete").formatted(Formatting.RED), btn -> {
            onConfirm.run();
            client.setScreen(parent);
        }).dimensions(width / 2 - 104, height / 2 + 20, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> client.setScreen(parent))
                .dimensions(width / 2 + 4, height / 2 + 20, 100, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, width, height, 0xC0000000, 0xC0000000);
        int cx = width / 2, cy = height / 2;
        context.fill(cx - 120, cy - 50, cx + 120, cy + 50, 0xFF1E1E1E);
        context.fill(cx - 120, cy - 50, cx + 120, cy - 48, 0xFFCC3333);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("⚠ Delete Character?").formatted(Formatting.RED, Formatting.BOLD),
                cx, cy - 38, 0xFF5555);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\"" + characterName + "\"").formatted(Formatting.WHITE),
                cx, cy - 22, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("All progress will be lost forever.").formatted(Formatting.GRAY),
                cx, cy - 8, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }
}