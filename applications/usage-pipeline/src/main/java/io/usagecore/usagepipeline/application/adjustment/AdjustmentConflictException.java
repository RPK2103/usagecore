package io.usagecore.usagepipeline.application.adjustment;

/**
 * Conflict for UsageAdjustment commands (HTTP 409 with a specific errorCode).
 */
public class AdjustmentConflictException extends RuntimeException {

    private final String errorCode;

    public AdjustmentConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
