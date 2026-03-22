package net.tompsen.charsel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public class ConfirmDeleteScreen extends Screen {
    private final Screen parent;
    private final Runnable onConfirm;
    private final String characterName;

    private static final Identifier TRASH_ICON = Identifier.of("characterselection", "textures/gui/trash.png");

    public ConfirmDeleteScreen(Screen parent, String characterName, Runnable onConfirm) {
        super(Text.literal("Confirm Delete"));
        this.parent = parent;
        this.characterName = characterName;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("Delete").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.RED), btn -> {
            onConfirm.run();
            client.setScreen(parent);
        }).dimensions(cx - 104, cy + 24, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)), btn -> client.setScreen(parent))
                .dimensions(cx + 4, cy + 24, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Draw nothing! We want the previous screen (CharacterListScreen) to still be visible behind our modal.
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 1. Draw the parent screen underneath first! This creates the "modal overlay" effect without a black dim.
        parent.render(ctx, mouseX, mouseY, delta);

        // 2. Draw a completely dark translucent overlay to dim the background
        ctx.fillGradient(0, 0, width, height, 0xD0000000, 0xD0000000);

        int cx = width / 2;
        int cy = height / 2;
        int panelW = 280; // Increased width so text easily fits
        int panelH = 110;
        int panelX = cx - panelW / 2;
        int panelY = cy - panelH / 2;

        // 3. Draw the retro Minecraft panel
        CharacterListScreen.drawMinecraftPanel(ctx, panelX, panelY, panelW, panelH);

        // 4. Draw a red warning stripe at the top
        ctx.fill(panelX + 4, panelY + 4, panelX + panelW - 4, panelY + 6, 0xFFCC3333);

        // 5. Draw the Trash Icon next to the Scaled Title
        int titleY = panelY + 16;
        int trashW = 16;
        int titleTextW = (int) (textRenderer.getWidth("DELETE CHARACTER?") * 1.2f);

        // Center the icon+title block together
        int blockX = cx - (trashW + 4 + titleTextW) / 2;

        ctx.drawTexture(TRASH_ICON, blockX, titleY - 2, 0, 0, 16, 16, 16, 16);
        drawScaledWarningTitle(ctx, "DELETE CHARACTER?", blockX + 20 + titleTextW / 2, titleY, 1.2f);

        // 6. Draw the Character Name (Centered)
        Text nameTxt = Text.literal("\"" + characterName + "\"").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.WHITE);
        int nameW = textRenderer.getWidth(nameTxt);
        CharacterListScreen.drawRetroText(ctx, textRenderer, nameTxt, cx - nameW / 2, panelY + 38, 0xFFFFFF);

        // 7. Draw the Warning Subtitle (Centered)
        Text warnTxt = Text.literal("All progress will be lost forever.").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.GRAY);
        int warnW = textRenderer.getWidth(warnTxt);
        CharacterListScreen.drawRetroText(ctx, textRenderer, warnTxt, cx - warnW / 2, panelY + 52, 0xFFFFFF);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawScaledWarningTitle(DrawContext ctx, String text, int centerX, int y, float scale) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(centerX, y, 0);
        ctx.getMatrices().scale(scale, scale, 1.0F);

        int w = textRenderer.getWidth(text);

        // Pure black shadow
        Text shadowText = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
        ctx.drawText(textRenderer, shadowText, -w / 2 + 1, 1, 0xFF000000, false);

        // Red title text
        Text t = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
        ctx.drawText(textRenderer, t, -w / 2, 0, 0xFF5555, false);

        ctx.getMatrices().pop();
    }
}