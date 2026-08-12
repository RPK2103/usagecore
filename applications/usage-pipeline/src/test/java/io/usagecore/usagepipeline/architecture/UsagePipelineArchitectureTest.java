package io.usagecore.usagepipeline.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "io.usagecore.usagepipeline",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class UsagePipelineArchitectureTest {

    @ArchTest
    static final ArchRule mustNotDependOnControlPlane = noClasses()
            .that()
            .resideInAPackage("io.usagecore.usagepipeline..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.usagecore.controlplane..")
            .because("usage-pipeline must remain independent of the control-plane module");

    @ArchTest
    static final ArchRule mustNotDependOnEntitlementRuntime = noClasses()
            .that()
            .resideInAPackage("io.usagecore.usagepipeline..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.usagecore.entitlementruntime..")
            .because("usage-pipeline must remain independent of the entitlement-runtime module");

    @ArchTest
    static final ArchRule applicationMustNotDependOnSpringKafka = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.kafka..", "org.apache.kafka..")
            .because("application ports must stay free of Kafka client APIs");

    @ArchTest
    static final ArchRule applicationMustNotDependOnSpringMvc = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
            .because("application layer must not depend on HTTP adapters");
}
