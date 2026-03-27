# NexusCharacters — "Terraria Architecture" Refactoring Plan

## 0. What This Fixes

| Issue | Root Cause | Fix |
|---|---|---|
| Network crash when player has Cobblemon data | `SelectCharacterPayload` serializes full `CharacterDto` (with megabytes of mod files) into a single NBT packet | `CharacterDto` becomes lightweight metadata; files are transferred via a chunked streamer |
| 20-tick TPS bomb | `saveCurrentCharacter()` calls `ModDataScanner.scanPlayerModData()` (recursive FS walk + file reads) on the server thread every second | Eliminated entirely — Minecraft's own auto-save handles file persistence |
| RAM bloat | All mod files for every character serialized as byte arrays inside `NbtCompound modData` held in RAM | Vault files stay on disk; UI reads them lazily with a per-session cache |

---

## 1. The New Data Flow at a Glance

```
CLIENT                                          SERVER
──────────────────────────────────────────────────────────────────
 .minecraft/nexuscharacters/
   characters.dat  ← lightweight metadata only
   vaults/
     <char-uuid>/
       playerdata/__player__.dat
       advancements/__player__.json
       stats/__player__.json
       cobblemon/.../__player__.*
       world_positions.json         ← per-world last position

 ── SINGLEPLAYER ──
 [JOIN]   VaultManager.copyVaultToWorld()   →   world playerdata dir
 [LEAVE]  VaultManager.copyWorldToVault()   ←   world playerdata dir

 ── MULTIPLAYER ──
 [JOIN]  SelectCharacterPayload(UUID only) ────►
         ◄──── VaultReceiveReadyPayload
         VaultChunkC2SPayload × N ───────────►  CharacterTransferManager
                                                  .assembleAndClear()
         VaultTransferDoneC2SPayload ───────►  unzip → world dir
                                                  applyCharacterData()
 [LEAVE]                              zip ◄──── VaultChunkS2CPayload × N
         VaultTransferDoneS2CPayload ◄────
         VaultManager.unzipToVault()
```

---

## 2. Complete File Inventory

### New files
| File | Side | Purpose |
|---|---|---|
| `VaultManager.java` | Client | The vault: copy in/out, UI file reads, zip/unzip |
| `CharacterTransferManager.java` | Shared | Chunk split/assemble logic |
| `VaultChunkC2SPayload.java` | Shared | C→S: one 30 KB chunk |
| `VaultTransferDoneC2SPayload.java` | Shared | C→S: upload complete |
| `VaultReceiveReadyPayload.java` | Shared | S→C: "start sending" trigger |
| `VaultChunkS2CPayload.java` | Shared | S→C: one 30 KB chunk on disconnect |
| `VaultTransferDoneS2CPayload.java` | Shared | S→C: download complete |

### Heavily rewritten
| File | What changes |
|---|---|
| `CharacterDto.java` | Strips `playerNbt`, `modData`, `worldPositions`; adds `gameMode`, `hardcore` |
| `CharacterDataManager.java` | `loadCharacterToPlayer` splits into `prepareLoad` + `applyCharacterData`; `saveCurrentCharacter` becomes near-empty (no more scan) |
| `ModDataScanner.java` | Reduced to file delete + server-side zip-extract helpers only |
| `NexusCharactersNetwork.java` | Adds chunk-reception and vault-extraction handlers |
| `NexusCharactersClientNetwork.java` | Adds chunk-send trigger and download-assembly handlers |
| `NexusCharacters.java` | Removes 20-tick ServerTickEvents; adds disconnect vault-save |
| `SelectCharacterPayload.java` | Carries UUID only (not full DTO) |
| `SaveCharacterPayload.java` | Eliminated (replaced by VaultChunkS2CPayload pipeline) |

### Minor updates (UI)
| File | What changes |
|---|---|
| `CharacterDto.java` downstream | `c.gameMode` / `c.hardcore` read directly; `c.playerNbt()` calls replaced by `VaultManager.readPlayerNbt(c.id())` |
| `CharacterCardRenderer.java` | `gameMode`/`hardcore` from DTO; `XpLevel` from vault NBT |
| `CharacterUiHelper.java` | `getPlayerStats()` + `getLatestAdvancement()` read vault JSON files |
| `DummyPlayerManager.java` | Reads equipment from vault NBT |
| `NexusCharactersScreen.java` / `CharacterListScreen.java` | `drawRightPanel` inventory/stats from vault |
| `CharacterCreationScreen.java` | Creates vault dir on character creation |
| `CharacterEditScreen.java` | Calls `VaultManager.invalidateCache()` on save |
| `DataFileManager.java` | Simpler `save()`/`read()` (no more heavy NBT); add vault cleanup on delete |

### Removed files
- `SaveCharacterPayload.java`

---

## 3. New / Rewritten Files — Complete Code

---

### 3.1 `CharacterDto.java`

