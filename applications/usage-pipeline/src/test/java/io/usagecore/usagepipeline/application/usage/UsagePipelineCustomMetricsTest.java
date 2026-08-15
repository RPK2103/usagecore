package io.usagecore.usagepipeline.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodStatus;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodView;
import io.usagecore.usagepipeline.application.observability.UsagePipelineMetrics;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessorTest.InMemoryCommercialUsageExceptionRepository;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessorTest.InMemoryMeterDefinitionLookup;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessorTest.InMemoryProcessedEventRepository;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessorTest.InMemoryUsageAggregateRepository;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessorTest.InMemoryUsageLedgerRepository;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessorTest.InMemoryUsageWindowAggregateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsagePipelineCustomMetricsTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final Instant FIXED = Instant.parse("2026-08-12T14:31:00Z");
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PRODUCT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void processorRecordsAppliedDuplicateAndQuarantined() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsagePipelineMetrics metrics = new UsagePipelineMetrics(registry);
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        IdempotentUsageReceivedProcessor processor = new IdempotentUsageReceivedProcessor(
                inbox,
                new InMemoryUsageLedgerRepository(),
                InMemoryMeterDefinitionLookup.countMeter(),
                new InMemoryUsageAggregateRepository(),
                new InMemoryUsageWindowAggregateRepository(),
                new UsageWindowResolver(),
                (tenantId, productId, occurredAt) -> Optional.empty(),
                new InMemoryCommercialUsageExceptionRepository(),
                Clock.fixed(FIXED, ZoneOffset.UTC),
                metrics
        );

        processor.process(sampleEvent(EVENT_ID));
        processor.process(sampleEvent(EVENT_ID));

        assertThat(registry.find(UsagePipelineMetrics.USAGE_EVENTS_PROCESSED)
                .tag("result", UsagePipelineMetrics.RESULT_APPLIED).counter().count()).isEqualTo(1.0d);
        assertThat(registry.find(UsagePipelineMetrics.USAGE_EVENTS_PROCESSED)
                .tag("result", UsagePipelineMetrics.RESULT_DUPLICATE).counter().count()).isEqualTo(1.0d);
        assertThat(registry.find(UsagePipelineMetrics.AGGREGATE_UPDATES)
                .tag("aggregationType", "COUNT").counter().count()).isEqualTo(1.0d);

        SimpleMeterRegistry quarantineRegistry = new SimpleMeterRegistry();
        UsagePipelineMetrics quarantineMetrics = new UsagePipelineMetrics(quarantineRegistry);
        UUID periodId = UUID.randomUUID();
        IdempotentUsageReceivedProcessor quarantining = new IdempotentUsageReceivedProcessor(
                new InMemoryProcessedEventRepository(),
                new InMemoryUsageLedgerRepository(),
                InMemoryMeterDefinitionLookup.countMeter(),
                new InMemoryUsageAggregateRepository(),
                new InMemoryUsageWindowAggregateRepository(),
                new UsageWindowResolver(),
                (tenantId, productId, occurredAt) -> Optional.of(new CommercialPeriodView(
                        periodId,
                        tenantId,
                        productId,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"),
                        CommercialPeriodStatus.FINALIZED
                )),
                new InMemoryCommercialUsageExceptionRepository(),
                Clock.fixed(FIXED, ZoneOffset.UTC),
                quarantineMetrics
        );
        quarantining.process(sampleEvent(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")));
        assertThat(quarantineRegistry.find(UsagePipelineMetrics.USAGE_EVENTS_PROCESSED)
                .tag("result", UsagePipelineMetrics.RESULT_QUARANTINED).counter().count()).isEqualTo(1.0d);
        assertThat(quarantineRegistry.find(UsagePipelineMetrics.COMMERCIAL_USAGE_EXCEPTIONS)
                .tag("reason", "PERIOD_FINALIZED").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void outboxPublisherRecordsSuccessAndFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsagePipelineMetrics metrics = new UsagePipelineMetrics(registry);
        InMemoryOutbox repo = new InMemoryOutbox();
        repo.insertPending(pendingRow(UUID.randomUUID(), "{\"correlationId\":\"c1\"}"));
        OutboxPublisherApplicationService successPublisher = new OutboxPublisherApplicationService(
                repo,
                (topic, key, envelope, eventId, type, version, correlationId) -> {
                },
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.fixed(FIXED, ZoneOffset.UTC),
                metrics
        );
        assertThat(successPublisher.publishBatch(10)).isEqualTo(1);
        assertThat(registry.find(UsagePipelineMetrics.OUTBOX_PUBLISH)
                .tag("result", UsagePipelineMetrics.RESULT_SUCCESS).counter().count()).isEqualTo(1.0d);

        SimpleMeterRegistry failRegistry = new SimpleMeterRegistry();
        UsagePipelineMetrics failMetrics = new UsagePipelineMetrics(failRegistry);
        InMemoryOutbox failRepo = new InMemoryOutbox();
        failRepo.insertPending(pendingRow(UUID.randomUUID(), "{\"correlationId\":\"c2\"}"));
        OutboxPublisherApplicationService failing = new OutboxPublisherApplicationService(
                failRepo,
                (topic, key, envelope, eventId, type, version, correlationId) -> {
                    throw new UsagePublicationException("broker down");
                },
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.fixed(FIXED, ZoneOffset.UTC),
                failMetrics
        );
        assertThatThrownBy(() -> failing.publishBatch(10)).isInstanceOf(UsagePublicationException.class);
        assertThat(failRegistry.find(UsagePipelineMetrics.OUTBOX_PUBLISH)
                .tag("result", UsagePipelineMetrics.RESULT_FAILURE).counter().count()).isEqualTo(1.0d);
    }

    @Test
    void customMetricTagsStayBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsagePipelineMetrics metrics = new UsagePipelineMetrics(registry);
        metrics.recordOutboxPublish(UsagePipelineMetrics.RESULT_SUCCESS);
        metrics.recordUsageProcessed(UsagePipelineMetrics.RESULT_APPLIED);
        metrics.recordQuotaDecision("ACCEPTED", "WITHIN_QUOTA");
        metrics.recordReconciliationRun(UsagePipelineMetrics.RESULT_MATCH);
        metrics.recordReconciliationMismatch("AGGREGATE_VALUE_MISMATCH");
        metrics.recordAdjustment(UsagePipelineMetrics.RESULT_APPLIED);
        metrics.recordDlq("non_retryable");
        metrics.recordCommercialException("PERIOD_FINALIZED");
        metrics.recordAggregateUpdate("SUM");

        java.util.Set<String> forbidden = java.util.Set.of(
                "tenantId", "eventId", "correlationId", "idempotencyKey", "contractId",
                "principalId", "commercialPeriodId", "reconciliationRunId", "adjustmentId"
        );
        registry.getMeters().forEach(meter -> {
            if (!meter.getId().getName().startsWith("usagecore.")) {
                return;
            }
            meter.getId().getTags().forEach(tag ->
                    assertThat(forbidden).doesNotContain(tag.getKey()));
        });
    }

    @Test
    void metricRecordingFailureDoesNotPropagate() {
        UsagePipelineMetrics metrics = new UsagePipelineMetrics(null);
        metrics.recordOutboxPublish(UsagePipelineMetrics.RESULT_SUCCESS);
        metrics.recordQuotaDecision("REJECTED", "QUOTA_EXHAUSTED");
    }

    private static EventEnvelope<UsageReceivedPayload> sampleEvent(UUID eventId) {
        return new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                OCCURRED,
                TENANT,
                "agg",
                "corr-1",
                null,
                null,
                FIXED,
                new UsageReceivedPayload("datapilot-cloud", "scheduled_export", 3L, "export-job-1", "svc")
        );
    }

    private static OutboxEventRecord pendingRow(UUID eventId, String envelope) {
        return new OutboxEventRecord(
                UUID.randomUUID(),
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                "usagecore.usage.received.v1",
                "key",
                envelope,
                OutboxStatus.PENDING,
                FIXED,
                null
        );
    }

    private static final class InMemoryOutbox implements OutboxEventRepository {
        private final List<OutboxEventRecord> rows = new ArrayList<>();

        @Override
        public void insertPending(OutboxEventRecord record) {
            rows.add(record);
        }

        @Override
        public List<OutboxEventRecord> claimPending(int batchSize) {
            return rows.stream().filter(r -> r.status() == OutboxStatus.PENDING).limit(batchSize).toList();
        }

        @Override
        public void markPublished(UUID id, Instant publishedAt) {
        }

        @Override
        public Optional<OutboxEventRecord> findByEventId(UUID eventId) {
            return rows.stream().filter(r -> r.eventId().equals(eventId)).findFirst();
        }

        @Override
        public long countByStatus(OutboxStatus status) {
            return rows.stream().filter(r -> r.status() == status).count();
        }

        @Override
        public long countAll() {
            return rows.size();
        }

        @Override
        public Optional<Instant> oldestPendingCreatedAt() {
            return Optional.empty();
        }
    }
}
