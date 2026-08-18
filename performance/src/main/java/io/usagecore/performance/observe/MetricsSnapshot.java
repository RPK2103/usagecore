package io.usagecore.performance.observe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pulls a bounded set of existing Actuator/Prometheus names. Does not add labels.
 */
public final class MetricsSnapshot {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final List<String> PREFIXES = List.of(
            "http_server_requests_seconds",
            "hikaricp_connections",
            "jvm_cpu_usage",
            "process_cpu_usage",
            "jvm_memory_used_bytes",
            "jvm_gc_pause_seconds",
            "jvm_threads_live_threads",
            "tomcat_threads",
            "usagecore_outbox_pending",
            "usagecore_outbox_publish_total",
            "usagecore_outbox_oldest_pending_age_seconds",
            "usagecore_usage_events_processed_total",
            "usagecore_quota_decisions_total",
            "usagecore_entitlement_decisions_total",
            "usagecore_usage_dlq_total"
    );

    private MetricsSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:8083/actuator/prometheus";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Prometheus scrape failed: HTTP " + response.statusCode());
        }
        List<String> matched = new ArrayList<>();
        for (String line : response.body().split("\n")) {
            if (line.startsWith("#")) {
                continue;
            }
            for (String prefix : PREFIXES) {
                if (line.startsWith(prefix)) {
                    matched.add(line);
                    break;
                }
            }
        }
        System.out.println("Metrics snapshot from " + url);
        if (matched.isEmpty()) {
            System.out.println("  NO MATCHING SERIES (app may be down or unscraped)");
            return;
        }
        for (String line : matched) {
            System.out.println("  " + line);
        }
        System.out.println("  series=" + matched.size());
        boolean hikari = matched.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("hikaricp"));
        System.out.println("  hikariPresent=" + hikari);
    }
}
