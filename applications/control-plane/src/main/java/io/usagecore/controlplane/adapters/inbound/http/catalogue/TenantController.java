package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.application.catalogue.TenantApplicationService;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantApplicationService tenantApplicationService;

    public TenantController(TenantApplicationService tenantApplicationService) {
        this.tenantApplicationService = tenantApplicationService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        TenantResponse body = TenantResponse.from(
                tenantApplicationService.createTenant(
                        BusinessKey.of(request.tenantKey()),
                        request.displayName()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{tenantId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','CONTRACT_MANAGER','TENANT_ADMIN','AUDITOR')")
    public ResponseEntity<TenantResponse> getTenant(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(TenantResponse.from(tenantApplicationService.requireTenant(tenantId)));
    }
}
