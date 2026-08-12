package io.usagecore.events.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "io.usagecore.events",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class EventContractsArchitectureTest {

    @ArchTest
    static final ArchRule mustNotDependOnApplications = noClasses()
            .that()
            .resideInAPackage("io.usagecore.events..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.usagecore.controlplane..",
                    "io.usagecore.entitlementruntime..",
                    "io.usagecore.usagepipeline.."
            )
            .because("event-contracts is transport-only and must not depend on application modules");
}
