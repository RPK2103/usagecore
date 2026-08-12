package io.usagecore.usagepipeline.application.security;

public interface CorrelationIdAccessor {

    String currentCorrelationId();
}
