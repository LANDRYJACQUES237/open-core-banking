-- =====================================================================================
-- Droits du role applicatif.
--
-- Principe : l'utilisateur qui applique les migrations n'est pas l'utilisateur qui fait
-- tourner l'application. Le premier est proprietaire du schema et peut tout faire ; le
-- second recoit le minimum necessaire, et notamment PAS le droit de modifier ni de
-- supprimer une ecriture.
--
-- Consequence concrete : meme un bug applicatif, meme un ORM mal configure, meme une
-- injection SQL reussie ne peuvent pas alterer le grand livre. Le droit n'existe pas.
--
-- Ce script ne cree pas le role et ne fixe aucun mot de passe : ce sont des operations
-- d'exploitation, et un mot de passe n'a rien a faire dans un depot. Si le role est
-- absent, la migration ne fait rien plutot que d'echouer, afin qu'un poste de
-- developpement mono-utilisateur reste demarrable.
-- =====================================================================================

DO
$$
    DECLARE
        v_role text := '${ledgerAppRole}';
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
            RAISE NOTICE
                'Role applicatif % absent : grants ignores. En production, creez-le avant de migrer.',
                v_role;
            RETURN;
        END IF;

        EXECUTE format('GRANT USAGE ON SCHEMA ledger TO %I', v_role);

        -- Plan de comptes : mutable (ouverture, gel, cloture) mais jamais supprimable.
        -- Supprimer un compte rendrait ses ecritures orphelines et donc le grand livre
        -- illisible ; on le cloture.
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ledger.account TO %I', v_role);

        -- Ecritures : lecture et insertion seulement. C'est ici que se joue l'immuabilite
        -- au niveau des droits.
        EXECUTE format('GRANT SELECT, INSERT ON ledger.journal_entry TO %I', v_role);
        EXECUTE format('GRANT SELECT, INSERT ON ledger.posting_line TO %I', v_role);

        -- Audit : insertion seule, des deux cotes.
        EXECUTE format('GRANT SELECT, INSERT ON ledger.audit_log TO %I', v_role);
        EXECUTE format('GRANT SELECT, INSERT ON ledger.audit_seal TO %I', v_role);

        -- Instantanes de solde : cache reconstructible, donc pleinement mutable.
        EXECUTE format(
                'GRANT SELECT, INSERT, UPDATE, DELETE ON ledger.account_balance_snapshot TO %I',
                v_role);

        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ledger TO %I', v_role);
        EXECUTE format('GRANT EXECUTE ON FUNCTION ledger.fn_global_imbalance() TO %I', v_role);
    END
$$;
