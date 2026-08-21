-- =====================================================================================
-- Grand livre en partie double.
--
-- Deux notions absentes de ce schema, volontairement :
--   * aucun champ "solde" : le solde est la somme des ecritures (voir V3 et la vue
--     de calcul cote applicatif). Un solde stocke et mis a jour est la premiere
--     source de divergence d'un systeme comptable.
--   * aucune donnee personnelle : le titulaire d'un compte n'est designe que par
--     owner_ref, une reference opaque. Ni MSISDN, ni identite, ni statut KYC.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS ledger;

-- -------------------------------------------------------------------------------------
-- Plan de comptes
-- -------------------------------------------------------------------------------------
CREATE TABLE ledger.account
(
    id              uuid        NOT NULL PRIMARY KEY,
    account_number  text        NOT NULL,
    account_type    text        NOT NULL,
    normal_side     char(2)     NOT NULL,
    currency        char(3)     NOT NULL,
    owner_ref       text,
    name            text,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    is_postable     boolean     NOT NULL DEFAULT true,
    parent_id       uuid REFERENCES ledger.account (id),
    idempotency_key text,
    opened_at       timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,

    -- Contraintes nommees explicitement : l'adaptateur de persistance distingue un
    -- rejeu idempotent d'un numero de compte deja pris en lisant le nom de la contrainte
    -- violee. Les noms generes par PostgreSQL suivraient une convention implicite que
    -- rien ne garantit stable.
    CONSTRAINT ux_account_number UNIQUE (account_number),
    CONSTRAINT ux_account_idempotency_key UNIQUE (idempotency_key),

    CONSTRAINT ck_account_number_format
        CHECK (account_number ~ '^[0-9]{4}(\.[A-Za-z0-9_-]{1,64})?$'),
    CONSTRAINT ck_account_type
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE', 'EQUITY')),
    CONSTRAINT ck_account_status
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_account_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    -- Le cote normal n'est pas une donnee libre : il decoule du type de compte.
    -- L'inscrire en contrainte empeche qu'un portefeuille client (LIABILITY) soit
    -- enregistre comme debiteur, erreur qui produirait un bilan faux sans jamais
    -- declencher d'echec visible.
    CONSTRAINT ck_account_normal_side_matches_type
        CHECK ((account_type IN ('ASSET', 'EXPENSE') AND normal_side = 'DR')
            OR (account_type IN ('LIABILITY', 'REVENUE', 'EQUITY') AND normal_side = 'CR'))
);

COMMENT ON COLUMN ledger.account.owner_ref IS
    'Reference opaque du titulaire. Le grand livre ne detient aucune donnee personnelle.';
COMMENT ON COLUMN ledger.account.is_postable IS
    'Faux pour les comptes de regroupement (ex. 2100). Une ecriture directe y est refusee.';

