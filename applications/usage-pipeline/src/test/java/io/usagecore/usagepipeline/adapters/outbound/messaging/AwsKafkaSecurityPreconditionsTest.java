package io.usagecore.usagepipeline.adapters.outbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AwsKafkaSecurityPreconditionsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AwsKafkaSecurityPreconditions.class);

    @Test
    void defaultProfileDoesNotRequireJaas() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void awsProfileWithoutJaasFailsFast() {
        runner.withSystemProperties("spring.profiles.active=aws")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("USAGECORE_KAFKA_SASL_JAAS_CONFIG");
                });
    }

    @Test
    void awsProfileWithInjectedJaasStarts() {
        runner.withSystemProperties("spring.profiles.active=aws")
                .withPropertyValues(
                        "USAGECORE_KAFKA_SASL_JAAS_CONFIG=org.apache.kafka.common.security.scram.ScramLoginModule required username=\"u\" password=\"p\";"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }
}
