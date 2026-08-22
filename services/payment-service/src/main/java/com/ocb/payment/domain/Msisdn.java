package com.ocb.payment.domain;

import com.ocb.platform.domain.error.InvariantViolationException;

import java.util.regex.Pattern;

/**
 * Numero Mobile Money.
 *
 * <p>Le type existe pour une seule raison : rendre le masquage impossible a oublier.
 * Tant qu'un numero circule sous forme de {@code String}, rien n'empeche de le passer a un
 * logger. Ici, {@link #toString()} — la methode qu'appellent tous les frameworks de
 * journalisation — retourne la forme masquee. Obtenir le numero complet demande un appel
 * explicite a {@link #full()}, qui se repere en relecture et se cherche par grep.
 *
 * <p>La valeur complete n'est jamais persistee par ce service : seule
 * {@link #masked()} l'est. Une donnee qu'on ne conserve pas ne fuite pas, ne part pas
 * dans un export et ne demande aucune gestion de cle de chiffrement.
 */
public final class Msisdn {

    private static final Pattern FORMAT = Pattern.compile("^\\+[0-9]{9,15}$");
    public static final String ERR_INVALID = "PAYMENT_INVALID_MSISDN";

    private final String value;

    private Msisdn(String value) {
        this.value = value;
    }

    public static Msisdn of(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            // Le message ne contient ni la valeur refusee ni aucun numero d'exemple.
            //
            // La valeur refusee, parce qu'une entree invalide reste une donnee personnelle
            // et finirait recopiee dans les logs d'erreur. Le numero d'exemple, parce qu'un
            // message contenant une suite de chiffres rend impossible de verifier
            // automatiquement qu'aucun numero ne fuite — un test ne saurait pas distinguer
            // l'exemple de la vraie valeur.
            throw new InvariantViolationException(
                    ERR_INVALID,
                    "Numero invalide : format international attendu, un plus suivi de neuf a quinze chiffres");
        }
        return new Msisdn(value);
    }

    /** Numero complet. A n'utiliser que pour appeler l'operateur. */
    public String full() {
        return value;
    }

    /** Forme masquee : prefixe pays et quatre derniers chiffres conserves. */
    public String masked() {
        return mask(value);
    }

    public static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 9) {
            return "****";
        }
        String prefix = msisdn.substring(0, 5);
        String suffix = msisdn.substring(msisdn.length() - 4);
        return prefix + "*".repeat(msisdn.length() - 9) + suffix;
    }

    @Override
    public String toString() {
        return masked();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Msisdn m && value.equals(m.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
