package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.UUID;

/**
 * Logical commercial relationship between a tenant and a product.
 * One contract per tenant/product initially (ADR-004).
 */
public final class Contract {

    private final UUID id;
    private final UUID tenantId;
    private final UUID productId;
    private final BusinessKey contractKey;
    private ContractStatus status;

    private Contract(
            UUID id,
            UUID tenantId,
            UUID productId,
            BusinessKey contractKey,
            ContractStatus status
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.contractKey = Objects.requireNonNull(contractKey, "contractKey");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static Contract create(Tenant tenant, Product product, BusinessKey contractKey) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(product, "product");
        return new Contract(
                UUID.randomUUID(),
                tenant.id(),
                product.id(),
                contractKey,
                ContractStatus.ACTIVE
        );
    }

    public static Contract reconstitute(
            UUID id,
            UUID tenantId,
            UUID productId,
            BusinessKey contractKey,
            ContractStatus status
    ) {
        return new Contract(id, tenantId, productId, contractKey, status);
    }

    public void archive() {
        this.status = ContractStatus.ARCHIVED;
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

    public BusinessKey contractKey() {
        return contractKey;
    }

    public ContractStatus status() {
        return status;
    }
}
