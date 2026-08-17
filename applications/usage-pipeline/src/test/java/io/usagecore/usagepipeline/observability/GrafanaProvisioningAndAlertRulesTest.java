package io.usagecore.usagepipeline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GrafanaProvisioningAndAlertRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> FORBIDDEN = Set.of(
            "tenantId",
            "eventId",
            "correlationId",
            "idempotencyKey",
            "contractId",
            "commercialPeriodId",
            "reconciliationRunId",
            "adjustmentId",
            "principalId",
            "meterKey"
    );

    private static final Set<String> REQUIRED_ALERTS = Set.of(
            "UsageCoreWorkloadDown",
            "HighHttpServerErrorRate",
            "OutboxDeliveryDelayed",
            "OutboxPublishFailures",
            "UsageDlqDetected",
            "ReconciliationMismatchDetected",
            "ReconciliationFailureDetected",
            "CommercialExceptionsAccumulating",
            "DatabaseConnectionPressure"
    );

    private static final Set<String> REQUIRED_DASHBOARD_UIDS = Set.of(
            "usagecore-platform-overview",
            "usagecore-usage-delivery",
            "usagecore-reconciliation-correctness"
    );

    @Test
    void prometheusDatasourceIsProvisioned() throws IOException {
        String yaml = Files.readString(repoRoot().resolve(
                "infrastructure/observability/grafana/provisioning/datasources/datasources.yml"));
        assertThat(yaml).contains("type: prometheus");
        assertThat(yaml).contains("uid: prometheus");
        assertThat(yaml).contains("url: http://prometheus:9090");
        assertThat(yaml).doesNotContain("Bearer");
        assertThat(yaml).doesNotContain("eyJ");
    }

    @Test
    void dashboardsAreProvisionedAndParse() throws IOException {
        Path dashboards = repoRoot().resolve("infrastructure/observability/grafana/dashboards");
        String provider = Files.readString(repoRoot().resolve(
                "infrastructure/observability/grafana/provisioning/dashboards/dashboards.yml"));
        assertThat(provider).contains("path: /var/lib/grafana/dashboards");

        List<String> uids = new ArrayList<>();
        try (Stream<Path> files = Files.list(dashboards)) {
            List<Path> jsonFiles = files.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            assertThat(jsonFiles).isNotEmpty();
            for (Path file : jsonFiles) {
                JsonNode root = MAPPER.readTree(Files.readString(file));
                assertThat(root.path("uid").asText()).isNotBlank();
                assertThat(root.path("title").asText()).startsWith("UsageCore");
                uids.add(root.path("uid").asText());
                collectExprs(root).forEach(this::assertQuerySemantics);
                String blob = Files.readString(file);
                for (String label : FORBIDDEN) {
                    assertThat(blob).as("dashboard %s must not contain %s", file.getFileName(), label)
                            .doesNotContain(label);
                }
            }
        }
        assertThat(uids).containsAll(REQUIRED_DASHBOARD_UIDS);
    }

    @Test
    void alertRulesHaveRunbooksBoundedLabelsAndRequiredNames() throws IOException {
        Path alertsPath = repoRoot().resolve("infrastructure/observability/prometheus/rules/alerts.yml");
        Path recordingPath = repoRoot().resolve("infrastructure/observability/prometheus/rules/recording.yml");
        String alerts = Files.readString(alertsPath);
        String recording = Files.readString(recordingPath);
        assertThat(recording).contains("usagecore:http:server_error_ratio");
        assertThat(recording).contains("histogram_quantile");

        for (String label : FORBIDDEN) {
            assertThat(alerts).doesNotContain(label);
            assertThat(recording).doesNotContain(label);
        }

        Matcher names = Pattern.compile("(?m)^\\s+- alert: (\\S+)").matcher(alerts);
        List<String> found = new ArrayList<>();
        while (names.find()) {
            found.add(names.group(1));
        }
        assertThat(found).containsAll(REQUIRED_ALERTS);
        assertThat(found).hasSizeLessThanOrEqualTo(12);

        Matcher runbooks = Pattern.compile("runbook_url: (\\S+)").matcher(alerts);
        int mapped = 0;
        while (runbooks.find()) {
            mapped++;
            Path runbook = repoRoot().resolve(runbooks.group(1));
            assertThat(runbook).as("runbook %s", runbooks.group(1)).exists();
        }
        assertThat(mapped).isEqualTo(found.size());
    }

    @Test
    void composeMountsGrafanaAndPrometheusRules() throws IOException {
        String compose = Files.readString(repoRoot().resolve("infrastructure/docker/docker-compose.yml"));
        assertThat(compose).contains("usagecore-grafana");
        assertThat(compose).contains("../observability/grafana/provisioning");
        assertThat(compose).contains("../observability/prometheus/rules");
        String prometheus = Files.readString(
                repoRoot().resolve("infrastructure/observability/prometheus/prometheus.yml"));
        assertThat(prometheus).contains("rule_files:");
        assertThat(prometheus).contains("/etc/prometheus/rules/*.yml");
    }

    private void assertQuerySemantics(String expr) {
        String compact = expr.replaceAll("\\s+", " ");
        if (compact.contains("_total") || compact.contains("_count")) {
            assertThat(compact)
                    .as("counter query must use rate() or increase(): %s", compact)
                    .containsAnyOf("rate(", "increase(");
        }
        assertThat(compact).as("do not fake p95 from sum/count: %s", compact)
                .doesNotContain("_sum /")
                .doesNotContain("_sum/");
        if (compact.contains("0.95")) {
            assertThat(compact).contains("histogram_quantile");
        }
    }

    private static List<String> collectExprs(JsonNode node) {
        List<String> exprs = new ArrayList<>();
        collectExprs(node, exprs);
        return exprs;
    }

    private static void collectExprs(JsonNode node, List<String> exprs) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            if (node.has("expr")) {
                exprs.add(node.get("expr").asText());
            }
            node.fields().forEachRemaining(e -> collectExprs(e.getValue(), exprs));
        } else if (node.isArray()) {
            node.forEach(child -> collectExprs(child, exprs));
        }
    }

    static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.exists(current.resolve("infrastructure/observability/prometheus/prometheus.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate UsageCore repository root from " + System.getProperty("user.dir"));
    }
}