CREATE INDEX ix_account_owner_ref ON ledger.account (owner_ref) WHERE owner_ref IS NOT NULL;
CREATE INDEX ix_account_parent ON ledger.account (parent_id) WHERE parent_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- Ecritures. IMMUABLES (voir V2).
-- -------------------------------------------------------------------------------------
CREATE TABLE ledger.journal_entry
(
    id                  uuid        NOT NULL PRIMARY KEY,
    entry_seq           bigint      NOT NULL GENERATED ALWAYS AS IDENTITY,
    entry_ref           text        NOT NULL,

    -- Garde-fou ultime contre le double mouvement d'argent. Il est en base, pas dans
    -- la couche HTTP : meme si un appelant contourne l'API, rejoue une commande ou
    -- qu'un service redemarre au mauvais moment, la meme cle ne peut produire qu'une
    -- seule ecriture.
    idempotency_key     text        NOT NULL,

    -- Empreinte de la requete d'origine. Permet de distinguer un rejeu legitime
    -- (meme cle, meme contenu -> on renvoie l'ecriture existante) d'un bug appelant
    -- (meme cle, contenu different -> on refuse).
    request_fingerprint text        NOT NULL,

    transaction_ref     text,
    description         text        NOT NULL,
    value_date          date        NOT NULL,
    posted_at           timestamptz NOT NULL DEFAULT now(),
    source_service      text        NOT NULL,
    correlation_id      text,

    -- Une ecriture ne peut etre contre-passee qu'une seule fois. L'unicite rend la
    -- compensation d'une saga idempotente sans code supplementaire : rejouer la
    -- compensation trois fois ne produit qu'une contre-passation.
    reverses_entry_id   uuid REFERENCES ledger.journal_entry (id),

    created_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ux_journal_entry_ref UNIQUE (entry_ref),
    CONSTRAINT ux_journal_entry_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ux_journal_entry_reverses UNIQUE (reverses_entry_id),

    CONSTRAINT ck_journal_entry_not_self_reversing
        CHECK (reverses_entry_id IS NULL OR reverses_entry_id <> id)
);

CREATE UNIQUE INDEX ux_journal_entry_seq ON ledger.journal_entry (entry_seq);
CREATE INDEX ix_journal_entry_transaction_ref ON ledger.journal_entry (transaction_ref)
    WHERE transaction_ref IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- Lignes d'ecriture. IMMUABLES (voir V2).
-- -------------------------------------------------------------------------------------
CREATE TABLE ledger.posting_line
(
    id               uuid          NOT NULL PRIMARY KEY,
    journal_entry_id uuid          NOT NULL REFERENCES ledger.journal_entry (id),
    line_no          integer       NOT NULL,
    account_id       uuid          NOT NULL REFERENCES ledger.account (id),
    direction        char(2)       NOT NULL,
    amount           numeric(23, 4) NOT NULL,
    currency         char(3)       NOT NULL,

    -- La direction porte le signe, le montant reste toujours positif. C'est le modele
    -- comptable canonique : il rend l'invariant "somme des debits = somme des credits"
    -- verifiable directement, et interdit un montant negatif deguise en credit.
    signed_amount    numeric(23, 4) NOT NULL
        GENERATED ALWAYS AS (CASE WHEN direction = 'DR' THEN amount ELSE -amount END) STORED,

    CONSTRAINT ck_posting_line_direction CHECK (direction IN ('DR', 'CR')),
    CONSTRAINT ck_posting_line_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_posting_line_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ux_posting_line_no UNIQUE (journal_entry_id, line_no)
);

CREATE INDEX ix_posting_line_account ON ledger.posting_line (account_id);
CREATE INDEX ix_posting_line_entry ON ledger.posting_line (journal_entry_id);

-- -------------------------------------------------------------------------------------
-- Instantanes de solde.
--
-- Cache, jamais source de verite. La regle qui rend cette table legitime dans un
-- systeme "sans champ solde" : on doit pouvoir la vider entierement et retrouver
-- exactement les memes soldes. Un test le verifie.
--
-- raw_balance est la somme des signed_amount, donc exprimee dans le sens debiteur.
-- La conversion vers le sens normal du compte est faite dans le domaine, ou elle est
-- testable sans base.
-- -------------------------------------------------------------------------------------
CREATE TABLE ledger.account_balance_snapshot
(
    account_id      uuid           NOT NULL PRIMARY KEY REFERENCES ledger.account (id),
    up_to_entry_seq bigint         NOT NULL,
    raw_balance     numeric(23, 4) NOT NULL,
    computed_at     timestamptz    NOT NULL DEFAULT now()
);

-- -------------------------------------------------------------------------------------
-- Journal d'audit. APPEND-ONLY (voir V2).
--
-- Le scellement (chainage de hachage) vit dans une table separee : audit_log doit
-- rester strictement en insertion seule, or ecrire le hachage dans la ligne elle-meme
-- exigerait un UPDATE. Deux tables insert-only valent mieux qu'une table qu'on doit
-- autoriser a muter.
-- -------------------------------------------------------------------------------------
CREATE TABLE ledger.audit_log
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

CREATE UNIQUE INDEX ux_audit_log_seq ON ledger.audit_log (seq);

CREATE TABLE ledger.audit_seal
(
    audit_seq bigint      NOT NULL PRIMARY KEY REFERENCES ledger.audit_log (seq),
    prev_hash text,
    hash      text        NOT NULL,
    sealed_at timestamptz NOT NULL DEFAULT now()
);
