package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.CommercialPeriodRepository;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriod;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriodStatus;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriodTransition;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class CommercialPeriodPersistenceAdapter implements CommercialPeriodRepository {

    private static final RowMapper<CommercialPeriod> PERIOD_MAPPER = (rs, rowNum) -> CommercialPeriod.reconstitute(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("product_id"),
            rs.getTimestamp("period_start").toInstant(),
            rs.getTimestamp("period_end").toInstant(),
            CommercialPeriodStatus.valueOf(rs.getString("status")),
            Optional.ofNullable(rs.getTimestamp("closing_started_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("reconciling_started_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("finalized_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("finalized_by")
    );

    private final CommercialPeriodJpaRepository commercialPeriodJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    CommercialPeriodPersistenceAdapter(
            CommercialPeriodJpaRepository commercialPeriodJpaRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.commercialPeriodJpaRepository = commercialPeriodJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public CommercialPeriod saveNew(CommercialPeriod period) {
        Instant now = Instant.now();
        commercialPeriodJpaRepository.save(new CommercialPeriodJpaEntity(
                period.id(),
                period.tenantId(),
                period.productId(),
                period.periodStart(),
                period.periodEnd(),
                period.status().name(),
                now,
                now,
                period.closingStartedAt(),
                period.reconcilingStartedAt(),
                period.finalizedAt(),
                period.finalizedBy()
        ));
        return period;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommercialPeriod> findById(UUID id) {
        return commercialPeriodJpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommercialPeriod> findByIdAndTenantIdAndProductId(UUID id, UUID tenantId, UUID productId) {
        return commercialPeriodJpaRepository
                .findByIdAndTenantIdAndProductId(id, tenantId, productId)
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<CommercialPeriod> transitionIfStatus(
            UUID id,
            CommercialPeriodStatus fromStatus,
            CommercialPeriodStatus toStatus,
            Instant transitionedAt,
            String finalizedByOrNull
    ) {
        Timestamp at = Timestamp.from(transitionedAt);
        String sql = switch (toStatus) {
            case CLOSING -> """
                    UPDATE commercial_period
                    SET status = 'CLOSING',
                        closing_started_at = ?,
                        updated_at = ?
                    WHERE id = ?
                      AND status = 'OPEN'
                    RETURNING id, tenant_id, product_id, period_start, period_end, status,
                              closing_started_at, reconciling_started_at, finalized_at, finalized_by
                    """;
            case RECONCILING -> """
                    UPDATE commercial_period
                    SET status = 'RECONCILING',
                        reconciling_started_at = ?,
                        updated_at = ?
                    WHERE id = ?
                      AND status = 'CLOSING'
                    RETURNING id, tenant_id, product_id, period_start, period_end, status,
                              closing_started_at, reconciling_started_at, finalized_at, finalized_by
                    """;
            case FINALIZED -> """
                    UPDATE commercial_period
                    SET status = 'FINALIZED',
                        finalized_at = ?,
                        finalized_by = ?,
                        updated_at = ?
                    WHERE id = ?
                      AND status = 'RECONCILING'
                    RETURNING id, tenant_id, product_id, period_start, period_end, status,
                              closing_started_at, reconciling_started_at, finalized_at, finalized_by
                    """;
            case OPEN -> throw new IllegalArgumentException("Cannot transition to OPEN");
        };

        List<CommercialPeriod> rows = switch (toStatus) {
            case CLOSING, RECONCILING -> jdbcTemplate.query(sql, PERIOD_MAPPER, at, at, id);
            case FINALIZED -> jdbcTemplate.query(sql, PERIOD_MAPPER, at, finalizedByOrNull, at, id);
            case OPEN -> List.of();
        };
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public void appendTransition(CommercialPeriodTransition transition) {
        jdbcTemplate.update(
                """
                INSERT INTO commercial_period_transition (
                    id, commercial_period_id, from_status, to_status, principal_id, occurred_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                transition.id(),
                transition.commercialPeriodId(),
                transition.fromStatus().name(),
                transition.toStatus().name(),
                transition.principalId(),
                Timestamp.from(transition.occurredAt()),
                transition.correlationId()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommercialPeriodTransition> findTransitionsByPeriodId(UUID commercialPeriodId) {
        return jdbcTemplate.query(
                """
                SELECT id, commercial_period_id, from_status, to_status, principal_id, occurred_at, correlation_id
                FROM commercial_period_transition
                WHERE commercial_period_id = ?
                ORDER BY occurred_at ASC
                """,
                (rs, rowNum) -> new CommercialPeriodTransition(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("commercial_period_id"),
                        CommercialPeriodStatus.valueOf(rs.getString("from_status")),
                        CommercialPeriodStatus.valueOf(rs.getString("to_status")),
                        rs.getString("principal_id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("correlation_id")
                ),
                commercialPeriodId
        );
    }

    private CommercialPeriod toDomain(CommercialPeriodJpaEntity entity) {
        return CommercialPeriod.reconstitute(
                entity.getId(),
                entity.getTenantId(),
                entity.getProductId(),
                entity.getPeriodStart(),
                entity.getPeriodEnd(),
                CommercialPeriodStatus.valueOf(entity.getStatus()),
                entity.getClosingStartedAt(),
                entity.getReconcilingStartedAt(),
                entity.getFinalizedAt(),
                entity.getFinalizedBy()
        );
    }
}
