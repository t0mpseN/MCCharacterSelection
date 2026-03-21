package net.tompsen.charsel;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;
import java.util.List;

import static net.tompsen.charsel.CharacterCardRenderer.CARD_W;

public class CharacterListScreen extends Screen {
    private final Screen parent;
    private int hoveredIndex = -1;
    private int selectedIndex = -1;
    private double scrollAmount = 0;
    private final int stride = CharacterCardRenderer.CARD_H + 6;

    public static final Identifier CUSTOM_FONT = Identifier.of("characterselection", "charsel");
    private static final Identifier TRASH_ICON = Identifier.of("characterselection", "textures/gui/trash.png");
    private static final Identifier EDIT_ICON = Identifier.of("characterselection", "textures/gui/edit.png");
    private static final Identifier HEART_ICON = Identifier.of("characterselection", "textures/gui/heart.png");
    private static final Identifier HUNGER_ICON = Identifier.of("characterselection", "textures/gui/hunger.png");

    private static final Identifier[] ARMOR_ICONS = {
            Identifier.of("minecraft", "textures/item/empty_armor_slot_helmet.png"),
            Identifier.of("minecraft", "textures/item/empty_armor_slot_chestplate.png"),
            Identifier.of("minecraft", "textures/item/empty_armor_slot_leggings.png"),
            Identifier.of("minecraft", "textures/item/empty_armor_slot_boots.png")
    };

