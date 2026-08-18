package io.usagecore.performance.verify;

/**
 * Commercial quota checks used after a consume lab. Independent of JDBC so they can be unit-tested.
 */
public final class QuotaCorrectnessRules {

    private QuotaCorrectnessRules() {
    }

    public static void assertMeterState(
            String meterKey,
            long acceptedContribution,
            long consumedQuantity,
            long configuredLimit
    ) {
        if (consumedQuantity > configuredLimit) {
            throw new IllegalStateException(
                    meterKey + ": consumed_quantity " + consumedQuantity
                            + " exceeded configured_limit " + configuredLimit
            );
        }
        if (acceptedContribution != consumedQuantity) {
            throw new IllegalStateException(
                    meterKey + ": ACCEPTED contribution " + acceptedContribution
                            + " must equal quota_state.consumed_quantity " + consumedQuantity
            );
        }
    }
}
