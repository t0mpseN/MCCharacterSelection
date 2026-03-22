package net.tompsen.charsel;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public class CharacterCardRenderer {
    public static final int CARD_W = 280;
    public static final int CARD_H = 54;

    public static void drawCard(DrawContext ctx, TextRenderer tr, CharacterDto c,
                                int x, int y, boolean isHovered) {

        int bgColorStart = isHovered ? 0xFF64648C : 0xFF505050;
        int bgColorEnd   = isHovered ? 0xFF505078 : 0xFF3C3C3C;
        int borderColorTopLeft = isHovered ? 0xFFC0C0E0 : 0xFFA0A0A0;
        int borderColorBottomRight = isHovered ? 0xFF6666CC : 0xFF555555;

        if (isHovered) {
            ctx.fill(x - 2, y - 2, x + CARD_W + 2, y + CARD_H + 2, 0x446666CC);
        }

        ctx.fill(x, y, x + CARD_W, y + CARD_H, borderColorBottomRight);
        ctx.fill(x, y, x + CARD_W - 3, y + CARD_H - 3, borderColorTopLeft);
        ctx.fillGradient(x + 3, y + 3, x + CARD_W - 3, y + CARD_H - 3, bgColorStart, bgColorEnd);

        SkinTextures skinTextures = DummyPlayerManager.getSkinTextures(c);
        Identifier skin = skinTextures.texture();
        ctx.drawTexture(skin, x + 8, y + 7, 40, 40, 8, 8, 8, 8, 64, 64);
        ctx.drawTexture(skin, x + 8, y + 7, 40, 40, 40, 8, 8, 8, 64, 64);

        // Name
        Text nameTxt = Text.literal(c.name()).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.WHITE);
        CharacterListScreen.drawRetroText(ctx, tr, nameTxt, x + 56, y + 14, 0xFFFFFF);

        // Creative/Survival right next to the Name
        int gameMode = c.playerNbt().isEmpty() ? -1 : c.playerNbt().getInt("playerGameType");
        boolean isCreative = gameMode == 1;
        String classStr = isCreative ? "★ Creative" : "Survival";
        Text classTxt = Text.literal(classStr).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(isCreative ? Formatting.AQUA : Formatting.GRAY);
        CharacterListScreen.drawRetroText(ctx, tr, classTxt, x + 56 + tr.getWidth(nameTxt) + 8, y + 14, 0xFFFFFF);

        // Level directly below the name (Only render if NOT in creative!)
        if (!isCreative) {
            int level = c.playerNbt().isEmpty() ? 0 : c.playerNbt().getInt("XpLevel");
            Text lvlTxt = Text.literal("LVL " + level).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterListScreen.CUSTOM_FONT)).formatted(Formatting.YELLOW);
            CharacterListScreen.drawRetroText(ctx, tr, lvlTxt, x + 56, y + 30, 0xFFFFFF);
        }
    }
}