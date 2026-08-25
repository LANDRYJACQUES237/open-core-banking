package com.ocb.payment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regles d'architecture, cote paiement.
 *
 * <p>Une regle ArchUnit ne voit que ce que son module compile. Celle sur la purete de
 * {@code platform/} existait deja dans ledger-service, mais elle n'y a aucune prise sur
 * les classes de ce service : elles ne sont pas sur son chemin de compilation. Comme
 * l'extraction de {@code common-kafka} est partie d'ici, c'est ici qu'elle doit etre
 * verifiee.
 */
@AnalyzeClasses(packages = "com.ocb", importOptions = ImportOption.DoNotIncludeTests.class)
class PaymentArchitectureTest {

    @ArchTest
    static final ArchRule platformCarriesNoBusinessLogic = noClasses()
            .that().resideInAPackage("com.ocb.platform..")
            .should().dependOnClassesThat(
                    resideInAPackage("com.ocb..")
                            .and(resideOutsideOfPackage("com.ocb.platform..")))
            .because("un module partage qui connait un service devient le vecteur du "
                    + "monolithe distribue que le decoupage cherche a eviter");

    @ArchTest
    static final ArchRule domainDependsOnNoFramework = noClasses()
            .that().resideInAPackage("com.ocb.payment.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "javax..",
                    "java.sql..",
                    "javax.sql..",
                    "com.fasterxml.jackson..",
                    "org.postgresql..")
            .because("la machine a etats doit rester testable en millisecondes, sans "
                    + "contexte applicatif : c'est ce qui rend abordables ses 139 cas");

    @ArchTest
    static final ArchRule domainIgnoresTheOuterLayers = noClasses()
            .that().resideInAPackage("com.ocb.payment.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ocb.payment.application..",
                    "com.ocb.payment.adapter..",
                    "com.ocb.payment.api..")
            .because("les dependances vont vers l'interieur : le domaine ne connait ni ses "
                    + "appelants ni ses adaptateurs");
}
