package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.Plan;
import io.usagecore.controlplane.domain.catalogue.PlanStatus;
import java.util.List;
import java.util.UUID;

public record PlanResponse(
        UUID id,
        UUID productId,
        String planKey,
        String name,
        PlanStatus status,
        List<PlanFeatureResponse> features
) {

    public static PlanResponse from(Plan plan) {
        return new PlanResponse(
                plan.id(),
                plan.productId(),
                plan.planKey().value(),
                plan.name(),
                plan.status(),
                plan.planFeatures().stream().map(PlanFeatureResponse::from).toList()
        );
    }
}
