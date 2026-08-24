-- =====================================================================================
-- Adaptateur des operateurs Mobile Money.
--
-- Ce service ne detient aucune verite metier : ni solde, ni statut de paiement. Il
-- detient l'etat du DIALOGUE avec un operateur — ce qu'on lui a demande, ce qu'il a
-- repondu, combien de fois on a insiste, et depuis quand on attend.
--
-- C'est la seule base de la plateforme qui contient un numero de telephone en clair,
-- et c'est assume : sans lui, impossible de relancer une operation aupres de
-- l'operateur. payment-service, lui, n'en garde que la forme masquee.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS provider;

-- -------------------------------------------------------------------------------------
-- Operation aupres d'un operateur.
-- -------------------------------------------------------------------------------------
CREATE TABLE provider.provider_operation
(
    id                    uuid           NOT NULL PRIMARY KEY,
    transaction_id        uuid           NOT NULL,
    provider_code         text           NOT NULL,
    operation_type        text           NOT NULL,
    external_ref          text           NOT NULL,

    -- Cle transmise a l'operateur. Une relance apres expiration du delai ne doit pas
    -- creer un second paiement chez lui : c'est elle qui l'en empeche.
    provider_idempotency_key text        NOT NULL,

    payer_msisdn          text,
    amount                numeric(23, 4) NOT NULL,
    currency              char(3)        NOT NULL,

    provider_ref          text,
    status                text           NOT NULL,
    provider_fee          numeric(23, 4),
    error_code            text,
    error_message         text,
    last_error            text,

    -- Nombre d'appels sortants, relances de statut comprises.
    attempt_count         integer        NOT NULL DEFAULT 0,
    poll_attempts         integer        NOT NULL DEFAULT 0,
    last_polled_at        timestamptz,

    -- Nul quand plus aucune relance n'est prevue : soit l'operation est resolue, soit
    -- le budget est epuise. C'est ce champ qui pilote l'ordonnanceur.
    next_poll_at          timestamptz,
    poll_budget_exhausted boolean        NOT NULL DEFAULT false,

    created_at            timestamptz    NOT NULL DEFAULT now(),
    updated_at            timestamptz    NOT NULL DEFAULT now(),
    version               bigint         NOT NULL DEFAULT 0,

    -- Une seule operation par transaction et par operateur. C'est le garde-fou ultime
    -- contre le double prelevement : meme si la commande Kafka etait livree deux fois,
    -- aucune seconde operation ne pourrait naitre.
    CONSTRAINT ux_operation_transaction UNIQUE (provider_code, transaction_id),

    CONSTRAINT ck_operation_status CHECK (status IN
        ('PENDING', 'ACCEPTED', 'SUCCEEDED', 'FAILED', 'UNRESOLVED')),
    CONSTRAINT ck_operation_type CHECK (operation_type IN ('COLLECTION', 'DISBURSEMENT')),
    CONSTRAINT ck_operation_amount_positive CHECK (amount > 0)
);

-- Index partiel sur les seules operations a relancer. L'ordonnanceur interroge cette
-- table en boucle ; sans ce filtre, il parcourrait un historique qui ne fera que grossir.
CREATE INDEX ix_operation_due ON provider.provider_operation (next_poll_at)
    WHERE next_poll_at IS NOT NULL;

CREATE INDEX ix_operation_provider_ref ON provider.provider_operation (provider_ref)
    WHERE provider_ref IS NOT NULL;
CREATE INDEX ix_operation_external_ref ON provider.provider_operation (external_ref);

COMMENT ON COLUMN provider.provider_operation.status IS
    'UNRESOLVED n''est pas un echec : le budget de relance est epuise sans statut
     definitif, et l''argent a peut-etre bouge. Seule la reconciliation tranche.';

-- -------------------------------------------------------------------------------------
-- Rappels entrants. APPEND-ONLY (voir V2).
--
-- On conserve le message BRUT, signature comprise, y compris quand la signature est
-- invalide. Deux raisons : reconstituer ce que l'operateur a reellement envoye lors
-- d'un litige, et disposer d'une trace des tentatives non authentifiees.
-- -------------------------------------------------------------------------------------
CREATE TABLE provider.provider_callback
(
    id                uuid        NOT NULL PRIMARY KEY,
    seq               bigint      NOT NULL GENERATED ALWAYS AS IDENTITY,
    provider_code     text        NOT NULL,
    provider_event_id text        NOT NULL,
    external_ref      text,
    transaction_id    uuid,
    signature         text,
    signature_valid   boolean     NOT NULL,
    raw_payload       text        NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now(),
    processed         boolean     NOT NULL DEFAULT false,

    -- Les operateurs rejouent leurs rappels, parfois des heures plus tard. C'est ici
    -- que le doublon est reconnu, avant meme d'atteindre la logique metier.
    CONSTRAINT ux_callback_event UNIQUE (provider_code, provider_event_id)
);

CREATE INDEX ix_callback_unprocessed ON provider.provider_callback (seq)
    WHERE processed = false AND signature_valid = true;

-- -------------------------------------------------------------------------------------
-- Tables transverses.
-- -------------------------------------------------------------------------------------
CREATE TABLE provider.outbox_event
(
    id              uuid        NOT NULL PRIMARY KEY,
    event_id        text        NOT NULL UNIQUE,
    seq             bigint      NOT NULL GENERATED ALWAYS AS IDENTITY,
    aggregate_type  text        NOT NULL,
    aggregate_id    text        NOT NULL,
    event_type      text        NOT NULL,
    topic           text        NOT NULL,
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

CREATE INDEX ix_provider_outbox_pending ON provider.outbox_event (seq)
    WHERE published_at IS NULL;

CREATE TABLE provider.processed_message
(
    consumer_group text        NOT NULL,
    event_id       text        NOT NULL,
    event_type     text,
    processed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE provider.audit_log
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

CREATE UNIQUE INDEX ux_provider_audit_seq ON provider.audit_log (seq);
