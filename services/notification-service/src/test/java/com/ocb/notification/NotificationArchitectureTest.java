package com.ocb.notification;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regles d'architecture, cote notification.
 *
 * <p>Ce service est le premier a consommer {@code common-kafka} sans avoir participe a son
 * extraction. C'est donc ici que la regle sur la purete de {@code platform/} a le plus de
 * valeur : elle verifie qu'un module partage reste utilisable par un service qu'il ne
 * connaissait pas.
 */
@AnalyzeClasses(packages = "com.ocb", importOptions = ImportOption.DoNotIncludeTests.class)
class NotificationArchitectureTest {

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
            .that().resideInAPackage("com.ocb.notification.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "java.sql..",
                    "javax.sql..",
                    "com.fasterxml.jackson..",
                    "org.postgresql..")
            .because("le domaine doit rester testable sans contexte applicatif");

    @ArchTest
    static final ArchRule domainIgnoresTheOuterLayers = noClasses()
            .that().resideInAPackage("com.ocb.notification.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ocb.notification.application..",
                    "com.ocb.notification.adapter..",
                    "com.ocb.notification.api..")
            .because("les dependances vont vers l'interieur : le domaine ne connait ni ses "
                    + "appelants ni ses adaptateurs");

    /**
     * Un service qui ne publie rien ne doit pas se mettre a publier par inadvertance.
     *
     * <p>Une notification est un point terminal : rien n'en attend la suite. Le jour ou
     * quelqu'un ajoutera un {@code KafkaTemplate} dans la couche application, ce sera un
     * choix d'architecture, pas un detail — et cette regle l'obligera a l'assumer.
     */
    @ArchTest
    static final ArchRule applicationPublishesNothing = noClasses()
            .that().resideInAPackage("com.ocb.notification.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.kafka.core..",
                    "com.ocb.platform.outbox..")
            .because("une notification est un point terminal : ce service consomme, il ne "
                    + "produit pas");
}
