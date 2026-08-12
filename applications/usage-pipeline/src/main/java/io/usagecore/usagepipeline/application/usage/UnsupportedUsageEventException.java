package io.usagecore.usagepipeline.application.usage;

/**
 * Raised when a consumed event has an unsupported type or version.
 * Must not be treated as a successful supported processing path.
 */
public class UnsupportedUsageEventException extends RuntimeException {

    public UnsupportedUsageEventException(String message) {
        super(message);
    }
}
