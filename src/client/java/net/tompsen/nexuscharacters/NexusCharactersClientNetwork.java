package net.tompsen.nexuscharacters;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class NexusCharactersClientNetwork {
    public static void register() {

        // ── Configuration-phase handlers (dedicated server join) ─────────────
        // These fire BEFORE the player entity enters the world, so vault files
        // are in place when Cobblemon and other mods first read their data.

        // Server says "pick a character before entering the world"
        ClientConfigurationNetworking.registerGlobalReceiver(CharacterSelectRequestPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                NexusCharacters.LOGGER.info("[Client] Config: received CharacterSelectRequest — showing picker.");
                ctx.client().setScreen(new NexusCharactersScreen(null, () -> {
                    if (NexusCharacters.selectedCharacter == null) {
                        NexusCharacters.LOGGER.warn("[Client] Config: no character selected — cannot proceed.");
                        return;
                    }
                    NexusCharacters.LOGGER.info("[Client] Config: character selected: {}", NexusCharacters.selectedCharacter.name());
                    // Send the full DTO to the server (config-phase channel)
                    ClientConfigurationNetworking.send(new SelectCharacterPayload(NexusCharacters.selectedCharacter));
                    ctx.client().setScreen(null);
                }));
            });
        });

        // Server says "I have no vault for you, please upload yours" (config phase)
        ClientConfigurationNetworking.registerGlobalReceiver(VaultReceiveReadyPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (NexusCharacters.selectedCharacter != null) {
                    NexusCharacters.LOGGER.info("[Client] Config: VaultReceiveReady — starting upload for {}.", NexusCharacters.selectedCharacter.name());
                    CharacterTransferClientManager.startConfigUpload(NexusCharacters.selectedCharacter.id());
                } else {
                    NexusCharacters.LOGGER.warn("[Client] Config: VaultReceiveReady but no character selected.");
                }
            });
        });

        // ── Play-phase handlers (singleplayer / LAN / post-join vault sync) ──

        // Server sends ModPresent → show character picker (LAN / singleplayer)
        ClientPlayNetworking.registerGlobalReceiver(ModPresentPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                ctx.client().setScreen(new NexusCharactersScreen(null, () -> {
                    if (NexusCharacters.selectedCharacter == null) return;
                    ClientPlayNetworking.send(new SelectCharacterPayload(NexusCharacters.selectedCharacter));
                    ctx.client().setScreen(null);
                }));
            });
        });

        // Server ready to receive vault → start upload (play phase, LAN upload fallback)
        ClientPlayNetworking.registerGlobalReceiver(VaultReceiveReadyPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (NexusCharacters.selectedCharacter != null) {
                    CharacterTransferClientManager.startUpload(NexusCharacters.selectedCharacter.id());
                }
            });
        });

        // Server streams vault back after join (S2C download)
        ClientPlayNetworking.registerGlobalReceiver(VaultChunkS2CPayload.ID, (payload, ctx) -> {
            CharacterTransferClientManager.clientReceiveChunk(payload.index(), payload.total(), payload.data());
        });

        ClientPlayNetworking.registerGlobalReceiver(VaultTransferDoneS2CPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                byte[] zip = CharacterTransferClientManager.clientAssemble();
                try {
                    VaultManager.unzipToVault(payload.characterId(), zip);
                    NexusCharacters.LOGGER.info("[Client] Vault received and stored for char {}.", payload.characterId());
                } catch (Exception e) {
                    NexusCharacters.LOGGER.error("[Client] Failed to store received vault:", e);
                }
            });
        });

        // Incremental vault sync: server pushes small files every ~1 second.
        // Write them straight into the local vault so the client always has
        // up-to-date data even if the server crashes or the player disconnects.
        ClientPlayNetworking.registerGlobalReceiver(VaultSyncPayload.ID, (payload, ctx) -> {
            if (payload.files().isEmpty()) return;
            // Write on the client thread to avoid races with UI reads
            ctx.client().execute(() -> {
                VaultManager.applyVaultSync(payload.characterId(), payload.files());
            });
        });
    }
}
