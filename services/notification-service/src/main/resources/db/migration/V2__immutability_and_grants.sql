-- =====================================================================================
-- Immuabilite de la trace, et droits du role applicatif.
-- =====================================================================================

CREATE OR REPLACE FUNCTION notification.fn_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION
        'NOTIFICATION_IMMUTABLE: % refuse sur %.% ; un message emis ne se reecrit pas',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = '0A000';
END;
$$;

-- Un message envoye est un fait. Le corriger apres coup reviendrait a pretendre avoir dit
-- autre chose que ce qu'on a dit — exactement ce que la trace sert a empecher lorsque le
-- litige porte sur "le client avait-il ete prevenu".
CREATE TRIGGER trg_notification_immutable
    BEFORE UPDATE OR DELETE
    ON notification.notification
    FOR EACH ROW
EXECUTE FUNCTION notification.fn_reject_mutation();

CREATE TRIGGER trg_notification_no_truncate
    BEFORE TRUNCATE
    ON notification.notification
    FOR EACH STATEMENT
EXECUTE FUNCTION notification.fn_reject_mutation();

-- -------------------------------------------------------------------------------------
-- Le role qui execute n'est pas celui qui migre. Il peut ecrire une notification et la
-- relire, jamais la modifier ni la supprimer — le declencheur ci-dessus le refuserait de
-- toute facon, mais les droits l'expriment aussi, et une defense qui tient a un seul
-- mecanisme tient a peu de chose.
-- -------------------------------------------------------------------------------------
DO
$$
    DECLARE
        v_role text := '${notificationAppRole}';
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
            RAISE NOTICE
                'Role applicatif % absent : grants ignores. En production, creez-le avant de migrer.',
                v_role;
            RETURN;
        END IF;

        EXECUTE format('GRANT USAGE ON SCHEMA notification TO %I', v_role);

        -- Aucun UPDATE, aucun DELETE : la trace ne se retouche pas.
        EXECUTE format('GRANT SELECT, INSERT ON notification.notification TO %I', v_role);

        -- DELETE autorise sur la deduplication, qui est une donnee technique a purger.
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON notification.processed_message TO %I',
                       v_role);

        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA notification TO %I', v_role);
    END
$$;
