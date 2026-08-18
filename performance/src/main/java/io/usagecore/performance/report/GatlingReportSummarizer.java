package io.usagecore.performance.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads the latest Gatling {@code js/stats.json} and prints a lab report block.
 * Does not invent metrics: missing fields are printed as NOT MEASURED.
 */
public final class GatlingReportSummarizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GatlingReportSummarizer() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "target/gatling");
        Optional<Path> latest = latestStats(root);
        if (latest.isEmpty()) {
            System.out.println("No Gatling stats.json found under " + root.toAbsolutePath());
            return;
        }
        JsonNode stats = MAPPER.readTree(latest.get().toFile());
        JsonNode total = stats.path("stats");
        if (total.isMissingNode() || total.isEmpty()) {
            total = stats;
        }
        System.out.println("Gatling summary");
        System.out.println("  file=" + latest.get().toAbsolutePath());
        System.out.println("  capturedAt=" + Instant.now());
        System.out.println("  requests=" + number(total, "numberOfRequests", "total"));
        System.out.println("  success=" + number(total, "numberOfRequests", "ok"));
        System.out.println("  technicalFailed=" + number(total, "numberOfRequests", "ko"));
        System.out.println("  throughputMeanReqPerSec=" + decimal(total, "meanNumberOfRequestsPerSecond", "total"));
        System.out.println("  p50=" + number(total, "percentiles1", "ok"));
        System.out.println("  p95=" + number(total, "percentiles3", "ok"));
        System.out.println("  p99=" + number(total, "percentiles4", "ok"));
        System.out.println("  max=" + number(total, "maxResponseTime", "ok"));
        System.out.println("  min=" + number(total, "minResponseTime", "ok"));
        System.out.println("  mean=" + number(total, "meanResponseTime", "ok"));
        System.out.println("  note=percentiles assume Gatling defaults p50/p75/p95/p99 unless gatling.conf changed.");
    }

    public static Optional<Path> latestStats(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(path -> path.getFileName().toString().equals("stats.json"))
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()));
        }
    }

    private static String number(JsonNode total, String field, String nested) {
        JsonNode node = total.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return "NOT MEASURED";
        }
        if (node.isNumber()) {
            return node.asText();
        }
        JsonNode child = node.path(nested);
        return child.isMissingNode() || child.isNull() ? "NOT MEASURED" : child.asText();
    }

    private static String decimal(JsonNode total, String field, String nested) {
        return number(total, field, nested);
    }
}
