package io.usagecore.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.usagecore.events.usage.UsageReceivedPayload;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void roundTripsUsageReceivedEnvelope() throws Exception {
        Instant occurredAt = Instant.parse("2026-08-12T14:30:00Z");
        Instant publishedAt = Instant.parse("2026-08-12T14:30:01Z");
        EventEnvelope<UsageReceivedPayload> original = new EventEnvelope<>(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                occurredAt,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "11111111-1111-1111-1111-111111111111:datapilot-cloud:scheduled_export",
                "corr-1",
                null,
                null,
                publishedAt,
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "scheduled_export",
                        1L,
                        "export-job-174",
                        "acme-developer"
                )
        );

        String json = objectMapper.writeValueAsString(original);
        EventEnvelope<UsageReceivedPayload> restored = objectMapper.readValue(
                json,
                new TypeReference<>() {
                }
        );

        assertThat(restored).isEqualTo(original);
        assertThat(restored.eventType()).isEqualTo(EventTypes.USAGE_RECEIVED);
        assertThat(restored.eventVersion()).isEqualTo(EventVersions.V1);
        assertThat(restored.occurredAt()).isEqualTo(occurredAt);
        assertThat(restored.payload().idempotencyKey()).isEqualTo("export-job-174");
    }
}
