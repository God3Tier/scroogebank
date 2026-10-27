package bank.engagement;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Each module reads and writes only its own tables (ADR-0051 decision 2): no
 * class outside a module may use that module's entities or repositories.
 * Modules talk to each other through their services.
 */
class ArchitectureTest {

    private static final List<String> MODULES =
            List.of("calendar", "reminders", "notifications", "drafting", "holidays", "audit");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bank.engagement");

    @Test
    void modulesUseOnlyTheirOwnTables() {
        for (String module : MODULES) {
            String modulePackage = "bank.engagement." + module;
            DescribedPredicate<JavaClass> tables = DescribedPredicate.describe(
                    "entities or repositories of " + module,
                    type -> type.getPackageName().startsWith(modulePackage)
                            && (type.isAnnotatedWith(Entity.class) || type.isAssignableTo(Repository.class)));
            noClasses().that().resideOutsideOfPackage(modulePackage + "..")
                    .should().dependOnClassesThat(tables)
                    .check(CLASSES);
        }
    }

    @Test
    void onlyNotificationsTalksToSes() {
        noClasses().that().resideOutsideOfPackage("bank.engagement.notifications..")
                .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk.services.sesv2..")
                .check(CLASSES);
    }

    @Test
    void onlyDraftingTalksToBedrock() {
        noClasses().that().resideOutsideOfPackage("bank.engagement.drafting..")
                .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk.services.bedrockruntime..")
                .check(CLASSES);
    }
}
