-- =====================================================================================
-- Immuabilite du grand livre.
--
-- Deux couches independantes, parce qu'aucune des deux ne suffit seule :
--
--   * Les GRANT (V5) retirent UPDATE et DELETE au role applicatif. C'est la protection
--     de production. Elle ne protege pas d'un superutilisateur, qui contourne tout
--     controle de droits.
--
--   * Les triggers ci-dessous refusent la mutation quel que soit le role, y compris
--     superutilisateur. C'est la protection contre l'erreur humaine en console et
--     contre un service qui tenterait un UPDATE par accident.
--
-- Une correction ne se fait jamais par mutation : elle se fait par contre-passation,
-- c'est-a-dire par une nouvelle ecriture equilibree qui annule l'effet de la premiere.
-- On ne reecrit pas l'histoire comptable, on la corrige par une ligne de plus.
-- =====================================================================================

CREATE OR REPLACE FUNCTION ledger.fn_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION
        'LEDGER_IMMUTABLE: % refuse sur %.% ; une correction se fait par contre-passation, jamais par mutation',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = '0A000';
END;
$$;

COMMENT ON FUNCTION ledger.fn_reject_mutation() IS
    'Refuse toute mutation, y compris pour un superutilisateur. ERRCODE 0A000 = feature_not_supported.';

CREATE TRIGGER trg_journal_entry_immutable
    BEFORE UPDATE OR DELETE
    ON ledger.journal_entry
    FOR EACH ROW
EXECUTE FUNCTION ledger.fn_reject_mutation();

CREATE TRIGGER trg_posting_line_immutable
    BEFORE UPDATE OR DELETE
    ON ledger.posting_line
    FOR EACH ROW
EXECUTE FUNCTION ledger.fn_reject_mutation();

CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE
    ON ledger.audit_log
    FOR EACH ROW
EXECUTE FUNCTION ledger.fn_reject_mutation();

CREATE TRIGGER trg_audit_seal_immutable
    BEFORE UPDATE OR DELETE
    ON ledger.audit_seal
    FOR EACH ROW
EXECUTE FUNCTION ledger.fn_reject_mutation();

-- Un TRUNCATE ne declenche pas les triggers ligne a ligne ci-dessus : il faut un
-- trigger de niveau instruction. Sans lui, l'immuabilite serait contournable par
-- une seule commande.
CREATE TRIGGER trg_journal_entry_no_truncate
    BEFORE TRUNCATE
    ON ledger.journal_entry
    FOR EACH STATEMENT
EXECUTE FUNCTION ledger.fn_reject_mutation();

CREATE TRIGGER trg_posting_line_no_truncate
    BEFORE TRUNCATE
    ON ledger.posting_line
    FOR EACH STATEMENT
EXECUTE FUNCTION ledger.fn_reject_mutation();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE
    ON ledger.audit_log
    FOR EACH STATEMENT
EXECUTE FUNCTION ledger.fn_reject_mutation();

CREATE TRIGGER trg_audit_seal_no_truncate
    BEFORE TRUNCATE
    ON ledger.audit_seal
    FOR EACH STATEMENT
EXECUTE FUNCTION ledger.fn_reject_mutation();
