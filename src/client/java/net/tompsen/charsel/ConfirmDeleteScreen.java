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

    public ConfirmDeleteScreen(Screen parent, String characterName, Runnable onConfirm) {
        super(Text.literal("Confirm Delete"));
        this.parent = parent;
        this.characterName = characterName;
        this.onConfirm = onConfirm;
    }

    private float getScale() {
        return CharacterUiHelper.getScale(350.0f, 150.0f, width, height);
    }

    @Override
    protected void init() {
        float scale = getScale();
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        int cx = vw / 2;
        int cy = vh / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("Delete").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.RED), btn -> {
            onConfirm.run();
            client.setScreen(parent);
        }).dimensions(cx - 104, cy + 24, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)), btn -> client.setScreen(parent))
                .dimensions(cx + 4, cy + 24, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Parent screen needs to remain visible underneath
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Draw parent screen without hover effects
        parent.render(ctx, -1, -1, delta);

        float scale = getScale();
        int smX = (int) (mouseX / scale);
        int smY = (int) (mouseY / scale);
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        ctx.fill(0, 0, width, height, 0xD0000000);

        ctx.getMatrices().scale(scale, scale, 1.0f);

        int cx = vw / 2;
        int cy = vh / 2;
        int panelW = 280;
        int panelH = 110;
        int panelX = cx - panelW / 2;
        int panelY = cy - panelH / 2;

        CharacterUiHelper.drawMinecraftPanel(ctx, panelX, panelY, panelW, panelH);
        ctx.fill(panelX + 4, panelY + 4, panelX + panelW - 4, panelY + 6, 0xFFCC3333);

        int titleY = panelY + 16;
        int trashW = 16;
        int titleTextW = (int) (textRenderer.getWidth("DELETE CHARACTER?") * 1.2f);
        int blockX = cx - (trashW + 4 + titleTextW) / 2;

        ctx.drawTexture(CharacterUiHelper.TRASH_ICON, blockX, titleY - 2, 0, 0, 16, 16, 16, 16);
        CharacterUiHelper.drawScaledTitle(ctx, textRenderer, "DELETE CHARACTER?", blockX + 20 + titleTextW / 2, titleY, 1.2f, 0xFF5555);

        Text nameTxt = Text.literal("\"" + characterName + "\"").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.WHITE);
        CharacterUiHelper.drawRetroText(ctx, textRenderer, nameTxt, cx - textRenderer.getWidth(nameTxt) / 2, panelY + 38, 0xFFFFFF);

        Text warnTxt = Text.literal("All progress will be lost forever.").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY);
        CharacterUiHelper.drawRetroText(ctx, textRenderer, warnTxt, cx - textRenderer.getWidth(warnTxt) / 2, panelY + 52, 0xFFFFFF);

        // Draw child buttons scaled
        for (net.minecraft.client.gui.Element child : this.children()) {
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
}