    public CharacterListScreen(Screen parent) {
        super(Text.literal("Characters"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        DummyPlayerManager.invalidateDummies();
        refreshList();
    }

    private void refreshList() {
        clearChildren();

        int cw = CARD_W + 40;
        int ch = 4 * stride + 100;
        int cx = width / 2 - cw / 2;
        int cy = height / 2 - ch / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("<").setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)),
                        btn -> client.setScreen(parent))
                .dimensions(cx + 8, cy + 8, 20, 20).build());

        int btnHeight = 26;
        int buttonsY = cy + ch - 16 - btnHeight;
        addDrawableChild(ButtonWidget.builder(Text.literal("+ Add Character").setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)),
                        btn -> client.setScreen(new CharacterCreationScreen(this, () -> { clearChildren(); refreshList(); })))
                .dimensions(cx + 20, buttonsY, cw - 40, btnHeight).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        int cw = CARD_W + 40, ch = 4 * stride + 100;
        int cx = width / 2 - cw / 2, cy = height / 2 - ch / 2;
        drawMinecraftPanel(ctx, cx, cy, cw, ch);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        List<CharacterDto> characters = CharacterSelection.DATA_FILE_MANAGER.characterList;

        int cw = CARD_W + 40, ch = 4 * stride + 100;
        int cx = width / 2 - cw / 2, cy = height / 2 - ch / 2;
        int listX = cx + 20, listY = cy + 40;

        drawScaledTitle(ctx, textRenderer, "SELECT CHARACTER", width / 2, cy + 16, 1.2f);

        int maxScroll = Math.max(0, characters.size() * stride - (4 * stride));
        scrollAmount = MathHelper.clamp(scrollAmount, 0, maxScroll);

        hoveredIndex = -1;
        ctx.enableScissor(listX, listY, listX + CARD_W, listY + 4 * stride);

        for (int i = 0; i < characters.size(); i++) {
            int cardY = (int) (listY + i * stride - scrollAmount);
            if (cardY + CharacterCardRenderer.CARD_H < listY || cardY > listY + 4 * stride) continue;

            boolean isHovered = mouseX >= listX && mouseX <= listX + CARD_W && mouseY >= cardY && mouseY <= cardY + CharacterCardRenderer.CARD_H && mouseY >= listY && mouseY <= listY + 4 * stride;
            if (isHovered) hoveredIndex = i;

            boolean highlight = (i == hoveredIndex) || (i == selectedIndex);
            CharacterCardRenderer.drawCard(ctx, textRenderer, characters.get(i), listX, cardY, highlight);

            int btnY = cardY + (CharacterCardRenderer.CARD_H - 20) / 2;

            int delX = listX + CARD_W - 28;
            boolean delHovered = isHovered && mouseX >= delX && mouseX <= delX + 20 && mouseY >= btnY && mouseY <= btnY + 20;
            ctx.fill(delX, btnY, delX + 20, btnY + 20, delHovered ? 0xFF995555 : 0xFF774444);
            ctx.drawBorder(delX, btnY, 20, 20, 0xFF222222);
            ctx.drawTexture(TRASH_ICON, delX + 2, btnY + 2, 0, 0, 16, 16, 16, 16);

            int editX = delX - 24;
            boolean editHovered = isHovered && mouseX >= editX && mouseX <= editX + 20 && mouseY >= btnY && mouseY <= btnY + 20;
            ctx.fill(editX, btnY, editX + 20, btnY + 20, editHovered ? 0xFF999999 : 0xFF777777);
            ctx.drawBorder(editX, btnY, 20, 20, 0xFF222222);
            ctx.drawTexture(EDIT_ICON, editX + 2, btnY + 2, 0, 0, 16, 16, 16, 16);
        }

        ctx.disableScissor();

        if (maxScroll > 0) {
            int scrollBarX = listX + CARD_W + 6;
            int scrollBarH = 4 * stride;
            ctx.fill(scrollBarX, listY, scrollBarX + 4, listY + scrollBarH, 0xFF111111);
            int thumbH = Math.max(20, (int) ((4 * stride / (float) (characters.size() * stride)) * scrollBarH));
            int thumbY = listY + (int) ((scrollAmount / maxScroll) * (scrollBarH - thumbH));
            ctx.fill(scrollBarX, thumbY, scrollBarX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        if (characters.isEmpty()) {
            Text emptyTxt = Text.literal("No characters yet").setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT));
            drawRetroText(ctx, textRenderer, emptyTxt, width / 2 - textRenderer.getWidth(emptyTxt) / 2, listY + 20, 0x666666);
        }

        ItemStack tooltipItem = ItemStack.EMPTY;
        int activeIndex = (hoveredIndex >= 0) ? hoveredIndex : selectedIndex;
        if (activeIndex >= 0 && activeIndex < characters.size()) {
            CharacterDto activeChar = characters.get(activeIndex);
            drawLeftPanel(ctx, activeChar, cx, cy, ch, mouseX, mouseY);
            tooltipItem = drawRightPanel(ctx, textRenderer, activeChar, cx + cw, cy, ch, mouseX, mouseY);
        }

        if (!tooltipItem.isEmpty()) ctx.drawItemTooltip(textRenderer, tooltipItem, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            List<CharacterDto> characters = CharacterSelection.DATA_FILE_MANAGER.characterList;
            int cw = CARD_W + 40, ch = 4 * stride + 100;
            int cx = width / 2 - cw / 2, cy = height / 2 - ch / 2;
            int listX = cx + 20, listY = cy + 40;

            if (mouseX >= listX && mouseX <= listX + CARD_W && mouseY >= listY && mouseY <= listY + 4 * stride) {
                double adjustedY = mouseY - listY + scrollAmount;
                int clickedIndex = (int) (adjustedY / stride);

                if (clickedIndex >= 0 && clickedIndex < characters.size()) {
                    int cardY = (int) (listY + clickedIndex * stride - scrollAmount);
                    int btnY = cardY + (CharacterCardRenderer.CARD_H - 20) / 2;
                    int delX = listX + CARD_W - 28, editX = delX - 24;
                    CharacterDto chDto = characters.get(clickedIndex);

                    if (mouseX >= delX && mouseX <= delX + 20 && mouseY >= btnY && mouseY <= btnY + 20) {
                        client.setScreen(new ConfirmDeleteScreen(this, chDto.name(), () -> {
                            CharacterSelection.DATA_FILE_MANAGER.deleteCharacter(chDto.id());
                            selectedIndex = -1;
                            refreshList();
                        }));
                        return true;
                    }

                    if (mouseX >= editX && mouseX <= editX + 20 && mouseY >= btnY && mouseY <= btnY + 20) {
                        client.setScreen(new CharacterEditScreen(this, chDto, this::refreshList));
                        return true;
                    }

                    selectedIndex = clickedIndex;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollAmount -= verticalAmount * (stride / 2.0);
        return true;
    }

    private void drawLeftPanel(DrawContext ctx, CharacterDto c, int centerPanelX, int centerPanelY, int centerHeight, int mouseX, int mouseY) {
        int pw = 220, ph = centerHeight;
        int px = centerPanelX - pw - 12;
        int py = centerPanelY;

        drawMinecraftPanel(ctx, px, py, pw, ph);
        drawScaledTitle(ctx, textRenderer, "CHARACTER MODEL", px + pw / 2, py + 16, 1.2f);

        int boxX = px + 16, boxY = py + 36, boxW = pw - 32, boxH = ph - 52;
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF111111);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + 2, 0xFF222222);
        ctx.fill(boxX, boxY, boxX + 2, boxY + boxH, 0xFF222222);

        OtherClientPlayerEntity dummy = DummyPlayerManager.getDummyPlayer(c);
        if (dummy != null) {
            injectCameraIfNeeded();
            dummy.age = 20;
            InventoryScreen.drawEntity(ctx, boxX + 6, boxY + 16, boxX + boxW - 6, boxY + boxH - 10, 60, 0.0625F, (float)mouseX, (float)mouseY, dummy);
        }
    }

    private ItemStack drawRightPanel(DrawContext ctx, TextRenderer tr, CharacterDto c, int centerPanelRightX, int centerPanelY, int centerHeight, int mouseX, int mouseY) {
        int pw = 270, ph = centerHeight;
        int px = centerPanelRightX + 12;
        int py = centerPanelY;
        ItemStack hoveredItem = ItemStack.EMPTY;

        drawMinecraftPanel(ctx, px, py, pw, ph);
        drawScaledTitle(ctx, tr, "INVENTORY", px + pw / 2, py + 16, 1.2f);

        int startX = px + 28, slotSize = 20, gap = 2, hotbarGap = 8;

        ItemStack[] invItems = new ItemStack[104];
        Arrays.fill(invItems, ItemStack.EMPTY);
        NbtCompound nbt = c.playerNbt();
        if (nbt != null && nbt.contains("Inventory")) {
            NbtList inventory = nbt.getList("Inventory", 10);
            for (int i = 0; i < inventory.size(); i++) {
                NbtCompound itemTag = inventory.getCompound(i);
                int slot = itemTag.getByte("Slot") & 255;
                if (slot < 104) invItems[slot] = ItemStack.fromNbtOrEmpty(DummyWorldManager.getRegistries(), itemTag);
            }
        }

        // --- 1. HOTBAR ON TOP ---
        int hotbarY = py + 40;
        Text hTxt = Text.literal("H").setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)).formatted(Formatting.GREEN);
        drawRetroText(ctx, tr, hTxt, startX - 16, hotbarY + 6, 0xFFFFFF);
        for (int i = 0; i < 9; i++) {
            int sx = startX + i * (slotSize + gap);
            drawMinecraftRect(ctx, sx, hotbarY, slotSize, slotSize);
            if (!invItems[i].isEmpty()) {
                ctx.drawItem(invItems[i], sx + 2, hotbarY + 2);
                ctx.drawItemInSlot(tr, invItems[i], sx + 2, hotbarY + 2);
                if (mouseX >= sx && mouseX <= sx + slotSize && mouseY >= hotbarY && mouseY <= hotbarY + slotSize) hoveredItem = invItems[i];
            }
        }

        // --- 2. MAIN INVENTORY ---
        int invY = hotbarY + slotSize + hotbarGap;
        for (int i = 0; i < 27; i++) {
            int row = i / 9;
            int col = i % 9;
            int sx = startX + col * (slotSize + gap);
            int sy = invY + row * (slotSize + gap);
            drawMinecraftRect(ctx, sx, sy, slotSize, slotSize);

            if (col == 0) {
                Text rowTxt = Text.literal(String.valueOf(row + 1)).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)).formatted(Formatting.GRAY);
                drawRetroText(ctx, tr, rowTxt, startX - 16, sy + 6, 0xFFFFFF);
            }

            int itemSlot = i + 9;
            if (!invItems[itemSlot].isEmpty()) {
                ctx.drawItem(invItems[itemSlot], sx + 2, sy + 2);
                ctx.drawItemInSlot(tr, invItems[itemSlot], sx + 2, sy + 2);
                if (mouseX >= sx && mouseX <= sx + slotSize && mouseY >= sy && mouseY <= sy + slotSize) hoveredItem = invItems[itemSlot];
            }
        }

        // --- 3. ARMOR SLOTS ---
        int armorX = startX + 9 * (slotSize + gap) + 6;
        for (int i = 0; i < 4; i++) {
            int sy = hotbarY + i * (slotSize + 4);
            drawMinecraftRect(ctx, armorX, sy, slotSize, slotSize);

            int armorSlot = 103 - i;
            if (invItems[armorSlot].isEmpty()) {
                ctx.drawTexture(ARMOR_ICONS[i], armorX + 2, sy + 2, 0, 0, 16, 16, 16, 16);
            } else {
                ctx.drawItem(invItems[armorSlot], armorX + 2, sy + 2);
                ctx.drawItemInSlot(tr, invItems[armorSlot], armorX + 2, sy + 2);
                if (mouseX >= armorX && mouseX <= armorX + slotSize && mouseY >= sy && mouseY <= sy + slotSize) hoveredItem = invItems[armorSlot];
            }
        }

        // --- 4. DIVIDER & STATS TITLE ---
        int divY = invY + 3 * (slotSize + gap) + 8;
        ctx.fill(px + 16, divY, px + pw - 16, divY + 2, 0xFF555555);
        ctx.fill(px + 16, divY + 2, px + pw - 16, divY + 4, 0xFF222222);

        int statsY = divY + 10;
        drawScaledTitle(ctx, tr, "STATS", px + pw / 2, statsY, 1.2f);

        // --- 5. STATS MATRIX ---
        float hp = nbt != null && nbt.contains("Health") ? nbt.getFloat("Health") : 20f;
        int hunger = nbt != null && nbt.contains("foodLevel") ? nbt.getInt("foodLevel") : 20;
        int level = nbt != null && nbt.contains("XpLevel") ? nbt.getInt("XpLevel") : 0;
        float xpP = nbt != null && nbt.contains("XpP") ? nbt.getFloat("XpP") : 0f;

        int iconY = statsY + 18;
        int statsX = px + 24;

        ctx.drawTexture(HEART_ICON, statsX, iconY, 0, 0, 12, 12, 12, 12);
        Text hpTxt = Text.literal((int)hp + "/20").setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)).formatted(Formatting.RED);
        drawRetroText(ctx, tr, hpTxt, statsX + 16, iconY + 3, 0xFFFFFF);

        Text hungerTxt = Text.literal(hunger + "/20").setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)).formatted(Formatting.GOLD);
        int hungerX = px + pw - 24 - tr.getWidth(hungerTxt);
        ctx.drawTexture(HUNGER_ICON, hungerX - 16, iconY, 0, 0, 12, 12, 12, 12);
        drawRetroText(ctx, tr, hungerTxt, hungerX, iconY + 3, 0xFFFFFF);

        int barY = iconY + 22;
        int barW = pw - 48;
        ctx.fill(statsX, barY, statsX + barW, barY + 6, 0xFF333300);
        ctx.fill(statsX, barY, statsX + (int)(barW * xpP), barY + 6, 0xFF80FF20);
        Text xpTxt = Text.literal("LVL " + level).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)).formatted(Formatting.GREEN);
        drawRetroText(ctx, tr, xpTxt, statsX + barW / 2 - tr.getWidth(xpTxt) / 2, barY - 1, 0xFFFFFF);

        String dimName = nbt != null && nbt.contains("Dimension") ? nbt.getString("Dimension").replace("minecraft:", "").toUpperCase() : "OVERWORLD";
        Text locTxt = Text.literal("LOCATION: " + dimName).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)).formatted(Formatting.GRAY);
        drawRetroText(ctx, tr, locTxt, statsX + barW / 2 - tr.getWidth(locTxt) / 2, barY + 14, 0xFFFFFF);

        // --- 6. LATEST ADVANCEMENT ---
        int badgeH = 36;
        int badgeW = pw - 32;
        int badgeX = px + 16;
        int badgeY = py + ph - 16 - badgeH;

        int advTitleY = badgeY - 16;
        drawScaledTitle(ctx, tr, "LATEST ADVANCEMENT", px + pw / 2, advTitleY, 1.2f);

        int div2Y = advTitleY - 10;
        ctx.fill(px + 16, div2Y, px + pw - 16, div2Y + 2, 0xFF555555);
        ctx.fill(px + 16, div2Y + 2, px + pw - 16, div2Y + 4, 0xFF222222);

        ctx.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, 0xFFB8860B);
        ctx.fill(badgeX + 2, badgeY + 2, badgeX + badgeW - 2, badgeY + badgeH - 2, 0xFF2A2A2A);
        ctx.fillGradient(badgeX + 2, badgeY + 2, badgeX + badgeW - 2, badgeY + badgeH - 2, 0xFF404040, 0xFF222222);

        ctx.getMatrices().push();
        ctx.getMatrices().translate(badgeX + 8, badgeY + 10, 0);
        ctx.drawItem(new ItemStack(Items.MAP), 0, 0);
        ctx.getMatrices().pop();

        Text actTxt = Text.literal("Explored " + dimName).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT)).formatted(Formatting.YELLOW);
        drawRetroText(ctx, tr, actTxt, badgeX + 32, badgeY + (badgeH / 2) - 4, 0xFFFFFF);

        return hoveredItem;
    }

    // --- UTILITY METHODS ---

    public static void drawRetroText(DrawContext ctx, TextRenderer tr, Text text, int x, int y, int color) {
        // Strip the formatting for the shadow so it renders pure black instead of a dark tint of the original color
        Text shadowText = Text.literal(text.getString()).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT));
        ctx.drawText(tr, shadowText, x + 1, y + 1, 0xFF000000, false);

        ctx.drawText(tr, text, x, y, color, false);
    }

    private void drawScaledTitle(DrawContext ctx, TextRenderer tr, String text, int centerX, int y, float scale) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(centerX, y, 0);
        ctx.getMatrices().scale(scale, scale, 1.0F);

        // Use raw string for title shadows to avoid formatting bleed here as well
        int w = tr.getWidth(text);
        Text shadowText = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT));
        ctx.drawText(tr, shadowText, -w / 2 + 1, 1, 0xFF000000, false);

        Text t = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT));
        ctx.drawText(tr, t, -w / 2, 0, 0xFFFFFF, false);
        ctx.getMatrices().pop();
    }

    public static void drawMinecraftPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, 0xFF373737);
        ctx.fill(x, y, x + w - 4, y + h - 4, 0xFF8B8B8B);
        ctx.fillGradient(x + 4, y + 4, x + w - 4, y + h - 4, 0xFF3C3C3C, 0xFF282828);
    }

    private void drawMinecraftRect(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, 0xFF555555);
        ctx.fill(x, y, x + w - 2, y + h - 2, 0xFF2A2A2A);
        ctx.fillGradient(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF1E1E1E, 0xFF141414);
    }

    private void injectCameraIfNeeded() {
        try {
            EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
            java.lang.reflect.Field cameraField = EntityRenderDispatcher.class.getDeclaredField("camera");
            cameraField.setAccessible(true);
            if (cameraField.get(dispatcher) == null) cameraField.set(dispatcher, new Camera());
        } catch (Throwable e) {
            CharacterSelection.LOGGER.error("[CharSel] Camera inject fail:", e);
        }
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public void close() {
        DummyPlayerManager.clearCache();
        client.setScreen(parent);
    }
}