```java
package net.tompsen.nexuscharacters;

import net.minecraft.nbt.NbtCompound;
import java.util.UUID;

/**
 * Lightweight character metadata only. No player NBT, no mod data, no world positions.
 * All heavy data lives in VaultManager.
 */
public record CharacterDto(
        UUID   id,
        String name,
        String skinValue,
        String skinSignature,
        String skinUsername,
        int    gameMode,   // 0=survival, 1=creative, 2=adventure, 3=spectator
        boolean hardcore
) {
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putUuid("id", id);
        nbt.putString("name", name);
        nbt.putString("skinValue",      skinValue      != null ? skinValue      : "");
        nbt.putString("skinSignature",  skinSignature  != null ? skinSignature  : "");
        nbt.putString("skinUsername",   skinUsername   != null ? skinUsername   : "");
        nbt.putInt("gameMode", gameMode);
        nbt.putBoolean("hardcore", hardcore);
        return nbt;
    }

    public static CharacterDto fromNbt(NbtCompound nbt) {
        return new CharacterDto(
                nbt.getUuid("id"),
                nbt.getString("name"),
                nbt.getString("skinValue"),
                nbt.getString("skinSignature"),
                nbt.getString("skinUsername"),
                nbt.getInt("gameMode"),
                nbt.getBoolean("hardcore")
        );
    }

    // ── Migration helper ────────────────────────────────────────────────────
    // Called once by DataFileManager when it finds a legacy record with playerNbt.
    public static CharacterDto fromLegacyNbt(NbtCompound nbt) {
        int gm = 0;
        boolean hc = false;
        if (nbt.contains("playerNbt")) {
            NbtCompound pnbt = nbt.getCompound("playerNbt");
            gm = pnbt.getInt("playerGameType");
            hc = pnbt.getBoolean("hardcore");
        }
        return new CharacterDto(
                nbt.getUuid("id"),
                nbt.getString("name"),
                nbt.getString("skinValue"),
                nbt.getString("skinSignature"),
                nbt.getString("skinUsername"),
                gm, hc
        );
    }
}
```

---

### 3.2 `VaultManager.java`

```java
package net.tompsen.nexuscharacters;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
                    .filter(f -> f.toString().replace("\\", "/").contains(uuidStr))
                    .forEach(file -> {
                        String rel = worldDir.relativize(file).toString().replace("\\", "/");
                        String vaultRel = worldToVault(rel);
                        Path target = vaultDir.resolve(vaultRel);
                        try {
                            Files.createDirectories(target.getParent());
                            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
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

        try (Stream<Path> walk = Files.walk(vaultDir)) {
            walk.filter(Files::isRegularFile)
                .forEach(file -> {
                    String vaultRel = vaultDir.relativize(file).toString().replace("\\", "/");
                    // Skip metadata files that don't belong in the world dir
                    if (vaultRel.startsWith("world_positions")) return;
                    String worldRel = vaultToWorld(vaultRel, playerUuid);
                    Path target = worldDir.resolve(worldRel);
                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ex) {
                        NexusCharacters.LOGGER.warn("[VaultManager] Restore failed {}: {}", vaultRel, ex.getMessage());
                    }
                });
        } catch (IOException e) {
            NexusCharacters.LOGGER.error("[VaultManager] Failed to copy vault → world:", e);
        }

        NexusCharacters.LOGGER.info("[VaultManager] Restored vault → world for char {}", characterId);
    }

    // ── Clear world files (before restoring, to prevent cross-character bleed)

    public static void clearWorldFiles(Path worldDir, UUID playerUuid) {
        String uuidStr = playerUuid.toString();
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
                    .filter(f -> f.toString().replace("\\", "/").contains(uuidStr))
                    .forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
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

    // ── Zip / Unzip (multiplayer transfer) ──────────────────────────────────

    public static byte[] zipVault(UUID characterId) throws IOException {
        Path vaultDir = getVaultDir(characterId);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            if (Files.exists(vaultDir)) {
                try (Stream<Path> walk = Files.walk(vaultDir)) {
                    for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
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
                Files.createDirectories(target.getParent());
                Files.write(target, zis.readAllBytes());
                zis.closeEntry();
            }
        }
        invalidateCache(characterId);
        NexusCharacters.LOGGER.info("[VaultManager] Unzipped received vault for char {}", characterId);
    }
}
```

---

### 3.3 `CharacterTransferManager.java`

