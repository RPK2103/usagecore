package io.usagecore.controlplane.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "io.usagecore.controlplane",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class DomainArchitectureTest {

    @ArchTest
    static final ArchRule domainMustNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapters..", "..configuration..")
            .because("domain must stay free of adapter and infrastructure concerns");

    @ArchTest
    static final ArchRule domainMustNotDependOnSpring = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .because("domain must remain framework-independent");

    @ArchTest
    static final ArchRule domainMustNotDependOnJpa = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "javax.persistence..", "org.hibernate..")
            .because("domain must not use JPA annotations or Hibernate APIs");

    @ArchTest
    static final ArchRule domainMustNotDependOnWeb = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..",
                    "jakarta.servlet..",
                    "javax.servlet.."
            )
            .because("domain must remain independent of HTTP/web infrastructure");

    @ArchTest
    static final ArchRule domainMustNotDependOnObservabilityInfrastructure = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.micrometer..",
                    "io.opentelemetry..",
                    "org.slf4j.."
            )
            .because("domain must remain free of metrics, tracing, and MDC APIs");

    @ArchTest
    static final ArchRule domainMustNotDependOnSecurityInfrastructure = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.security..",
                    "org.keycloak.."
            )
            .because("domain must remain independent of Spring Security and Keycloak");

    @ArchTest
    static final ArchRule mustNotDependOnEntitlementRuntime = noClasses()
            .that()
            .resideInAPackage("io.usagecore.controlplane..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.usagecore.entitlementruntime..")
            .because("control-plane must remain independent of the entitlement-runtime module");

    @ArchTest
    static final ArchRule controllersMustNotDependOnJpaRepositories = noClasses()
            .that()
            .resideInAPackage("..adapters.inbound.http..")
            .and()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapters.outbound.persistence..")
            .because("controllers must call application services, not persistence adapters/JPA repositories");
}
