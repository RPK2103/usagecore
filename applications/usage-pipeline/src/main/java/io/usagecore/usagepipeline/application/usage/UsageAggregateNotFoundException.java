package io.usagecore.usagepipeline.application.usage;

/**
 * Raised when a tenant-scoped aggregate read finds no row.
 */
public class UsageAggregateNotFoundException extends RuntimeException {

    public UsageAggregateNotFoundException(String message) {
        super(message);
    }
}
