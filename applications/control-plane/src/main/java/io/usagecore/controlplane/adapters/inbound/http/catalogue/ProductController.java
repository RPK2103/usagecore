package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.application.catalogue.FeatureApplicationService;
import io.usagecore.controlplane.application.catalogue.ProductApplicationService;
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
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductApplicationService productApplicationService;
    private final FeatureApplicationService featureApplicationService;

    public ProductController(
            ProductApplicationService productApplicationService,
            FeatureApplicationService featureApplicationService
    ) {
        this.productApplicationService = productApplicationService;
        this.featureApplicationService = featureApplicationService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse body = ProductResponse.from(
                productApplicationService.createProduct(
                        BusinessKey.of(request.productKey()),
                        request.name()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','CONTRACT_MANAGER','TENANT_ADMIN','AUDITOR')")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID productId) {
        return ResponseEntity.ok(ProductResponse.from(productApplicationService.requireProduct(productId)));
    }

    @PostMapping("/{productId}/features")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<FeatureResponse> createFeature(
            @PathVariable UUID productId,
            @Valid @RequestBody CreateFeatureRequest request
    ) {
        FeatureResponse body = FeatureResponse.from(
                featureApplicationService.createFeature(
                        productId,
                        BusinessKey.of(request.featureKey()),
                        request.name()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{productId}/features/{featureId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','CONTRACT_MANAGER','TENANT_ADMIN','AUDITOR')")
    public ResponseEntity<FeatureResponse> getFeature(
            @PathVariable UUID productId,
            @PathVariable UUID featureId
    ) {
        return ResponseEntity.ok(
                FeatureResponse.from(featureApplicationService.requireFeatureForProduct(productId, featureId))
        );
    }
}
