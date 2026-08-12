package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, UUID> {

    Optional<ProductJpaEntity> findByProductKey(String productKey);

    boolean existsByProductKey(String productKey);
}
