package net.tompsen.nexuscharacters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class CharacterUiHelper {
    public static final Identifier CUSTOM_FONT = Identifier.of("nexuscharacters", "nexuscharacters");
    public static final Identifier TRASH_ICON = Identifier.of("nexuscharacters", "textures/gui/trash.png");
    public static final Identifier EDIT_ICON = Identifier.of("nexuscharacters", "textures/gui/edit.png");
    public static final Identifier HEART_ICON = Identifier.of("nexuscharacters", "textures/gui/heart.png");

    // Toggle states for model rendering
    public static boolean autoRotate = false;
    public static boolean showEquipment = true;

    public static final Identifier[] ARMOR_ICONS = {
            Identifier.of("minecraft", "textures/item/empty_armor_slot_helmet.png"),
            Identifier.of("minecraft", "textures/item/empty_armor_slot_chestplate.png"),
            Identifier.of("minecraft", "textures/item/empty_armor_slot_leggings.png"),
            Identifier.of("minecraft", "textures/item/empty_armor_slot_boots.png")
    };

    public static float getScale(float targetW, float targetH, int width, int height) {
        float scaleX = width / targetW;
        float scaleY = height / targetH;
        return Math.min(1.0f, Math.min(scaleX, scaleY));
    }

    public static void drawRetroText(DrawContext ctx, TextRenderer tr, Text text, int x, int y, int color) {
        Text shadowText = Text.literal(text.getString()).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT));
        ctx.drawText(tr, shadowText, x + 1, y + 1, 0xFF000000, false);
        ctx.drawText(tr, text, x, y, color, false);
    }

    public static void drawScaledTitle(DrawContext ctx, TextRenderer tr, String text, int centerX, int y, float scale, int color) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(centerX, y, 0);
        ctx.getMatrices().scale(scale, scale, 1.0F);

        int w = tr.getWidth(text);
        Text shadowText = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT));
        ctx.drawText(tr, shadowText, -w / 2 + 1, 1, 0xFF000000, false);

        Text t = Text.literal(text).setStyle(net.minecraft.text.Style.EMPTY.withFont(CUSTOM_FONT));
        ctx.drawText(tr, t, -w / 2, 0, color, false);
        ctx.getMatrices().pop();
    }

    public static void drawMinecraftPanel(DrawContext ctx, int x, int y, int w, int h) {
        // 1. Outer drop shadow (Matches CSS: 4px 4px 8px rgba(0, 0, 0, 0.5))
        ctx.fill(x + 4, y + 4, x + w + 4, y + h + 4, 0x80000000);

        // 2. Crisp black outline (Creates the sharp separation seen in the image)
        ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF000000);

        // 3. Dark gray base / Bottom-Right border (#373737)
        ctx.fill(x, y, x + w, y + h, 0xFF373737);

        // 4. Light gray Top-Left border (#8B8B8B) - 4px thick
        ctx.fill(x, y, x + w - 4, y + h - 4, 0xFF8B8B8B);

        // 5. Main background gradient (#3C3C3C to #282828)
        ctx.fillGradient(x + 4, y + 4, x + w - 4, y + h - 4, 0xFF3C3C3C, 0xFF282828);

        // 6. Inset Highlight Top/Left (Matches CSS: inset 2px 2px 0px rgba(255, 255, 255, 0.1))
        // 0x1AFFFFFF is roughly 10% opacity white
        ctx.fill(x + 4, y + 4, x + w - 4, y + 6, 0x1AFFFFFF); // Top inner
        ctx.fill(x + 4, y + 4, x + 6, y + h - 4, 0x1AFFFFFF); // Left inner

        // 7. Inset Shadow Bottom/Right (Matches CSS: inset -2px -2px 0px rgba(0, 0, 0, 0.5))
        // 0x80000000 is 50% opacity black
        ctx.fill(x + 4, y + h - 6, x + w - 4, y + h - 4, 0x80000000); // Bottom inner
        ctx.fill(x + w - 6, y + 4, x + w - 4, y + h - 4, 0x80000000); // Right inner
    }

    public static void drawMinecraftButton(DrawContext ctx, int x, int y, int w, int h, boolean hovered) {
        // 1. Crisp black outline
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);

        // 2. Dark gray base / Bottom-Right border (#373737)
        ctx.fill(x, y, x + w, y + h, 0xFF373737);

        // 3. Light gray Top-Left border (#8B8B8B) - 2px thick for buttons
        int border = 2;
        ctx.fill(x, y, x + w - border, y + h - border, hovered ? 0xFFAFAFAF : 0xFF8B8B8B);

        // 4. Main background gradient (#3C3C3C to #282828)
        ctx.fillGradient(x + border, y + border, x + w - border, y + h - border,
                hovered ? 0xFF4C4C4C : 0xFF3C3C3C,
                hovered ? 0xFF383838 : 0xFF282828);

        // 5. Inset Highlight Top/Left
        ctx.fill(x + border, y + border, x + w - border, y + border + 1, 0x1AFFFFFF);
        ctx.fill(x + border, y + border, x + border + 1, y + h - border, 0x1AFFFFFF);

        // 6. Inset Shadow Bottom/Right
        ctx.fill(x + border, y + h - border - 1, x + w - border, y + h - border, 0x40000000);
        ctx.fill(x + w - border - 1, y + border, x + w - border, y + h - border, 0x40000000);
    }

    public static void drawMinecraftCard(DrawContext ctx, int x, int y, int w, int h, boolean active) {
        // 1. Black outline (Ensuring all sides are covered)
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        
        // 2. Base border / Shadow
        ctx.fill(x, y, x + w - 1, y + h, active ? 0xFFFFFFFF : 0xFF373737);
        
        // 3. Thinner highlight border (2px)
        int border = 2;
        ctx.fill(x, y, x + w - border, y + h - border, active ? 0xFFAFAFAF : 0xFF8B8B8B);
        
        // 4. Brighter background gradient
        int startCol = active ? 0xFF707070 : 0xFF4C4C4C;
        int endCol   = active ? 0xFF505050 : 0xFF333333;
        ctx.fillGradient(x + border, y + border, x + w - border, y + h - border, startCol, endCol);
    }

    public static void drawMinecraftRect(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, 0xFF555555);
        ctx.fill(x, y, x + w - 2, y + h - 2, 0xFF2A2A2A);
        ctx.fillGradient(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF1E1E1E, 0xFF141414);
    }

    public static void injectCameraIfNeeded(MinecraftClient client) {
        try {
            EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
            java.lang.reflect.Field cameraField = EntityRenderDispatcher.class.getDeclaredField("camera");
            cameraField.setAccessible(true);
            if (cameraField.get(dispatcher) == null) cameraField.set(dispatcher, new Camera());
        } catch (Throwable e) {
            NexusCharacters.LOGGER.error("[NexusCharacters] Camera inject fail:", e);
        }
    }

    public static PlayerStatsInfo getPlayerStats(CharacterDto c) {
        int blocksMined = 0, mobKills = 0, diamonds = 0, playTime = 0;

        String statsJson = VaultManager.readStatsJson(c.id());
        if (statsJson != null) {
            try {
                JsonObject root = JsonParser.parseString(statsJson).getAsJsonObject();
                if (root.has("stats")) {
                    JsonObject stats = root.getAsJsonObject("stats");
                    if (stats.has("minecraft:custom")) {
                        JsonObject custom = stats.getAsJsonObject("minecraft:custom");
                        if (custom.has("minecraft:mob_kills")) mobKills  = custom.get("minecraft:mob_kills").getAsInt();
                        if (custom.has("minecraft:play_time")) playTime  = custom.get("minecraft:play_time").getAsInt();
                    }
                    if (stats.has("minecraft:mined")) {
                        for (var entry : stats.getAsJsonObject("minecraft:mined").entrySet()) {
                            blocksMined += entry.getValue().getAsInt();
                            if (entry.getKey().equals("minecraft:diamond_ore")
                                    || entry.getKey().equals("minecraft:deepslate_diamond_ore"))
                                diamonds += entry.getValue().getAsInt();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return new PlayerStatsInfo(blocksMined, mobKills, diamonds, playTime);
    }

    public static AdvancementInfo getLatestAdvancement(CharacterDto c) {
        String advJson = VaultManager.readAdvancementsJson(c.id());
        if (advJson == null) return null;

        String latestId = null;
        String latestTime = "";
        String latestCriteriaKey = null;

        try {
            JsonObject json = JsonParser.parseString(advJson).getAsJsonObject();
            for (var entry : json.entrySet()) {
                String id = entry.getKey();
                if (id.equals("DataVersion") || id.contains("recipes/")) continue;
                JsonObject node = entry.getValue().getAsJsonObject();
                if (node.has("done") && node.get("done").getAsBoolean() && node.has("criteria")) {
                    for (var crit : node.getAsJsonObject("criteria").entrySet()) {
                        String time = crit.getValue().getAsString();
                        if (time.compareTo(latestTime) > 0) {
                            latestTime = time;
                            latestId = id;
                            latestCriteriaKey = crit.getKey();
                        }
                    }
                }
            }
        } catch (Exception e) { return null; }

        if (latestId == null) return null;

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            Identifier advId = Identifier.tryParse(latestId);

            // First try: live advancement manager (works while in-game)
            if (client.getNetworkHandler() != null && advId != null) {
                var entry = client.getNetworkHandler().getAdvancementHandler().getManager().get(advId);
                if (entry != null && entry.getAdvancementEntry().value().display().isPresent()) {
                    net.minecraft.advancement.AdvancementDisplay display = entry.getAdvancementEntry().value().display().get();
                    return new AdvancementInfo(display.getTitle().getString(), display.getDescription().getString(), display.getIcon());
                }
            }

            // Fallback: language file lookup (works for vanilla advancements when not in-game)
            String title = latestId; // use raw id as last resort instead of "Advancement"
            String desc = "";
            ItemStack iconStack = new ItemStack(Items.MAP);

            if (advId != null) {
                net.minecraft.util.Language lang = net.minecraft.util.Language.getInstance();

                // Vanilla key format: advancements.<namespace>.<path_dots>.title
                // e.g. minecraft:story/mine_stone → advancements.story.mine_stone.title
                String pathDots = advId.getPath().replace('/', '.');
                String vanillaTitleKey = "advancements." + pathDots + ".title";
                String vanillaDescKey  = "advancements." + pathDots + ".description";

                // Mod key format may also include namespace:
                // e.g. cobblemon:cobblemon/starter → advancements.cobblemon.cobblemon.starter.title
                String nsDots = advId.getNamespace() + "." + pathDots;
                String modTitleKey = "advancements." + nsDots + ".title";
                String modDescKey  = "advancements." + nsDots + ".description";

                if (lang.hasTranslation(vanillaTitleKey)) {
                    title = lang.get(vanillaTitleKey);
                    desc  = lang.hasTranslation(vanillaDescKey) ? lang.get(vanillaDescKey) : "";
                } else if (lang.hasTranslation(modTitleKey)) {
                    title = lang.get(modTitleKey);
                    desc  = lang.hasTranslation(modDescKey) ? lang.get(modDescKey) : "";
                } else {
                    // Format the raw id nicely: "cobblemon:cobblemon/catch_starter" → "Cobblemon: Catch Starter"
                    String rawPath = advId.getPath().substring(advId.getPath().lastIndexOf('/') + 1);
                    title = rawPath.replace('_', ' ');
                    title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
                }
            }
            return new AdvancementInfo(title, desc, iconStack);
        } catch (Exception e) {
            return null;
        }
    }

    public record PlayerStatsInfo(int blocksMined, int mobKills, int diamonds, int playTime) {}
    public record AdvancementInfo(String title, String description, ItemStack icon) {}
}
