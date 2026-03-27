package net.tompsen.nexuscharacters;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.*;
import java.util.Set;

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