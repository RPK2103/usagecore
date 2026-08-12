package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import io.usagecore.usagepipeline.application.usage.UsageIngestionApplicationService;
import io.usagecore.usagepipeline.application.usage.UsageIngestionResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/usage", produces = MediaType.APPLICATION_JSON_VALUE)
public class UsageController {

    private final UsageIngestionApplicationService usageIngestionApplicationService;

    public UsageController(UsageIngestionApplicationService usageIngestionApplicationService) {
        this.usageIngestionApplicationService = usageIngestionApplicationService;
    }

    /**
     * Accepts a usage event for asynchronous processing.
     * HTTP 202 means Kafka acknowledged publication — not aggregation or quota update.
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
                .body(new SubmitUsageEventResponse(result.eventId(), result.status(), result.correlationId()));
    }
}
