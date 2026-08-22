package com.ocb.payment.domain;

import com.ocb.platform.domain.error.InvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MsisdnTest {

    @ParameterizedTest
    @CsvSource({
            "+237670000001, +2376****0001",
            "+237690123456, +2376****3456",
            "+33612345678,  +3361***5678"
    })
    @DisplayName("le masquage conserve le prefixe pays et les quatre derniers chiffres")
    void masking(String full, String expected) {
        assertThat(Msisdn.of(full).masked()).isEqualTo(expected);
    }

    @Test
    @DisplayName("toString rend la forme masquee, pour que journaliser un numero soit sans risque")
    void toStringIsMasked() {
        // C'est la raison d'etre du type. Tant qu'un numero circule en String, rien
        // n'empeche de le passer a un logger. Ici, la methode qu'appellent tous les
        // frameworks de journalisation rend deja la forme masquee.
        Msisdn msisdn = Msisdn.of("+237670000001");

        assertThat(msisdn.toString()).isEqualTo("+2376****0001");
        assertThat("Paiement de " + msisdn).doesNotContain("670000001");
        assertThat(String.format("%s", msisdn)).isEqualTo("+2376****0001");
    }

    @Test
    @DisplayName("obtenir le numero complet demande un appel explicite")
    void fullRequiresExplicitCall() {
        assertThat(Msisdn.of("+237670000001").full()).isEqualTo("+237670000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"237670000001", "+23767", "+2376700000012345678", "abc", "+237-670-000", ""})
    @DisplayName("un numero mal forme est refuse")
    void rejectsMalformed(String value) {
        assertThatThrownBy(() -> Msisdn.of(value))
                .isInstanceOf(InvariantViolationException.class);
    }

    @Test
    @DisplayName("le message d'erreur ne recopie pas la valeur refusee")
    void errorMessageDoesNotLeakTheValue() {
        // Une entree invalide reste une donnee personnelle : recopiee dans le message,
        // elle finirait dans les logs d'erreur, c'est-a-dire exactement la ou on ne veut
        // pas de numeros.
        assertThatThrownBy(() -> Msisdn.of("237670000001"))
                .hasMessageNotContaining("670000001");
    }

    @Test
    @DisplayName("null est refuse")
    void rejectsNull() {
        assertThatThrownBy(() -> Msisdn.of(null)).isInstanceOf(InvariantViolationException.class);
    }
}
