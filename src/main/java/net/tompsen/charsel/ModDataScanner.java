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

    public static NbtCompound scanPlayerModData(ServerPlayerEntity player) {
        NbtCompound modData = new NbtCompound();
        Path worldDir = player.server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        UUID uuid = player.getUuid();

        CharacterSelection.LOGGER.info("Scanning world dir: {}", worldDir);

        try (Stream<Path> dirs = Files.list(worldDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> !IGNORED_FOLDERS.contains(dir.getFileName().toString()))
                    .forEach(dir -> {
                        CharacterSelection.LOGGER.info("Scanning folder: {}", dir.getFileName());
                        scanFolder(dir, worldDir, uuid, modData);
                    });
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
                            CharacterSelection.LOGGER.info("Saved mod data: {} ({} bytes)", key, bytes.length);
                        } catch (IOException e) {
                            CharacterSelection.LOGGER.warn("Failed to read mod file: {}", file);
                        }
                    });
        } catch (IOException e) {
            CharacterSelection.LOGGER.warn("Failed to walk folder: {}", folder);
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
                CharacterSelection.LOGGER.info("Restored mod data: {} ({} bytes) → {}", resolvedKey, bytes.length, target.toAbsolutePath());
            } catch (IOException e) {
                CharacterSelection.LOGGER.warn("Failed to restore mod file: {}", key);
            }
        }
    }

    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String replaceUuidInPath(String path, String newUuid) {
        return UUID_PATTERN.matcher(path).replaceAll(newUuid);
    }
}