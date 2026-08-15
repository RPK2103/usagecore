package io.usagecore.entitlementruntime.application.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.usagecore.entitlementruntime.application.observability.EntitlementRuntimeMetrics;
import io.usagecore.entitlementruntime.application.security.AuthenticatedPrincipal;
import io.usagecore.entitlementruntime.application.security.CorrelationIdAccessor;
import io.usagecore.entitlementruntime.application.security.CurrentPrincipal;
import io.usagecore.entitlementruntime.application.security.PlatformRole;
import io.usagecore.entitlementruntime.domain.CommercialInvariantException;
import io.usagecore.entitlementruntime.domain.SnapshotEntitlementMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntitlementCheckApplicationServiceTest {

    private static final Instant FIXED = Instant.parse("2026-06-15T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private CurrentPrincipal currentPrincipal;
    @Mock
    private CorrelationIdAccessor correlationIdAccessor;
    @Mock
    private CommercialEntitlementReader commercialEntitlementReader;
    @Mock
    private EntitlementDecisionRecorder decisionRecorder;

    private EntitlementCheckApplicationService service;

    @BeforeEach
    void setUp() {
        service = new EntitlementCheckApplicationService(
                currentPrincipal,
                correlationIdAccessor,
                commercialEntitlementReader,
                decisionRecorder,
                Clock.fixed(FIXED, ZoneOffset.UTC),
                new EntitlementRuntimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
        );
        when(currentPrincipal.require()).thenReturn(new AuthenticatedPrincipal(
                "dev-1",
                Optional.of(TENANT),
                EnumSet.of(PlatformRole.DEVELOPER)
        ));
        when(correlationIdAccessor.currentCorrelationId()).thenReturn("corr-unit");
    }

    @Test
    void multipleEffectiveMatches_failLoudlyWithoutChoosingOne() {
        CommercialEntitlementMatch first = new CommercialEntitlementMatch(
                UUID.randomUUID(), UUID.randomUUID(), 1, SnapshotEntitlementMode.ENABLED, null
        );
        CommercialEntitlementMatch second = new CommercialEntitlementMatch(
                UUID.randomUUID(), UUID.randomUUID(), 2, SnapshotEntitlementMode.ENABLED, null
        );
        when(commercialEntitlementReader.findEffectiveEntitlements(
                eq(TENANT), eq("datapilot-cloud"), eq("scheduled_exports"), eq(FIXED)
        )).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.check("datapilot-cloud", "scheduled_exports", 1))
                .isInstanceOf(CommercialInvariantException.class)
                .hasMessageContaining("Multiple effective");
    }

    @Test
    void limitedWithinConfiguredLimit_persistsAllowWithLimit() {
        UUID contractId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(commercialEntitlementReader.findEffectiveEntitlements(any(), any(), any(), any()))
                .thenReturn(List.of(new CommercialEntitlementMatch(
                        contractId, versionId, 3, SnapshotEntitlementMode.LIMITED, 1_000_000L
                )));

        EntitlementCheckResult result = service.check("datapilot-cloud", "scheduled_exports", 1);

        assertThat(result.decision().name()).isEqualTo("ALLOW_WITH_LIMIT");
        assertThat(result.configuredLimit()).isEqualTo(1_000_000L);

        ArgumentCaptor<EntitlementDecisionRecord> captor = ArgumentCaptor.forClass(EntitlementDecisionRecord.class);
        verify(decisionRecorder).append(captor.capture());
        assertThat(captor.getValue().correlationId()).isEqualTo("corr-unit");
        assertThat(captor.getValue().contractVersionNumber()).isEqualTo(3);
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
    }
}
