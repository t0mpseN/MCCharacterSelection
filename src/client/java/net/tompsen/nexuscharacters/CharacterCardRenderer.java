package net.tompsen.nexuscharacters;

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
                                int x, int y, boolean highlight) {

        CharacterUiHelper.drawMinecraftCard(ctx, x, y, CARD_W, CARD_H, highlight);

        // Face Border (Matching card style)
        int faceX = x + 8, faceY = y + 7, faceSize = 40;
        ctx.fill(faceX - 2, faceY - 2, faceX + faceSize + 2, faceY + faceSize + 2, 0xFF000000); // Black outline
        ctx.fill(faceX - 1, faceY - 1, faceX + faceSize + 1, faceY + faceSize + 1, highlight ? 0xFFFFFFFF : 0xFF8B8B8B); // Border
        ctx.fill(faceX, faceY, faceX + faceSize, faceY + faceSize, 0xFF222222); // Inner bg

        SkinTextures skinTextures = DummyPlayerManager.getSkinTextures(c);
        Identifier skin = skinTextures.texture();
        ctx.drawTexture(skin, faceX, faceY, faceSize, faceSize, 8, 8, 8, 8, 64, 64);
        ctx.drawTexture(skin, faceX, faceY, faceSize, faceSize, 40, 8, 8, 8, 64, 64);

        // Name
        Text nameTxt = Text.literal(c.name()).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.WHITE);
        CharacterUiHelper.drawRetroText(ctx, tr, nameTxt, x + 56, y + 14, 0xFFFFFF);

        // Game Mode Display
        int gameMode    = c.gameMode();
        boolean isHardcore = c.hardcore();
        String classStr;
        Formatting color;
        
        if (isHardcore) {
            classStr = "Hardcore";
            color = Formatting.DARK_RED;
        } else {
            switch (gameMode) {
                case 1 -> { classStr = "★ Creative"; color = Formatting.AQUA; }
                case 2 -> { classStr = "Adventure"; color = Formatting.GOLD; }
                case 3 -> { classStr = "Spectator"; color = Formatting.DARK_GRAY; }
                default -> { classStr = "Survival"; color = Formatting.GRAY; }
            }
        }

        Text classTxt = Text.literal(classStr).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(color);
        CharacterUiHelper.drawRetroText(ctx, tr, classTxt, x + 56 + tr.getWidth(nameTxt) + 8, y + 14, 0xFFFFFF);

        // Level (Don't show for Creative/Spectator)
        if (gameMode != 1 && gameMode != 3) {
            int level = VaultManager.readPlayerNbt(c.id()).getInt("XpLevel");
            Text lvlTxt = Text.literal("LVL " + level).setStyle(net.minecraft.text.Style.EMPTY.withFont(CharacterUiHelper.CUSTOM_FONT)).formatted(Formatting.YELLOW);
            CharacterUiHelper.drawRetroText(ctx, tr, lvlTxt, x + 56, y + 30, 0xFFFFFF);
        }
    }
}
