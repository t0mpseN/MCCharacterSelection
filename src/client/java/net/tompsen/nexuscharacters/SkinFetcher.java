package net.tompsen.nexuscharacters;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public class SkinFetcher {

    public interface SkinCallback {
        void onResult(String skinValue, String skinSignature, String error);
    }

    public static void fetchByUsername(String username, SkinCallback callback) {
        Thread thread = new Thread(() -> {
            try {
                // Step 1: Username → UUID
                URL profileUrl = URI.create(
                        "https://api.mojang.com/users/profiles/minecraft/" + username).toURL();
                HttpURLConnection conn = (HttpURLConnection) profileUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code == 404 || code == 204) {
                    callback.onResult(null, null, "Player not found: " + username);
                    return;
                }
                if (code != 200) {
                   callback.onResult(null, null, "Mojang API error: HTTP " + code);
                    return;
                }

                String profileJson = new String(conn.getInputStream().readAllBytes()).trim();
                NexusCharacters.LOGGER.info("[CharSel] Profile response: {}", profileJson);

                String uuid = extractField(profileJson, "id");
                if (uuid == null || uuid.isEmpty()) {
                    callback.onResult(null, null, "Could not parse UUID from: " + profileJson);
                    return;
                }

                // Step 2: UUID → Skin
                URL skinUrl = URI.create(
                        "https://sessionserver.mojang.com/session/minecraft/profile/"
                                + uuid + "?unsigned=false").toURL();
                HttpURLConnection conn2 = (HttpURLConnection) skinUrl.openConnection();
                conn2.setRequestMethod("GET");
                conn2.setConnectTimeout(8000);
                conn2.setReadTimeout(8000);
                conn2.setRequestProperty("Accept", "application/json");

                int code2 = conn2.getResponseCode();
                if (code2 != 200) {
                    callback.onResult(null, null, "Session server error: HTTP " + code2);
                    return;
                }

                String skinJson = new String(conn2.getInputStream().readAllBytes()).trim();
                NexusCharacters.LOGGER.info("[CharSel] Skin response (truncated): {}",
                        skinJson.substring(0, Math.min(200, skinJson.length())));

                // Pega o primeiro bloco de properties
                String value     = extractField(skinJson, "value");
                String signature = extractField(skinJson, "signature");

                if (value == null || value.isEmpty()) {
                    callback.onResult(null, null, "No skin texture found for " + username);
                    return;
                }

                callback.onResult(value, signature, null);

            } catch (Exception e) {
                NexusCharacters.LOGGER.error("[CharSel] SkinFetcher error:", e);
                callback.onResult(null, null, "Network error: " + e.getMessage());
            }
        }, "CharSel-SkinFetcher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Extrai o valor de um campo JSON simples: "key":"value"
     * Trata espaços ao redor do : e do valor.
     */
    private static String extractField(String json, String key) {
        // Tenta com e sem espaços: "key" : "value" ou "key":"value"
        String[] patterns = {
                "\"" + key + "\":\"",
                "\"" + key + "\" : \""
        };
        for (String pattern : patterns) {
            int start = json.indexOf(pattern);
            if (start >= 0) {
                start += pattern.length();
                int end = json.indexOf("\"", start);
                if (end > start) return json.substring(start, end);
            }
        }
        return null;
    }
}