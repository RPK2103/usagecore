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
}
