package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.support.RecordingUsageProcessorConfiguration.RecordingUsageReceivedProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxPublisherIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RecordingUsageReceivedProcessor recordingProcessor;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        recordingProcessor.clear();
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
    }

    @Test
    void successfulPublication_reachesKafka_marksPublished_keepsEventIdAndPartitionKey() throws Exception {
        String eventId = submit("export-job-publish");
        String expectedKey = UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export");

        OutboxEventRecord pending = outboxEventRepository.findByEventId(UUID.fromString(eventId)).orElseThrow();
        assertThat(pending.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.partitionKey()).isEqualTo(expectedKey);

        int published = outboxPublisher.publishBatch(10);
        assertThat(published).isEqualTo(1);

        OutboxEventRecord done = outboxEventRepository.findByEventId(UUID.fromString(eventId)).orElseThrow();
        assertThat(done.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(done.publishedAt()).isNotNull();
        assertThat(done.eventId().toString()).isEqualTo(eventId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(recordingProcessor.events()).isNotEmpty()
        );
        assertThat(recordingProcessor.events().getFirst().eventId().toString()).isEqualTo(eventId);
        assertThat(recordingProcessor.events().getFirst().aggregateId()).isEqualTo(expectedKey);

        JsonNode envelope = objectMapper.readTree(done.serializedEnvelope());
        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId);
    }

    @Test
    void retryingPendingOutbox_reusesSameEventId() {
        String eventId = submit("export-job-retry-id");
        OutboxEventRecord before = outboxEventRepository.findByEventId(UUID.fromString(eventId)).orElseThrow();
        String storedEnvelope = before.serializedEnvelope();

        int published = outboxPublisher.publishBatch(10);
        assertThat(published).isEqualTo(1);

        OutboxEventRecord after = outboxEventRepository.findByEventId(UUID.fromString(eventId)).orElseThrow();
        assertThat(after.serializedEnvelope()).isEqualTo(storedEnvelope);
        assertThat(after.eventId().toString()).isEqualTo(eventId);
    }

    @Test
    void concurrentPublisherWorkers_doNotNormallyClaimSamePendingRow() throws Exception {
        for (int i = 0; i < 8; i++) {
            submit("export-job-claim-" + i);
        }
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(8);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<List<UUID>>> futures = new ArrayList<>();

        for (int w = 0; w < 2; w++) {
            futures.add(pool.submit(() -> tx.execute(status -> {
                List<OutboxEventRecord> claimed = outboxEventRepository.claimPending(4);
                ready.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return claimed.stream().map(OutboxEventRecord::id).toList();
            })));
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        Set<UUID> allClaimed = new HashSet<>();
        int total = 0;
        for (Future<List<UUID>> future : futures) {
            List<UUID> ids = future.get(30, TimeUnit.SECONDS);
            total += ids.size();
            for (UUID id : ids) {
                assertThat(allClaimed.add(id)).as("duplicate claim of outbox id %s", id).isTrue();
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(total).isEqualTo(8);
    }

    private String submit(String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", Instant.parse("2026-08-12T14:30:00Z").toString());
        body.put("idempotencyKey", idempotencyKey);

        return givenBearer(developerToken(ACME_TENANT))
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .extract()
                .path("eventId");
    }
}