```java
package net.tompsen.nexuscharacters;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles chunked vault transfers in both directions.
 *
 *   CLIENT  →  SERVER :  startUpload()
 *   SERVER  →  CLIENT :  startDownloadToClient()
 */
public class CharacterTransferManager {

    public static final int CHUNK_SIZE = 30 * 1024; // 30 KB

    // ── Client → Server upload ───────────────────────────────────────────────

    public static void startUpload(UUID characterId) {
        new Thread(() -> {
            try {
                byte[] zip   = VaultManager.zipVault(characterId);
                int    total = Math.max(1, (zip.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                NexusCharacters.LOGGER.info("[Transfer] Uploading {} bytes ({} chunks).", zip.length, total);

                for (int i = 0; i < total; i++) {
                    int from  = i * CHUNK_SIZE;
                    int to    = Math.min(from + CHUNK_SIZE, zip.length);
                    byte[] chunk = Arrays.copyOfRange(zip, from, to);
                    ClientPlayNetworking.send(new VaultChunkC2SPayload(i, total, chunk));
                    Thread.sleep(30); // gentle back-pressure
                }

                ClientPlayNetworking.send(new VaultTransferDoneC2SPayload(characterId));
                NexusCharacters.LOGGER.info("[Transfer] Upload complete.");
            } catch (Exception e) {
                NexusCharacters.LOGGER.error("[Transfer] Upload failed:", e);
            }
        }, "NexusChars-Upload").start();
    }

    // ── Server-side: assemble uploaded chunks ────────────────────────────────

    // playerUUID → chunk array (indexed by chunk number)
    private static final Map<UUID, byte[][]> uploading = new ConcurrentHashMap<>();

    public static void serverReceiveChunk(UUID playerUuid, int index, int total, byte[] data) {
        uploading.computeIfAbsent(playerUuid, k -> new byte[total][])[index] = data;
    }

    /**
     * Reassemble all received chunks for playerUuid, clear state, return zip bytes.
     */
    public static byte[] serverAssemble(UUID playerUuid) {
        byte[][] chunks = uploading.remove(playerUuid);
        if (chunks == null) return new byte[0];
        int len = 0;
        for (byte[] c : chunks) if (c != null) len += c.length;
        byte[] zip = new byte[len];
        int pos = 0;
        for (byte[] c : chunks) { if (c != null) { System.arraycopy(c, 0, zip, pos, c.length); pos += c.length; } }
        return zip;
    }

    // ── Server → Client download ─────────────────────────────────────────────

    public static void startDownloadToClient(ServerPlayerEntity player, byte[] zipData) {
        new Thread(() -> {
            try {
                int total = Math.max(1, (zipData.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                NexusCharacters.LOGGER.info("[Transfer] Downloading {} bytes ({} chunks) to client.", zipData.length, total);

                for (int i = 0; i < total; i++) {
                    int from  = i * CHUNK_SIZE;
                    int to    = Math.min(from + CHUNK_SIZE, zipData.length);
                    byte[] chunk = Arrays.copyOfRange(zipData, from, to);
                    ServerPlayNetworking.send(player, new VaultChunkS2CPayload(i, total, chunk));
                    Thread.sleep(30);
                }

                ServerPlayNetworking.send(player, new VaultTransferDoneS2CPayload(
                        NexusCharacters.getSelectedCharacter(player) != null
                        ? NexusCharacters.getSelectedCharacter(player).id()
                        : player.getUuid()));
            } catch (Exception e) {
                NexusCharacters.LOGGER.error("[Transfer] Download to client failed:", e);
            }
        }, "NexusChars-Download").start();
    }

    // ── Client-side: assemble downloaded chunks ──────────────────────────────

    private static byte[][] clientChunks;

    public static void clientReceiveChunk(int index, int total, byte[] data) {
        if (clientChunks == null || clientChunks.length != total) clientChunks = new byte[total][];
        clientChunks[index] = data;
    }

    public static byte[] clientAssemble() {
        if (clientChunks == null) return new byte[0];
        int len = 0;
        for (byte[] c : clientChunks) if (c != null) len += c.length;
        byte[] zip = new byte[len];
        int pos = 0;
        for (byte[] c : clientChunks) { if (c != null) { System.arraycopy(c, 0, zip, pos, c.length); pos += c.length; } }
        clientChunks = null;
        return zip;
    }
}
```

---

### 3.4 New Payloads

```java
// ── VaultChunkC2SPayload.java ────────────────────────────────────────────────
package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record VaultChunkC2SPayload(int index, int total, byte[] data) implements CustomPayload {
    public static final Id<VaultChunkC2SPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_chunk_c2s"));
    public static final PacketCodec<PacketByteBuf, VaultChunkC2SPayload> CODEC =
            PacketCodec.of(VaultChunkC2SPayload::write, VaultChunkC2SPayload::new);

    public VaultChunkC2SPayload(PacketByteBuf buf) {
        this(buf.readInt(), buf.readInt(), buf.readByteArray());
    }
    public void write(PacketByteBuf buf) {
        buf.writeInt(index); buf.writeInt(total); buf.writeByteArray(data);
    }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}

// ── VaultTransferDoneC2SPayload.java ─────────────────────────────────────────
package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

public record VaultTransferDoneC2SPayload(UUID characterId) implements CustomPayload {
    public static final Id<VaultTransferDoneC2SPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_done_c2s"));
    public static final PacketCodec<PacketByteBuf, VaultTransferDoneC2SPayload> CODEC =
            PacketCodec.of(VaultTransferDoneC2SPayload::write, VaultTransferDoneC2SPayload::new);

    public VaultTransferDoneC2SPayload(PacketByteBuf buf) { this(buf.readUuid()); }
    public void write(PacketByteBuf buf) { buf.writeUuid(characterId); }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}

// ── VaultReceiveReadyPayload.java ─────────────────────────────────────────────
package net.tompsen.nexuscharacters;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server → Client: "I'm ready, start sending your vault." */
public record VaultReceiveReadyPayload() implements CustomPayload {
    public static final Id<VaultReceiveReadyPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_receive_ready"));
    public static final PacketCodec<net.minecraft.network.PacketByteBuf, VaultReceiveReadyPayload> CODEC =
            PacketCodec.unit(new VaultReceiveReadyPayload());
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}

// ── VaultChunkS2CPayload.java ─────────────────────────────────────────────────
package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record VaultChunkS2CPayload(int index, int total, byte[] data) implements CustomPayload {
    public static final Id<VaultChunkS2CPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_chunk_s2c"));
    public static final PacketCodec<PacketByteBuf, VaultChunkS2CPayload> CODEC =
            PacketCodec.of(VaultChunkS2CPayload::write, VaultChunkS2CPayload::new);

    public VaultChunkS2CPayload(PacketByteBuf buf) {
        this(buf.readInt(), buf.readInt(), buf.readByteArray());
    }
    public void write(PacketByteBuf buf) {
        buf.writeInt(index); buf.writeInt(total); buf.writeByteArray(data);
    }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}

// ── VaultTransferDoneS2CPayload.java ─────────────────────────────────────────
package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

public record VaultTransferDoneS2CPayload(UUID characterId) implements CustomPayload {
    public static final Id<VaultTransferDoneS2CPayload> ID =
            new Id<>(Identifier.of(NexusCharacters.MOD_ID, "vault_done_s2c"));
    public static final PacketCodec<PacketByteBuf, VaultTransferDoneS2CPayload> CODEC =
            PacketCodec.of(VaultTransferDoneS2CPayload::write, VaultTransferDoneS2CPayload::new);

    public VaultTransferDoneS2CPayload(PacketByteBuf buf) { this(buf.readUuid()); }
    public void write(PacketByteBuf buf) { buf.writeUuid(characterId); }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
```

