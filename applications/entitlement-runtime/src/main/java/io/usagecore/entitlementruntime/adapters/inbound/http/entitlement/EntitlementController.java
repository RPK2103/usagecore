package io.usagecore.entitlementruntime.adapters.inbound.http.entitlement;

import io.usagecore.entitlementruntime.application.entitlement.EntitlementCheckApplicationService;
import io.usagecore.entitlementruntime.application.entitlement.EntitlementCheckResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/entitlements", produces = MediaType.APPLICATION_JSON_VALUE)
public class EntitlementController {

    private final EntitlementCheckApplicationService entitlementCheckApplicationService;

    public EntitlementController(EntitlementCheckApplicationService entitlementCheckApplicationService) {
        this.entitlementCheckApplicationService = entitlementCheckApplicationService;
    }

    @PostMapping(path = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('DEVELOPER','TENANT_ADMIN','CONTRACT_MANAGER')")
    public CheckEntitlementResponse check(@Valid @RequestBody CheckEntitlementRequest request) {
        long requestedUnits = request.requestedUnits() == null ? 1L : request.requestedUnits();
        EntitlementCheckResult result = entitlementCheckApplicationService.check(
                request.productKey(),
                request.featureKey(),
                requestedUnits
        );
        return new CheckEntitlementResponse(
                result.decisionId(),
                result.decision(),
                result.reason(),
                result.productKey(),
                result.featureKey(),
                result.requestedUnits(),
                result.configuredLimit(),
                result.contractVersion(),
                result.evaluatedAt(),
                result.correlationId()
        );
    }
}
