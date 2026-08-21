package com.ocb.ledger;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Regles d'architecture rendues executables.
 *
 * <p>Une regle ecrite dans un README est une intention ; une regle verifiee par la CI est
 * une contrainte. Celles-ci correspondent aux engagements pris dans le document
 * d'architecture, et un projet qui derive les fait echouer plutot que de laisser
 * la derive se decouvrir six mois plus tard.
 */
@AnalyzeClasses(packages = "com.ocb", importOptions = ImportOption.DoNotIncludeTests.class)
class LedgerArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNoFramework = noClasses()
            .that().resideInAPackage("com.ocb.ledger.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "javax..",
                    "java.sql..",
                    "javax.sql..",
                    "com.fasterxml.jackson..",
                    "org.postgresql..")
            .because("le domaine doit rester testable en millisecondes, sans contexte applicatif, "
                    + "et reutilisable derriere un consommateur Kafka en Phase 2");

    @ArchTest
    static final ArchRule domainIgnoresTheOuterLayers = noClasses()
            .that().resideInAPackage("com.ocb.ledger.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ocb.ledger.application..",
                    "com.ocb.ledger.adapter..",
                    "com.ocb.ledger.api..")
            .because("les dependances vont vers l'interieur : le domaine ne connait ni ses "
                    + "appelants ni ses adaptateurs");

    @ArchTest
    static final ArchRule applicationIgnoresAdapters = noClasses()
            .that().resideInAPackage("com.ocb.ledger.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ocb.ledger.adapter..",
                    "com.ocb.ledger.api..")
            .because("la couche application s'exprime en ports ; dependre d'un adaptateur "
                    + "ou du contrat HTTP la rendrait inutilisable depuis un autre point d'entree");

    @ArchTest
    static final ArchRule platformCarriesNoBusinessLogic = noClasses()
            .that().resideInAPackage("com.ocb.platform..")
            .should().dependOnClassesThat().resideInAPackage("com.ocb.ledger..")
            .because("un module partage qui connait un service devient le vecteur du "
                    + "monolithe distribue que le decoupage cherche a eviter");

    /**
     * L'exigence "BigDecimal partout, jamais double" rendue verifiable.
     *
     * <p>Elle porte sur les champs et sur les types de retour, la ou un montant peut
     * reellement se loger. Un {@code double} n'est pas une imprecision theorique :
     * {@code 0.1 + 0.2} vaut {@code 0.30000000000000004}, et un grand livre qui l'accepte
     * finit desequilibre sans que rien ne le signale.
     */
    @ArchTest
    static final ArchRule noFloatingPointFields = noFields()
            .that().areDeclaredInClassesThat().resideInAnyPackage(
                    "com.ocb.ledger.domain..",
                    "com.ocb.ledger.application..",
                    "com.ocb.platform.domain..")
            .should().haveRawType(double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(Float.class)
            .because("un montant en virgule flottante derive silencieusement");

    @ArchTest
    static final ArchRule noFloatingPointReturnTypes = noMethods()
            .that().areDeclaredInClassesThat().resideInAnyPackage(
                    "com.ocb.ledger.domain..",
                    "com.ocb.ledger.application..",
                    "com.ocb.platform.domain..")
            .should().haveRawReturnType(double.class)
            .orShould().haveRawReturnType(float.class)
            .orShould().haveRawReturnType(Double.class)
            .orShould().haveRawReturnType(Float.class)
            .because("un montant en virgule flottante derive silencieusement");

    /**
     * Le controle d'etat d'un compte passe par les methodes du domaine.
     *
     * <p>C'est l'equivalent, pour la Phase 1, de la regle qui interdira les
     * {@code if (status == ...)} disperses autour de la machine a etats en Phase 2 :
     * une regle metier dispersee finit par etre appliquee a un endroit et oubliee ailleurs.
     */
    @ArchTest
    static final ArchRule persistenceDoesNotDecideBusinessRules = noClasses()
            .that().resideInAPackage("com.ocb.ledger.adapter.persistence..")
            .should().dependOnClassesThat().resideInAPackage("com.ocb.ledger.application..")
            .because("un adaptateur de sortie est pilote par l'application, il ne la pilote pas");
}
