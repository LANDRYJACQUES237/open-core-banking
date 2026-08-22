-- =====================================================================================
-- Outbox, deduplication des messages consommes, journal d'audit.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Transactional Outbox.
--
-- L'evenement est ecrit ici dans la MEME transaction que la donnee metier. Soit les deux
-- existent, soit aucun. Un relais separe publie ensuite vers Kafka.
--
-- Ce que ce pattern supprime : la base valide, la publication echoue, l'evenement est
-- perdu et rien ne le signale. Inverser l'ordre ne ferait que deplacer le probleme —
-- un evenement annoncerait alors un fait qui ne s'est jamais produit.
--
-- Les noms de colonnes suivent la convention de l'Outbox Event Router de Debezium :
-- passer du polling au CDC en Phase 5 sera un changement de configuration.
-- -------------------------------------------------------------------------------------
CREATE TABLE payment.outbox_event
(
    id              uuid        NOT NULL PRIMARY KEY,

    -- Attribue a l'ECRITURE, pas a la publication. C'est ce qui rend la republication
    -- inoffensive : un evenement reemis apres un incident porte le meme identifiant,
    -- donc les consommateurs le reconnaissent comme un doublon.
    event_id        text        NOT NULL UNIQUE,

    seq             bigint      NOT NULL GENERATED ALWAYS AS IDENTITY,
    aggregate_type  text        NOT NULL,
    aggregate_id    text        NOT NULL,
    event_type      text        NOT NULL,
    topic           text        NOT NULL,

    -- Toujours l'identifiant de l'agregat : c'est la cle de partition Kafka, donc ce qui
    -- garantit que deux evenements d'une meme transaction arrivent dans l'ordre.
    partition_key   text        NOT NULL,

    payload         jsonb       NOT NULL,
    headers         jsonb,

    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        int         NOT NULL DEFAULT 0,
    last_error      text,
    kafka_partition int,
    kafka_offset    bigint
);

-- Index partiel : le relais ne lit que les lignes en attente, et cette table sera
-- majoritairement composee de lignes deja publiees.
CREATE INDEX ix_outbox_pending ON payment.outbox_event (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published_at ON payment.outbox_event (published_at)
    WHERE published_at IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- Deduplication des messages consommes.
--
-- Kafka garantit une livraison AU MOINS UNE FOIS : un consommateur qui redemarre entre
-- le traitement d'un message et la validation de son offset le recevra a nouveau.
--
-- La ligne est inseree dans la MEME transaction que l'effet metier, et AVANT lui.
-- L'ordre compte : inserer apres rouvrirait exactement la fenetre que le pattern ferme.
-- -------------------------------------------------------------------------------------
CREATE TABLE payment.processed_message
(
    consumer_group text        NOT NULL,
    event_id       text        NOT NULL,
    event_type     text,
    processed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX ix_processed_message_at ON payment.processed_message (processed_at);

-- -------------------------------------------------------------------------------------
-- Journal d'audit. APPEND-ONLY (voir V3).
-- -------------------------------------------------------------------------------------
CREATE TABLE payment.audit_log
(
    id             uuid        NOT NULL PRIMARY KEY,
    seq            bigint      NOT NULL GENERATED ALWAYS AS IDENTITY,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_type     text        NOT NULL,
    actor_id       text        NOT NULL,
    action         text        NOT NULL,
    resource_type  text        NOT NULL,
    resource_id    text        NOT NULL,
    correlation_id text,
    payload        jsonb
);

CREATE UNIQUE INDEX ux_payment_audit_log_seq ON payment.audit_log (seq);
