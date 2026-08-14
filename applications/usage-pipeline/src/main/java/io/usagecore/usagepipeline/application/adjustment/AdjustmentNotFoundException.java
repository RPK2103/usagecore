package io.usagecore.usagepipeline.application.adjustment;

public class AdjustmentNotFoundException extends RuntimeException {

    public AdjustmentNotFoundException(String message) {
        super(message);
    }
}
