package de.kalypzo.pantheon.paper;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Pantheon never shuts the server down (P-26, megalodon §1): no class calls Bukkit.shutdown or Server.shutdown. */
class NoShutdownCallsTest {

    @Test
    void noCallsToServerShutdown() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.kalypzo.pantheon");
        noClasses().should().callMethod(org.bukkit.Bukkit.class, "shutdown")
                .orShould().callMethod(org.bukkit.Server.class, "shutdown")
                .orShould().callMethod(org.bukkit.Bukkit.class, "restart")
                .orShould().callMethod(org.bukkit.Server.class, "restart")
                .check(classes);
    }
}
