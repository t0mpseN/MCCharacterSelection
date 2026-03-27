package net.tompsen.nexuscharacters;

import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles chunked vault transfers in both directions (Server-side parts).
 */
public class CharacterTransferManager {

    public static final int CHUNK_SIZE = 30 * 1024; // 30 KB

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
        CharacterDto character = NexusCharacters.getSelectedCharacter(player);
        UUID characterId = character != null ? character.id() : player.getUuid();
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

                ServerPlayNetworking.send(player, new VaultTransferDoneS2CPayload(characterId));
            } catch (Exception e) {
                NexusCharacters.LOGGER.error("[Transfer] Download to client failed:", e);
            }
        }, "NexusChars-Download").start();
    }
}