package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.FeatureStatus;
import java.util.UUID;

public record FeatureResponse(
        UUID id,
        UUID productId,
        String featureKey,
        String name,
        FeatureStatus status
) {

    public static FeatureResponse from(Feature feature) {
        return new FeatureResponse(
                feature.id(),
                feature.productId(),
                feature.featureKey().value(),
                feature.name(),
                feature.status()
        );
    }
}
