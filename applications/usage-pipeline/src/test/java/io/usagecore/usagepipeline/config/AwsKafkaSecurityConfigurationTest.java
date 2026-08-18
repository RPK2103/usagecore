package io.usagecore.usagepipeline.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AwsKafkaSecurityConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaPropertiesConfiguration.class);

    @Test
    void localApplicationYamlDoesNotEnableMskScram() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).doesNotContain("SASL_SSL");
        assertThat(yaml).doesNotContain("SCRAM-SHA-512");
        assertThat(yaml).doesNotContain("USAGECORE_KAFKA_SASL_JAAS_CONFIG");
        assertThat(yaml).doesNotContain("password=");
    }

    @Test
    void awsProfileMapsScramTlsAndRequiresInjectedJaas() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application-aws.yml"));

        assertThat(yaml).contains("security.protocol: SASL_SSL");
        assertThat(yaml).contains("sasl.mechanism: SCRAM-SHA-512");
        assertThat(yaml).contains("${USAGECORE_KAFKA_SASL_JAAS_CONFIG}");
        assertThat(yaml).doesNotContain("password=");
        assertThat(yaml).doesNotContain("username=");
    }

    @Test
    void defaultKafkaPropertiesRemainPlaintextCompatible() {
        runner.withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .run(context -> {
                    KafkaProperties kafka = context.getBean(KafkaProperties.class);
                    Map<String, String> extra = kafka.getProperties();
                    assertThat(extra.get("security.protocol")).isNull();
                    assertThat(extra.get("sasl.mechanism")).isNull();
                    assertThat(extra.get("sasl.jaas.config")).isNull();
                    assertThat(kafka.getBootstrapServers()).containsExactly("localhost:9092");
                });
    }

    @Test
    void awsStyleEnvironmentBindsSaslSslScramWithoutEmbeddingSecretInSource() {
        String jaas = "org.apache.kafka.common.security.scram.ScramLoginModule required "
                + "username=\"msk-user\" password=\"from-secrets-manager\";";

        runner.withPropertyValues(
                        "spring.kafka.bootstrap-servers=b-1.example.amazonaws.com:9096",
                        "spring.kafka.properties.security.protocol=SASL_SSL",
                        "spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512",
                        "spring.kafka.properties.sasl.jaas.config=" + jaas
                )
                .run(context -> {
                    KafkaProperties kafka = context.getBean(KafkaProperties.class);
                    Map<String, String> extra = kafka.getProperties();
                    assertThat(extra)
                            .containsEntry("security.protocol", "SASL_SSL")
                            .containsEntry("sasl.mechanism", "SCRAM-SHA-512");
                    assertThat(extra.get("sasl.jaas.config")).contains("ScramLoginModule");
                    assertThat(extra.get("sasl.jaas.config")).contains("from-secrets-manager");
                });
    }

    @Configuration
    @EnableConfigurationProperties(KafkaProperties.class)
    static class KafkaPropertiesConfiguration {
    }
}
