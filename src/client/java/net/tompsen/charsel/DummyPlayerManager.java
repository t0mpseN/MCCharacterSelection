package net.tompsen.charsel;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DummyPlayerManager {

    private static final Map<UUID, OtherClientPlayerEntity> DUMMY_CACHE = new HashMap<>();
    private static final Map<UUID, SkinTextures> SKIN_CACHE = new HashMap<>();

    // ── Subclasse que bypassa o getNetworkHandler() ──────────────────────────
    static class DummyClientPlayer extends OtherClientPlayerEntity {
        private final UUID characterId;

        DummyClientPlayer(ClientWorld world, GameProfile profile, UUID characterId) {
            super(world, profile);
            this.characterId = characterId;
        }

        @Override
        public SkinTextures getSkinTextures() {
            SkinTextures cached = SKIN_CACHE.get(characterId);
            if (cached != null) return cached;
            return DefaultSkinHelper.getSkinTextures(
                    DummyPlayerManager.resolveDefaultSkinUUID(characterId));
        }

        @Override
        public boolean isSpectator() { return false; }

        @Override
        public boolean isCreative() { return false; }

        @Override
        public boolean isInvisibleTo(net.minecraft.entity.player.PlayerEntity player) {
            return false;
        }

        @Override
        public boolean isCustomNameVisible() {
            return false;
        }

        @Override
        protected net.minecraft.client.network.PlayerListEntry getPlayerListEntry() {
            return null;
        }
    }

    // ── API pública ───────────────────────────────────────────────────────────

    public static OtherClientPlayerEntity getDummyPlayer(CharacterDto character) {
        ClientWorld world = DummyWorldManager.getDummyWorld();
        if (world == null) return null;

        if (DUMMY_CACHE.containsKey(character.id())) {
            OtherClientPlayerEntity cached = DUMMY_CACHE.get(character.id());
            if (cached.getWorld() == world) return cached;
        }

        // 1. Montar GameProfile com a skin armazenada
        GameProfile profile = new GameProfile(character.id(), character.name());
        String skinVal = character.skinValue();
        String skinSig = character.skinSignature();
        if (skinVal != null && !skinVal.isEmpty()) {
            profile.getProperties().put("textures",
                    new Property("textures", skinVal, skinSig != null ? skinSig : ""));
        }

        // 2. Criar a entidade com nossa subclasse
        DummyClientPlayer dummy = new DummyClientPlayer(world, profile, character.id());
        dummy.setPosition(0, 0, 0);
        dummy.age = 20;

        // Ativa todas as camadas de overlay (chapéu, jaqueta, mangas, calças)
        try {
            java.lang.reflect.Field partsField = net.minecraft.entity.player.PlayerEntity.class
                    .getDeclaredField("PLAYER_MODEL_PARTS");
            partsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            net.minecraft.entity.data.TrackedData<Byte> partsData =
                    (net.minecraft.entity.data.TrackedData<Byte>) partsField.get(null);
            dummy.getDataTracker().set(partsData, (byte) 0x7F);
        } catch (Throwable e) {
            CharacterSelection.LOGGER.warn("[CharSel] Falha ao ativar model parts: {}", e.getMessage());
        }

        // 3. Equipar itens do inventário
        NbtCompound playerNbt = character.playerNbt();
        if (playerNbt != null && playerNbt.contains("Inventory")) {
            NbtList inventory = playerNbt.getList("Inventory", 10);
            int selectedSlot = playerNbt.contains("SelectedItemSlot")
                    ? playerNbt.getInt("SelectedItemSlot") : 0;

            for (int i = 0; i < inventory.size(); i++) {
                NbtCompound itemTag = inventory.getCompound(i);
                int slot = itemTag.getByte("Slot") & 255;
                ItemStack stack = ItemStack.fromNbtOrEmpty(DummyWorldManager.getRegistries(), itemTag);

                if (!stack.isEmpty()) {
                    if (slot == 100) dummy.equipStack(EquipmentSlot.FEET, stack);
                    else if (slot == 101) dummy.equipStack(EquipmentSlot.LEGS, stack);
                    else if (slot == 102) dummy.equipStack(EquipmentSlot.CHEST, stack);
                    else if (slot == 103) dummy.equipStack(EquipmentSlot.HEAD, stack);
                    else if (slot == 150) dummy.equipStack(EquipmentSlot.OFFHAND, stack); // -106 & 0xFF = 150
                    if (slot == selectedSlot) dummy.equipStack(EquipmentSlot.MAINHAND, stack);
                }
            }
        }

        if (skinVal != null && !skinVal.isEmpty()) {
            // Skin custom: usa o profile que já tem a property textures
            MinecraftClient.getInstance().getSkinProvider()
                    .fetchSkinTextures(profile)
                    .thenAccept(textures -> SKIN_CACHE.put(character.id(), textures));
        } else {
            // Usa character.id() diretamente — cada personagem tem seu UUID único → skin default única
            MinecraftClient.getInstance().getSkinProvider()
                    .fetchSkinTextures(new GameProfile(character.id(), character.name()))
                    .thenAccept(textures -> SKIN_CACHE.put(character.id(), textures));
        }

        DUMMY_CACHE.put(character.id(), dummy);
        return dummy;
    }

    public static SkinTextures getSkinTextures(CharacterDto character) {
        SkinTextures cached = SKIN_CACHE.get(character.id());
        if (cached != null) return cached;

        if (character.skinValue() != null && !character.skinValue().isEmpty()) {
            GameProfile profile = new GameProfile(character.id(), character.name());
            profile.getProperties().put("textures",
                    new Property("textures", character.skinValue(),
                            character.skinSignature() != null ? character.skinSignature() : ""));
            MinecraftClient.getInstance().getSkinProvider()
                    .fetchSkinTextures(profile)
                    .thenAccept(t -> SKIN_CACHE.put(character.id(), t));
        }

        // Deriva a skin default usando o UUID real do jogador (não o UUID aleatório do character)
        return DefaultSkinHelper.getSkinTextures(resolveDefaultSkinUUID(character));
    }

    // Extrai o UUID real guardado, ou usa o id do character como fallback
    private static UUID resolveDefaultSkinUUID(CharacterDto character) {
        String skinUsername = character.skinUsername();
        if (skinUsername != null && skinUsername.startsWith("__default__:")) {
            try {
                return UUID.fromString(skinUsername.substring("__default__:".length()));
            } catch (Exception ignored) {}
        }
        return character.id();
    }

    static UUID resolveDefaultSkinUUID(UUID characterId) {
        // O SKIN_CACHE já foi populado com o UUID correto pelo getDummyPlayer()
        // Este fallback só é chamado nos primeiros frames antes do fetch completar
        return characterId;
    }

    public static void clearCache() {
        DUMMY_CACHE.clear();
        SKIN_CACHE.clear();
        DummyWorldManager.clear();
    }

    public static void invalidateDummies() {
        DUMMY_CACHE.clear();
    }
}