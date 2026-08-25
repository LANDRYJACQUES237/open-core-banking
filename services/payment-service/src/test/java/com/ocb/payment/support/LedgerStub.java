package com.ocb.payment.support;

import com.ocb.payment.domain.PaymentErrors;
import com.ocb.payment.domain.port.LedgerPort;
import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.money.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Grand livre en memoire, pour les tests qui portent sur la saga et non sur la comptabilite.
 *
 * <p><b>Pourquoi une doublure plutot qu'un vrai grand livre.</b> Ce qui est teste ici est
 * la serialisation du couple "lire le solde / ecrire en fonction de ce qu'on a lu". Le
 * grand livre n'en est que le support : ses propres invariants — equilibre, immuabilite,
 * contrainte differee — sont eprouves chez lui, par ses 62 tests d'integration. Les
 * refaire passer par le reseau ici n'ajouterait rien et rendrait le test dependant d'un
 * serveur de plus.
 *
 * <p><b>Ce qu'elle modelise fidelement.</b> Un appel synchrone a un service <b>distant</b> :
 * l'ecriture prend effet immediatement et ne participe pas a la transaction locale de
 * l'appelant. C'est exactement le comportement d'un appel REST, et c'est ce qui rend le
 * probleme de concurrence reel plutot que masque par une transaction englobante.
 *
 * <p><b>Ce qu'elle ne modelise pas.</b> Le plan de comptes. Seuls les portefeuilles sont
 * suivis, parce qu'eux seuls sont interroges. Une doublure qui pretendrait tenir un bilan
 * complet donnerait une fausse impression de fidelite.
 */
public class LedgerStub implements LedgerPort {

    /** Soldes exprimes dans le sens normal du compte, comme le fait le vrai grand livre. */
    private final Map<String, BigDecimal> walletBalances = new ConcurrentHashMap<>();

    /** Ecritures acceptees, par cle d'idempotence : c'est ce qui rend le rejeu inoffensif. */
    private final Map<String, String> postedByKey = new ConcurrentHashMap<>();

    private final Map<String, String> reversalsByOriginal = new ConcurrentHashMap<>();
    private final List<EntryRequest> entries = new ArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile boolean unavailable;
    private volatile boolean refusesReversal;

    /**
     * Remet la doublure a neuf.
     *
     * <p>Appelee par {@code StubbedLedgerTestBase} avant chaque test. Cette doublure est un bean
     * singleton porte par un contexte Spring <b>mis en cache et partage entre classes de
     * test</b> : un drapeau positionne par une classe survit jusqu'a la suivante. Un test
     * qui simule un grand livre injoignable rendrait ainsi injoignable le grand livre de
     * tous les tests executes apres lui.
     *
     * <p>Le defaut correspondant ne se voit pas localement si l'ordre d'execution place la
     * victime avant le coupable : c'est une panne qui n'apparait qu'en integration
     * continue, sur une machine ou l'ordre des fichiers differe.
     */
    public synchronized void reset() {
        walletBalances.clear();
        postedByKey.clear();
        reversalsByOriginal.clear();
        entries.clear();
        unavailable = false;
        refusesReversal = false;
    }

    public void credit(String walletAccountRef, String amount) {
        walletBalances.put(walletAccountRef, new BigDecimal(amount));
    }

    public Money balanceOfWallet(String walletAccountRef) {
        return Money.of(walletBalances.getOrDefault(walletAccountRef, BigDecimal.ZERO),
                Currency.getInstance("XAF"));
    }

    public void becomeUnavailable() {
        this.unavailable = true;
    }

    public void refuseReversals() {
        this.refusesReversal = true;
    }

    public synchronized List<EntryRequest> postedEntries() {
        return List.copyOf(entries);
    }

    public long countEntriesWithRef(String entryRef) {
        return postedEntries().stream()
                .filter(e -> entryRef.equals(e.entryRef()))
                .count();
    }

    @Override
    public Money balanceOf(String accountNumber) {
        if (unavailable) {
            throw new LedgerUnavailableException("Grand livre injoignable (double)", null);
        }
        return balanceOfWallet(accountNumber);
    }

    @Override
    public synchronized String post(EntryRequest request) {
        if (unavailable) {
            throw new LedgerUnavailableException("Grand livre injoignable (double)", null);
        }

        // Rejeu de la meme cle : on rend l'ecriture existante, exactement comme le vrai
        // grand livre. Sans cela, une retentative apres redelivrance ferait bouger les
        // soldes une seconde fois et le test ne prouverait rien de l'idempotence.
        String existing = postedByKey.get(request.idempotencyKey());
        if (existing != null) {
            return existing;
        }

        requireBalanced(request);
        request.lines().forEach(this::apply);

        String entryRef = request.entryRef() != null
                ? request.entryRef()
                : "JE-STUB-" + sequence.incrementAndGet();
        postedByKey.put(request.idempotencyKey(), entryRef);
        entries.add(request);
        return entryRef;
    }

    @Override
    public synchronized String reverse(ReversalRequest request) {
        if (unavailable) {
            throw new LedgerUnavailableException("Grand livre injoignable (double)", null);
        }
        if (refusesReversal) {
            throw new InvariantViolationException(PaymentErrors.LEDGER_REJECTED,
                    "Contre-passation refusee (double)");
        }

        String already = reversalsByOriginal.get(request.originalEntryRef());
        if (already != null) {
            // Une ecriture ne peut etre contre-passee qu'une fois. Rendre l'existante
            // plutot qu'echouer reproduit le rejeu legitime d'une compensation.
            return already;
        }

        EntryRequest original = postedEntries().stream()
                .filter(e -> request.originalEntryRef().equals(e.entryRef()))
                .findFirst()
                .orElseThrow(() -> new InvariantViolationException(
                        PaymentErrors.LEDGER_REJECTED,
                        "Ecriture %s introuvable (double)".formatted(request.originalEntryRef())));

        // Inverse chaque ligne : c'est la definition d'une contre-passation.
        original.lines().forEach(line -> apply(new Line(
                line.accountNumber(), "DR".equals(line.direction()) ? "CR" : "DR", line.amount())));

        String reversalRef = "JE-STUB-REV-" + sequence.incrementAndGet();
        reversalsByOriginal.put(request.originalEntryRef(), reversalRef);
        return reversalRef;
    }

    private void apply(Line line) {
        if (!line.accountNumber().startsWith("2100")) {
            return;
        }
        // Un portefeuille est un compte de passif : le debiter diminue ce que nous devons
        // au client, donc son solde exprime dans le sens normal.
        BigDecimal delta = "DR".equals(line.direction())
                ? line.amount().amount().negate()
                : line.amount().amount();
        walletBalances.merge(line.accountNumber(), delta, BigDecimal::add);
    }

    /**
     * Le vrai grand livre refuse une ecriture desequilibree, et cette doublure aussi.
     *
     * <p>C'est la seule regle comptable qu'elle reprend, parce que c'est la seule que les
     * tests de saga peuvent enfreindre : une erreur de montant dans l'ecriture de
     * livraison passerait autrement inapercue.
     */
    private void requireBalanced(EntryRequest request) {
        BigDecimal debit = sum(request, "DR");
        BigDecimal credit = sum(request, "CR");
        if (debit.compareTo(credit) != 0) {
            throw new InvariantViolationException(PaymentErrors.LEDGER_REJECTED,
                    "Ecriture desequilibree : debit %s, credit %s".formatted(debit, credit));
        }
    }

    private BigDecimal sum(EntryRequest request, String direction) {
        return request.lines().stream()
                .filter(line -> direction.equals(line.direction()))
                .map(line -> line.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
