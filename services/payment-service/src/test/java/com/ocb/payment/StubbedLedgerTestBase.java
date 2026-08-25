package com.ocb.payment;

import com.ocb.payment.domain.port.LedgerPort;
import com.ocb.payment.support.LedgerStub;
import com.ocb.payment.support.LedgerStubConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Socle des tests qui remplacent le grand livre par une doublure en memoire.
 *
 * <p><b>Pourquoi ce socle existe plutot qu'un simple {@code @Import} par test.</b> La
 * doublure est un bean singleton porte par un contexte Spring <b>mis en cache et partage
 * entre classes de test</b>. Son etat survit donc d'une classe a l'autre : un test qui
 * simule un grand livre injoignable rend le grand livre injoignable pour tout ce qui
 * s'execute ensuite.
 *
 * <p>Ce defaut a la particularite de ne pas se voir localement quand l'ordre d'execution
 * place la victime avant le coupable. Il n'apparait qu'en integration continue, sur une
 * machine ou l'ordre des fichiers differe — et il s'y presente sous la forme la plus
 * trompeuse qui soit : un test de concurrence qui echoue, ce qui oriente vers le verrou
 * plutot que vers la doublure.
 *
 * <p>La remise a zero est donc faite <b>ici</b>, une fois, et non repetee dans chaque
 * classe ou elle finirait par etre oubliee. Meme raisonnement que pour les autres
 * invariants faciles a oublier de ce service : on ne les confie pas a la vigilance.
 */
@Import(LedgerStubConfiguration.class)
public abstract class StubbedLedgerTestBase extends PaymentPersistenceTestBase {

    @Autowired
    private LedgerPort ledger;

    protected LedgerStub stub;

    /** Portefeuille propre a ce test, derive du suffixe aleatoire du socle parent. */
    protected String wallet;

    @BeforeEach
    void resetStubbedLedger() {
        stub = (LedgerStub) ledger;
        stub.reset();
        wallet = "2100.wallet-" + suffix;
    }
}
