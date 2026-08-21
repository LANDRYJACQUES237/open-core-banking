-- =====================================================================================
-- Invariant de la partie double, verifie par PostgreSQL.
--
-- Pourquoi en base alors que le domaine valide deja ?
--
-- Parce qu'un controle applicatif protege un seul chemin d'ecriture. La base protege
-- tous les chemins : un script de reprise, une migration de donnees, un correctif
-- passe en console un dimanche soir, un futur service qui ecrirait directement.
-- Un grand livre dont l'equilibre depend du fait que personne ne se trompe n'est pas
-- un grand livre, c'est une convention.
--
-- Pourquoi DEFERRABLE INITIALLY DEFERRED ?
--
-- Parce qu'une ecriture s'insere en plusieurs instructions : l'en-tete d'abord, les
-- lignes ensuite. Une contrainte immediate refuserait l'en-tete, qui n'a encore aucune
-- ligne, donc une somme de zero sur zero ligne. La contrainte differee attend le COMMIT,
-- moment ou l'ecriture est complete, pour se prononcer.
--
-- L'application, elle, force l'evaluation avant le COMMIT via SET CONSTRAINTS ALL
-- IMMEDIATE, afin d'obtenir un verdict synchrone qu'elle peut traduire en 422 propre
-- plutot qu'en echec de commit. La contrainte reste differable : c'est ce qui rend
-- l'insertion en plusieurs etapes possible, pas une tolerance au desequilibre.
-- =====================================================================================

CREATE OR REPLACE FUNCTION ledger.fn_assert_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_entry_id    uuid;
    v_sum         numeric(23, 4);
    v_line_count  integer;
    v_currencies  integer;
BEGIN
    IF TG_TABLE_NAME = 'journal_entry' THEN
        v_entry_id := NEW.id;
    ELSE
        v_entry_id := NEW.journal_entry_id;
    END IF;

    SELECT COALESCE(SUM(signed_amount), 0), COUNT(*), COUNT(DISTINCT currency)
      INTO v_sum, v_line_count, v_currencies
      FROM ledger.posting_line
     WHERE journal_entry_id = v_entry_id;

    -- Une ecriture a au minimum deux lignes : quelque chose vient de quelque part.
    -- Ce controle attrape aussi l'en-tete orpheline, qu'une contrainte portant
    -- uniquement sur les lignes laisserait passer.
    IF v_line_count < 2 THEN
        RAISE EXCEPTION
            'LEDGER_UNBALANCED: ecriture % comporte % ligne(s), au moins 2 sont requises',
            v_entry_id, v_line_count
            USING ERRCODE = '23514';
    END IF;

    -- La v1 n'a pas de comptes de position de change : une ecriture multidevise
    -- serait forcement desequilibree au sens economique meme si elle s'annule
    -- arithmetiquement.
    IF v_currencies > 1 THEN
        RAISE EXCEPTION
            'LEDGER_MIXED_CURRENCY: ecriture % melange % devises',
            v_entry_id, v_currencies
            USING ERRCODE = '23514';
    END IF;

    IF v_sum <> 0 THEN
        RAISE EXCEPTION
            'LEDGER_UNBALANCED: ecriture % presente un ecart debit/credit de %',
            v_entry_id, v_sum
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced
    AFTER INSERT
    ON ledger.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION ledger.fn_assert_entry_balanced();

CREATE CONSTRAINT TRIGGER trg_posting_line_balanced
    AFTER INSERT
    ON ledger.posting_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION ledger.fn_assert_entry_balanced();

-- -------------------------------------------------------------------------------------
-- Controle de coherence globale : la somme de TOUTES les ecritures du grand livre
-- doit valoir zero. Si elle ne vaut pas zero, quelque chose est gravement casse.
-- Expose en fonction pour etre appelable par un job de supervision et par les tests.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger.fn_global_imbalance() RETURNS numeric
    LANGUAGE sql STABLE AS
$$
SELECT COALESCE(SUM(signed_amount), 0)
  FROM ledger.posting_line;
$$;

COMMENT ON FUNCTION ledger.fn_global_imbalance() IS
    'Doit toujours retourner 0. Toute autre valeur signale une corruption du grand livre.';