---

### 3.5 `SelectCharacterPayload.java` (simplified)

```java
package net.tompsen.nexuscharacters;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

/** Client → Server: "I chose character X." Carries UUID only — no heavy data. */
public record SelectCharacterPayload(UUID characterId) implements CustomPayload {
    public static final Id<SelectCharacterPayload> ID =
            new Id<>(Identifier.of("nexuscharacters", "select_character"));
    public static final PacketCodec<PacketByteBuf, SelectCharacterPayload> CODEC =
            PacketCodec.of(SelectCharacterPayload::write, SelectCharacterPayload::new);

    public SelectCharacterPayload(PacketByteBuf buf) { this(buf.readUuid()); }
    public void write(PacketByteBuf buf) { buf.writeUuid(characterId); }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
```

---

### 3.6 `NexusCharactersNetwork.java` (server-side)

```java
package net.tompsen.nexuscharacters;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.UUID;

public class NexusCharactersNetwork {

    public static void register() {
        // ── C2S ──────────────────────────────────────────────────────────────
        PayloadTypeRegistry.playC2S().register(SelectCharacterPayload.ID,   SelectCharacterPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(VaultChunkC2SPayload.ID,     VaultChunkC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(VaultTransferDoneC2SPayload.ID, VaultTransferDoneC2SPayload.CODEC);

        // ── S2C ──────────────────────────────────────────────────────────────
        PayloadTypeRegistry.playS2C().register(ModPresentPayload.ID,           PacketCodec.unit(new ModPresentPayload()));
        PayloadTypeRegistry.playS2C().register(SkinReloadPayload.ID,           SkinReloadPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VaultReceiveReadyPayload.ID,    VaultReceiveReadyPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VaultChunkS2CPayload.ID,        VaultChunkS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VaultTransferDoneS2CPayload.ID, VaultTransferDoneS2CPayload.CODEC);

        // ── Handlers ─────────────────────────────────────────────────────────

        // 1. Client chose a character → store selection, request vault upload
        ServerPlayNetworking.registerGlobalReceiver(SelectCharacterPayload.ID, (payload, ctx) -> {
            ctx.server().execute(() -> {
                ServerPlayerEntity player = ctx.player();
                NexusCharacters.DATA_FILE_MANAGER.findById(payload.characterId()).ifPresent(dto -> {
                    NexusCharacters.setSelectedCharacter(player, dto);
                    NexusCharacters.LOGGER.info("[Server] Character selected: {} for {}",
                            dto.name(), player.getName().getString());
                });

                if (ctx.server().isDedicated()) {
                    // Multiplayer: ask client to upload its vault
                    if (ServerPlayNetworking.canSend(player.networkHandler, VaultReceiveReadyPayload.ID)) {
                        ServerPlayNetworking.send(player, new VaultReceiveReadyPayload());
                    }
                } else {
                    // Singleplayer / LAN host: vault was already copied to world dir
                    // by the client-side join handler before this packet arrived.
                    CharacterDataManager.applyCharacterData(player);
                }
            });
        });

        // 2. Receive one vault chunk from client
        ServerPlayNetworking.registerGlobalReceiver(VaultChunkC2SPayload.ID, (payload, ctx) -> {
            CharacterTransferManager.serverReceiveChunk(
                    ctx.player().getUuid(), payload.index(), payload.total(), payload.data());
        });

        // 3. Client signals upload complete → assemble, write to world, spawn player
        ServerPlayNetworking.registerGlobalReceiver(VaultTransferDoneC2SPayload.ID, (payload, ctx) -> {
            ctx.server().execute(() -> {
                ServerPlayerEntity player = ctx.player();
                UUID playerUuid = player.getUuid();
                byte[] zip = CharacterTransferManager.serverAssemble(playerUuid);

                Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();

                try {
                    // Clear stale files first, then extract the uploaded vault
                    VaultManager.clearWorldFiles(worldDir, playerUuid);

                    // Unzip directly into world dir, substituting __player__ → real UUID
                    // We reuse VaultManager.copyVaultToWorld by first unzipping to a temp vault slot
                    UUID tempId = UUID.randomUUID();
                    VaultManager.unzipToVault(tempId, zip);
                    VaultManager.copyVaultToWorld(tempId, worldDir, playerUuid);
                    VaultManager.deleteVault(tempId);

                    NexusCharacters.LOGGER.info("[Server] Vault installed for {}.", player.getName().getString());
                } catch (Exception e) {
                    NexusCharacters.LOGGER.error("[Server] Failed to install vault:", e);
                }

                CharacterDataManager.applyCharacterData(player);
            });
        });
    }
}
```

---

### 3.7 `CharacterDataManager.java` (rewritten)

