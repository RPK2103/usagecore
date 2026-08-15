package io.usagecore.usagepipeline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StructuredLoggingLocalProfileTest {

    @Test
    void localProfileEnablesLogstashJsonLayout() throws Exception {
        Path yaml = Path.of("src/main/resources/application-local.yml");
        if (!Files.exists(yaml)) {
            yaml = Path.of("applications/usage-pipeline/src/main/resources/application-local.yml");
        }
        String content = Files.readString(yaml);
        assertThat(content).contains("structured:");
        assertThat(content).contains("console: logstash");
        assertThat(content).contains("USAGECORE_OTLP_ENABLED");
        assertThat(content).contains("USAGECORE_OTLP_ENDPOINT");
    }
}
