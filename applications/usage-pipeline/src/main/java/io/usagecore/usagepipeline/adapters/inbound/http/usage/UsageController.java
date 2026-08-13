package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import io.usagecore.usagepipeline.application.usage.UsageAggregateQueryService;
import io.usagecore.usagepipeline.application.usage.UsageIngestionApplicationService;
import io.usagecore.usagepipeline.application.usage.UsageIngestionResult;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/usage", produces = MediaType.APPLICATION_JSON_VALUE)
public class UsageController {

    private final UsageIngestionApplicationService usageIngestionApplicationService;
    private final UsageAggregateQueryService usageAggregateQueryService;
    private final UsageWindowAggregateQueryService usageWindowAggregateQueryService;

    public UsageController(
            UsageIngestionApplicationService usageIngestionApplicationService,
            UsageAggregateQueryService usageAggregateQueryService,
            UsageWindowAggregateQueryService usageWindowAggregateQueryService
    ) {
        this.usageIngestionApplicationService = usageIngestionApplicationService;
        this.usageAggregateQueryService = usageAggregateQueryService;
        this.usageWindowAggregateQueryService = usageWindowAggregateQueryService;
    }

    /**
     * Accepts a usage event for asynchronous processing.
     * HTTP 202 means durably accepted in PostgreSQL (ingestion + outbox) — not Kafka processed,
     * aggregated, quota updated, or billed.
     */
    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('DEVELOPER')")
    public ResponseEntity<SubmitUsageEventResponse> submit(@Valid @RequestBody SubmitUsageEventRequest request) {
        UsageIngestionResult result = usageIngestionApplicationService.ingest(
                request.productKey(),
                request.meterKey(),
                request.quantity(),
                request.occurredAt(),
                request.idempotencyKey()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new SubmitUsageEventResponse(
                        result.eventId(),
                        result.status(),
                        result.correlationId(),
                        result.idempotentReplay()
                ));
    }

    /**
     * Tenant-scoped read of derived Phase 6A lifetime aggregate state.
     * Tenant identity comes only from the JWT — never from the path or body.
     */
    @GetMapping("/aggregates/{productKey}/{meterKey}")
    @PreAuthorize("hasAnyRole('DEVELOPER','TENANT_ADMIN','AUDITOR','BILLING_OPERATOR')")
    public ResponseEntity<UsageAggregateResponse> getAggregate(
            @PathVariable String productKey,
            @PathVariable String meterKey
    ) {
        return ResponseEntity.ok(
                UsageAggregateResponse.from(
                        productKey,
                        usageAggregateQueryService.requireAggregate(productKey, meterKey)
                )
        );
    }

    /**
     * Tenant-scoped read of the current event-time window aggregate (UTC calendar window).
     * Tenant identity comes only from the JWT — never from the path or body.
     */
    @GetMapping("/aggregates/{productKey}/{meterKey}/windows/current")
    @PreAuthorize("hasAnyRole('DEVELOPER','TENANT_ADMIN','AUDITOR','BILLING_OPERATOR')")
    public ResponseEntity<UsageWindowAggregateResponse> getCurrentWindowAggregate(
            @PathVariable String productKey,
            @PathVariable String meterKey
    ) {
        return ResponseEntity.ok(
                UsageWindowAggregateResponse.from(
                        productKey,
                        usageWindowAggregateQueryService.requireCurrentWindow(productKey, meterKey)
                )
        );
    }
}
