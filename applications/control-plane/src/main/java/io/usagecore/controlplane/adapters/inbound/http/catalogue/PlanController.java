package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.application.catalogue.PlanApplicationService;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/plans")
public class PlanController {

    private final PlanApplicationService planApplicationService;

    public PlanController(PlanApplicationService planApplicationService) {
        this.planApplicationService = planApplicationService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PlanResponse> createPlan(
            @PathVariable UUID productId,
            @Valid @RequestBody CreatePlanRequest request
    ) {
        PlanResponse body = PlanResponse.from(
                planApplicationService.createDraftPlan(
                        productId,
                        BusinessKey.of(request.planKey()),
                        request.name()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{planId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','CONTRACT_MANAGER','TENANT_ADMIN','AUDITOR')")
    public ResponseEntity<PlanResponse> getPlan(
            @PathVariable UUID productId,
            @PathVariable UUID planId
    ) {
        return ResponseEntity.ok(
                PlanResponse.from(planApplicationService.requirePlanForProduct(productId, planId))
        );
    }

    @PutMapping("/{planId}/features/{featureId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PlanResponse> configureFeature(
            @PathVariable UUID productId,
            @PathVariable UUID planId,
            @PathVariable UUID featureId,
            @Valid @RequestBody EntitlementConfigRequest request
    ) {
        return ResponseEntity.ok(
                PlanResponse.from(
                        planApplicationService.configurePlanFeature(
                                productId,
                                planId,
                                featureId,
                                request.mode(),
                                request.toLimitConfiguration()
                        )
                )
        );
    }

    @PostMapping("/{planId}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PlanResponse> publishPlan(
            @PathVariable UUID productId,
            @PathVariable UUID planId
    ) {
        return ResponseEntity.ok(
                PlanResponse.from(planApplicationService.publishPlan(productId, planId))
        );
    }
}
