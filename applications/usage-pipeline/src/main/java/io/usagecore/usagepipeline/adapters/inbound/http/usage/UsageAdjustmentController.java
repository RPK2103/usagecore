package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import io.usagecore.usagepipeline.adapters.inbound.http.reconciliation.UsageAdjustmentResponse;
import io.usagecore.usagepipeline.application.adjustment.UsageAdjustmentApplicationService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/usage/adjustments", produces = MediaType.APPLICATION_JSON_VALUE)
public class UsageAdjustmentController {

    private final UsageAdjustmentApplicationService usageAdjustmentApplicationService;

    public UsageAdjustmentController(UsageAdjustmentApplicationService usageAdjustmentApplicationService) {
        this.usageAdjustmentApplicationService = usageAdjustmentApplicationService;
    }

    @GetMapping("/{adjustmentId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR','AUDITOR')")
    public ResponseEntity<UsageAdjustmentResponse> get(@PathVariable UUID adjustmentId) {
        return ResponseEntity.ok(
                UsageAdjustmentResponse.from(usageAdjustmentApplicationService.requireAdjustment(adjustmentId))
        );
    }
}
