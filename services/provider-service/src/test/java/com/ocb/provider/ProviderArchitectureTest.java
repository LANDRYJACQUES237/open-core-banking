package com.ocb.provider;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regles d'architecture, cote operateur.
 *
 * <p>Meme raison qu'en face : la moitie de {@code common-kafka} vient de ce service, donc
 * la regle sur la purete de {@code platform/} doit avoir prise ici. Elle est verifiee la
 * ou les classes concernees sont compilees, pas seulement la ou elle est ecrite.
 */
@AnalyzeClasses(packages = "com.ocb", importOptions = ImportOption.DoNotIncludeTests.class)
class ProviderArchitectureTest {

    @ArchTest
    static final ArchRule platformCarriesNoBusinessLogic = noClasses()
            .that().resideInAPackage("com.ocb.platform..")
            .should().dependOnClassesThat(
                    resideInAPackage("com.ocb..")
                            .and(resideOutsideOfPackage("com.ocb.platform..")))
            .because("un module partage qui connait un service devient le vecteur du "
                    + "monolithe distribue que le decoupage cherche a eviter");

    /**
     * Le domaine ne depend d'aucun framework.
     *
     * <p>{@code javax.crypto} n'est volontairement pas dans la liste, contrairement au
     * grand livre qui interdit {@code javax..} en bloc. Le calcul HMAC de
     * {@link com.ocb.provider.domain.WebhookSignature} est une regle du domaine — c'est
     * elle qui decide si un rappel est authentique — et {@code Mac} est une primitive du
     * JDK, pas une infrastructure : rien a demarrer, rien a injecter, rien qui empeche de
     * l'eprouver en millisecondes. L'interdire pousserait la verification de signature
     * dans un adaptateur, ou elle serait plus difficile a tester et plus facile a
     * contourner.
     */
    @ArchTest
    static final ArchRule domainDependsOnNoFramework = noClasses()
            .that().resideInAPackage("com.ocb.provider.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "java.sql..",
                    "javax.sql..",
                    "com.fasterxml.jackson..",
                    "org.postgresql..")
            .because("PollSchedule et WebhookSignature sont des fonctions pures : c'est ce "
                    + "qui permet de les eprouver sans infrastructure");

    @ArchTest
    static final ArchRule domainIgnoresTheOuterLayers = noClasses()
            .that().resideInAPackage("com.ocb.provider.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ocb.provider.application..",
                    "com.ocb.provider.adapter..",
                    "com.ocb.provider.api..")
            .because("les dependances vont vers l'interieur : le domaine ne connait ni ses "
                    + "appelants ni ses adaptateurs");
}
