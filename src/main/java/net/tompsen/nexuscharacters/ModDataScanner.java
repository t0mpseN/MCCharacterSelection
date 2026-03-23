package net.tompsen.nexuscharacters;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public class ModDataScanner {

    // Folders to skip — vanilla or non-player-specific
    private static final Set<String> IGNORED_FOLDERS = Set.of(
            "playerdata",   // handled separately by player.readNbt()
            "data",         // world-level data, not player-specific
            "datapacks",    // world datapacks
            "region",       // chunk data
            "entities",     // entity chunk data
            "poi",          // points of interest
            "DIM1",         // nether chunks
            "DIM-1",        // end chunks
            "dimensions"    // custom dimension chunks
    );

    public static NbtCompound scanPlayerModData(ServerPlayerEntity player, boolean fullScan) {
        NbtCompound modData = new NbtCompound();
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        UUID uuid = player.getUuid();

        // 1. Explicitly scan advancements and stats (Vanilla) - always do this
        scanFolder(worldDir.resolve("advancements"), worldDir, uuid, modData);
        scanFolder(worldDir.resolve("stats"), worldDir, uuid, modData);

        if (fullScan) {
            // 2. Scan other mod folders only during full scan
            try (Stream<Path> dirs = Files.list(worldDir)) {
                dirs.filter(Files::isDirectory)
                        .filter(dir -> !IGNORED_FOLDERS.contains(dir.getFileName().toString()))
                        .filter(dir -> !dir.getFileName().toString().equals("advancements"))
                        .filter(dir -> !dir.getFileName().toString().equals("stats"))
                        .forEach(dir -> scanFolder(dir, worldDir, uuid, modData));
            } catch (IOException e) {
                NexusCharacters.LOGGER.warn("Failed to scan mod data: {}", e.getMessage());
            }
        }

        return modData;
    }

    private static void scanFolder(Path folder, Path worldDir, UUID uuid, NbtCompound modData) {
        if (!Files.exists(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".tmp")) return false;
                        if (name.endsWith(".old")) return false;
                        if (name.endsWith(".dat_old")) return false;
                        if (name.endsWith(".json.old")) return false;
                        return name.contains(uuid.toString());
                    })
                    .forEach(file -> {
                        try {
                            String key = worldDir.relativize(file).toString().replace("\\", "/");
                            byte[] bytes = Files.readAllBytes(file);
                            modData.putByteArray(key, bytes);
                            NexusCharacters.LOGGER.info("Saved mod data: {} ({} bytes)", key, bytes.length);
                        } catch (IOException e) {
                            NexusCharacters.LOGGER.warn("Failed to read mod file: {}", file);
                        }
                    });
        } catch (IOException e) {
            NexusCharacters.LOGGER.warn("Failed to walk folder: {}", folder);
        }
    }

    public static void restorePlayerModData(ServerPlayerEntity player, NbtCompound modData) {
        if (modData.isEmpty()) return;

        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        String currentUuid = player.getUuid().toString();

        for (String key : modData.getKeys()) {
            try {
                byte[] bytes = modData.getByteArray(key);
                String resolvedKey = replaceUuidInPath(key, currentUuid);
                Path target = worldDir.resolve(resolvedKey);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                NexusCharacters.LOGGER.info("Restored mod data: {} ({} bytes) → {}", resolvedKey, bytes.length, target.toAbsolutePath());
            } catch (IOException e) {
                NexusCharacters.LOGGER.warn("Failed to restore mod file: {}", key);
            }
        }
    }

    public static void clearPlayerModData(ServerPlayerEntity player) {
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        String uuidStr = player.getUuid().toString();

        // 1. Clear files in subfolders (Mods + Vanilla folders we usually skip in scanning)
        try (Stream<Path> dirs = Files.list(worldDir)) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> {
                        // Skip folders that definitely don't contain player files
                        String dirName = dir.getFileName().toString();
                        if (dirName.equals("region") || dirName.equals("DIM1") || dirName.equals("DIM-1") || dirName.equals("datapacks")) return;

                        try (Stream<Path> walk = Files.walk(dir)) {
                            walk.filter(Files::isRegularFile)
                                    .filter(file -> file.getFileName().toString().contains(uuidStr))
                                    .forEach(file -> {
                                        try {
                                            Files.delete(file);
                                            NexusCharacters.LOGGER.info("[CharSel] Deleted stale data file: {}", file);
                                        } catch (IOException ignored) {}
                                    });
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}

        // 2. Specifically clear root files if any
        try (Stream<Path> rootFiles = Files.list(worldDir)) {
            rootFiles.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().contains(uuidStr))
                    .forEach(file -> {
                        try {
                            Files.delete(file);
                            NexusCharacters.LOGGER.info("[CharSel] Deleted stale root file: {}", file);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    public static NbtCompound loadPlayerNbtFromWorld(ServerPlayerEntity player) {
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        Path playerFile = worldDir.resolve("playerdata/" + player.getUuid().toString() + ".dat");
        
        if (Files.exists(playerFile)) {
            try {
                return net.minecraft.nbt.NbtIo.readCompressed(playerFile, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
            } catch (IOException e) {
                NexusCharacters.LOGGER.warn("[CharSel] Failed to read world player data: {}", e.getMessage());
            }
        }
        return new NbtCompound();
    }

    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String replaceUuidInPath(String path, String newUuid) {
        return UUID_PATTERN.matcher(path).replaceAll(newUuid);
    }
}