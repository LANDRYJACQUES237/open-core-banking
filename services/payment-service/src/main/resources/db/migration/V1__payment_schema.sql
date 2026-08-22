-- =====================================================================================
-- Orchestration des paiements.
--
-- Ce service ne detient aucune verite financiere : les soldes et les ecritures
-- appartiennent au grand livre. Il detient l'etat d'avancement d'une operation, ce qui
-- est une donnee differente et tout aussi critique — c'est elle qui dit si l'argent a
-- bouge, s'il est en vol, ou si personne ne sait.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS payment;

-- -------------------------------------------------------------------------------------
-- Transaction de paiement.
-- -------------------------------------------------------------------------------------
CREATE TABLE payment.payment_transaction
(
    id                 uuid           NOT NULL PRIMARY KEY,
    external_ref       text           NOT NULL,
    type               text           NOT NULL,
    status             text           NOT NULL,

    amount             numeric(23, 4) NOT NULL,
    currency           char(3)        NOT NULL,
    platform_fee       numeric(23, 4) NOT NULL,
    provider_fee       numeric(23, 4),

    wallet_account_ref text           NOT NULL,
    provider_code      text           NOT NULL,

    -- Seule la forme masquee est conservee.
    --
    -- Le schema de cadrage prevoyait un numero chiffre au repos. Ne pas le conserver du
    -- tout est plus fort : une donnee absente ne fuite pas, ne s'exporte pas dans un dump
    -- et ne demande aucune gestion de cle. Le numero complet transite une seule fois,
    -- dans la commande adressee a l'adaptateur operateur, qui en a reellement besoin.
    masked_msisdn      text,

    provider_ref       text,
    ledger_entry_ref   text,
    failure_code       text,
    failure_reason     text,

    created_at         timestamptz    NOT NULL DEFAULT now(),
    updated_at         timestamptz    NOT NULL DEFAULT now(),
    version            bigint         NOT NULL DEFAULT 0,

    CONSTRAINT ck_transaction_type CHECK (type IN ('COLLECTION', 'DISBURSEMENT', 'TRANSFER')),
    CONSTRAINT ck_transaction_status CHECK (status IN (
        'CREATED', 'PENDING_PROVIDER', 'PROVIDER_ACCEPTED', 'PROVIDER_CONFIRMED',
        'PROVIDER_DECLINED', 'POSTING', 'COMPENSATING', 'MANUAL_REVIEW',
        'COMPLETED', 'FAILED', 'REVERSED')),
    CONSTRAINT ck_transaction_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transaction_fee_not_negative CHECK (platform_fee >= 0),
    CONSTRAINT ck_transaction_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_transaction_external_ref ON payment.payment_transaction (external_ref);
CREATE INDEX ix_transaction_status ON payment.payment_transaction (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'REVERSED');
CREATE INDEX ix_transaction_provider_ref ON payment.payment_transaction (provider_ref)
    WHERE provider_ref IS NOT NULL;

COMMENT ON INDEX payment.ix_transaction_status IS
    'Index partiel sur les seules transactions non terminees : ce sont les seules que la
     supervision et la reconciliation interrogent. Les terminees, majoritaires a terme,
     n''ont pas a alourdir l''index.';

-- -------------------------------------------------------------------------------------
-- Historique des transitions d'etat. APPEND-ONLY (voir V3).
--
-- On journalise aussi les transitions REFUSEES, et c'est le point important.
--
-- Un callback duplique ou tardif ne doit rien changer, mais le fait qu'il soit arrive
-- doit rester visible. Sans cette trace, la neutralisation d'un doublon serait invisible
-- et impossible a demontrer autrement que par un log, c'est-a-dire par quelque chose qui
-- aura ete purge le jour ou on en aura besoin.
-- -------------------------------------------------------------------------------------
CREATE TABLE payment.transaction_state_transition
(
    id               uuid        NOT NULL PRIMARY KEY,
    seq              bigint      NOT NULL GENERATED ALWAYS AS IDENTITY,
    transaction_id   uuid        NOT NULL REFERENCES payment.payment_transaction (id),
    from_status      text,
    to_status        text        NOT NULL,
    trigger_event    text        NOT NULL,
    accepted         boolean     NOT NULL,
    rejection_reason text,
    correlation_id   text,
    occurred_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_transition_transaction ON payment.transaction_state_transition (transaction_id, seq);

-- -------------------------------------------------------------------------------------
-- Idempotence de la couche HTTP.
--
-- Semantique retenue, calquee sur celle de Stripe :
--   cle inconnue                      -> traitement normal, reponse memorisee
--   cle connue + meme empreinte       -> la reponse memorisee est rejouee a l'identique
--   cle connue + empreinte differente -> 422, c'est un bug appelant et non un rejeu
--   cle connue + traitement en cours  -> 409, l'appelant doit reessayer
--
-- Ce n'est pas la seule protection. Le grand livre porte sa propre contrainte d'unicite
-- sur la cle d'idempotence de l'ecriture : meme si cette couche etait contournee, aucun
-- second mouvement d'argent ne pourrait etre enregistre.
-- -------------------------------------------------------------------------------------
CREATE TABLE payment.idempotency_record
(
    id            uuid        NOT NULL PRIMARY KEY,
    scope         text        NOT NULL,
    key           text        NOT NULL,
    request_hash  text        NOT NULL,
    status        text        NOT NULL,
    http_status   int,
    response_body jsonb,
    resource_id   uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz,
    expires_at    timestamptz NOT NULL DEFAULT now() + interval '30 days',

    -- Le scope est l'identifiant du client appelant. Sans lui, deux clients qui
    -- choisissent la meme cle — ce qui arrive des qu'un client utilise des compteurs
    -- plutot que des UUID — se voleraient mutuellement leurs reponses.
    CONSTRAINT ux_idempotency_scope_key UNIQUE (scope, key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX ix_idempotency_expiry ON payment.idempotency_record (expires_at);
