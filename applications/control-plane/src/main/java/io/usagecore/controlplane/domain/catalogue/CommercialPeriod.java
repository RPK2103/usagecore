package io.usagecore.controlplane.domain.catalogue;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Commercial accounting window for a tenant + product over a half-open UTC interval
 * {@code [periodStart, periodEnd)}.
 * <p>
 * Lifecycle authority for whether ordinary usage may still mutate commercial derived
 * state for that range. Separate from event-time {@code UsageWindow} aggregates and
 * from {@link ContractVersion} activation immutability.
 * <p>
 * FINALIZED is terminal. Phase 7 finalization is administrative and does not prove
 * aggregate reconciliation correctness (Phase 8).
 */
public final class CommercialPeriod {

    private final UUID id;
    private final UUID tenantId;
    private final UUID productId;
    private final Instant periodStart;
    private final Instant periodEnd;
    private CommercialPeriodStatus status;
    private Instant closingStartedAt;
    private Instant reconcilingStartedAt;
    private Instant finalizedAt;
    private String finalizedBy;

    private CommercialPeriod(
            UUID id,
            UUID tenantId,
            UUID productId,
            Instant periodStart,
            Instant periodEnd,
            CommercialPeriodStatus status,
            Instant closingStartedAt,
            Instant reconcilingStartedAt,
            Instant finalizedAt,
            String finalizedBy
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.periodStart = Objects.requireNonNull(periodStart, "periodStart");
        this.periodEnd = Objects.requireNonNull(periodEnd, "periodEnd");
        if (!periodEnd.isAfter(periodStart)) {
            throw new DomainInvariantException("periodEnd must be strictly after periodStart");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.closingStartedAt = closingStartedAt;
        this.reconcilingStartedAt = reconcilingStartedAt;
        this.finalizedAt = finalizedAt;
        this.finalizedBy = finalizedBy;
        assertTimestampConsistency();
    }

    public static CommercialPeriod create(UUID tenantId, UUID productId, Instant periodStart, Instant periodEnd) {
        return new CommercialPeriod(
                UUID.randomUUID(),
                tenantId,
                productId,
                periodStart,
                periodEnd,
                CommercialPeriodStatus.OPEN,
                null,
                null,
                null,
                null
        );
    }

    public static CommercialPeriod reconstitute(
            UUID id,
            UUID tenantId,
            UUID productId,
            Instant periodStart,
            Instant periodEnd,
            CommercialPeriodStatus status,
            Instant closingStartedAt,
            Instant reconcilingStartedAt,
            Instant finalizedAt,
            String finalizedBy
    ) {
        return new CommercialPeriod(
                id,
                tenantId,
                productId,
                periodStart,
                periodEnd,
                status,
                closingStartedAt,
                reconcilingStartedAt,
                finalizedAt,
                finalizedBy
        );
    }

    /**
     * Domain-level transition guard. Concurrent authority remains PostgreSQL conditional UPDATE.
     */
    public void beginClosing(Instant at) {
        Objects.requireNonNull(at, "at");
        requireStatus(CommercialPeriodStatus.OPEN, CommercialPeriodStatus.CLOSING);
        this.status = CommercialPeriodStatus.CLOSING;
        this.closingStartedAt = at;
    }

    public void beginReconciling(Instant at) {
        Objects.requireNonNull(at, "at");
        requireStatus(CommercialPeriodStatus.CLOSING, CommercialPeriodStatus.RECONCILING);
        this.status = CommercialPeriodStatus.RECONCILING;
        this.reconcilingStartedAt = at;
    }

    public void finalizePeriod(Instant at, String principalId) {
        Objects.requireNonNull(at, "at");
        if (principalId == null || principalId.isBlank()) {
            throw new DomainInvariantException("finalizedBy principal is required");
        }
        requireStatus(CommercialPeriodStatus.RECONCILING, CommercialPeriodStatus.FINALIZED);
        this.status = CommercialPeriodStatus.FINALIZED;
        this.finalizedAt = at;
        this.finalizedBy = principalId;
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(periodStart) && instant.isBefore(periodEnd);
    }

    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        Objects.requireNonNull(otherStart, "otherStart");
        Objects.requireNonNull(otherEnd, "otherEnd");
        return periodStart.isBefore(otherEnd) && otherStart.isBefore(periodEnd);
    }

    private void requireStatus(CommercialPeriodStatus expected, CommercialPeriodStatus target) {
        if (status == CommercialPeriodStatus.FINALIZED) {
            throw new DomainInvariantException(
                    "FINALIZED commercial period is terminal and cannot transition to " + target
            );
        }
        if (status != expected) {
            throw new DomainInvariantException(
                    "Invalid commercial period transition from " + status + " to " + target
            );
        }
    }

    private void assertTimestampConsistency() {
        switch (status) {
            case OPEN -> {
                if (closingStartedAt != null || reconcilingStartedAt != null
                        || finalizedAt != null || finalizedBy != null) {
                    throw new DomainInvariantException("OPEN commercial period must not carry transition timestamps");
                }
            }
            case CLOSING -> {
                if (closingStartedAt == null
                        || reconcilingStartedAt != null
                        || finalizedAt != null
                        || finalizedBy != null) {
                    throw new DomainInvariantException("CLOSING commercial period requires closingStartedAt only");
                }
            }
            case RECONCILING -> {
                if (closingStartedAt == null
                        || reconcilingStartedAt == null
                        || finalizedAt != null
                        || finalizedBy != null) {
                    throw new DomainInvariantException(
                            "RECONCILING commercial period requires closing and reconciling timestamps"
                    );
                }
            }
            case FINALIZED -> {
                if (closingStartedAt == null
                        || reconcilingStartedAt == null
                        || finalizedAt == null
                        || finalizedBy == null
                        || finalizedBy.isBlank()) {
                    throw new DomainInvariantException(
                            "FINALIZED commercial period requires full transition evidence"
                    );
                }
            }
        }
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID productId() {
        return productId;
    }

    public Instant periodStart() {
        return periodStart;
    }

    public Instant periodEnd() {
        return periodEnd;
    }

    public CommercialPeriodStatus status() {
        return status;
    }

    public Instant closingStartedAt() {
        return closingStartedAt;
    }

    public Instant reconcilingStartedAt() {
        return reconcilingStartedAt;
    }

    public Instant finalizedAt() {
        return finalizedAt;
    }

    public String finalizedBy() {
        return finalizedBy;
    }
}
