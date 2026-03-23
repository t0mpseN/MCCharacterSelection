package net.tompsen.nexuscharacters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static net.tompsen.nexuscharacters.CharacterCardRenderer.CARD_W;

public class CharacterListScreen extends Screen {
    private final Screen parent;
    private int hoveredIndex = -1;
    private int selectedIndex = -1;
    private double scrollAmount = 0;
    private final int stride = CharacterCardRenderer.CARD_H + 6;

    public CharacterListScreen(Screen parent) {
        super(Text.literal("Characters"));
        this.parent = parent;
    }

    private float getScale() {
        return CharacterUiHelper.getScale(850.0f, 380.0f, width, height);
    }

    @Override
    protected void init() {
        DummyPlayerManager.invalidateDummies();
        refreshList();
    }

    private void refreshList() {
        clearChildren();

        float scale = getScale();
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        int cw = CARD_W + 40;
        int ch = 4 * stride + 100;
        int cx = vw / 2 - cw / 2;
        int cy = vh / 2 - ch / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("<").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)),
                        btn -> client.setScreen(parent))
                .dimensions(cx + 8, cy + 8, 20, 20).build());

        int btnHeight = 26;
        int buttonsY = cy + ch - 16 - btnHeight;
        addDrawableChild(ButtonWidget.builder(Text.literal("+ Add Character").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)),
                        btn -> client.setScreen(new CharacterCreationScreen(this, () -> { clearChildren(); refreshList(); })))
                .dimensions(cx + 20, buttonsY, cw - 40, btnHeight).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);

        float scale = getScale();
        int smX = (int) (mouseX / scale);
        int smY = (int) (mouseY / scale);
        int vw = (int) (width / scale);
        int vh = (int) (height / scale);

        ctx.getMatrices().push();
        ctx.getMatrices().scale(scale, scale, 1.0f);

        List<CharacterDto> characters = NexusCharacters.DATA_FILE_MANAGER.characterList;

        int cw = CARD_W + 40, ch = 4 * stride + 100;
        int cx = vw / 2 - cw / 2, cy = vh / 2 - ch / 2;
        int listX = cx + 20, listY = cy + 40;

        CharacterUiHelper.drawMinecraftPanel(ctx, cx, cy, cw, ch);
        CharacterUiHelper.drawScaledTitle(ctx, textRenderer, "SELECT CHARACTER", vw / 2, cy + 16, 1.2f, 0xFFFFFF);

        int maxScroll = Math.max(0, characters.size() * stride - (4 * stride));
        scrollAmount = MathHelper.clamp(scrollAmount, 0, maxScroll);

        hoveredIndex = -1;

        // FIX: Pop the matrix, enable scissor with physical coordinates, then push back in!
        ctx.getMatrices().pop();
        int scX1 = (int) (listX * scale);
        int scY1 = (int) (listY * scale);
        int scX2 = (int) ((listX + CARD_W) * scale);
        int scY2 = (int) ((listY + 4 * stride) * scale);
        ctx.enableScissor(scX1, scY1, scX2, scY2);
        ctx.getMatrices().push();
        ctx.getMatrices().scale(scale, scale, 1.0f);

        for (int i = 0; i < characters.size(); i++) {
            int cardY = (int) (listY + i * stride - scrollAmount);
            if (cardY + CharacterCardRenderer.CARD_H < listY || cardY > listY + 4 * stride) continue;

            boolean isHovered = smX >= listX && smX <= listX + CARD_W && smY >= cardY && smY <= cardY + CharacterCardRenderer.CARD_H && smY >= listY && smY <= listY + 4 * stride;
            if (isHovered) hoveredIndex = i;

            boolean highlight = (i == hoveredIndex) || (i == selectedIndex);
            CharacterCardRenderer.drawCard(ctx, textRenderer, characters.get(i), listX, cardY, highlight);

            int btnY = cardY + 27; // Aligned with bottom of face (y+7+40 = y+47, button is 20h, so y+47-20 = y+27)

            int delX = listX + CARD_W - 28;
            boolean delHovered = isHovered && smX >= delX && smX <= delX + 20 && smY >= btnY && smY <= btnY + 20;
            ctx.fill(delX, btnY, delX + 20, btnY + 20, delHovered ? 0xFF995555 : 0xFF774444);
            ctx.drawBorder(delX, btnY, 20, 20, 0xFF222222);
            ctx.drawTexture(CharacterUiHelper.TRASH_ICON, delX + 2, btnY + 2, 0, 0, 16, 16, 16, 16);

            int editX = delX - 24;
            boolean editHovered = isHovered && smX >= editX && smX <= editX + 20 && smY >= btnY && smY <= btnY + 20;
            ctx.fill(editX, btnY, editX + 20, btnY + 20, editHovered ? 0xFF999999 : 0xFF777777);
            ctx.drawBorder(editX, btnY, 20, 20, 0xFF222222);
            ctx.drawTexture(CharacterUiHelper.EDIT_ICON, editX + 2, btnY + 2, 0, 0, 16, 16, 16, 16);
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
            Text emptyTxt = Text.literal("No characters yet").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT));
            CharacterUiHelper.drawRetroText(ctx, textRenderer, emptyTxt, vw / 2 - textRenderer.getWidth(emptyTxt) / 2, listY + 20, 0x666666);
        }

        for (net.minecraft.client.gui.Element child : this.children()) {
            if (child instanceof net.minecraft.client.gui.Drawable drawable) {
                drawable.render(ctx, smX, smY, delta);
            }
        }

        ItemStack tooltipItem = ItemStack.EMPTY;
        int activeIndex = (hoveredIndex >= 0) ? hoveredIndex : selectedIndex;
        if (activeIndex >= 0 && activeIndex < characters.size()) {
            CharacterDto activeChar = characters.get(activeIndex);
            drawLeftPanel(ctx, activeChar, cx, cy, ch, smX, smY);
            tooltipItem = drawRightPanel(ctx, textRenderer, activeChar, cx + cw, cy, ch, smX, smY);
        }

        ctx.getMatrices().pop();

        if (!tooltipItem.isEmpty()) ctx.drawItemTooltip(textRenderer, tooltipItem, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float scale = getScale();
        double smX = mouseX / scale;
        double smY = mouseY / scale;

        if (button == 0) {
            List<CharacterDto> characters = NexusCharacters.DATA_FILE_MANAGER.characterList;
            int vw = (int) (width / scale), vh = (int) (height / scale);
            int cw = CARD_W + 40, ch = 4 * stride + 100;
            int cx = vw / 2 - cw / 2, cy = vh / 2 - ch / 2;
            int listX = cx + 20, listY = cy + 40;

            if (smX >= listX && smX <= listX + CARD_W && smY >= listY && smY <= listY + 4 * stride) {
                double adjustedY = smY - listY + scrollAmount;
                int clickedIndex = (int) (adjustedY / stride);

                if (clickedIndex >= 0 && clickedIndex < characters.size()) {
                    int cardY = (int) (listY + clickedIndex * stride - scrollAmount);
                    int btnY = cardY + 27; // Centered vertically relative to the face bottom
                    int delX = listX + CARD_W - 28, editX = delX - 24;
                    CharacterDto chDto = characters.get(clickedIndex);

                    if (smX >= delX && smX <= delX + 20 && smY >= btnY && smY <= btnY + 20) {
                        client.setScreen(new ConfirmDeleteScreen(this, chDto.name(), () -> {
                            NexusCharacters.DATA_FILE_MANAGER.deleteCharacter(chDto.id());
                            selectedIndex = -1;
                            refreshList();
                        }));
                        return true;
                    }

                    if (smX >= editX && smX <= editX + 20 && smY >= btnY && smY <= btnY + 20) {
                        client.setScreen(new CharacterEditScreen(this, chDto, this::refreshList));
                        return true;
                    }

                    selectedIndex = clickedIndex;
                    return true;
                }
            }
        }
        return super.mouseClicked(smX, smY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollAmount -= verticalAmount * (stride / 2.0);
        return true;
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

    private void drawLeftPanel(DrawContext ctx, CharacterDto c, int centerPanelX, int centerPanelY, int centerHeight, int mouseX, int mouseY) {
        int pw = 220, ph = centerHeight;
        int px = centerPanelX - pw - 12;
        int py = centerPanelY;

        CharacterUiHelper.drawMinecraftPanel(ctx, px, py, pw, ph);
        CharacterUiHelper.drawScaledTitle(ctx, textRenderer, "CHARACTER MODEL", px + pw / 2, py + 16, 1.2f, 0xFFFFFF);

        int boxX = px + 16, boxY = py + 36, boxW = pw - 32, boxH = ph - 52;
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF111111);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + 2, 0xFF222222);
        ctx.fill(boxX, boxY, boxX + 2, boxY + boxH, 0xFF222222);

        OtherClientPlayerEntity dummy = DummyPlayerManager.getDummyPlayer(c);
        if (dummy != null) {
            CharacterUiHelper.injectCameraIfNeeded(client);
            dummy.age = 20;
            InventoryScreen.drawEntity(ctx, boxX + 6, boxY + 16, boxX + boxW - 6, boxY + boxH - 10, 60, 0.0625F, (float)mouseX, (float)mouseY, dummy);
        }
    }

    private ItemStack drawRightPanel(DrawContext ctx, TextRenderer tr, CharacterDto c, int centerPanelRightX, int centerPanelY, int centerHeight, int mouseX, int mouseY) {
        int pw = 270, ph = centerHeight;
        int px = centerPanelRightX + 12;
        int py = centerPanelY;
        ItemStack hoveredItem = ItemStack.EMPTY;

        CharacterUiHelper.drawMinecraftPanel(ctx, px, py, pw, ph);
        CharacterUiHelper.drawScaledTitle(ctx, tr, "INVENTORY", px + pw / 2, py + 16, 1.2f, 0xFFFFFF);

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

        int hotbarY = py + 40;
        ctx.drawTexture(Identifier.of("minecraft", "textures/item/iron_pickaxe.png"), startX - 18, hotbarY + 2, 0, 0, 16, 16, 16, 16);
        for (int i = 0; i < 9; i++) {
            int sx = startX + i * (slotSize + gap);
            CharacterUiHelper.drawMinecraftRect(ctx, sx, hotbarY, slotSize, slotSize);
            if (!invItems[i].isEmpty()) {
                ctx.drawItem(invItems[i], sx + 2, hotbarY + 2);
                ctx.drawItemInSlot(tr, invItems[i], sx + 2, hotbarY + 2);
                if (mouseX >= sx && mouseX <= sx + slotSize && mouseY >= hotbarY && mouseY <= hotbarY + slotSize) hoveredItem = invItems[i];
            }
        }

        int invY = hotbarY + slotSize + hotbarGap;
        for (int i = 0; i < 27; i++) {
            int row = i / 9;
            int col = i % 9;
            int sx = startX + col * (slotSize + gap);
            int sy = invY + row * (slotSize + gap);
            CharacterUiHelper.drawMinecraftRect(ctx, sx, sy, slotSize, slotSize);

            if (col == 0) {
                Text rowTxt = Text.literal(String.valueOf(row + 1)).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY);
                CharacterUiHelper.drawRetroText(ctx, tr, rowTxt, startX - 16, sy + 6, 0xFFFFFF);
            }

            int itemSlot = i + 9;
            if (!invItems[itemSlot].isEmpty()) {
                ctx.drawItem(invItems[itemSlot], sx + 2, sy + 2);
                ctx.drawItemInSlot(tr, invItems[itemSlot], sx + 2, sy + 2);
                if (mouseX >= sx && mouseX <= sx + slotSize && mouseY >= sy && mouseY <= sy + slotSize) hoveredItem = invItems[itemSlot];
            }
        }

        int armorX = startX + 9 * (slotSize + gap) + 6;
        for (int i = 0; i < 4; i++) {
            int sy = hotbarY + i * (slotSize + 4);
            CharacterUiHelper.drawMinecraftRect(ctx, armorX, sy, slotSize, slotSize);

            int armorSlot = 103 - i;
            if (invItems[armorSlot].isEmpty()) {
                ctx.drawTexture(CharacterUiHelper.ARMOR_ICONS[i], armorX + 2, sy + 2, 0, 0, 16, 16, 16, 16);
            } else {
                ctx.drawItem(invItems[armorSlot], armorX + 2, sy + 2);
                ctx.drawItemInSlot(tr, invItems[armorSlot], armorX + 2, sy + 2);
                if (mouseX >= armorX && mouseX <= armorX + slotSize && mouseY >= sy && mouseY <= sy + slotSize) hoveredItem = invItems[armorSlot];
            }
        }

        int divY = invY + 3 * (slotSize + gap) + 8;
        ctx.fill(px + 16, divY, px + pw - 16, divY + 2, 0xFF555555);
        ctx.fill(px + 16, divY + 2, px + pw - 16, divY + 4, 0xFF222222);

        int statsY = divY + 10;
        CharacterUiHelper.drawScaledTitle(ctx, tr, "STATS", px + pw / 2, statsY, 1.2f, 0xFFFFFF);

        float hp = nbt != null && nbt.contains("Health") ? nbt.getFloat("Health") : 20f;
        int level = nbt != null && nbt.contains("XpLevel") ? nbt.getInt("XpLevel") : 0;
        float xpP = nbt != null && nbt.contains("XpP") ? nbt.getFloat("XpP") : 0f;
        int gameMode = nbt != null ? nbt.getInt("playerGameType") : 0;
        boolean isCreative = gameMode == 1;

        int row1Y = statsY + 16;

        int hpX = px + 20;
        ctx.drawTexture(CharacterUiHelper.HEART_ICON, hpX, row1Y, 0, 0, 12, 12, 12, 12);
        Text hpTxt = Text.literal((int)hp + "/20").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.RED);
        CharacterUiHelper.drawRetroText(ctx, tr, hpTxt, hpX + 16, row1Y + 3, 0xFFFFFF);

        int barW = 110;
        int barX = (px + pw - 20) - barW;
        int barY = row1Y + 4;

        Text lvlTxt = Text.literal("LVL " + (isCreative ? "∞" : level)).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(isCreative ? Formatting.AQUA : Formatting.GREEN);
        CharacterUiHelper.drawRetroText(ctx, tr, lvlTxt, barX - tr.getWidth(lvlTxt) - 6, row1Y + 3, 0xFFFFFF);

        ctx.fill(barX, barY, barX + barW, barY + 5, 0xFF000000);
        ctx.fill(barX + 1, barY + 1, barX + barW - 1, barY + 4, 0xFF383838);

        if (isCreative || xpP > 0) {
            float progress = isCreative ? 1.0f : xpP;
            int progressW = (int)((barW - 2) * progress);
            if (progressW > 0) {
                int topCol = isCreative ? 0xFFB2FFFF : 0xFFB4FF4C;
                int midCol = isCreative ? 0xFF55FFFF : 0xFF5CE626;
                int botCol = isCreative ? 0xFF00AAAA : 0xFF38B200;
                ctx.fill(barX + 1, barY + 1, barX + 1 + progressW, barY + 2, topCol);
                ctx.fill(barX + 1, barY + 2, barX + 1 + progressW, barY + 3, midCol);
                ctx.fill(barX + 1, barY + 3, barX + 1 + progressW, barY + 4, botCol);
            }
        }

        CharacterUiHelper.PlayerStatsInfo stats = CharacterUiHelper.getPlayerStats(c);
        int extraY = barY + 14;

        int hours = stats.playTime() / (20 * 60 * 60);
        int minutes = (stats.playTime() / (20 * 60)) % 60;
        String timeStr = hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
        if (stats.playTime() == 0) timeStr = "---";

        Text stat1 = Text.literal("Time played: ").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY).append(Text.literal(timeStr).formatted(Formatting.WHITE));
        Text stat2 = Text.literal("Mob Kills: ").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY).append(Text.literal(stats.mobKills() > 0 ? String.valueOf(stats.mobKills()) : "---").formatted(Formatting.WHITE));
        Text stat3 = Text.literal("Diamonds mined: ").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.GRAY).append(Text.literal(stats.diamonds() > 0 ? String.valueOf(stats.diamonds()) : "---").formatted(Formatting.WHITE));

        int col1 = px + 20;

        CharacterUiHelper.drawRetroText(ctx, tr, stat1, col1, extraY, 0xFFFFFF);
        CharacterUiHelper.drawRetroText(ctx, tr, stat2, col1, extraY + 14, 0xFFFFFF);
        CharacterUiHelper.drawRetroText(ctx, tr, stat3, col1, extraY + 28, 0xFFFFFF);


        CharacterUiHelper.AdvancementInfo latestAdv = CharacterUiHelper.getLatestAdvancement(c);

        int badgeH = 64;
        int badgeW = pw - 32;
        int badgeX = px + 16;
        int badgeY = py + ph - 16 - badgeH;

        int advTitleY = badgeY - 16;
        CharacterUiHelper.drawScaledTitle(ctx, tr, "LATEST ADVANCEMENT", px + pw / 2, advTitleY, 1.2f, 0xFFFFFF);

        int div2Y = advTitleY - 10;
        ctx.fill(px + 16, div2Y, px + pw - 16, div2Y + 2, 0xFF555555);
        ctx.fill(px + 16, div2Y + 2, px + pw - 16, div2Y + 4, 0xFF222222);

        if (latestAdv != null) {
            ctx.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, 0xFF1A1A1A);
            ctx.drawBorder(badgeX, badgeY, badgeW, badgeH, 0xFF555555);

            int headerH = 24;
            ctx.fill(badgeX + 1, badgeY + 1, badgeX + badgeW - 1, badgeY + headerH, 0xFFC08811);
            ctx.fill(badgeX + 1, badgeY + 1, badgeX + 24, badgeY + headerH, 0xFFA0700A);
            ctx.fill(badgeX + 24, badgeY + 1, badgeX + 25, badgeY + headerH, 0xFF555555);

            ctx.getMatrices().push();
            ctx.getMatrices().translate(badgeX + 4, badgeY + 4, 0);
            ctx.drawItem(latestAdv.icon(), 0, 0);
            ctx.getMatrices().pop();

            Text titleTxt = Text.literal(latestAdv.title()).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.WHITE);
            CharacterUiHelper.drawRetroText(ctx, tr, titleTxt, badgeX + 28, badgeY + 8, 0xFFFFFF);

            Text descText = Text.literal(latestAdv.description()).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT));
            List<OrderedText> lines = tr.wrapLines(descText, badgeW - 8);

            int lineY = badgeY + headerH + 4;
            for (int i = 0; i < lines.size() && i < 3; i++) {
                OrderedText line = lines.get(i);
                ctx.drawText(tr, line, badgeX + 4 + 1, lineY + 1, 0xFF000000, false);
                ctx.drawText(tr, line, badgeX + 4, lineY, 0xFF55FF55, false);
                lineY += 10;
            }

        } else {
            ctx.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, 0xFF1A1A1A);
            ctx.drawBorder(badgeX, badgeY, badgeW, badgeH, 0xFF555555);
            Text noAdv = Text.literal("No advancements yet").setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.DARK_GRAY);
            CharacterUiHelper.drawRetroText(ctx, tr, noAdv, badgeX + (badgeW - tr.getWidth(noAdv)) / 2, badgeY + (badgeH / 2) - 4, 0xFFFFFF);
        }

        return hoveredItem;
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public void close() {
        DummyPlayerManager.clearCache();
        client.setScreen(parent);
    }
}