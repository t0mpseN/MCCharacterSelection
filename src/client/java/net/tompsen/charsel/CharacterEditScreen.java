package net.tompsen.charsel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class CharacterEditScreen extends Screen {
    private final Screen parent;
    private final CharacterDto character;
    private final Runnable onSave;
    private TextFieldWidget nameField;
    private TextFieldWidget skinField;
    private String statusMessage = "";
    private int statusColor = 0xAAAAAA;
    private boolean fetching = false;

    public CharacterEditScreen(Screen parent, CharacterDto character, Runnable onSave) {
        super(Text.literal("Edit Character"));
        this.parent = parent;
        this.character = character;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int py = height / 2 - 80;

        nameField = new TextFieldWidget(textRenderer, cx - 100, py + 50, 200, 20, Text.literal("Name"));
        nameField.setText(character.name());
        addDrawableChild(nameField);

        skinField = new TextFieldWidget(textRenderer, cx - 100, py + 100, 200, 20, Text.literal("Skin"));
        String currentUsername = character.skinUsername() != null ? character.skinUsername() : "";
        skinField.setText(currentUsername.startsWith("__default__:") ? "" : currentUsername);
        skinField.setPlaceholder(Text.literal("Minecraft username..."));
        addDrawableChild(skinField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Save").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)), btn -> confirm())
                .dimensions(cx - 100, py + 140, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)), btn -> client.setScreen(parent))
                .dimensions(cx + 5, py + 140, 95, 20).build());
    }

    private void confirm() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) { statusMessage = "Name cannot be empty"; statusColor = 0xFF5555; return; }

        String newUsername = skinField.getText().trim();
        String oldUsername = character.skinUsername() != null ? character.skinUsername() : "";
        boolean usernameChanged = !newUsername.isEmpty() && !newUsername.equals(
                oldUsername.startsWith("__default__:") ? "" : oldUsername);

        if (usernameChanged) {
            fetching = true;
            statusMessage = "Fetching skin for " + newUsername + "...";
            statusColor = 0xAAAAAA;
            SkinFetcher.fetchByUsername(newUsername, (value, signature, error) ->
                    client.execute(() -> {
                        fetching = false;
                        if (error != null) { statusMessage = error; statusColor = 0xFF5555; return; }
                        save(name, newUsername, value, signature);
                    }));
        } else {
            save(name, oldUsername, character.skinValue(), character.skinSignature());
        }
    }

    private void save(String name, String skinUsername, String skinValue, String skinSignature) {
        CharacterDto updated = new CharacterDto(
                character.id(), name, character.playerNbt(), character.worldPositions(),
                skinValue  != null ? skinValue  : "",
                skinSignature != null ? skinSignature : "",
                skinUsername, character.modData()
        );
        CharacterSelection.DATA_FILE_MANAGER.updateCharacter(updated);
        DummyPlayerManager.invalidateDummies();
        onSave.run();
        client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        int pw = 280, ph = 210;
        int px = width / 2 - pw / 2;
        int py = height / 2 - 80;
        CharacterListScreen.drawMinecraftPanel(ctx, px, py, pw, ph);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int py = height / 2 - 80;

        Text title = Text.literal("EDIT CHARACTER").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
        ctx.drawTextWithShadow(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, py + 16, 0xFFFFFF);

        Text nameLabel = Text.literal("Name").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.GRAY);
        ctx.drawTextWithShadow(textRenderer, nameLabel, width / 2 - 100, py + 38, 0x888888);

        Text skinLabel = Text.literal("Skin Username (optional)").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.GRAY);
        ctx.drawTextWithShadow(textRenderer, skinLabel, width / 2 - 100, py + 88, 0x888888);

        if (!statusMessage.isEmpty()) {
            Text statusTxt = Text.literal(statusMessage).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT));
            ctx.drawTextWithShadow(textRenderer, statusTxt, width / 2 - textRenderer.getWidth(statusTxt) / 2, py + 175, statusColor);
        }
    }
}