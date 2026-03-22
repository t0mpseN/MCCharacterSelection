package net.tompsen.charsel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

public class CharacterCreationScreen extends Screen {
    private final Screen parent;
    private final Runnable onAdd;
    private TextFieldWidget nameField;
    private TextFieldWidget skinField;
    private String statusMessage = "";
    private int statusColor = 0xFFAAAAAA;
    private boolean fetching = false;

    public CharacterCreationScreen(Screen parent, Runnable onAdd) {
        super(Text.literal("New Character"));
        this.parent = parent;
        this.onAdd = onAdd;
    }

    private float getScale() {
        float targetW = 350.0f;
        float targetH = 260.0f;
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
        int py = vh / 2 - 80;

        nameField = new TextFieldWidget(textRenderer, cx - 100, py + 50, 200, 20, Text.literal("Name"));
        nameField.setPlaceholder(Text.literal("Character name..."));
        addDrawableChild(nameField);

        skinField = new TextFieldWidget(textRenderer, cx - 100, py + 100, 200, 20, Text.literal("Skin"));
        skinField.setPlaceholder(Text.literal("Minecraft username..."));
        addDrawableChild(skinField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Create").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)), btn -> confirm())
                .dimensions(cx - 100, py + 140, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)), btn -> client.setScreen(parent))
                .dimensions(cx + 5, py + 140, 95, 20).build());
    }

    private void confirm() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            statusMessage = "Name cannot be empty";
            statusColor = 0xFFFF5555;
            return;
        }

        String skinUsername = skinField.getText().trim();
        if (!skinUsername.isEmpty()) {
            fetching = true;
            statusMessage = "Fetching skin for " + skinUsername + "...";
            statusColor = 0xFFAAAAAA;

            SkinFetcher.fetchByUsername(skinUsername, (value, signature, error) -> {
                client.execute(() -> {
                    fetching = false;
                    if (error != null) {
                        statusMessage = error;
                        statusColor = 0xFFFF5555;
                        return;
                    }
                    createCharacter(name, skinUsername, value, signature);
                });
            });
        } else {
            createCharacter(name, "", "", "");
        }
    }

    private void createCharacter(String name, String skinUsername, String skinValue, String skinSignature) {
        CharacterSelection.DATA_FILE_MANAGER.addCharacter(new CharacterDto(
                UUID.randomUUID(), name, new NbtCompound(), new NbtCompound(),
                skinValue, skinSignature, skinUsername, new NbtCompound()
        ));
        onAdd.run();
        client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Keeps parent screen visible below the modal
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Draw parent screen and a dark overlay
        parent.render(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xD0000000, 0xD0000000);

        float scale = getScale();
        int smX = (int) (mouseX / scale);
        int smY = (int) (mouseY / scale);
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        ctx.getMatrices().push();
        ctx.getMatrices().scale(scale, scale, 1.0f);

        int pw = 240, ph = 210;
        int cx = vw / 2;
        int py = vh / 2 - 80;
        int px = cx - pw / 2;

        // Draw Panel
        CharacterListScreen.drawMinecraftPanel(ctx, px, py - 10, pw, ph);

        // Draw Scaled Title
        drawScaledTitle(ctx, "NEW CHARACTER", cx, py + 8, 1.2f);

        // Draw Labels
        Text nameLabel = Text.literal("Name").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.GRAY);
        CharacterListScreen.drawRetroText(ctx, textRenderer, nameLabel, cx - 100, py + 38, 0xFFFFFF);

        Text skinLabel = Text.literal("Skin Username (optional)").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.GRAY);
        CharacterListScreen.drawRetroText(ctx, textRenderer, skinLabel, cx - 100, py + 88, 0xFFFFFF);

        // Render input fields and buttons with scaled mouse coordinates
        for (var child : this.children()) {
            if (child instanceof net.minecraft.client.gui.Drawable drawable) {
                drawable.render(ctx, smX, smY, delta);
            }
        }

        // Draw Status Message
        if (!statusMessage.isEmpty()) {
            Text statusTxt = Text.literal(statusMessage).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
            CharacterListScreen.drawRetroText(ctx, textRenderer, statusTxt, cx - textRenderer.getWidth(statusTxt) / 2, py + 175, statusColor);
        }

        ctx.getMatrices().pop();
    }

    private void drawScaledTitle(DrawContext ctx, String text, int centerX, int y, float scale) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(centerX, y, 0);
        ctx.getMatrices().scale(scale, scale, 1.0F);

        int w = textRenderer.getWidth(text);
        Text shadowText = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
        ctx.drawText(textRenderer, shadowText, -w / 2 + 1, 1, 0xFF000000, false);

        Text t = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
        ctx.drawText(textRenderer, t, -w / 2, 0, 0xFFFFFF, false);
        ctx.getMatrices().pop();
    }

    // Scale the mouse inputs for widgets to respond correctly
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        float scale = getScale();
        return super.mouseScrolled(mouseX / scale, mouseY / scale, horizontalAmount, verticalAmount);
    }
}