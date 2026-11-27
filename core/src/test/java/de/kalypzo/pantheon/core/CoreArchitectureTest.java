package de.kalypzo.pantheon.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = {"de.kalypzo.pantheon.core", "de.kalypzo.pantheon.agora"}, importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

    @ArchTest
    static final ArchRule coreDoesNotDependOnBukkit = noClasses()
            .that().resideInAPackage("de.kalypzo.pantheon.core..")
            .should().dependOnClassesThat().resideInAPackage("org.bukkit..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule agoraClientIsPlain = noClasses()
            .that().resideInAPackage("de.kalypzo.pantheon.agora..")
            .should().dependOnClassesThat().resideInAnyPackage("de.kalypzo.pantheon.core..", "net.kyori..")
            .allowEmptyShould(true);
}
