package net.tompsen.nexuscharacters;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.*;

/**
 * Owns everything under .minecraft/nexuscharacters/vaults/.
 */
public class VaultManager {

    // ── Constants ────────────────────────────────────────────────────────────

    public static final String PLAYER_TOKEN = "__player__";

    private static final Pattern UUID_PAT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> IGNORED_WORLD_DIRS = Set.of(
            "region", "DIM1", "DIM-1", "entities", "poi", "dimensions", "level.dat",
            "level.dat_old", "session.lock", "icon.png", "resources.zip"
    );

    /** File suffixes that are mod-internal backups and should not be vaulted. */
    private static boolean isBackupFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".old") || name.endsWith(".bak") || name.endsWith(".tmp");
    }

    private static final Path VAULTS_DIR =
            FabricLoader.getInstance().getGameDir().resolve("nexuscharacters/vaults");

    // ── NBT cache (UI-only) ───────────────────

    private static final Map<UUID, NbtCompound> NBT_CACHE = new HashMap<>();

    // ── Path resolution ──────────────────────────────────────────────────────

    public static Path getVaultDir(UUID characterId) {
        return VAULTS_DIR.resolve(characterId.toString());
    }

    public static String worldToVault(String rel) {
        return UUID_PAT.matcher(rel).replaceAll(PLAYER_TOKEN);
    }

    public static String vaultToWorld(String rel, UUID playerUuid) {
        return rel.replace(PLAYER_TOKEN, playerUuid.toString());
    }

    /**
     * NBT int-array tag header for a field named "UUID" with 4 elements.
     * Used to locate and replace the binary UUID stored inside playerdata NBT.
     * Format: tag_type(0x0b) + name_len(0x0004) + "UUID" + array_len(0x00000004)
     */
    private static final byte[] NBT_UUID_HEADER = {
        0x0b, 0x00, 0x04, 0x55, 0x55, 0x49, 0x44, 0x00, 0x00, 0x00, 0x04
    };
    /** 16-byte sentinel used in place of the binary UUID in vault files. */
    private static final byte[] NBT_UUID_TOKEN = new byte[16]; // all zeros

    private static byte[] uuidToBinaryBigEndian(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] b = new byte[16];
        for (int i = 7; i >= 0; i--) { b[i] = (byte)(msb & 0xFF); msb >>= 8; }
        for (int i = 15; i >= 8; i--) { b[i] = (byte)(lsb & 0xFF); lsb >>= 8; }
        return b;
    }

    private static byte[] rewriteContentToVault(byte[] content, UUID playerUuid) {
        // Replace string form: "a289ec68-645c-..." → "__player__"
        byte[] uuidStrBytes = playerUuid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tokenStrBytes = PLAYER_TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        content = replaceAll(content, uuidStrBytes, tokenStrBytes);
        // Replace binary NBT int-array form (inside playerdata NBT) with zero sentinel
        content = replaceNbtUuid(content, uuidToBinaryBigEndian(playerUuid), NBT_UUID_TOKEN);
        return content;
    }

    private static byte[] rewriteContentFromVault(byte[] content, UUID playerUuid) {
        // Replace string form: "__player__" → "a289ec68-645c-..."
        byte[] tokenStrBytes = PLAYER_TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] uuidStrBytes = playerUuid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        content = replaceAll(content, tokenStrBytes, uuidStrBytes);
        // Replace binary NBT int-array sentinel with the target player's UUID
        content = replaceNbtUuid(content, NBT_UUID_TOKEN, uuidToBinaryBigEndian(playerUuid));
        return content;
    }

    /**
     * Replaces the 16-byte UUID payload that follows an NBT int-array "UUID" tag header.
     * Only replaces occurrences where {@code fromUuid} matches; leaves all other data intact.
     */
    private static byte[] replaceNbtUuid(byte[] src, byte[] fromUuid, byte[] toUuid) {
        int headerLen = NBT_UUID_HEADER.length; // 11 bytes
        int limit = src.length - headerLen - 16;
        if (limit < 0) return src;
        ByteArrayOutputStream out = null; // lazy-init only if a replacement is needed
        int i = 0;
        while (i <= limit) {
            if (matches(src, i, NBT_UUID_HEADER) && matches(src, i + headerLen, fromUuid)) {
                if (out == null) {
                    out = new ByteArrayOutputStream(src.length);
                    out.write(src, 0, i);
                }
                out.write(NBT_UUID_HEADER, 0, headerLen);
                out.write(toUuid, 0, 16);
                i += headerLen + 16;
            } else {
                if (out != null) out.write(src[i]);
                i++;
            }
        }
        if (out == null) return src;
        if (i < src.length) out.write(src, i, src.length - i);
        return out.toByteArray();
    }

    private static byte[] replaceAll(byte[] src, byte[] from, byte[] to) {
        if (from.length == 0 || src.length < from.length) return src;
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length);
        int i = 0;
        while (i <= src.length - from.length) {
            if (matches(src, i, from)) {
                out.write(to, 0, to.length);
                i += from.length;
            } else {
                out.write(src[i++]);
            }
        }
        while (i < src.length) out.write(src[i++]);
        byte[] result = out.toByteArray();
        return result.length == src.length ? src : result;
    }

    private static boolean matches(byte[] src, int offset, byte[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (src[offset + i] != pattern[i]) return false;
        }
        return true;
    }

    public static final String SHARD_TOKEN = "__shard__";
    private static final java.util.regex.Pattern SHARD_PAT =
            java.util.regex.Pattern.compile("((?:[^/]+/)*)([0-9a-fA-F]{2})(/.+)");

    public static String worldShardToVault(String worldRel) {
        java.util.regex.Matcher m = SHARD_PAT.matcher(worldRel);
        if (!m.matches()) return worldRel;
        return m.group(1) + SHARD_TOKEN + m.group(3);
    }

    public static boolean isMatchingShard(String worldRel, UUID playerUuid) {
        java.util.regex.Matcher m = SHARD_PAT.matcher(worldRel);
        if (!m.matches()) return true;
        String shardDir = m.group(2).toLowerCase();
        String uuidNoHyphens = playerUuid.toString().replace("-", "").toLowerCase();
        String shard0 = uuidNoHyphens.substring(0, 2);
        String shard1 = uuidNoHyphens.substring(1, 3);
        return shardDir.equals(shard0) || shardDir.equals(shard1);
    }

    public static List<String> vaultToWorldAll(String vaultRel, UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        String uuidNoHyphens = uuidStr.replace("-", "");
        if (vaultRel.contains(SHARD_TOKEN)) {
            String withUuid = vaultRel.replace(PLAYER_TOKEN, uuidStr);
            Set<String> seen = new LinkedHashSet<>();
            seen.add(withUuid.replace(SHARD_TOKEN, uuidNoHyphens.substring(0, 2)));
            seen.add(withUuid.replace(SHARD_TOKEN, uuidNoHyphens.substring(1, 3)));
            return new ArrayList<>(seen);
        }
        java.util.regex.Matcher m = SHARD_PAT.matcher(vaultRel);
        if (m.matches()) {
            String prefix = m.group(1);
            String suffix = m.group(3).replace(PLAYER_TOKEN, uuidStr);
            Set<String> seen = new LinkedHashSet<>();
            seen.add(prefix + uuidNoHyphens.substring(0, 2) + suffix);
            seen.add(prefix + uuidNoHyphens.substring(1, 3) + suffix);
            return new ArrayList<>(seen);
        }
        return List.of(vaultRel.replace(PLAYER_TOKEN, uuidStr));
    }

    public static void purgeStaleUuidFiles(UUID characterId) {
        Path vaultDir = getVaultDir(characterId);
        if (!Files.exists(vaultDir)) return;
        try (Stream<Path> walk = Files.walk(vaultDir)) {
            walk.filter(Files::isRegularFile)
                .filter(f -> UUID_PAT.matcher(vaultDir.relativize(f).toString().replace("\\", "/")).find())
                .forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
        } catch (IOException e) {
            NexusCharacters.LOGGER.warn("[VaultManager] purgeStaleUuidFiles failed: {}", e.getMessage());
        }
    }

    // ── Vault lifecycle ──────────────────────────────────────────────────────

    public static void createVault(UUID characterId) {
        try { Files.createDirectories(getVaultDir(characterId)); } catch (IOException ignored) {}
    }

    public static void deleteVault(UUID characterId) {
        Path vaultDir = getVaultDir(characterId);
        if (!Files.exists(vaultDir)) return;
        try (Stream<Path> walk = Files.walk(vaultDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /**
     * If this character's vault has no playerdata yet, looks for a pre-existing player in the
     * world whose username matches the character name and imports their data into the vault.
     * Resolution order:
     *   1. usercache.json (online-mode servers — exact name match)
     *   2. Offline UUID: {@code UUID.nameUUIDFromBytes("OfflinePlayer:<name>".getBytes("UTF-8"))}
     *      (singleplayer and offline-mode servers)
     *
     * @return true if legacy data was found and imported
     */
    public static boolean importLegacyDataIfNeeded(UUID characterId, String characterName, Path worldDir) {
        Path vaultPlayerData = getVaultDir(characterId).resolve("playerdata/__player__.dat");
        if (Files.exists(vaultPlayerData)) return false; // vault already has data

        Path playerDataDir = worldDir.resolve("playerdata");
        if (!Files.isDirectory(playerDataDir)) return false;

        // Try to resolve the legacy UUID for this character name
        UUID legacyUuid = resolveLegacyUuid(characterName, worldDir);
        if (legacyUuid == null) return false;

        // Check if a playerdata file exists for that UUID
        Path legacyFile = playerDataDir.resolve(legacyUuid + ".dat");
        if (!Files.exists(legacyFile)) return false;

        NexusCharacters.LOGGER.info("[Nexus] Importing legacy data for character '{}' from uuid {} into vault {}",
                characterName, legacyUuid, characterId);

        // Copy all per-player files from the world into the vault, tokenizing the legacy UUID
        copyWorldToVault(characterId, worldDir, legacyUuid);
        return true;
    }

    /**
     * Resolves a legacy player UUID from a username.
     * Checks usercache.json first, then falls back to the offline UUID formula.
     * Returns null only if the resulting UUID has no matching playerdata file.
     */
    private static UUID resolveLegacyUuid(String name, Path worldDir) {
        // 1. usercache.json lookup (online mode)
        Path usercache = FabricLoader.getInstance().getGameDir().resolve("usercache.json");
        if (Files.exists(usercache)) {
            try {
                JsonArray arr = JsonParser.parseString(Files.readString(usercache)).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    if (name.equalsIgnoreCase(obj.get("name").getAsString())) {
                        return UUID.fromString(obj.get("uuid").getAsString());
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Offline UUID: UUID.nameUUIDFromBytes("OfflinePlayer:<name>")
        try {
            byte[] bytes = ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            UUID offlineUuid = UUID.nameUUIDFromBytes(bytes);
            Path offlineFile = worldDir.resolve("playerdata/" + offlineUuid + ".dat");
            if (Files.exists(offlineFile)) return offlineUuid;
        } catch (Exception ignored) {}

        return null;
    }

    public static void copyWorldToVault(UUID characterId, Path worldDir, UUID playerUuid) {
        Path vaultDir = getVaultDir(characterId);
        String uuidStr = playerUuid.toString();
        try { Files.createDirectories(vaultDir); } catch (IOException ignored) { return; }

        // Per-player directories: only copy files that contain the player UUID in their path.
        List<Path> playerDirs = new ArrayList<>();
        for (String d : new String[]{"playerdata", "advancements", "stats"}) {
            Path p = worldDir.resolve(d);
            if (Files.isDirectory(p)) playerDirs.add(p);
        }
        try (Stream<Path> top = Files.list(worldDir)) {
            top.filter(Files::isDirectory)
                    .filter(d -> {
                        String n = d.getFileName().toString();
                        return !IGNORED_WORLD_DIRS.contains(n)
                                && !n.equals("playerdata")
                                && !n.equals("advancements")
                                && !n.equals("stats")
                                && !n.equals("data");
                    })
                    .forEach(playerDirs::add);
        } catch (IOException ignored) {}

        for (Path dir : playerDirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> !isBackupFile(f))
                        .filter(f -> f.toString().replace("\\", "/").contains(uuidStr))
                        .forEach(file -> {
                            String rel = worldDir.relativize(file).toString().replace("\\", "/");
                            if (!isMatchingShard(rel, playerUuid)) return;
                            String vaultRel = worldShardToVault(worldToVault(rel));
                            Path target = vaultDir.resolve(vaultRel);
                            try {
                                Files.createDirectories(target.getParent());
                                byte[] content = Files.readAllBytes(file);
                                content = rewriteContentToVault(content, playerUuid);
                                Files.write(target, content);
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }

        // data/ directory: save ALL files without UUID filtering.
        // Mods like Sophisticated Backpacks store per-item inventories here keyed by
        // item UUID, not player UUID — so the player UUID filter would miss them entirely.
        Path dataDir = worldDir.resolve("data");
        if (Files.isDirectory(dataDir)) {
            try (Stream<Path> walk = Files.walk(dataDir)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> !isBackupFile(f))
                        .forEach(file -> {
                            String rel = worldDir.relativize(file).toString().replace("\\", "/");
                            Path target = vaultDir.resolve(rel);
                            try {
                                Files.createDirectories(target.getParent());
                                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }

        NBT_CACHE.remove(characterId);
    }

    public static void copyVaultToWorld(UUID characterId, Path worldDir, UUID playerUuid) {
        Path vaultDir = getVaultDir(characterId);
        if (!Files.exists(vaultDir)) return;

        purgeStaleUuidFiles(characterId);

        Map<String, byte[]> vaultFiles = new LinkedHashMap<>();
        List<Path> oldFormat = new ArrayList<>();
        List<Path> newFormat = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(vaultDir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String rel = vaultDir.relativize(f).toString().replace("\\", "/");
                if (rel.contains(SHARD_TOKEN)) newFormat.add(f);
                else oldFormat.add(f);
            });
        } catch (IOException ignored) { return; }

        for (List<Path> batch : List.of(oldFormat, newFormat)) {
            for (Path file : batch) {
                String vaultRel = vaultDir.relativize(file).toString().replace("\\", "/");
                if (vaultRel.startsWith("world_positions")) continue;
                try {
                    byte[] content = Files.readAllBytes(file);
                    content = rewriteContentFromVault(content, playerUuid);
                    for (String worldRel : vaultToWorldAll(vaultRel, playerUuid)) {
                        vaultFiles.put(worldRel, content);
                    }
                } catch (IOException ignored) {}
            }
        }

        for (Map.Entry<String, byte[]> entry : vaultFiles.entrySet()) {
            Path target = worldDir.resolve(entry.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            } catch (IOException ignored) {}
        }
    }

    public static void clearWorldFiles(Path worldDir, UUID playerUuid) {
        List<Path> dirsToCheck = new ArrayList<>();
        for (String d : new String[]{"playerdata", "advancements", "stats"}) {
            Path p = worldDir.resolve(d);
            if (Files.isDirectory(p)) dirsToCheck.add(p);
        }
        try (Stream<Path> top = Files.list(worldDir)) {
            top.filter(Files::isDirectory)
                    .filter(d -> !IGNORED_WORLD_DIRS.contains(d.getFileName().toString()))
                    .forEach(dirsToCheck::add);
        } catch (IOException ignored) {}

        for (Path dir : dirsToCheck) {
            if (!Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> UUID_PAT.matcher(f.toString().replace("\\", "/")).find())
                        .forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    // ── World positions ──────────────────────────────────────────────────────

    public record WorldPos(double x, double y, double z, float yaw, float pitch, String dimension) {}

    public static Optional<WorldPos> getWorldPosition(UUID characterId, String worldId) {
        Path posFile = getVaultDir(characterId).resolve("world_positions.json");
        if (!Files.exists(posFile)) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(posFile)).getAsJsonObject();
            if (!root.has(worldId)) return Optional.empty();
            JsonObject p = root.getAsJsonObject(worldId);
            return Optional.of(new WorldPos(
                    p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble(),
                    p.get("yaw").getAsFloat(), p.get("pitch").getAsFloat(),
                    p.has("dimension") ? p.get("dimension").getAsString() : null
            ));
        } catch (Exception e) { return Optional.empty(); }
    }

    public static Optional<WorldPos> getAnyPositionForWorld(UUID characterId, String hostSaveMatch) {
        Path posFile = getVaultDir(characterId).resolve("world_positions.json");
        if (!Files.exists(posFile)) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(posFile)).getAsJsonObject();
            for (String key : root.keySet()) {
                if (key.startsWith(hostSaveMatch)) {
                    JsonObject p = root.getAsJsonObject(key);
                    return Optional.of(new WorldPos(
                            p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble(),
                            p.get("yaw").getAsFloat(), p.get("pitch").getAsFloat(),
                            p.has("dimension") ? p.get("dimension").getAsString() : null
                    ));
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    public static void saveWorldPosition(UUID characterId, String worldId,
                                         double x, double y, double z, float yaw, float pitch) {
        Path posFile = getVaultDir(characterId).resolve("world_positions.json");
        JsonObject root = new JsonObject();
        if (Files.exists(posFile)) {
            try { root = JsonParser.parseString(Files.readString(posFile)).getAsJsonObject().deepCopy(); }
            catch (Exception ignored) {}
        }
        JsonObject p = new JsonObject();
        p.addProperty("x", x); p.addProperty("y", y); p.addProperty("z", z);
        p.addProperty("yaw", yaw); p.addProperty("pitch", pitch);
        
        // Extract dimension from worldId if possible
        String[] parts = worldId.split("\\|");
        if (parts.length >= 4) {
            p.addProperty("dimension", parts[3]);
        }
        
        root.add(worldId, p);
        try {
            Files.createDirectories(posFile.getParent());
            Files.writeString(posFile, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException ignored) {}
    }

    // ── UI data readers ─────────────────────────────────────────────

    public static NbtCompound readPlayerNbt(UUID characterId) {
        return NBT_CACHE.computeIfAbsent(characterId, id -> {
            Path file = getVaultDir(id).resolve("playerdata/__player__.dat");
            if (!Files.exists(file)) return new NbtCompound();
            try { return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()); }
            catch (IOException e) { return new NbtCompound(); }
        });
    }

    public static String readStatsJson(UUID characterId) {
        Path file = getVaultDir(characterId).resolve("stats/__player__.json");
        if (!Files.exists(file)) return null;
        try { return Files.readString(file); } catch (IOException e) { return null; }
    }

    public static String readAdvancementsJson(UUID characterId) {
        Path file = getVaultDir(characterId).resolve("advancements/__player__.json");
        if (!Files.exists(file)) return null;
        try { return Files.readString(file); } catch (IOException e) { return null; }
    }

    public static void invalidateCache(UUID characterId) { NBT_CACHE.remove(characterId); }
    public static void invalidateAll() { NBT_CACHE.clear(); }

    public static byte[] serializePlayerNbt(net.minecraft.server.network.ServerPlayerEntity player) throws IOException {
        NbtCompound nbt = new NbtCompound();
        player.writeNbt(nbt);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, baos);
        return baos.toByteArray();
    }

    /**
     * Serializes the player NBT from memory and replaces the player's UUID with
     * the {@code __player__} token so it can be stored safely in the vault.
     * Use this instead of {@link #serializePlayerNbt} whenever the result will be
     * written to the vault (tick sync, disconnect sync, etc.).
     */
    public static byte[] serializePlayerNbtTokenized(net.minecraft.server.network.ServerPlayerEntity player) throws IOException {
        byte[] raw = serializePlayerNbt(player);
        return rewriteContentToVault(raw, player.getUuid());
    }

    // ── Sync collectors ─────────────────────────────────────────────

    public static Map<String, byte[]> collectEssentialFiles(UUID characterId, Path worldDir, UUID playerUuid) {
        return collectFromDirs(worldDir, playerUuid, List.of("playerdata", "stats"));
    }

    public static Map<String, byte[]> collectModFiles(UUID characterId, Path worldDir, UUID playerUuid) {
        // UUID-filtered mod directories (per-player files named with the player UUID)
        List<String> modDirs = new ArrayList<>();
        try (Stream<Path> top = Files.list(worldDir)) {
            top.filter(Files::isDirectory)
                    .filter(d -> {
                        String n = d.getFileName().toString();
                        return !IGNORED_WORLD_DIRS.contains(n)
                                && !n.equals("playerdata")
                                && !n.equals("advancements")
                                && !n.equals("stats")
                                && !n.equals("data");
                    })
                    .forEach(d -> modDirs.add(d.getFileName().toString()));
        } catch (IOException ignored) {}
        Map<String, byte[]> result = new java.util.LinkedHashMap<>(collectFromDirs(worldDir, playerUuid, modDirs));

        // data/ directory: collect ALL files without UUID filtering
        Path dataDir = worldDir.resolve("data");
        if (Files.isDirectory(dataDir)) {
            try (Stream<Path> walk = Files.walk(dataDir)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> !isBackupFile(f))
                        .forEach(file -> {
                            String rel = worldDir.relativize(file).toString().replace("\\", "/");
                            try {
                                result.put(rel, Files.readAllBytes(file));
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }
        return result;
    }

    private static Map<String, byte[]> collectFromDirs(Path worldDir, UUID playerUuid, List<String> dirNames) {
        String uuidStr = playerUuid.toString();
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String dirName : dirNames) {
            Path dir = worldDir.resolve(dirName);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> !isBackupFile(f))
                        .filter(f -> f.toString().replace("\\", "/").contains(uuidStr))
                        .forEach(file -> {
                            String rel = worldDir.relativize(file).toString().replace("\\", "/");
                            if (!isMatchingShard(rel, playerUuid)) return;
                            String vaultRel = worldShardToVault(worldToVault(rel));
                            try {
                                byte[] content = Files.readAllBytes(file);
                                content = rewriteContentToVault(content, playerUuid);
                                result.put(vaultRel, content);
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }
        return result;
    }

    public static Map<String, byte[]> collectAdvancementsFile(Path worldDir, UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        Path advFile = worldDir.resolve("advancements").resolve(uuidStr + ".json");
        if (!Files.exists(advFile)) return Collections.emptyMap();
        try {
            String vaultRel = worldToVault("advancements/" + uuidStr + ".json");
            byte[] content = Files.readAllBytes(advFile);
            content = rewriteContentToVault(content, playerUuid);
            return Collections.singletonMap(vaultRel, content);
        } catch (IOException e) { return Collections.emptyMap(); }
    }

    public static void applyVaultSync(UUID characterId, Map<String, byte[]> files) {
        Path vaultDir = getVaultDir(characterId);
        try { Files.createDirectories(vaultDir); } catch (IOException ignored) {}
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = vaultDir.resolve(entry.getKey()).normalize();
            if (!target.startsWith(vaultDir.normalize())) continue;
            if (isBackupFile(target)) continue;
            if (UUID_PAT.matcher(entry.getKey()).find()) continue;
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            } catch (IOException ignored) {}
        }
        invalidateCache(characterId);
    }

    // ── Zip / Unzip ──────────────────────────────────

    public static byte[] zipVault(UUID characterId) throws IOException {
        Path vaultDir = getVaultDir(characterId);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            if (Files.exists(vaultDir)) {
                try (Stream<Path> walk = Files.walk(vaultDir)) {
                    for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                                                          .filter(f -> !isBackupFile(f))::iterator) {
                        String entry = vaultDir.relativize(file).toString().replace("\\", "/");
                        zos.putNextEntry(new ZipEntry(entry));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    public static void unzipToVault(UUID characterId, byte[] zipData) throws IOException {
        unzipToVault(characterId, zipData, false);
    }

    /**
     * Extracts a vault ZIP, optionally preserving server-authoritative files.
     *
     * @param preserveServerFiles if true, files that are managed exclusively
     *   server-side (world_positions.json) are NOT overwritten.
     */
    public static void unzipToVault(UUID characterId, byte[] zipData, boolean preserveServerFiles) throws IOException {
        Path vaultDir = getVaultDir(characterId);
        Files.createDirectories(vaultDir);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                Path target = vaultDir.resolve(name).normalize();
                if (!target.startsWith(vaultDir.normalize())) continue;
                if (isBackupFile(target)) { zis.closeEntry(); continue; }
                if (UUID_PAT.matcher(name).find()) { zis.closeEntry(); continue; }
                // Never overwrite server-authoritative position data with client data
                if (preserveServerFiles && name.equals("world_positions.json")) { zis.closeEntry(); continue; }
                Files.createDirectories(target.getParent());
                Files.write(target, zis.readAllBytes());
                zis.closeEntry();
            }
        }
        invalidateCache(characterId);
    }
}