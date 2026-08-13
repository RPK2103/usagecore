package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.application.catalogue.CommercialPeriodApplicationService;
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
@RequestMapping("/api/v1/tenants/{tenantId}/products/{productId}/commercial-periods")
public class CommercialPeriodController {

    private final CommercialPeriodApplicationService commercialPeriodApplicationService;

    public CommercialPeriodController(CommercialPeriodApplicationService commercialPeriodApplicationService) {
        this.commercialPeriodApplicationService = commercialPeriodApplicationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR')")
    public ResponseEntity<CommercialPeriodResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID productId,
            @Valid @RequestBody CreateCommercialPeriodRequest request
    ) {
        CommercialPeriodResponse body = CommercialPeriodResponse.from(
                commercialPeriodApplicationService.create(
                        tenantId,
                        productId,
                        request.periodStart(),
                        request.periodEnd()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{periodId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR','CONTRACT_MANAGER','TENANT_ADMIN','AUDITOR')")
    public ResponseEntity<CommercialPeriodResponse> get(
            @PathVariable UUID tenantId,
            @PathVariable UUID productId,
            @PathVariable UUID periodId
    ) {
        return ResponseEntity.ok(CommercialPeriodResponse.from(
                commercialPeriodApplicationService.require(tenantId, productId, periodId)
        ));
    }

    @PostMapping("/{periodId}/closing")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR')")
    public ResponseEntity<CommercialPeriodResponse> beginClosing(
            @PathVariable UUID tenantId,
            @PathVariable UUID productId,
            @PathVariable UUID periodId
    ) {
        return ResponseEntity.ok(CommercialPeriodResponse.from(
                commercialPeriodApplicationService.beginClosing(tenantId, productId, periodId)
        ));
    }

    @PostMapping("/{periodId}/reconciling")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR')")
    public ResponseEntity<CommercialPeriodResponse> beginReconciling(
            @PathVariable UUID tenantId,
            @PathVariable UUID productId,
            @PathVariable UUID periodId
    ) {
        return ResponseEntity.ok(CommercialPeriodResponse.from(
                commercialPeriodApplicationService.beginReconciling(tenantId, productId, periodId)
        ));
    }

    @PostMapping("/{periodId}/finalize")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR')")
    public ResponseEntity<CommercialPeriodResponse> finalize(
            @PathVariable UUID tenantId,
            @PathVariable UUID productId,
            @PathVariable UUID periodId
    ) {
        return ResponseEntity.ok(CommercialPeriodResponse.from(
                commercialPeriodApplicationService.finalize(tenantId, productId, periodId)
        ));
    }
}
