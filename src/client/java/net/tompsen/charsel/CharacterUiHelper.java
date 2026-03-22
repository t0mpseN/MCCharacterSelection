package net.tompsen.charsel;

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
    public static final Identifier CUSTOM_FONT = Identifier.of("characterselection", "charsel");
    public static final Identifier TRASH_ICON = Identifier.of("characterselection", "textures/gui/trash.png");
    public static final Identifier EDIT_ICON = Identifier.of("characterselection", "textures/gui/edit.png");
    public static final Identifier HEART_ICON = Identifier.of("characterselection", "textures/gui/heart.png");

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
        ctx.fill(x, y, x + w, y + h, 0xFF373737);
        ctx.fill(x, y, x + w - 4, y + h - 4, 0xFF8B8B8B);
        ctx.fillGradient(x + 4, y + 4, x + w - 4, y + h - 4, 0xFF3C3C3C, 0xFF282828);
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
            CharacterSelection.LOGGER.error("[CharSel] Camera inject fail:", e);
        }
    }

    public static PlayerStatsInfo getPlayerStats(CharacterDto c) {
        int blocksMined = 0;
        int mobKills = 0;
        int diamonds = 0;
        int playTime = 0;

        NbtCompound modData = c.modData();
        if (modData != null) {
            for (String key : modData.getKeys()) {
                // Handle both world-prefixed ("worldId::relativizedPath") and global keys
                String path = key.contains("::") ? key.substring(key.indexOf("::") + 2) : key;
                if (!path.startsWith("stats/")) continue;

                try {
                    String jsonString = new String(modData.getByteArray(key), StandardCharsets.UTF_8);
                    JsonObject root = JsonParser.parseString(jsonString).getAsJsonObject();
                    if (root.has("stats")) {
                        JsonObject stats = root.getAsJsonObject("stats");

                        if (stats.has("minecraft:custom")) {
                            JsonObject custom = stats.getAsJsonObject("minecraft:custom");
                            if (custom.has("minecraft:mob_kills")) mobKills += custom.get("minecraft:mob_kills").getAsInt();
                            if (custom.has("minecraft:play_time")) playTime += custom.get("minecraft:play_time").getAsInt();
                        }

                        if (stats.has("minecraft:mined")) {
                            JsonObject mined = stats.getAsJsonObject("minecraft:mined");
                            for (Map.Entry<String, JsonElement> entry : mined.entrySet()) {
                                blocksMined += entry.getValue().getAsInt();
                                if (entry.getKey().equals("minecraft:diamond_ore") || entry.getKey().equals("minecraft:deepslate_diamond_ore")) {
                                    diamonds += entry.getValue().getAsInt();
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return new PlayerStatsInfo(blocksMined, mobKills, diamonds, playTime);
    }

    public static AdvancementInfo getLatestAdvancement(CharacterDto c) {
        NbtCompound modData = c.modData();
        if (modData == null || modData.isEmpty()) return null;

        String latestId = null;
        String latestTime = "";
        String latestAdvKey = null;

        // 1. Find the absolute latest advancement ID and its source file
        for (String key : modData.getKeys()) {
            if (key.startsWith("_charsel:")) continue;
            String path = key.contains("::") ? key.substring(key.indexOf("::") + 2) : key;
            if (!path.startsWith("advancements/")) continue;

            try {
                byte[] bytes = modData.getByteArray(key);
                String jsonString = new String(bytes, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();

                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    String id = entry.getKey();
                    if (id.equals("DataVersion") || id.contains("recipes/")) continue;

                    JsonObject advNode = entry.getValue().getAsJsonObject();
                    if (advNode.has("done") && advNode.get("done").getAsBoolean() && advNode.has("criteria")) {
                        JsonObject criteria = advNode.getAsJsonObject("criteria");
                        for (Map.Entry<String, JsonElement> crit : criteria.entrySet()) {
                            String time = crit.getValue().getAsString();
                            if (time.compareTo(latestTime) > 0) {
                                latestTime = time;
                                latestId = id;
                                latestAdvKey = key;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (latestId == null) return null;

        // 2. Try to use the cached display info (Icons, Titles, etc.)
        NbtCompound displayCache = modData.getCompound("_charsel:adv_display_cache");
        if (displayCache.contains(latestId)) {
            NbtCompound info = displayCache.getCompound(latestId);
            String title = info.getString("title");
            String desc = info.getString("desc");
            ItemStack iconStack = ItemStack.fromNbtOrEmpty(DummyWorldManager.getRegistries(), info.getCompound("icon"));
            if (!iconStack.isEmpty()) {
                return new AdvancementInfo(title, desc, iconStack);
            }
        }

        // 3. Fallback logic if not in cache (e.g., first join or cache missing)
        try {
            // Re-find criteria key for the fallback icon
            String latestCriteriaKey = null;
            byte[] bytes = modData.getByteArray(latestAdvKey);
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject advNode = json.getAsJsonObject(latestId);
            if (advNode != null && advNode.has("criteria")) {
                JsonObject criteria = advNode.getAsJsonObject("criteria");
                String maxTime = "";
                for (Map.Entry<String, JsonElement> crit : criteria.entrySet()) {
                    String t = crit.getValue().getAsString();
                    if (t.compareTo(maxTime) > 0) {
                        maxTime = t;
                        latestCriteriaKey = crit.getKey();
                    }
                }
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() != null) {
                Identifier advId = Identifier.tryParse(latestId);
                if (advId != null) {
                    net.minecraft.advancement.AdvancementEntry entry = client.getNetworkHandler().getAdvancementHandler().getManager().get(advId).getAdvancementEntry();
                    if (entry != null && entry.value().display().isPresent()) {
                        net.minecraft.advancement.AdvancementDisplay display = entry.value().display().get();
                        return new AdvancementInfo(display.getTitle().getString(), display.getDescription().getString(), display.getIcon());
                    }
                }
            }

            Identifier advId = Identifier.tryParse(latestId);
            String title = "Advancement";
            String desc = "Completed an advancement.";
            ItemStack iconStack = new ItemStack(Items.MAP);

            if (advId != null) {
                String path = advId.getPath().replace('/', '.');
                String titleKey = "advancements." + path + ".title";
                String descKey = "advancements." + path + ".description";
                net.minecraft.util.Language lang = net.minecraft.util.Language.getInstance();

                if (lang.hasTranslation(titleKey)) {
                    title = lang.get(titleKey);
                    desc = lang.get(descKey);
                }

                if (latestCriteriaKey != null) {
                    Identifier itemId = Identifier.tryParse(latestCriteriaKey);
                    if (itemId == null && !latestCriteriaKey.contains(":")) itemId = Identifier.of("minecraft", latestCriteriaKey);
                    if (itemId != null) {
                        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(itemId);
                        if (item != Items.AIR) iconStack = new ItemStack(item);
                    }
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
