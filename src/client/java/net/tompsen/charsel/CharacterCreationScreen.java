package net.tompsen.charsel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.UUID;

public class CharacterCreationScreen extends Screen {
    private final Screen parent;
    private final Runnable onAdd;
    private TextFieldWidget nameField;
    private TextFieldWidget skinField;
    private GameMode selectedGameMode = GameMode.SURVIVAL;
    private boolean isHardcore = false;
    private String statusMessage = "";
    private int statusColor = 0xFFAAAAAA;
    private boolean fetching = false;

    public CharacterCreationScreen(Screen parent, Runnable onAdd) {
        super(Text.literal("New Character"));
        this.parent = parent;
        this.onAdd = onAdd;
    }

    private float getScale() {
        return CharacterUiHelper.getScale(350.0f, 300.0f, width, height);
    }

    @Override
    protected void init() {
        float scale = getScale();
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        int cx = vw / 2;
        int py = vh / 2 - 100;

        nameField = new TextFieldWidget(textRenderer, cx - 100, py + 40, 200, 20, Text.literal("Name"));
        nameField.setPlaceholder(Text.literal("Character name..."));
        nameField.setMaxLength(20);
        addDrawableChild(nameField);

        skinField = new TextFieldWidget(textRenderer, cx - 100, py + 85, 200, 20, Text.literal("Skin"));
        skinField.setPlaceholder(Text.literal("Minecraft username..."));
        addDrawableChild(skinField);

        CyclingButtonWidget<GameModeOption> gmButton = CyclingButtonWidget.builder(GameModeOption::getText)
                .values(GameModeOption.values())
                .initially(GameModeOption.SURVIVAL)
                .build(cx - 100, py + 130, 200, 20, Text.literal("Game Mode"), (button, value) -> {
                    this.selectedGameMode = value.gameMode;
                    this.isHardcore = value.hardcore;
                });
        addDrawableChild(gmButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Create").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)), btn -> confirm())
                .dimensions(cx - 100, py + 170, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)), btn -> client.setScreen(parent))
                .dimensions(cx + 5, py + 170, 95, 20).build());
    }

    private void confirm() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            statusMessage = "Name cannot be empty";
            statusColor = 0xFFFF5555;
            return;
        }

        // Duplicate name check
        boolean exists = CharacterSelection.DATA_FILE_MANAGER.characterList.stream()
                .anyMatch(c -> c.name().equalsIgnoreCase(name));
        if (exists) {
            statusMessage = "A character with this name already exists";
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
        NbtCompound playerNbt = new NbtCompound();
        playerNbt.putInt("playerGameType", selectedGameMode.getId());
        playerNbt.putBoolean("hardcore", isHardcore);

        CharacterSelection.DATA_FILE_MANAGER.addCharacter(new CharacterDto(
                UUID.randomUUID(), name, playerNbt, new NbtCompound(),
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

        int pw = 240, ph = 240;
        int cx = vw / 2;
        int py = vh / 2 - 100;
        int px = cx - pw / 2;

        // Draw Panel
        CharacterUiHelper.drawMinecraftPanel(ctx, px, py - 10, pw, ph);

        // Draw Scaled Title
        CharacterUiHelper.drawScaledTitle(ctx, textRenderer, "NEW CHARACTER", cx, py + 8, 1.2f, 0xFFFFFF);

        // Draw Labels
        Text nameLabel = Text.literal("Name").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY);
        CharacterUiHelper.drawRetroText(ctx, textRenderer, nameLabel, cx - 100, py + 30, 0xFFFFFF);

        Text skinLabel = Text.literal("Skin Username (optional)").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY);
        CharacterUiHelper.drawRetroText(ctx, textRenderer, skinLabel, cx - 100, py + 75, 0xFFFFFF);

        Text gmLabel = Text.literal("Game Mode").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY);
        CharacterUiHelper.drawRetroText(ctx, textRenderer, gmLabel, cx - 100, py + 120, 0xFFFFFF);

        // Render input fields and buttons with scaled mouse coordinates
        for (net.minecraft.client.gui.Element child : this.children()) {
            if (child instanceof net.minecraft.client.gui.Drawable drawable) {
                drawable.render(ctx, smX, smY, delta);
            }
        }

        // Draw Status Message
        if (!statusMessage.isEmpty()) {
            if (statusMessage.contains("already exists")) {
                Text line1 = Text.literal("A character with this").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT));
                Text line2 = Text.literal("name already exists").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT));
                CharacterUiHelper.drawRetroText(ctx, textRenderer, line1, cx - textRenderer.getWidth(line1) / 2, py + 195, statusColor);
                CharacterUiHelper.drawRetroText(ctx, textRenderer, line2, cx - textRenderer.getWidth(line2) / 2, py + 207, statusColor);
            } else {
                Text statusTxt = Text.literal(statusMessage).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT));
                CharacterUiHelper.drawRetroText(ctx, textRenderer, statusTxt, cx - textRenderer.getWidth(statusTxt) / 2, py + 205, statusColor);
            }
        }

        ctx.getMatrices().pop();
    }

    private enum GameModeOption {
        SURVIVAL(GameMode.SURVIVAL, false, "Survival"),
        HARDCORE(GameMode.SURVIVAL, true, "Hardcore"),
        CREATIVE(GameMode.CREATIVE, false, "Creative"),
        ADVENTURE(GameMode.ADVENTURE, false, "Adventure"),
        SPECTATOR(GameMode.SPECTATOR, false, "Spectator");

        final GameMode gameMode;
        final boolean hardcore;
        final String label;

        GameModeOption(GameMode gameMode, boolean hardcore, String label) {
            this.gameMode = gameMode;
            this.hardcore = hardcore;
            this.label = label;
        }

        Text getText() {
            return Text.literal(label);
        }
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