package io.usagecore.usagepipeline.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.AuthorizationDeniedException;
import io.usagecore.usagepipeline.application.security.PlatformRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsageIngestionApplicationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final Instant FIXED = Instant.parse("2026-08-12T14:30:05Z");

    @Test
    void ingest_publishesEnvelopeFromJwtTenantOnly() {
        RecordingPublisher publisher = new RecordingPublisher();
        UsageIngestionApplicationService service = new UsageIngestionApplicationService(
                () -> new AuthenticatedPrincipal("acme-developer", Optional.of(TENANT), EnumSet.of(PlatformRole.DEVELOPER)),
                () -> "corr-demo",
                publisher,
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );

        UsageIngestionResult result = service.ingest(
                "datapilot-cloud",
                "scheduled_export",
                1L,
                OCCURRED,
                "export-job-174"
        );

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.correlationId()).isEqualTo("corr-demo");
        assertThat(publisher.events).hasSize(1);
        EventEnvelope<UsageReceivedPayload> event = publisher.events.getFirst();
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.eventType()).isEqualTo(EventTypes.USAGE_RECEIVED);
        assertThat(event.eventVersion()).isEqualTo(EventVersions.V1);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED);
        assertThat(event.publishedAt()).isEqualTo(FIXED);
        assertThat(event.correlationId()).isEqualTo("corr-demo");
        assertThat(event.payload().productKey()).isEqualTo("datapilot-cloud");
        assertThat(event.payload().meterKey()).isEqualTo("scheduled_export");
        assertThat(event.payload().quantity()).isEqualTo(1L);
        assertThat(event.payload().idempotencyKey()).isEqualTo("export-job-174");
        assertThat(publisher.keys.getFirst()).isEqualTo(UsagePartitionKey.of(TENANT, "datapilot-cloud", "scheduled_export"));
    }

    @Test
    void ingest_whenPublisherFails_propagatesPublicationException() {
        UsageIngestionApplicationService service = new UsageIngestionApplicationService(
                () -> new AuthenticatedPrincipal("acme-developer", Optional.of(TENANT), EnumSet.of(PlatformRole.DEVELOPER)),
                () -> "corr-demo",
                (event, key) -> {
                    throw new UsagePublicationException("broker unavailable");
                },
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.ingest(
                "datapilot-cloud",
                "scheduled_export",
                1L,
                OCCURRED,
                "export-job-174"
        )).isInstanceOf(UsagePublicationException.class);
    }

    @Test
    void ingest_withoutTenant_denied() {
        UsageIngestionApplicationService service = new UsageIngestionApplicationService(
                () -> new AuthenticatedPrincipal("platform-admin", Optional.empty(), EnumSet.of(PlatformRole.PLATFORM_ADMIN)),
                () -> "corr",
                (event, key) -> {
                },
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.ingest(
                "datapilot-cloud",
                "scheduled_export",
                1L,
                OCCURRED,
                "export-job-174"
        )).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void unsupportedEventVersion_failsExplicitly() {
        EventEnvelope<UsageReceivedPayload> event = new EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.USAGE_RECEIVED,
                "99",
                OCCURRED,
                TENANT,
                "agg",
                "corr",
                null,
                null,
                FIXED,
                new UsageReceivedPayload("datapilot-cloud", "scheduled_export", 1L, "k", "sub")
        );

        assertThatThrownBy(() -> LoggingUsageReceivedProcessor.validateSupportedContract(event))
                .isInstanceOf(UnsupportedUsageEventException.class)
                .hasMessageContaining("Unsupported eventVersion");
    }

    private static final class RecordingPublisher implements UsageEventPublisher {
        private final List<EventEnvelope<UsageReceivedPayload>> events = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();

        @Override
        public void publish(EventEnvelope<UsageReceivedPayload> event, String partitionKey) {
            events.add(event);
            keys.add(partitionKey);
        }
    }
}
