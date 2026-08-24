-- =====================================================================================
-- Immuabilite des journaux, et droits du role applicatif.
-- =====================================================================================

CREATE OR REPLACE FUNCTION provider.fn_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION
        'PROVIDER_IMMUTABLE: % refuse sur %.% ; un rappel recu ne se reecrit pas',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = '0A000';
END;
$$;

-- Le contenu d'un rappel est immuable : c'est la preuve de ce que l'operateur a envoye,
-- et elle ne vaut que si personne ne peut la retoucher apres coup. Seul le drapeau
-- "traite" doit pouvoir changer, d'ou un trigger qui autorise cette colonne et refuse
-- toutes les autres.
CREATE OR REPLACE FUNCTION provider.fn_callback_only_processed_may_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.provider_code IS DISTINCT FROM OLD.provider_code
        OR NEW.provider_event_id IS DISTINCT FROM OLD.provider_event_id
        OR NEW.raw_payload IS DISTINCT FROM OLD.raw_payload
        OR NEW.signature IS DISTINCT FROM OLD.signature
        OR NEW.signature_valid IS DISTINCT FROM OLD.signature_valid
        OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION
            'PROVIDER_IMMUTABLE: le contenu d''un rappel ne peut pas etre modifie ; seul son marquage de traitement peut evoluer'
            USING ERRCODE = '0A000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_callback_content_immutable
    BEFORE UPDATE
    ON provider.provider_callback
    FOR EACH ROW
EXECUTE FUNCTION provider.fn_callback_only_processed_may_change();

CREATE TRIGGER trg_callback_no_delete
    BEFORE DELETE
    ON provider.provider_callback
    FOR EACH ROW
EXECUTE FUNCTION provider.fn_reject_mutation();

CREATE TRIGGER trg_provider_audit_immutable
    BEFORE UPDATE OR DELETE
    ON provider.audit_log
    FOR EACH ROW
EXECUTE FUNCTION provider.fn_reject_mutation();

CREATE TRIGGER trg_callback_no_truncate
    BEFORE TRUNCATE
    ON provider.provider_callback
    FOR EACH STATEMENT
EXECUTE FUNCTION provider.fn_reject_mutation();

CREATE TRIGGER trg_provider_audit_no_truncate
    BEFORE TRUNCATE
    ON provider.audit_log
    FOR EACH STATEMENT
EXECUTE FUNCTION provider.fn_reject_mutation();

-- -------------------------------------------------------------------------------------
DO
$$
    DECLARE
        v_role text := '${providerAppRole}';
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
            RAISE NOTICE
                'Role applicatif % absent : grants ignores. En production, creez-le avant de migrer.',
                v_role;
            RETURN;
        END IF;

        EXECUTE format('GRANT USAGE ON SCHEMA provider TO %I', v_role);

        -- L'operation evolue : statut, compteurs, prochaine relance.
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON provider.provider_operation TO %I', v_role);

        -- Le rappel est insere puis marque traite, jamais reecrit ni supprime.
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON provider.provider_callback TO %I', v_role);

        EXECUTE format('GRANT SELECT, INSERT ON provider.audit_log TO %I', v_role);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON provider.outbox_event TO %I', v_role);
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON provider.processed_message TO %I', v_role);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA provider TO %I', v_role);
    END
$$;
