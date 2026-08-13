package io.usagecore.usagepipeline.application.usage;

/**
 * Raised when a usage event references a missing or inactive meter definition.
 * Non-retryable poison-message path — do not invent meters and do not default to SUM.
 */
public class UnknownUsageMeterException extends RuntimeException {

    public UnknownUsageMeterException(String message) {
        super(message);
    }
}
