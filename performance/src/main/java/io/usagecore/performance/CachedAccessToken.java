package io.usagecore.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Fetches one Keycloak access token and reuses it for the entire JVM/run.
 * Per-request token acquisition is intentionally not part of measured traffic.
 */
public final class CachedAccessToken {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile String token;
    private static volatile Instant expiresAt = Instant.EPOCH;

    private CachedAccessToken() {
    }

    public static synchronized String developerToken() {
        return PerformanceSettings.staticToken().orElseGet(CachedAccessToken::fetchIfNeeded);
    }

    private static String fetchIfNeeded() {
        if (token != null && Instant.now().plusSeconds(60).isBefore(expiresAt)) {
            return token;
        }
        try {
            String body = "grant_type=password"
                    + "&client_id=" + enc(PerformanceSettings.keycloakClientId())
                    + "&username=" + enc(PerformanceSettings.keycloakUsername())
                    + "&password=" + enc(PerformanceSettings.keycloakPassword());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PerformanceSettings.keycloakTokenUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Keycloak token request failed: HTTP " + response.statusCode() + " " + response.body()
                );
            }
            JsonNode json = MAPPER.readTree(response.body());
            token = Objects.requireNonNull(json.path("access_token").asText(null), "access_token missing");
            int expiresIn = json.path("expires_in").asInt(300);
            expiresAt = Instant.now().plusSeconds(expiresIn);
            return token;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to obtain a cached Keycloak token. Start Compose Keycloak, or set usagecore.perf.token.",
                    ex
            );
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
