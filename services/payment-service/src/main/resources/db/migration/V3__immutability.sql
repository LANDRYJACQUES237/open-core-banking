-- =====================================================================================
-- Immuabilite des journaux.
--
-- Contrairement au grand livre, la table des transactions est mutable : le statut d'une
-- transaction change, c'est sa raison d'etre. Ce qui doit rester immuable, c'est
-- l'HISTOIRE de ces changements. Sans elle, on saurait ou en est une transaction mais
-- plus comment elle y est arrivee, et il deviendrait impossible de demontrer qu'un
-- callback duplique a bien ete neutralise.
-- =====================================================================================

CREATE OR REPLACE FUNCTION payment.fn_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION
        'PAYMENT_IMMUTABLE: % refuse sur %.% ; l''historique des transitions ne se reecrit pas',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = '0A000';
END;
$$;

CREATE TRIGGER trg_transition_immutable
    BEFORE UPDATE OR DELETE
    ON payment.transaction_state_transition
    FOR EACH ROW
EXECUTE FUNCTION payment.fn_reject_mutation();

CREATE TRIGGER trg_payment_audit_immutable
    BEFORE UPDATE OR DELETE
    ON payment.audit_log
    FOR EACH ROW
EXECUTE FUNCTION payment.fn_reject_mutation();

-- TRUNCATE ne declenche pas les triggers ligne a ligne : sans trigger d'instruction,
-- une seule commande suffirait a effacer tout l'historique.
CREATE TRIGGER trg_transition_no_truncate
    BEFORE TRUNCATE
    ON payment.transaction_state_transition
    FOR EACH STATEMENT
EXECUTE FUNCTION payment.fn_reject_mutation();

CREATE TRIGGER trg_payment_audit_no_truncate
    BEFORE TRUNCATE
    ON payment.audit_log
    FOR EACH STATEMENT
EXECUTE FUNCTION payment.fn_reject_mutation();
