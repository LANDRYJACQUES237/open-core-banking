-- =====================================================================================
-- Plan de comptes initial (zone CEMAC, XAF).
--
-- Le point le moins intuitif, et le plus important : un portefeuille client est un
-- compte de PASSIF. L'argent depose par un client est une dette de la plateforme
-- envers lui, pas un actif de la plateforme. Crediter un client augmente notre dette.
-- Modeliser le portefeuille en actif produit un bilan faux qui ne se remarque qu'a
-- l'audit, quand il est trop tard.
--
-- Les portefeuilles individuels (2100.xxx) ne sont pas semes : ils sont ouverts a la
-- demande via POST /v1/accounts, sous le compte de regroupement 2100.
--
-- Les identifiants sont deterministes plutot que tires au hasard : un test, un script
-- de diagnostic ou un jeu de donnees de demonstration peut les referencer directement.
-- =====================================================================================

INSERT INTO ledger.account (id, account_number, account_type, normal_side, currency,
                            name, is_postable, idempotency_key)
VALUES ('00000000-0000-4000-8000-000000001100', '1100', 'ASSET', 'DR', 'XAF',
        'Float MTN Mobile Money', true, 'seed:1100'),

       ('00000000-0000-4000-8000-000000001101', '1101', 'ASSET', 'DR', 'XAF',
        'Float Orange Money', true, 'seed:1101'),

       ('00000000-0000-4000-8000-000000001200', '1200', 'ASSET', 'DR', 'XAF',
        'Compte bancaire de reglement', true, 'seed:1200'),

       -- Compte de passage : porte les fonds engages dans un decaissement, deja debites
       -- du portefeuille client mais pas encore livres par l'operateur. C'est lui qui
       -- rend la saga de decaissement lisible : tant qu'un montant y stationne, une
       -- operation est en vol.
       ('00000000-0000-4000-8000-000000001900', '1900', 'ASSET', 'DR', 'XAF',
        'Compte de passage decaissements', true, 'seed:1900'),

       -- Compte de regroupement des portefeuilles clients. Non postable : une ecriture
       -- doit designer un portefeuille precis, jamais l'agregat.
       ('00000000-0000-4000-8000-000000002100', '2100', 'LIABILITY', 'CR', 'XAF',
        'Portefeuilles clients (regroupement)', false, 'seed:2100'),

       ('00000000-0000-4000-8000-000000002900', '2900', 'LIABILITY', 'CR', 'XAF',
        'Encaissements non affectes', true, 'seed:2900'),

       ('00000000-0000-4000-8000-000000004100', '4100', 'REVENUE', 'CR', 'XAF',
        'Produits de commissions', true, 'seed:4100'),

       ('00000000-0000-4000-8000-000000005100', '5100', 'EXPENSE', 'DR', 'XAF',
        'Charges de commissions operateur', true, 'seed:5100');