```java
package net.tompsen.nexuscharacters;

import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.tompsen.nexuscharacters.mixin.PlayerManagerAccessor;

import java.nio.file.Path;
import java.util.*;

public class CharacterDataManager {

    /**
     * Full entry-point for singleplayer and LAN.
     * The vault files are already in the world dir (copied by NexusCharactersClient).
     * All we need to do is: refresh trackers, read NBT, apply it, and position the player.
     */
    public static void applyCharacterData(ServerPlayerEntity player) {
        CharacterDto character = NexusCharacters.getSelectedCharacter(player);
        if (character == null) {
            NexusCharacters.LOGGER.warn("[Nexus] applyCharacterData: no character for {}",
                    player.getName().getString());
            return;
        }

        String worldId = getWorldId(player);
        NexusCharacters.LOGGER.info("[Nexus] Applying character {} for {} in {}",
                character.name(), player.getName().getString(), worldId);

        // 1. Force tracker recreation so they pick up the just-restored vault files
        refreshTrackers(player);

        // 2. Read vanilla player NBT that Minecraft loaded from the world dir
        //    (it was put there by NexusCharactersClient or by VaultTransferDoneC2SPayload handler)
        NbtCompound playerNbt = readPlayerNbtFromWorld(player);

        player.getInventory().clear();
        player.clearStatusEffects();

        if (!playerNbt.isEmpty()) {
            playerNbt.remove("Pos");
            playerNbt.remove("Rotation");
            playerNbt.remove("Dimension");
            playerNbt.remove("SpawnDimension");
            playerNbt.remove("RootVehicle");

            UUID uuid = player.getUuid();
            player.readNbt(playerNbt);
            player.setUuid(uuid);
        } else {
            // Fresh character — clean slate
            player.experienceLevel = 0;
            player.experienceProgress = 0f;
            player.totalExperience = 0;
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);
        }

        // 3. Always enforce the authoritative game mode from the lightweight DTO
        player.changeGameMode(GameMode.byId(character.gameMode()));

        // 4. Position
        Path vaultDir = VaultManager.getVaultDir(character.id());
        Optional<double[]> pos = VaultManager.getWorldPosition(character.id(), worldId);
        if (pos.isPresent()) {
            double[] c = pos.get();
            player.teleport(player.getServerWorld(), c[0], c[1], c[2], Set.of(), (float)c[3], (float)c[4]);
        } else {
            BlockPos spawn = player.getServerWorld().getSpawnPos();
            player.teleport(player.getServerWorld(), spawn.getX(), spawn.getY() + 1, spawn.getZ(), Set.of(), 0f, 0f);
        }

        // 5. Skin
        applySkin(player, character);

        player.sendAbilitiesUpdate();
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
        NexusCharacters.LOGGER.info("[Nexus] applyCharacterData complete for {}.", character.name());
    }

    /**
     * Called on disconnect. Saves position to vault and initiates file transfer back
     * to client (multiplayer) OR relies on NexusCharactersClient to copy files (singleplayer).
     */
    public static void saveCurrentCharacter(ServerPlayerEntity player) {
        CharacterDto current = NexusCharacters.getSelectedCharacter(player);
        if (current == null) return;

        // Save last known position to the vault's world_positions.json
        // For singleplayer: vault is on the same machine; we write directly.
        // For multiplayer: we write to the vault on the server, then stream it back.
        String worldId = getWorldId(player);
        VaultManager.saveWorldPosition(current.id(), worldId,
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch());

        if (player.server.isDedicated()) {
            // ── Multiplayer: stream vault back to client ─────────────────────
            // First ensure vanilla Minecraft has written the player files to disk
            player.getAdvancementTracker().save();
            player.getStatHandler().save();

            Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            try {
                // Package current world files into a zip
                UUID tempId = UUID.randomUUID();
                VaultManager.copyWorldToVault(tempId, worldDir, player.getUuid());
                // Also include the world_positions.json we just wrote
                java.nio.file.Files.copy(
                        VaultManager.getVaultDir(current.id()).resolve("world_positions.json"),
                        VaultManager.getVaultDir(tempId).resolve("world_positions.json"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                byte[] zip = VaultManager.zipVault(tempId);
                VaultManager.deleteVault(tempId);

                CharacterTransferManager.startDownloadToClient(player, zip);
            } catch (Exception e) {
                NexusCharacters.LOGGER.error("[Nexus] Failed to stream vault to client:", e);
            }
        }
        // For singleplayer: NexusCharactersClient.DISCONNECT listener handles
        // the copyWorldToVault call after the server writes vanilla player data.
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static NbtCompound readPlayerNbtFromWorld(ServerPlayerEntity player) {
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        Path playerFile = worldDir.resolve("playerdata/" + player.getUuid() + ".dat");
        if (java.nio.file.Files.exists(playerFile)) {
            try {
                return net.minecraft.nbt.NbtIo.readCompressed(playerFile,
                        net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
            } catch (Exception e) {
                NexusCharacters.LOGGER.warn("[Nexus] Could not read player NBT from world: {}", e.getMessage());
            }
        }
        return new NbtCompound();
    }

    private static void refreshTrackers(ServerPlayerEntity player) {
        PlayerManager manager = player.server.getPlayerManager();
        PlayerManagerAccessor acc = (PlayerManagerAccessor) manager;
        NexusPlayerDuck duck = (NexusPlayerDuck) player;
        UUID uuid = player.getUuid();

        acc.getAdvancementTrackers().remove(uuid);
        acc.getStatHandlers().remove(uuid);

        var adv = manager.getAdvancementTracker(player);
        var stat = manager.createStatHandler(player);
        duck.nexus$setAdvancementTracker(adv);
        duck.nexus$setStatHandler(stat);
        if (adv != null) adv.sendUpdate(player);
    }

    private static void applySkin(ServerPlayerEntity player, CharacterDto character) {
        String value = character.skinValue();
        String sig   = character.skinSignature();
        if (value != null && !value.isEmpty()) {
            player.getGameProfile().getProperties().removeAll("textures");
            player.getGameProfile().getProperties().put("textures", new Property("textures", value, sig));
            broadcastSkinUpdate(player);
            if (ServerPlayNetworking.canSend(player.networkHandler, SkinReloadPayload.ID)) {
                ServerPlayNetworking.send(player, new SkinReloadPayload(value, sig != null ? sig : ""));
            }
        }
    }

    private static void broadcastSkinUpdate(ServerPlayerEntity player) {
        MinecraftServer server = player.server;
        PlayerManager pm = server.getPlayerManager();
        pm.sendToAll(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
        pm.sendToAll(new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.of(player)));
        for (ServerPlayerEntity other : pm.getPlayerList()) {
            if (other == player) continue;
            other.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
            other.networkHandler.sendPacket(new EntitySpawnS2CPacket(player, 0, player.getBlockPos()));
        }
        player.networkHandler.sendPacket(
                new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.of(player)));
    }

    public static String getWorldId(ServerPlayerEntity player) {
        String save    = player.server.getSaveProperties().getLevelName();
        String host    = player.server.isDedicated()
                ? player.server.getServerIp() + ":" + player.server.getServerPort()
                : "integrated";
        long seed = player.getServerWorld().getSeed();
        return host + "|" + save + "|" + seed;
    }
}
```

