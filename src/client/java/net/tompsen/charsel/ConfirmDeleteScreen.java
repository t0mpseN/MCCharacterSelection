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

    private float getScale() {
        float targetW = 350.0f;
        float targetH = 150.0f;
        float scaleX = width / targetW;
        float scaleY = height / targetH;
        return Math.min(1.0f, Math.min(scaleX, scaleY));
    }

    @Override
    protected void init() {
        float scale = getScale();
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        int cx = vw / 2;
        int cy = vh / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("Delete").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.RED), btn -> {
            onConfirm.run();
            client.setScreen(parent);
        }).dimensions(cx - 104, cy + 24, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)), btn -> client.setScreen(parent))
                .dimensions(cx + 4, cy + 24, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Parent screen needs to remain visible underneath
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        parent.render(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xD0000000, 0xD0000000);

        float scale = getScale();
        int smX = (int) (mouseX / scale);
        int smY = (int) (mouseY / scale);
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        ctx.getMatrices().push();
        ctx.getMatrices().scale(scale, scale, 1.0f);

        int cx = vw / 2;
        int cy = vh / 2;
        int panelW = 280;
        int panelH = 110;
        int panelX = cx - panelW / 2;
        int panelY = cy - panelH / 2;

        CharacterListScreen.drawMinecraftPanel(ctx, panelX, panelY, panelW, panelH);
        ctx.fill(panelX + 4, panelY + 4, panelX + panelW - 4, panelY + 6, 0xFFCC3333);

        int titleY = panelY + 16;
        int trashW = 16;
        int titleTextW = (int) (textRenderer.getWidth("DELETE CHARACTER?") * 1.2f);
        int blockX = cx - (trashW + 4 + titleTextW) / 2;

        ctx.drawTexture(TRASH_ICON, blockX, titleY - 2, 0, 0, 16, 16, 16, 16);
        drawScaledWarningTitle(ctx, "DELETE CHARACTER?", blockX + 20 + titleTextW / 2, titleY, 1.2f);

        Text nameTxt = Text.literal("\"" + characterName + "\"").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.WHITE);
        CharacterListScreen.drawRetroText(ctx, textRenderer, nameTxt, cx - textRenderer.getWidth(nameTxt) / 2, panelY + 38, 0xFFFFFF);

        Text warnTxt = Text.literal("All progress will be lost forever.").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.GRAY);
        CharacterListScreen.drawRetroText(ctx, textRenderer, warnTxt, cx - textRenderer.getWidth(warnTxt) / 2, panelY + 52, 0xFFFFFF);

        // Draw child buttons scaled
        for (var child : this.children()) {
            if (child instanceof net.minecraft.client.gui.Drawable drawable) {
                drawable.render(ctx, smX, smY, delta);
            }
        }

        ctx.getMatrices().pop();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float scale = getScale();
        return super.mouseClicked(mouseX / scale, mouseY / scale, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        float scale = getScale();
        return super.mouseReleased(mouseX / scale, mouseY / scale, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        float scale = getScale();
        return super.mouseDragged(mouseX / scale, mouseY / scale, button, deltaX / scale, deltaY / scale);
    }

    private void drawScaledWarningTitle(DrawContext ctx, String text, int centerX, int y, float scale) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(centerX, y, 0);
        ctx.getMatrices().scale(scale, scale, 1.0F);
        int w = textRenderer.getWidth(text);
        Text shadowText = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
        ctx.drawText(textRenderer, shadowText, -w / 2 + 1, 1, 0xFF000000, false);
        Text t = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
        ctx.drawText(textRenderer, t, -w / 2, 0, 0xFF5555, false);
        ctx.getMatrices().pop();
    }
}