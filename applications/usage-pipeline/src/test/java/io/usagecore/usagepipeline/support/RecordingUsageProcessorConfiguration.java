package io.usagecore.usagepipeline.support;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessor;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class RecordingUsageProcessorConfiguration {

    @Bean
    RecordingUsageReceivedProcessor recordingUsageReceivedProcessor() {
        return new RecordingUsageReceivedProcessor();
    }

    @Bean
    @Primary
    UsageReceivedProcessor usageReceivedProcessor(RecordingUsageReceivedProcessor recording) {
        return recording;
    }

    public static final class RecordingUsageReceivedProcessor implements UsageReceivedProcessor {

        private final List<EventEnvelope<UsageReceivedPayload>> events = new CopyOnWriteArrayList<>();
        private final List<RuntimeException> failures = new CopyOnWriteArrayList<>();

        @Override
        public void process(EventEnvelope<UsageReceivedPayload> event) {
            try {
                IdempotentUsageReceivedProcessor.validateSupportedContract(event);
                events.add(event);
            } catch (RuntimeException ex) {
                failures.add(ex);
                throw ex;
            }
        }

        public List<EventEnvelope<UsageReceivedPayload>> events() {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }

        public List<RuntimeException> failures() {
            return Collections.unmodifiableList(new ArrayList<>(failures));
        }

        public void clear() {
            events.clear();
            failures.clear();
        }
    }
}
