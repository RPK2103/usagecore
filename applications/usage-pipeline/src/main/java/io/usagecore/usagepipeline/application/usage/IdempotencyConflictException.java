package io.usagecore.usagepipeline.application.usage;

/**
 * Same tenant + idempotency key already accepted a different logical usage payload.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
