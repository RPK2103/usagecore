package io.usagecore.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.usagecore.performance.gatling.EntitlementCheckSimulation;
import io.usagecore.performance.gatling.IngestionBurstSimulation;
import io.usagecore.performance.gatling.UsageConsumeSimulation;
import io.usagecore.performance.gatling.UsageEventsSimulation;
import io.usagecore.performance.report.GatlingReportSummarizer;
import io.usagecore.performance.verify.QuotaCorrectnessRules;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Structural smoke for the performance harness. Does not start Gatling, Docker, or applications
 * and is not a latency gate.
 */
class PerformanceHarnessSmokeTest {

    @Test
    void loadProfilesParse() {
        assertEquals(LoadProfile.BASELINE, LoadProfile.from("baseline"));
        assertEquals(LoadProfile.WARMUP, LoadProfile.from("WARMUP"));
        assertEquals(LoadProfile.CONTENTION, LoadProfile.from("contention"));
    }

    @Test
    void defaultSettingsAreLocal() {
        assertTrue(PerformanceSettings.entitlementBaseUrl().contains("8082"));
        assertTrue(PerformanceSettings.usageBaseUrl().contains("8083"));
        assertEquals(PerformanceSettings.ACME_PLACEHOLDER_TENANT_ID, PerformanceSettings.tenantId());
        assertEquals("datapilot-cloud", PerformanceSettings.productKey());
        assertFalse(PerformanceSettings.runId().isBlank());
    }

    @Test
    void simulationClassesAreLoadable() {
        assertEquals("EntitlementCheckSimulation", EntitlementCheckSimulation.class.getSimpleName());
        assertEquals("UsageEventsSimulation", UsageEventsSimulation.class.getSimpleName());
        assertEquals("UsageConsumeSimulation", UsageConsumeSimulation.class.getSimpleName());
        assertEquals("IngestionBurstSimulation", IngestionBurstSimulation.class.getSimpleName());
    }

    @Test
    void missingGatlingStatsAreReportedNotInvented() throws Exception {
        assertTrue(GatlingReportSummarizer.latestStats(Path.of("target", "missing-gatling-dir")).isEmpty());
    }

    @Test
    void quotaRulesRejectOverLimitAndContributionMismatch() {
        QuotaCorrectnessRules.assertMeterState("scheduled_export", 120, 120, 1_000_000);
        IllegalStateException over = assertThrows(
                IllegalStateException.class,
                () -> QuotaCorrectnessRules.assertMeterState("quota_contention", 5001, 5001, 5000)
        );
        assertTrue(over.getMessage().contains("exceeded configured_limit"));
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> QuotaCorrectnessRules.assertMeterState("quota_contention", 2709, 120, 5000)
        );
        assertTrue(mismatch.getMessage().contains("must equal quota_state.consumed_quantity"));
    }
}
