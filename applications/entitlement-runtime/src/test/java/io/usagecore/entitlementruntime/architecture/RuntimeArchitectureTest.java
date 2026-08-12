package io.usagecore.entitlementruntime.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "io.usagecore.entitlementruntime",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class RuntimeArchitectureTest {

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
    static final ArchRule domainMustNotDependOnJdbc = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
            .because("domain must not use JDBC APIs");

    @ArchTest
    static final ArchRule mustNotDependOnControlPlane = noClasses()
            .that()
            .resideInAPackage("io.usagecore.entitlementruntime..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.usagecore.controlplane..")
            .because("entitlement-runtime must remain independent of the control-plane module");

    @ArchTest
    static final ArchRule controllersMustNotDependOnJdbcAdapters = noClasses()
            .that()
            .resideInAPackage("..adapters.inbound.http..")
            .and()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapters.outbound.persistence..")
            .because("controllers must call application services, not persistence adapters");
}