---

### 3.8 `NexusCharacters.java` (main initializer — key changes)

The **20-tick bomb is removed entirely**. The disconnect handler now calls `saveCurrentCharacter()`.

```java
// REMOVE the entire ServerTickEvents.END_SERVER_TICK block:
//   ServerTickEvents.END_SERVER_TICK.register(server -> { ... })
// and
//   ServerWorldEvents.UNLOAD.register(...)

// KEEP the disconnect handler, but simplify it:
ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
    server.execute(() -> {
        CharacterDataManager.saveCurrentCharacter(handler.player);
        NexusCharacters.clearSelectedCharacter(handler.player);
        NexusCharacters.playerJoinTick.remove(handler.player.getUuid());
        if (!server.isDedicated()) {
            NexusCharacters.selectedCharacter = null;
        }
    });
});

// The Hardcore death handler stays the same — just remove the deleteCharacter call
// from DATA_FILE_MANAGER (it deletes the vault instead):
net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, dmg) -> {
    if (entity instanceof ServerPlayerEntity player) {
        CharacterDto ch = getSelectedCharacter(player);
        if (ch != null && ch.hardcore()) {
            deadHardcorePlayers.add(player.getUuid());
            DATA_FILE_MANAGER.deleteCharacter(ch.id());  // also deletes vault — see DataFileManager
            clearSelectedCharacter(player);
        }
    }
});
```

---

### 3.9 `NexusCharactersClient.java` (client initializer — key changes)

```java
@Override
public void onInitializeClient() {
    NexusCharactersClientNetwork.register();

    // Skin reload handler (unchanged)
    ClientPlayNetworking.registerGlobalReceiver(SkinReloadPayload.ID, (payload, ctx) -> { /* unchanged */ });

    ClientLifecycleEvents.CLIENT_STARTED.register(client -> DummyWorldManager.initAtStartup());

    // ── JOIN: for singleplayer, copy vault → world dir BEFORE the server reads player files ──
    // Note: in integrated server, the server starts after the world is selected but
    // before player data is read. We hook ClientPlayConnectionEvents.JOIN which fires
    // after the connection is established but we need to act even earlier.
    // The correct hook is WorldEntryMixin / CreateWorldScreenMixin which intercept
    // BEFORE the world loads. The selected character is already set by NexusCharactersScreen.
    // So we add the copy call at the point the character is confirmed:

    // → This logic moves into NexusCharactersScreen.mouseClicked() and
    //   CreateWorldScreenMixin.onCreateLevel(), right after selectedCharacter is set
    //   and BEFORE the world join proceeds. See Section 4.2.

    // ── DISCONNECT: copy world → vault for singleplayer ──────────────────────
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
        if (client.getServer() != null && NexusCharacters.selectedCharacter != null) {
            // Integrated server: files are in the saves/<name> folder
            UUID characterId = NexusCharacters.selectedCharacter.id();
            UUID playerUuid  = client.getSession().getUuidOrNull();
            if (playerUuid != null) {
                Path worldDir = client.getServer()
                        .getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                        .toAbsolutePath().normalize();
                // Run on a background thread — server already wrote files by now
                new Thread(() ->
                    VaultManager.copyWorldToVault(characterId, worldDir, playerUuid),
                    "NexusChars-VaultSave"
                ).start();
            }
        }
        NexusCharacters.selectedCharacter = null;
    });
}
```

**Where to insert the vault-copy-in call (singleplayer join):**

In `NexusCharactersScreen.mouseClicked()`, after the character is selected and `onConfirm.run()` is about to fire world loading:

```java
// After: NexusCharacters.selectedCharacter = chDto;
// Before: onConfirm.run();
if (client.getServer() == null) {
    // The server hasn't started yet; the copy will happen too late here.
    // Instead, hook it in WorldEntryMixin / CreateWorldScreenMixin (see below).
}
// For new world: CreateWorldScreenMixin fires invokeCreateLevel() → the copy happens
// in PlayerManagerMixin.prepareCharacterData() on the server side (singleplayer server
// and client share the same JVM, so client-side vault path is accessible).
```

Actually the cleanest place: **`PlayerManagerMixin.prepareCharacterData()`** already exists and is called server-side just before player data is read. In integrated server the JVM is shared, so you can call `VaultManager.copyVaultToWorld()` there:

