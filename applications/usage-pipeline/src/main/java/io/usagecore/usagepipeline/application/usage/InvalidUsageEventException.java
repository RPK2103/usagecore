package io.usagecore.usagepipeline.application.usage;

/**
 * Raised when a consumed event payload/envelope fails validation.
 * Non-retryable poison-message path (not a transient infrastructure failure).
 */
public class InvalidUsageEventException extends RuntimeException {

    public InvalidUsageEventException(String message) {
        super(message);
    }
}
