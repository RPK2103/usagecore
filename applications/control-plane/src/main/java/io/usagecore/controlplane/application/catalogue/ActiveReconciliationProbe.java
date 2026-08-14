package io.usagecore.controlplane.application.catalogue;

import java.util.UUID;

/**
 * Narrow shared-PostgreSQL probe for Usage Pipeline–owned reconciliation runs.
 * Used to block FINALIZED transition while a run is RUNNING (Phase 8A).
 * No HTTP coupling between Control Plane and Usage Pipeline.
 */
public interface ActiveReconciliationProbe {

    boolean hasRunningReconciliation(UUID commercialPeriodId);
}
