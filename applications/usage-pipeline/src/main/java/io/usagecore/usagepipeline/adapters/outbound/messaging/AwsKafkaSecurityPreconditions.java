package io.usagecore.usagepipeline.adapters.outbound.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("aws")
class AwsKafkaSecurityPreconditions {

    AwsKafkaSecurityPreconditions(
            @Value("${USAGECORE_KAFKA_SASL_JAAS_CONFIG:}") String jaasConfig
    ) {
        if (jaasConfig == null || jaasConfig.isBlank()) {
            throw new IllegalStateException(
                    "AWS profile requires USAGECORE_KAFKA_SASL_JAAS_CONFIG from the runtime secret; "
                            + "the value must not be stored in source or Helm values."
            );
        }
    }
}