```java
// In PlayerManagerMixin.prepareCharacterData() — REPLACE the modData restore block with:
private void prepareCharacterData(ServerPlayerEntity player) {
    Map<UUID, PlayerAdvancementTracker> trackers = ((PlayerManagerAccessor)(Object)this).getAdvancementTrackers();
    if (trackers.containsKey(player.getUuid())) return;

    UUID charId = NexusCharacters.selectedCharacter != null
            ? NexusCharacters.selectedCharacter.id()
            : NexusCharacters.DATA_FILE_MANAGER.getLastUsed(player.getUuid());
    if (charId == null) return;

    NexusCharacters.DATA_FILE_MANAGER.findById(charId).ifPresent(character -> {
        NexusCharacters.setSelectedCharacter(player, character);
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();

        if (player.server.isDedicated()) {
            // Multiplayer: vault transfer happens via packets (VaultReceiveReadyPayload flow).
            // Nothing to do here.
            return;
        }

        // Singleplayer / integrated: copy vault files into world dir right now,
        // before Minecraft's PlayerManager reads player.dat, advancements, stats.
        VaultManager.clearWorldFiles(worldDir, player.getUuid());
        VaultManager.copyVaultToWorld(character.id(), worldDir, player.getUuid());
        NexusCharacters.LOGGER.info("[Nexus] Vault installed for singleplayer join: {}", character.name());
    });
}
```

---

### 3.10 `NexusCharactersClientNetwork.java`

```java
package net.tompsen.nexuscharacters;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class NexusCharactersClientNetwork {
    public static void register() {

        // ── Server sends ModPresent → show character picker ──────────────────
        ClientPlayNetworking.registerGlobalReceiver(ModPresentPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                ctx.client().setScreen(new NexusCharactersScreen(null, () -> {
                    if (NexusCharacters.selectedCharacter == null) return;
                    // Now sends only the UUID
                    ClientPlayNetworking.send(
                            new SelectCharacterPayload(NexusCharacters.selectedCharacter.id()));
                    ctx.client().setScreen(null);
                }));
            });
        });

        // ── Server ready to receive vault → start upload ─────────────────────
        ClientPlayNetworking.registerGlobalReceiver(VaultReceiveReadyPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (NexusCharacters.selectedCharacter != null) {
                    CharacterTransferManager.startUpload(NexusCharacters.selectedCharacter.id());
                }
            });
        });

        // ── Server streams vault back on disconnect ───────────────────────────
        ClientPlayNetworking.registerGlobalReceiver(VaultChunkS2CPayload.ID, (payload, ctx) -> {
            CharacterTransferManager.clientReceiveChunk(payload.index(), payload.total(), payload.data());
        });

        ClientPlayNetworking.registerGlobalReceiver(VaultTransferDoneS2CPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                byte[] zip = CharacterTransferManager.clientAssemble();
                try {
                    VaultManager.unzipToVault(payload.characterId(), zip);
                    NexusCharacters.LOGGER.info("[Client] Vault received and stored for char {}.",
                            payload.characterId());
                } catch (Exception e) {
                    NexusCharacters.LOGGER.error("[Client] Failed to store received vault:", e);
                }
            });
        });
    }
}
```

---

### 3.11 `DataFileManager.java` (simplified)

The `save()` / `read()` methods are nearly identical but now the NBT is much smaller (no modData blob). The main addition is vault cleanup on delete:

```java
public void deleteCharacter(UUID id) {
    characterList.removeIf(c -> c.id().equals(id));
    save();
    // Also delete the vault
    VaultManager.deleteVault(id);
}
```

**Migration in `init()`** — detect legacy format and convert:

```java
public void init() {
    if (Files.exists(DATA_FILE_PATH)) {
        characterList = read();
    } else {
        generate();
    }
}

public List<CharacterDto> read() {
    try {
        NbtCompound root = NbtIo.readCompressed(DATA_FILE_PATH, NbtSizeTracker.ofUnlimitedBytes());
        NbtList list = root.getList("characters", NbtElement.COMPOUND_TYPE);
        return list.stream().map(tag -> {
            NbtCompound nbt = (NbtCompound) tag;
            // Detect legacy record by presence of "playerNbt" key
            if (nbt.contains("playerNbt")) {
                NexusCharacters.LOGGER.info("[DataFileManager] Migrating legacy character: {}",
                        nbt.getString("name"));
                return CharacterDto.fromLegacyNbt(nbt);
                // Note: playerNbt and modData are silently dropped.
                // The vault for this character will be empty (fresh start).
                // A separate migration utility can be offered to extract data
                // from the legacy NBT into the vault if desired.
            }
            return CharacterDto.fromNbt(nbt);
        }).collect(Collectors.toCollection(ArrayList::new));
    } catch (IOException e) {
        e.printStackTrace();
        return new ArrayList<>();
    }
}
```

---

### 3.12 `ModDataScanner.java` (simplified)

Remove everything except `clearPlayerModData()` (still needed on the server to wipe stale files before restoring from vault). All the serialization methods (`scanPlayerModData`, `restorePlayerModData`, `mergeModData`, etc.) are deleted.

```java
package net.tompsen.nexuscharacters;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reduced to a single utility: clearing a player's world-dir files.
 * All scanning/serialization logic has been removed (it now lives in VaultManager).
 */
public class ModDataScanner {

    private static final Set<String> IGNORED = Set.of(
            "region", "DIM1", "DIM-1", "entities", "poi", "dimensions");

    /** Delete every file in the world dir that contains the player's UUID. */
    public static void clearPlayerModData(ServerPlayerEntity player) {
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        VaultManager.clearWorldFiles(worldDir, player.getUuid());
    }
}
```

