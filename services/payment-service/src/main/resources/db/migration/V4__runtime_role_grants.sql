-- =====================================================================================
-- Droits du role applicatif.
--
-- Meme principe que pour le grand livre : l'utilisateur qui migre possede le schema,
-- l'utilisateur qui execute recoit le minimum. Ici, la nuance porte sur ce qui est
-- mutable et ce qui ne l'est pas.
--
-- Les transactions changent d'etat : elles sont modifiables. L'historique de ces
-- changements et le journal d'audit ne le sont pas : insertion seule, meme pour
-- l'application.
-- =====================================================================================

DO
$$
    DECLARE
        v_role text := '${paymentAppRole}';
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
            RAISE NOTICE
                'Role applicatif % absent : grants ignores. En production, creez-le avant de migrer.',
                v_role;
            RETURN;
        END IF;

        EXECUTE format('GRANT USAGE ON SCHEMA payment TO %I', v_role);

        -- Etat courant : mutable.
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON payment.payment_transaction TO %I', v_role);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON payment.idempotency_record TO %I', v_role);

        -- Journaux : insertion seule. C'est ici que se joue l'immuabilite au niveau des droits.
        EXECUTE format('GRANT SELECT, INSERT ON payment.transaction_state_transition TO %I', v_role);
        EXECUTE format('GRANT SELECT, INSERT ON payment.audit_log TO %I', v_role);

        -- Outbox : le relais marque les lignes publiees et purge les anciennes.
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON payment.outbox_event TO %I', v_role);

        -- Deduplication : purge des entrees anciennes autorisee.
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON payment.processed_message TO %I', v_role);

        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA payment TO %I', v_role);
    END
$$;
