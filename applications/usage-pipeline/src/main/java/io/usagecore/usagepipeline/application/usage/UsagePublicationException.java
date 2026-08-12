package io.usagecore.usagepipeline.application.usage;

/**
 * Raised when Kafka publication cannot be confirmed.
 * Must not be mapped to HTTP 202.
 */
public class UsagePublicationException extends RuntimeException {

    public UsagePublicationException(String message, Throwable cause) {
        super(message, cause);
    }

    public UsagePublicationException(String message) {
        super(message);
    }
}