---

## 4. UI Changes (Minor)

### 4.1 `CharacterDto` access pattern

Every old read of `c.playerNbt()` becomes a vault read:

```java
// OLD
NbtCompound nbt = c.playerNbt();
int gameMode = nbt.getInt("playerGameType");
boolean hardcore = nbt.getBoolean("hardcore");
int xpLevel = nbt.getInt("XpLevel");

// NEW
int gameMode  = c.gameMode();       // directly on DTO
boolean hardcore = c.hardcore();    // directly on DTO
NbtCompound vaultNbt = VaultManager.readPlayerNbt(c.id()); // cached disk read
int xpLevel = vaultNbt.getInt("XpLevel");
```

---

### 4.2 `CharacterCardRenderer.java`

```java
// CHANGE these three lines:
int gameMode    = c.gameMode();
boolean isHardcore = c.hardcore();
int level = VaultManager.readPlayerNbt(c.id()).getInt("XpLevel");

// Everything else stays identical.
```

---

### 4.3 `CharacterUiHelper.java` — `getPlayerStats()`

```java
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
```

---

### 4.4 `CharacterUiHelper.java` — `getLatestAdvancement()`

```java
public static AdvancementInfo getLatestAdvancement(CharacterDto c) {
    String advJson = VaultManager.readAdvancementsJson(c.id());
    if (advJson == null) return null;

    String latestId = null;
    String latestTime = "";

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
                        latestTime = time; latestId = id;
                    }
                }
            }
        }
    } catch (Exception e) { return null; }

    if (latestId == null) return null;

    // Resolve display — identical fallback logic to the old version, unchanged.
    // (keep the existing network handler / language lookup fallback code)
    // ...
}
```

The fallback resolution logic (language key lookup, item icon from criteria key) is **identical** to the current implementation. Only the data source changes from `c.modData()` byte-array iteration to a single `VaultManager.readAdvancementsJson()` call.

---

### 4.5 `NexusCharactersScreen.java` / `CharacterListScreen.java` — `drawRightPanel`

```java
// OLD:
NbtCompound nbt = c.playerNbt();

// NEW:
NbtCompound nbt = VaultManager.readPlayerNbt(c.id());

// Then use nbt.contains("Inventory") etc. exactly as before.
// The inventory slot parsing and rendering code is unchanged.
```

Both screens should also call `VaultManager.invalidateAll()` in their `close()` method to free the per-session cache:

```java
@Override
public void close() {
    DummyPlayerManager.clearCache();
    VaultManager.invalidateAll();   // ← add this
    client.setScreen(parent);
}
```

---

### 4.6 `DummyPlayerManager.java` — equipment loading

```java
// In getDummyPlayer(), REPLACE:
NbtCompound playerNbt = character.playerNbt();

// WITH:
NbtCompound playerNbt = VaultManager.readPlayerNbt(character.id());
```

Everything else in `getDummyPlayer()` (slot parsing, `equipStack()` calls) stays identical.

---

### 4.7 `CharacterCreationScreen.java`

In `createCharacter()`, after `NexusCharacters.DATA_FILE_MANAGER.addCharacter(...)`:

```java
// Create the vault directory for this new character
VaultManager.createVault(newCharacter.id());
```

---

### 4.8 `CharacterEditScreen.java`

In `save()`, after `NexusCharacters.DATA_FILE_MANAGER.updateCharacter(updated)`:

```java
// Invalidate cached NBT so the UI picks up any skin changes next render
VaultManager.invalidateCache(updated.id());
DummyPlayerManager.invalidateDummies();
```

---

## 5. Payload Registry Summary

In `NexusCharactersNetwork.register()`, register all new payloads (see §3.6). No new C2S/S2C registrations are needed on the client side — `NexusCharactersClientNetwork.register()` only uses `ClientPlayNetworking.registerGlobalReceiver()`.

The old `SaveCharacterPayload` is fully removed. Delete `SaveCharacterPayload.java` and remove its registry line.

---

## 6. Migration Notes

1. **Existing `characters.dat`** — handled automatically by `DataFileManager.read()` via `CharacterDto.fromLegacyNbt()`. The `playerNbt` and `modData` blobs are silently dropped; vaults start empty. Characters keep their names, skins, game modes.

2. **Legacy character progress** — if you want to preserve existing progress for a player who has already used the mod, a one-time migration utility can be written that reads the old `characters.dat`, finds the `modData` byte arrays, and writes them into the vault using `VaultManager.worldToVault()` path mapping. This is optional and can be done out of band.

3. **`world_positions.json`** — the per-world position map replaces the old `worldPositions` NbtCompound. It is human-readable JSON and persists in the vault.

4. **`last_used.dat`** — unchanged, still lives at `nexuscharacters/last_used.dat`.

---

## 7. What You Can Delete

| File | Reason |
|---|---|
| `SaveCharacterPayload.java` | Replaced by VaultChunkS2CPayload pipeline |
| All heavy methods in `ModDataScanner.java` | `scanPlayerModData`, `restorePlayerModData`, `mergeModData`, `mergeJson`, `mergeNbt`, `mergeBytes`, `loadPlayerNbtFromWorld` — all gone |
| `modData` / `playerNbt` / `worldPositions` fields in `CharacterDto` | Stripped |
| 20-tick `ServerTickEvents` block in `NexusCharacters.java` | Gone |
| `ServerWorldEvents.UNLOAD` block | Gone |
| `updateAdvancementDisplayCache` + `_nexuscharacters:adv_display_cache` cache in `CharacterDataManager` | Gone — vault files are the source of truth |
