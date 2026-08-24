package com.ocb.platform.security;

/**
 * Portees d'acces de la plateforme.
 *
 * <p>Volontairement fines, et sans role fourre-tout du type {@code admin}. Une portee
 * large est commode a attribuer et impossible a retirer : elle finit accordee a tout le
 * monde parce que personne ne sait exactement ce qu'elle autorise.
 *
 * <p>La plus importante est {@link #LEDGER_POST}. Elle n'est jamais accordee a un client
 * externe : <b>seul le moteur de paiement peut ecrire au grand livre</b>. Un client
 * marchand qui pourrait passer ses propres ecritures contournerait toute la machine a
 * etats, l'idempotence et le calcul des frais.
 *
 * <p>Spring prefixe les portees d'un jeton par {@code SCOPE_} lorsqu'il les convertit en
 * autorisations ; les constantes ci-dessous portent la valeur nue, telle qu'elle apparait
 * dans la revendication {@code scope} du jeton.
 */
public final class OcbScopes {

    private OcbScopes() {
    }

    /** Demander un encaissement ou un decaissement. Accordee aux clients marchands. */
    public static final String PAYMENT_INITIATE = "payment:initiate";

    /** Consulter une transaction et son historique de transitions. */
    public static final String PAYMENT_READ = "payment:read";

    /**
     * Enregistrer une ecriture comptable.
     *
     * <p><b>Reservee au compte de service de payment-service.</b> Personne d'autre ne
     * doit pouvoir ecrire dans le grand livre, en aucune circonstance.
     */
    public static final String LEDGER_POST = "ledger:post";

    /** Consulter comptes, soldes et releves. */
    public static final String LEDGER_READ = "ledger:read";

    /** Consulter l'etat d'une operation operateur, pour le diagnostic. */
    public static final String PROVIDER_READ = "provider:read";

    /** Arbitrer une transaction restee sans reponse. Destinee a un operateur humain. */
    public static final String ADMIN_RECONCILE = "admin:reconcile";

    /** Forme attendue par Spring Security dans les regles d'autorisation. */
    public static String authority(String scope) {
        return "SCOPE_" + scope;
    }
}
