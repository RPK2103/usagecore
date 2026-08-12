package io.usagecore.entitlementruntime.application.entitlement;

public interface EntitlementDecisionRecorder {

    void append(EntitlementDecisionRecord record);
}
