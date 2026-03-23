package net.tompsen.nexuscharacters;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.damage.DamageSources;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Difficulty;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.border.WorldBorder;
import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class DummyWorldManager {

    private static ClientWorld dummyWorld;

    static final DynamicRegistryManager.Immutable REGISTRIES =
            DynamicRegistryManager.of(Registries.REGISTRIES);

    private static final DamageSources FAKE_DAMAGE_SOURCES = allocateDamageSources();

    private static DamageSources allocateDamageSources() {
        try {
            return (DamageSources) getUnsafe().allocateInstance(DamageSources.class);
        } catch (Throwable e) {
            NexusCharacters.LOGGER.error("[CharSel] Falha ao alocar DamageSources fake:", e);
            return null;
        }
    }

    @SuppressWarnings("all")
    static final class SafeClientWorld extends ClientWorld {

        private SafeClientWorld() {
            super(null, null, null, null, 2, 2, null, null, false, 0L);
        }

        @Override
        public DynamicRegistryManager getRegistryManager() {
            return REGISTRIES;
        }

        @Override
        public FeatureSet getEnabledFeatures() {
            return FeatureFlags.DEFAULT_ENABLED_FEATURES;
        }

        @Override
        public DamageSources getDamageSources() {
            return FAKE_DAMAGE_SOURCES;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.getDefaultState();
        }

        @Override
        public BlockPos getSpawnPos() {
            return BlockPos.ORIGIN; // (0, 0, 0) — nunca é realmente usado no preview
        }

        @Override
        public net.minecraft.scoreboard.Scoreboard getScoreboard() {
            return new net.minecraft.scoreboard.Scoreboard();
        }

        @Override
        public void disconnect() {}
    }

    // ── API pública ──────────────────────────────────────────────────────────

    public static ClientWorld getDummyWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) return client.world;
        if (dummyWorld == null) dummyWorld = buildFakeWorld(client);
        if (dummyWorld == null) {
            NexusCharacters.LOGGER.error("[CharSel] Falha ao criar dummyWorld — veja erro acima");
        }
        return dummyWorld;
    }

    public static DynamicRegistryManager.Immutable getRegistries() {
        return REGISTRIES;
    }

    public static void initAtStartup() { getDummyWorld(); }
    public static boolean isReady() { return getDummyWorld() != null; }
    public static void captureFromWorld(ClientWorld world) {}
    public static void clear() { dummyWorld = null; }

    // ── Construção ───────────────────────────────────────────────────────────

    private static ClientWorld buildFakeWorld(MinecraftClient client) {
        try {
            Unsafe unsafe = getUnsafe();
            SafeClientWorld world = (SafeClientWorld) unsafe.allocateInstance(SafeClientWorld.class);
            injectFields(unsafe, world, client);
            NexusCharacters.LOGGER.info("[CharSel] Mundo falso criado com sucesso.");
            return world;
        } catch (Throwable e) {
            NexusCharacters.LOGGER.error("[CharSel] Falha ao criar mundo falso:", e);
            return null;
        }
    }

    private static void injectFields(Unsafe unsafe, SafeClientWorld world, MinecraftClient client)
            throws Exception {

        for (Class<?> cls = ClientWorld.class;
             cls != null && cls != Object.class;
             cls = cls.getSuperclass()) {

            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                field.setAccessible(true);
                long offset = unsafe.objectFieldOffset(field);
                Class<?> type = field.getType();

                if (type.isPrimitive()) {
                    if (boolean.class == type && "isClient".equals(field.getName())) {
                        unsafe.putBoolean(world, offset, true);
                    }
                    continue;
                }

                Object value = resolveValue(type, client);
                if (value != null) {
                    unsafe.putObject(world, offset, value);
                }
            }
        }
    }

    private static Object resolveValue(Class<?> type, MinecraftClient client) {
        if (DynamicRegistryManager.class.isAssignableFrom(type)) return REGISTRIES;
        if (type == FeatureSet.class)                            return FeatureFlags.DEFAULT_ENABLED_FEATURES;
        if (type == MinecraftClient.class)                       return client;
        if (DamageSources.class.isAssignableFrom(type))          return FAKE_DAMAGE_SOURCES;
        if (WorldBorder.class.isAssignableFrom(type))            return new WorldBorder();
        if (type == Random.class)                                return Random.create();
        if (Supplier.class.isAssignableFrom(type))               return (Supplier<?>) client::getProfiler;

        if (type == Map.class
                || type == HashMap.class
                || type == LinkedHashMap.class)                  return new HashMap<>();
        if (type == ConcurrentHashMap.class)                     return new ConcurrentHashMap<>();
        if (type == List.class
                || type == ArrayList.class)                      return new ArrayList<>();
        if (type == LinkedList.class)                            return new LinkedList<>();
        if (type == Set.class
                || type == HashSet.class
                || type == LinkedHashSet.class)                  return new HashSet<>();
        if (type == Optional.class)                              return Optional.empty();
        if (MutableWorldProperties.class.isAssignableFrom(type))
            return new ClientWorld.Properties(Difficulty.NORMAL, false, false);
        return null;
    }

    private static Unsafe getUnsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        @SuppressWarnings("deprecation")
        Unsafe unsafe = (Unsafe) f.get(null);
        return unsafe;
    }
}