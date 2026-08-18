package io.usagecore.usagepipeline.resilience;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.adapters.outbound.persistence.JdbcUsageLedgerRepository;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessor;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRecord;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRepository;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-only seams around the real transactional processor and JDBC ledger.
 * Failures still run inside the existing listener/transaction boundaries.
 */
@TestConfiguration
class ConsumerCrashWindowTestConfiguration {

    static final class Gate {
        final AtomicInteger failBeforeLedgerRemaining = new AtomicInteger(0);
        final AtomicInteger failAfterProcessRemaining = new AtomicInteger(0);
        volatile CountDownLatch beforeLedgerHit = new CountDownLatch(1);
        volatile CountDownLatch releaseBeforeLedgerFailure = new CountDownLatch(0);

        void reset() {
            failBeforeLedgerRemaining.set(0);
            failAfterProcessRemaining.set(0);
            beforeLedgerHit = new CountDownLatch(1);
            releaseBeforeLedgerFailure = new CountDownLatch(0);
        }

        void armFailBeforeLedgerOnce() {
            beforeLedgerHit = new CountDownLatch(1);
            releaseBeforeLedgerFailure = new CountDownLatch(1);
            failBeforeLedgerRemaining.set(1);
        }
    }

    @Bean
    Gate consumerCrashGate() {
        return new Gate();
    }

    @Bean
    @Primary
    UsageLedgerRepository gatedLedger(JdbcUsageLedgerRepository jdbc, Gate gate) {
        return new UsageLedgerRepository() {
            @Override
            public void insert(UsageLedgerRecord record) {
                if (gate.failBeforeLedgerRemaining.get() > 0) {
                    gate.beforeLedgerHit.countDown();
                    awaitQuietly(gate.releaseBeforeLedgerFailure);
                    if (gate.failBeforeLedgerRemaining.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
                        throw new IllegalStateException("test: failure before ledger insert / DB commit");
                    }
                }
                jdbc.insert(record);
            }

            @Override
            public Optional<UsageLedgerRecord> findByEventId(UUID eventId) {
                return jdbc.findByEventId(eventId);
            }

            @Override
            public long countByEventId(UUID eventId) {
                return jdbc.countByEventId(eventId);
            }

            @Override
            public long countAll() {
                return jdbc.countAll();
            }
        };
    }

    @Bean
    @Primary
    UsageReceivedProcessor gatedProcessor(IdempotentUsageReceivedProcessor real, Gate gate) {
        return (EventEnvelope<UsageReceivedPayload> event) -> {
            real.process(event);
            if (gate.failAfterProcessRemaining.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
                throw new IllegalStateException("test: failure after DB commit before listener return");
            }
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test to release ledger failure");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for test to release ledger failure", ex);
        }
    }
}
