package net.tompsen.nexuscharacters;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.Arrays;
import java.util.UUID;

public class CharacterTransferClientManager {

    public static final int CHUNK_SIZE = CharacterTransferManager.CHUNK_SIZE;

    // ── Client → Server upload (play phase, LAN/singleplayer) ───────────────

    public static void startUpload(UUID characterId) {
        new Thread(() -> {
            try {
                byte[] zip   = VaultManager.zipVault(characterId);
                int    total = Math.max(1, (zip.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                NexusCharacters.LOGGER.info("[Transfer] Play upload {} bytes ({} chunks).", zip.length, total);

                for (int i = 0; i < total; i++) {
                    int from  = i * CHUNK_SIZE;
                    int to    = Math.min(from + CHUNK_SIZE, zip.length);
                    byte[] chunk = Arrays.copyOfRange(zip, from, to);
                    ClientPlayNetworking.send(new VaultChunkC2SPayload(i, total, chunk));
                    Thread.sleep(30);
                }

                ClientPlayNetworking.send(new VaultTransferDoneC2SPayload(characterId));
                NexusCharacters.LOGGER.info("[Transfer] Play upload complete.");
            } catch (Exception e) {
                NexusCharacters.LOGGER.error("[Transfer] Play upload failed:", e);
            }
        }, "NexusChars-Upload").start();
    }

    // ── Client → Server upload (configuration phase, dedicated server) ───────

    public static void startConfigUpload(UUID characterId) {
        new Thread(() -> {
            try {
                byte[] zip   = VaultManager.zipVault(characterId);
                int    total = Math.max(1, (zip.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                NexusCharacters.LOGGER.info("[Transfer] Config upload {} bytes ({} chunks).", zip.length, total);

                for (int i = 0; i < total; i++) {
                    int from  = i * CHUNK_SIZE;
                    int to    = Math.min(from + CHUNK_SIZE, zip.length);
                    byte[] chunk = Arrays.copyOfRange(zip, from, to);
                    ClientConfigurationNetworking.send(new VaultChunkC2SPayload(i, total, chunk));
                    Thread.sleep(30);
                }

                ClientConfigurationNetworking.send(new VaultTransferDoneC2SPayload(characterId));
                NexusCharacters.LOGGER.info("[Transfer] Config upload complete.");
            } catch (Exception e) {
                NexusCharacters.LOGGER.error("[Transfer] Config upload failed:", e);
            }
        }, "NexusChars-ConfigUpload").start();
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