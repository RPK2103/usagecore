package io.usagecore.usagepipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UsagePipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsagePipelineApplication.class, args);
    }
}
