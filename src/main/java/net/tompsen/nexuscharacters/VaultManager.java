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
 *
 * Vault layout for one character:
 *   vaults/<char-uuid>/
 *     playerdata/__player__.dat
 *     advancements/__player__.json
 *     stats/__player__.json
 *     <mod-dir>/.../__player__.*     (any mod that stores UUID-named files)
 *     world_positions.json           (lightweight {worldId→{x,y,z,yaw,pitch}} map)
 *
 * The token __player__ stands for any Minecraft account UUID.
 * On copy-in (vault → world): __player__ is replaced by the real player UUID.
 * On copy-out (world → vault): the real player UUID is replaced by __player__.
 */
public class VaultManager {

    // ── Constants ────────────────────────────────────────────────────────────

    public static final String PLAYER_TOKEN = "__player__";

    private static final Pattern UUID_PAT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> IGNORED_WORLD_DIRS = Set.of(
            "region", "DIM1", "DIM-1", "entities", "poi", "dimensions", "level.dat",
            "level.dat_old", "session.lock", "data", "icon.png", "resources.zip"
    );

    /** File suffixes that are mod-internal backups and should not be vaulted. */
    private static boolean isBackupFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".old") || name.endsWith(".bak") || name.endsWith(".tmp");
    }

    private static final Path VAULTS_DIR =
            FabricLoader.getInstance().getGameDir().resolve("nexuscharacters/vaults");

    // ── NBT cache (UI-only, cleared when screen closes) ───────────────────

    private static final Map<UUID, NbtCompound> NBT_CACHE = new HashMap<>();

    // ── Path resolution ──────────────────────────────────────────────────────

    public static Path getVaultDir(UUID characterId) {
        return VAULTS_DIR.resolve(characterId.toString());
    }

    /** world-relative path → vault-relative path  (UUID → __player__) */
    public static String worldToVault(String rel) {
        return UUID_PAT.matcher(rel).replaceAll(PLAYER_TOKEN);
    }

    /** vault-relative path → world-relative path  (__player__ → UUID) */
    public static String vaultToWorld(String rel, UUID playerUuid) {
        return rel.replace(PLAYER_TOKEN, playerUuid.toString());
    }

    /**
     * Rewrites the content of a file read from the world dir: replaces all
     * occurrences of playerUuid (as a text string) with __player__.
     * Works for both text (JSON) and binary files (NBT dat) since Cobblemon
     * embeds text UUID strings in both formats.
     */
    private static byte[] rewriteContentToVault(byte[] content, UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        byte[] uuidBytes = uuidStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tokenBytes = PLAYER_TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return replaceAll(content, uuidBytes, tokenBytes);
    }

    /**
     * Rewrites the content of a vault file: replaces all occurrences of
     * __player__ with the current playerUuid text string.
     * Works for both text and binary files.
     */
    private static byte[] rewriteContentFromVault(byte[] content, UUID playerUuid) {
        byte[] tokenBytes = PLAYER_TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] uuidBytes = playerUuid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return replaceAll(content, tokenBytes, uuidBytes);
    }

    /** Replaces all non-overlapping occurrences of {@code from} with {@code to} in {@code src}. */
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
        // write remaining tail
        while (i < src.length) out.write(src[i++]);
        byte[] result = out.toByteArray();
        return result.length == src.length ? src : result; // return original if unchanged
    }

    private static boolean matches(byte[] src, int offset, byte[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (src[offset + i] != pattern[i]) return false;
        }
        return true;
    }

    /**
     * Returns every shard-dir variant of a vault-relative path for the given UUID.
     *
     * Cobblemon (and similar mods) shard per-player files into subdirectories named
     * after 2-char substrings of the player UUID: positions 0-1 and 1-2 of the UUID
     * string without hyphens (e.g. UUID "a289ec68-..." → shards "a2" and "28").
     *
     * The vault uses a canonical "__shard__" token in place of the 2-char shard dir.
     * On restore we write to BOTH shard positions of the current UUID so the mod
     * finds its data regardless of which position it actually reads.
     *
     * For non-sharded paths (no 2-char hex dir segment) returns a single entry.
     */
    public static final String SHARD_TOKEN = "__shard__";

    /** Matches a path containing a 2-char hex shard dir: captures prefix, shard, suffix. */
    private static final java.util.regex.Pattern SHARD_PAT =
            java.util.regex.Pattern.compile("((?:[^/]+/)*)([0-9a-fA-F]{2})(/.+)");

    /**
     * Converts a world-relative sharded path to its canonical vault form,
     * replacing the 2-char shard dir with __shard__.
     * Non-sharded paths are returned unchanged.
     */
    public static String worldShardToVault(String worldRel) {
        java.util.regex.Matcher m = SHARD_PAT.matcher(worldRel);
        if (!m.matches()) return worldRel;
        return m.group(1) + SHARD_TOKEN + m.group(3);
    }

    /**
     * Returns whether a world-relative path's shard dir matches one of
     * the two shard positions for the given UUID.
     * For non-sharded paths, always returns true.
     */
    public static boolean isMatchingShard(String worldRel, UUID playerUuid) {
        java.util.regex.Matcher m = SHARD_PAT.matcher(worldRel);
        if (!m.matches()) return true; // not sharded — always include
        String shardDir = m.group(2).toLowerCase();
        String uuidNoHyphens = playerUuid.toString().replace("-", "").toLowerCase();
        String shard0 = uuidNoHyphens.substring(0, 2);
        String shard1 = uuidNoHyphens.substring(1, 3);
        return shardDir.equals(shard0) || shardDir.equals(shard1);
    }

    public static List<String> vaultToWorldAll(String vaultRel, UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        String uuidNoHyphens = uuidStr.replace("-", "");

        // New format: vault path contains __shard__ token
        if (vaultRel.contains(SHARD_TOKEN)) {
            String withUuid = vaultRel.replace(PLAYER_TOKEN, uuidStr);
            Set<String> seen = new LinkedHashSet<>();
            seen.add(withUuid.replace(SHARD_TOKEN, uuidNoHyphens.substring(0, 2))); // primary shard
            seen.add(withUuid.replace(SHARD_TOKEN, uuidNoHyphens.substring(1, 3))); // secondary shard
            return new ArrayList<>(seen);
        }

        // Old format: vault path has a raw 2-char shard dir (e.g. "a2/__player__")
        // Normalize to __shard__ then expand to both shard positions for current UUID.
        java.util.regex.Matcher m = SHARD_PAT.matcher(vaultRel);
        if (m.matches()) {
            String prefix = m.group(1);
            String suffix = m.group(3).replace(PLAYER_TOKEN, uuidStr);
            Set<String> seen = new LinkedHashSet<>();
            seen.add(prefix + uuidNoHyphens.substring(0, 2) + suffix);
            seen.add(prefix + uuidNoHyphens.substring(1, 3) + suffix);
            return new ArrayList<>(seen);
        }

        // Not a sharded path — simple __player__ replacement
        return List.of(vaultRel.replace(PLAYER_TOKEN, uuidStr));
    }

    /**
     * Removes all files in the vault that still contain a raw UUID in their path
     * (i.e. were saved before the __player__ token was introduced, or leaked in
     * from an old code path). Called before restoring the vault to keep it clean.
     */
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
        try {
            Files.createDirectories(getVaultDir(characterId));
        } catch (IOException e) {
            NexusCharacters.LOGGER.error("[VaultManager] Cannot create vault:", e);
        }
    }

    public static void deleteVault(UUID characterId) {
        Path vaultDir = getVaultDir(characterId);
        if (!Files.exists(vaultDir)) return;
        try (Stream<Path> walk = Files.walk(vaultDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            NexusCharacters.LOGGER.warn("[VaultManager] Failed to delete vault {}: {}", characterId, e.getMessage());
        }
    }

    // ── Copy world → vault ───────────────────────────────────────────────────

    /**
     * Scans worldDir for every file containing playerUuid in its path,
     * then stores it in the vault with the UUID replaced by __player__.
     *
     * Called on singleplayer disconnect (client side) or by the server
     * before streaming files back to the client.
     */
    public static void copyWorldToVault(UUID characterId, Path worldDir, UUID playerUuid) {
        Path vaultDir = getVaultDir(characterId);
        String uuidStr = playerUuid.toString();

        try { Files.createDirectories(vaultDir); }
        catch (IOException e) {
            NexusCharacters.LOGGER.error("[VaultManager] Cannot create vault dir:", e);
            return;
        }

        List<Path> dirsToScan = new ArrayList<>();
        // Always include these standard dirs
        for (String d : new String[]{"playerdata", "advancements", "stats"}) {
            Path p = worldDir.resolve(d);
            if (Files.isDirectory(p)) dirsToScan.add(p);
        }
        // Also scan mod-added directories
        try (Stream<Path> top = Files.list(worldDir)) {
            top.filter(Files::isDirectory)
                    .filter(d -> {
                        String n = d.getFileName().toString();
                        return !IGNORED_WORLD_DIRS.contains(n)
                                && !n.equals("playerdata")
                                && !n.equals("advancements")
                                && !n.equals("stats");
                    })
                    .forEach(dirsToScan::add);
        } catch (IOException e) {
            NexusCharacters.LOGGER.warn("[VaultManager] Failed to list world root: {}", e.getMessage());
        }

        for (Path dir : dirsToScan) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> !isBackupFile(f))
                        .filter(f -> f.toString().replace("\\", "/").contains(uuidStr))
                        .forEach(file -> {
                            String rel = worldDir.relativize(file).toString().replace("\\", "/");
                            // For sharded paths, only collect files from shards that match
                            // the current player UUID. This prevents stale shard data from
                            // other UUID sessions (e.g. singleplayer offline UUID) leaking
                            // into the vault and overwriting the current session's data.
                            if (!isMatchingShard(rel, playerUuid)) return;
                            // Normalize the shard dir to __shard__ for portable vault storage
                            String vaultRel = worldShardToVault(worldToVault(rel));
                            Path target = vaultDir.resolve(vaultRel);
                            try {
                                Files.createDirectories(target.getParent());
                                byte[] content = Files.readAllBytes(file);
                                // Also rewrite UUID in file content so mods that embed UUID
                                // strings (e.g. Cobblemon's "uuid" JSON field) work correctly
                                // when the vault is restored under a different UUID.
                                content = rewriteContentToVault(content, playerUuid);
                                Files.write(target, content);
                            } catch (IOException ex) {
                                NexusCharacters.LOGGER.warn("[VaultManager] Save failed {}: {}", rel, ex.getMessage());
                            }
                        });
            } catch (IOException e) {
                NexusCharacters.LOGGER.warn("[VaultManager] Walk failed {}: {}", dir, e.getMessage());
            }
        }

        NBT_CACHE.remove(characterId);
        NexusCharacters.LOGGER.info("[VaultManager] Saved world → vault for char {}", characterId);
    }

    // ── Copy vault → world ───────────────────────────────────────────────────

    /**
     * Copies all vault files for characterId into worldDir,
     * re-inserting playerUuid into paths.
     *
     * Called on singleplayer world join (client side), or by the server
     * after receiving and unzipping a vault upload from the client.
     */
    public static void copyVaultToWorld(UUID characterId, Path worldDir, UUID playerUuid) {
        Path vaultDir = getVaultDir(characterId);
        if (!Files.exists(vaultDir)) {
            NexusCharacters.LOGGER.info("[VaultManager] No vault for {} — fresh start.", characterId);
            return;
        }

        purgeStaleUuidFiles(characterId);

        // Collect vault files into a world-path → bytes map.
        // Process old-format shard dirs (e.g. "a2/", "ca/") first, then new-format
        // "__shard__" entries last so they overwrite old stale data.
        Map<String, byte[]> vaultFiles = new LinkedHashMap<>();
        List<Path> oldFormat = new ArrayList<>();
        List<Path> newFormat = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(vaultDir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String rel = vaultDir.relativize(f).toString().replace("\\", "/");
                if (rel.contains(SHARD_TOKEN)) newFormat.add(f);
                else oldFormat.add(f);
            });
        } catch (IOException e) {
            NexusCharacters.LOGGER.error("[VaultManager] Failed to walk vault:", e);
            return;
        }

        for (List<Path> batch : List.of(oldFormat, newFormat)) {
            for (Path file : batch) {
                String vaultRel = vaultDir.relativize(file).toString().replace("\\", "/");
                if (vaultRel.startsWith("world_positions")) continue;
                try {
                    byte[] content = Files.readAllBytes(file);
                    // Rewrite __player__ token back to the current UUID in file content.
                    content = rewriteContentFromVault(content, playerUuid);
                    // For each world path this vault entry maps to, record the content.
                    // New-format __shard__ entries (processed second) overwrite old-format.
                    for (String worldRel : vaultToWorldAll(vaultRel, playerUuid)) {
                        vaultFiles.put(worldRel, content);
                    }
                } catch (IOException ex) {
                    NexusCharacters.LOGGER.warn("[VaultManager] Read failed {}: {}", vaultRel, ex.getMessage());
                }
            }
        }

        // Write all files to world dir
        for (Map.Entry<String, byte[]> entry : vaultFiles.entrySet()) {
            Path target = worldDir.resolve(entry.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            } catch (IOException ex) {
                NexusCharacters.LOGGER.warn("[VaultManager] Restore failed {}: {}", entry.getKey(), ex.getMessage());
            }
        }

        NexusCharacters.LOGGER.info("[VaultManager] Restored vault → world for char {}", characterId);
    }

    // ── Clear world files (before restoring, to prevent cross-character bleed)

    /**
     * Removes all UUID-named player files from worldDir, regardless of which UUID
     * they belong to.  Since this mod's character system places exactly one
     * character's data in the world dir at a time, any UUID-named file that does
     * NOT belong to the current player is stale data from a previous session
     * (e.g. singleplayer offline-UUID files left over after an online-UUID join)
     * and must be removed before the new vault is installed.
     *
     * @param worldDir  the world root directory
     * @param playerUuid the current player's UUID (kept for logging only)
     */
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
                        .forEach(f -> {
                            try {
                                Files.delete(f);
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }
        NexusCharacters.LOGGER.info("[VaultManager] clearWorldFiles: removed all UUID-named player files from world dir (player={})", playerUuid);
    }

    // ── World positions ──────────────────────────────────────────────────────

    public static Optional<double[]> getWorldPosition(UUID characterId, String worldId) {
        Path posFile = getVaultDir(characterId).resolve("world_positions.json");
        if (!Files.exists(posFile)) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(posFile)).getAsJsonObject();
            if (!root.has(worldId)) return Optional.empty();
            JsonObject p = root.getAsJsonObject(worldId);
            return Optional.of(new double[]{
                    p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble(),
                    p.get("yaw").getAsFloat(), p.get("pitch").getAsFloat()
            });
        } catch (Exception e) { return Optional.empty(); }
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
        root.add(worldId, p);
        try {
            Files.createDirectories(posFile.getParent());
            Files.writeString(posFile, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException e) {
            NexusCharacters.LOGGER.warn("[VaultManager] Failed to write positions: {}", e.getMessage());
        }
    }

    // ── UI data readers (cached) ─────────────────────────────────────────────

    /**
     * Returns the player's NBT from the vault, or an empty compound.
     * Cached until invalidateCache() is called.
     */
    public static NbtCompound readPlayerNbt(UUID characterId) {
        return NBT_CACHE.computeIfAbsent(characterId, id -> {
            Path file = getVaultDir(id).resolve("playerdata/__player__.dat");
            if (!Files.exists(file)) return new NbtCompound();
            try {
                return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            } catch (IOException e) {
                NexusCharacters.LOGGER.warn("[VaultManager] Cannot read playerdata: {}", e.getMessage());
                return new NbtCompound();
            }
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

    // ── In-memory playerdata serialization ───────────────────────────────────

    /**
     * Serializes the player's current in-memory state to compressed NBT bytes,
     * exactly as Minecraft would write playerdata/<uuid>.dat.
     * Used by the 1-second sync so we capture live state, not stale disk files.
     */
    public static byte[] serializePlayerNbt(net.minecraft.server.network.ServerPlayerEntity player) throws IOException {
        NbtCompound nbt = new NbtCompound();
        player.writeNbt(nbt);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, baos);
        return baos.toByteArray();
    }

    // ── Incremental small-file collect (1-second server→client sync) ────────

    /**
     * Reads every UUID-named file in worldDir that belongs to playerUuid,
     * EXCEPT advancements (too large for per-second sync).
     * Returns a map of vault-relative path → file bytes.
     *
     * Used by the 1-second periodic S2C sync so the client vault always has
     * fresh playerdata, stats, and mod data (e.g. Cobblemon).
     */
    public static Map<String, byte[]> collectSmallFiles(UUID characterId, Path worldDir, UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        Map<String, byte[]> result = new LinkedHashMap<>();

        List<Path> dirsToScan = new ArrayList<>();
        for (String d : new String[]{"playerdata", "stats"}) {
            Path p = worldDir.resolve(d);
            if (Files.isDirectory(p)) dirsToScan.add(p);
        }
        // Mod dirs (e.g. cobblemon) — exclude advancements
        try (Stream<Path> top = Files.list(worldDir)) {
            top.filter(Files::isDirectory)
                    .filter(d -> {
                        String n = d.getFileName().toString();
                        return !IGNORED_WORLD_DIRS.contains(n)
                                && !n.equals("playerdata")
                                && !n.equals("advancements")
                                && !n.equals("stats");
                    })
                    .forEach(dirsToScan::add);
        } catch (IOException e) {
            NexusCharacters.LOGGER.warn("[VaultManager] collectSmallFiles: failed to list world root: {}", e.getMessage());
        }

        for (Path dir : dirsToScan) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> !isBackupFile(f))
                        .filter(f -> f.toString().replace("\\", "/").contains(uuidStr))
                        .forEach(file -> {
                            String rel = worldDir.relativize(file).toString().replace("\\", "/");
                            // Only collect from matching shards (skip stale other-UUID shard dirs)
                            if (!isMatchingShard(rel, playerUuid)) return;
                            // Normalize shard dir to __shard__ for portable vault storage
                            String vaultRel = worldShardToVault(worldToVault(rel));
                            try {
                                byte[] content = Files.readAllBytes(file);
                                // Rewrite UUID in content so the vault stores __player__ tokens
                                content = rewriteContentToVault(content, playerUuid);
                                result.put(vaultRel, content);
                            } catch (IOException ex) {
                                NexusCharacters.LOGGER.warn("[VaultManager] collectSmallFiles: read failed {}: {}", rel, ex.getMessage());
                            }
                        });
            } catch (IOException e) {
                NexusCharacters.LOGGER.warn("[VaultManager] collectSmallFiles: walk failed {}: {}", dir, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Reads the advancements file for playerUuid from worldDir.
     * Returns a single-entry map, or empty if the file doesn't exist.
     */
    public static Map<String, byte[]> collectAdvancementsFile(Path worldDir, UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        Path advFile = worldDir.resolve("advancements").resolve(uuidStr + ".json");
        if (!Files.exists(advFile)) return Collections.emptyMap();
        try {
            String vaultRel = worldToVault("advancements/" + uuidStr + ".json");
            byte[] content = Files.readAllBytes(advFile);
            content = rewriteContentToVault(content, playerUuid);
            return Collections.singletonMap(vaultRel, content);
        } catch (IOException e) {
            NexusCharacters.LOGGER.warn("[VaultManager] collectAdvancementsFile: read failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Writes a set of vault-relative path → bytes into the local vault,
     * replacing __player__ with the current player's UUID for the world.
     * Used client-side when receiving VaultSyncPayload.
     */
    public static void applyVaultSync(UUID characterId, Map<String, byte[]> files) {
        Path vaultDir = getVaultDir(characterId);
        try { Files.createDirectories(vaultDir); } catch (IOException ignored) {}
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = vaultDir.resolve(entry.getKey()).normalize();
            if (!target.startsWith(vaultDir.normalize())) continue; // path-traversal guard
            if (isBackupFile(target)) continue;
            if (UUID_PAT.matcher(entry.getKey()).find()) continue; // skip stale raw-UUID paths
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            } catch (IOException e) {
                NexusCharacters.LOGGER.warn("[VaultManager] applyVaultSync: write failed {}: {}", entry.getKey(), e.getMessage());
            }
        }
        invalidateCache(characterId);
    }

    // ── Zip / Unzip (multiplayer transfer) ──────────────────────────────────

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
        Path vaultDir = getVaultDir(characterId);
        Files.createDirectories(vaultDir);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = vaultDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(vaultDir.normalize())) continue; // path-traversal guard
                if (isBackupFile(target)) { zis.closeEntry(); continue; } // skip .old/.bak files
                // Skip entries that still have a raw UUID in the path (stale shard files)
                if (UUID_PAT.matcher(entry.getName()).find()) { zis.closeEntry(); continue; }
                Files.createDirectories(target.getParent());
                Files.write(target, zis.readAllBytes());
                zis.closeEntry();
            }
        }
        invalidateCache(characterId);
        NexusCharacters.LOGGER.info("[VaultManager] Unzipped received vault for char {}", characterId);
    }
}