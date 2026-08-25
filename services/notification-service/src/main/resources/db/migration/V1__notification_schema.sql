-- =====================================================================================
-- notification-service : ce qui a ete dit, a qui, et quand.
--
-- Ce service ne detient aucune verite financiere et n'en produit aucune. Il detient une
-- trace : celle des messages emis. C'est une donnee de preuve — "le client avait-il ete
-- prevenu ?" est une question qui se pose apres coup, en litige — donc elle est
-- append-only comme les autres journaux de la plateforme.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.notification
(
    id             uuid        NOT NULL PRIMARY KEY,
    seq            bigint      NOT NULL GENERATED ALWAYS AS IDENTITY,
    transaction_id uuid        NOT NULL,
    type           text        NOT NULL,
    channel        text        NOT NULL,

    -- Reference de portefeuille, jamais un numero de telephone.
    --
    -- Aucun service de la plateforme ne conserve le numero en clair : payment-service ne
    -- garde que sa forme masquee, et un numero masque ne permet de joindre personne. Le
    -- destinataire est donc designe par son compte, et le resoudre en canal joignable
    -- appartient a l'adaptateur d'envoi. C'est une consequence directe de la decision de
    -- ne pas conserver la donnee plutot que de la chiffrer, et elle est assumee.
    recipient_ref  text        NOT NULL,

    message        text        NOT NULL,
    correlation_id text,
    delivered_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_notification_type CHECK (type IN (
        'COLLECTION_COMPLETED', 'COLLECTION_FAILED',
        'DISBURSEMENT_COMPLETED', 'DISBURSEMENT_REVERSED',
        'TRANSFER_COMPLETED', 'MANUAL_REVIEW_REQUIRED')),

    CONSTRAINT ck_notification_channel CHECK (channel IN ('CUSTOMER', 'OPS'))
);

CREATE INDEX ix_notification_transaction ON notification.notification (transaction_id);
CREATE INDEX ix_notification_delivered_at ON notification.notification (delivered_at);

-- -------------------------------------------------------------------------------------
-- Deduplication des messages consommes.
--
-- Meme structure que dans payment et provider : c'est common-kafka qui l'ecrit, et le
-- module partage ne fournit deliberement pas la migration — chaque service possede son
-- schema.
--
-- La cle est composite parce que deux groupes de consommation lisent le meme flux de
-- maniere independante. Ce service est precisement le second lecteur de
-- ocb.evt.payment.v1 : sans cette composition, le premier a consommer masquerait
-- l'evenement au second.
-- -------------------------------------------------------------------------------------
CREATE TABLE notification.processed_message
(
    consumer_group text        NOT NULL,
    event_id       text        NOT NULL,
    event_type     text,
    processed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX ix_notification_processed_at ON notification.processed_message (processed_at);
