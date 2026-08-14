package io.usagecore.usagepipeline.adapters.inbound.http.reconciliation;

import io.usagecore.usagepipeline.application.reconciliation.ReconciliationApplicationService;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationItemRecord;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRunRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative reconciliation API (Phase 8A). Read / rebuild / compare / report only —
 * never repairs derived commercial state.
 */
@RestController
@RequestMapping(path = "/api/v1/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReconciliationController {

    private final ReconciliationApplicationService reconciliationApplicationService;

    public ReconciliationController(ReconciliationApplicationService reconciliationApplicationService) {
        this.reconciliationApplicationService = reconciliationApplicationService;
    }

    @PostMapping("/periods/{commercialPeriodId}/runs")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR')")
    public ResponseEntity<ReconciliationRunResponse> startRun(@PathVariable UUID commercialPeriodId) {
        ReconciliationRunRecord run = reconciliationApplicationService.startAndExecute(commercialPeriodId);
        return ResponseEntity.ok(ReconciliationRunResponse.from(run));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR','AUDITOR')")
    public ResponseEntity<ReconciliationRunResponse> getRun(@PathVariable UUID runId) {
        return ResponseEntity.ok(ReconciliationRunResponse.from(reconciliationApplicationService.requireRun(runId)));
    }

    @GetMapping("/runs/{runId}/items")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','BILLING_OPERATOR','AUDITOR')")
    public ResponseEntity<List<ReconciliationItemResponse>> getItems(@PathVariable UUID runId) {
        List<ReconciliationItemResponse> items = reconciliationApplicationService.requireItems(runId).stream()
                .map(ReconciliationItemResponse::from)
                .toList();
        return ResponseEntity.ok(items);
    }
}
