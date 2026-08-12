package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.application.catalogue.ContractApplicationService;
import io.usagecore.controlplane.application.catalogue.ContractVersionApplicationService;
import io.usagecore.controlplane.application.catalogue.ResourceNotFoundException;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.ContractVersion;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractApplicationService contractApplicationService;
    private final ContractVersionApplicationService contractVersionApplicationService;

    public ContractController(
            ContractApplicationService contractApplicationService,
            ContractVersionApplicationService contractVersionApplicationService
    ) {
        this.contractApplicationService = contractApplicationService;
        this.contractVersionApplicationService = contractVersionApplicationService;
    }

    @PostMapping
    public ResponseEntity<ContractResponse> createContract(@Valid @RequestBody CreateContractRequest request) {
        ContractResponse body = ContractResponse.from(
                contractApplicationService.createContract(
                        request.tenantId(),
                        request.productId(),
                        BusinessKey.of(request.contractKey())
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{contractId}")
    public ResponseEntity<ContractResponse> getContract(@PathVariable UUID contractId) {
        return ResponseEntity.ok(ContractResponse.from(contractApplicationService.requireContract(contractId)));
    }

    @PostMapping("/{contractId}/versions")
    public ResponseEntity<ContractVersionResponse> createDraftVersion(
            @PathVariable UUID contractId,
            @Valid @RequestBody CreateContractVersionRequest request
    ) {
        ContractVersionResponse body = ContractVersionResponse.from(
                contractVersionApplicationService.createDraftVersion(
                        contractId,
                        request.effectiveFrom(),
                        request.effectiveUntil()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/{contractId}/versions/from-plan")
    public ResponseEntity<ContractVersionResponse> createDraftFromPlan(
            @PathVariable UUID contractId,
            @Valid @RequestBody CreateContractVersionFromPlanRequest request
    ) {
        ContractVersionResponse body = ContractVersionResponse.from(
                contractVersionApplicationService.createDraftFromPlan(
                        contractId,
                        request.planId(),
                        request.effectiveFrom(),
                        request.effectiveUntil()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{contractId}/versions/{versionNumber}")
    public ResponseEntity<ContractVersionResponse> getVersion(
            @PathVariable UUID contractId,
            @PathVariable int versionNumber
    ) {
        return ResponseEntity.ok(
                ContractVersionResponse.from(
                        contractVersionApplicationService.requireVersion(contractId, versionNumber)
                )
        );
    }

    @PutMapping("/{contractId}/versions/{versionNumber}/entitlements/{featureId}")
    public ResponseEntity<ContractVersionResponse> upsertEntitlement(
            @PathVariable UUID contractId,
            @PathVariable int versionNumber,
            @PathVariable UUID featureId,
            @Valid @RequestBody EntitlementConfigRequest request
    ) {
        return ResponseEntity.ok(
                ContractVersionResponse.from(
                        contractVersionApplicationService.upsertDraftEntitlement(
                                contractId,
                                versionNumber,
                                featureId,
                                request.mode(),
                                request.toLimitConfiguration()
                        )
                )
        );
    }

    @PostMapping("/{contractId}/versions/{versionNumber}/activate")
    public ResponseEntity<ContractVersionResponse> activateVersion(
            @PathVariable UUID contractId,
            @PathVariable int versionNumber
    ) {
        return ResponseEntity.ok(
                ContractVersionResponse.from(
                        contractVersionApplicationService.activateVersion(contractId, versionNumber)
                )
        );
    }

    @GetMapping("/{contractId}/effective-version")
    public ResponseEntity<ContractVersionResponse> resolveEffectiveVersion(
            @PathVariable UUID contractId,
            @RequestParam("at") Instant at
    ) {
        ContractVersion version = contractVersionApplicationService
                .resolveEffectiveVersion(contractId, at)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No activated contract version governs instant " + at
                ));
        return ResponseEntity.ok(ContractVersionResponse.from(version));
    }
}
