package net.tompsen.charsel;

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
            "playerdata", "advancements", "stats", "data",
            "datapacks", "region", "entities", "poi",
            "DIM1", "DIM-1", "dimensions"
    );

    public static NbtCompound scanPlayerModData(ServerPlayerEntity player) {
        NbtCompound modData = new NbtCompound();
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        UUID uuid = player.getUuid();

        try (Stream<Path> dirs = Files.list(worldDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> !IGNORED_FOLDERS.contains(dir.getFileName().toString()))
                    .forEach(dir -> scanFolder(dir, worldDir, uuid, modData));
        } catch (IOException e) {
            CharacterSelection.LOGGER.warn("Failed to scan mod data: {}", e.getMessage());
        }

        return modData;
    }

    private static void scanFolder(Path folder, Path worldDir, UUID uuid, NbtCompound modData) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        // Skip temp files and .old backups — Cobblemon manages these itself
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
                            CharacterSelection.LOGGER.info("Saved mod data: {}", key);
                        } catch (IOException e) {
                            CharacterSelection.LOGGER.warn("Failed to read mod file: {}", file);
                        }
                    });
        } catch (IOException e) {
            CharacterSelection.LOGGER.warn("Failed to walk folder: {}", folder);
        }
    }

    public static void restorePlayerModData(ServerPlayerEntity player, NbtCompound modData) {
        if (modData == null || modData.isEmpty()) return;
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();

        for (String key : modData.getKeys()) {
            byte[] bytes = modData.getByteArray(key);
            Path target = worldDir.resolve(key);
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                CharacterSelection.LOGGER.info("Restored mod data: {}", key);
            } catch (IOException e) {
                CharacterSelection.LOGGER.warn("Failed to restore mod file: {}", key);
            }
        }
    }

    public static void debugWorldStructure(ServerPlayerEntity player) {
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        UUID uuid = player.getUuid();
        String uuidStr = uuid.toString();
        String uuidNoDashes = uuidStr.replace("-", "");

        CharacterSelection.LOGGER.info("=== World dir: {}", worldDir);

        try (Stream<Path> all = Files.walk(worldDir)) {
            all.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (name.contains(uuidStr) || name.contains(uuidNoDashes)) {
                    CharacterSelection.LOGGER.info("FOUND player file: {}",
                            worldDir.relativize(file));
                }
            });
        } catch (IOException e) {
            CharacterSelection.LOGGER.warn("Debug scan failed: {}", e.getMessage());
        }
    }
}