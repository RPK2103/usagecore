package io.usagecore.usagepipeline.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Compile-time module boundary evidence via the usage-pipeline POM.
 */
class UsagePipelineModuleBoundaryTest {

    @Test
    void pomMustNotDependOnControlPlaneOrEntitlementRuntime() throws Exception {
        Path pom = Path.of("pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("applications/usage-pipeline/pom.xml");
        }
        String content = Files.readString(pom);
        assertThat(content).doesNotContain("<artifactId>control-plane</artifactId>");
        assertThat(content).doesNotContain("<artifactId>entitlement-runtime</artifactId>");
        assertThat(content).contains("<artifactId>event-contracts</artifactId>");
        assertThat(content).contains("<artifactId>database-migrations</artifactId>");
        assertThat(content).contains("<artifactId>spring-boot-starter-jdbc</artifactId>");
    }
}
