# ADR-0007 — L'interdiction de decouvert vit dans payment-service, sous verrou de base

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-25 |
| **Prise d'effet** | commit `c4c4746` |
| **Verifie par** | `DisbursementConcurrencyIT`, `PgAdvisoryWalletLockTest` |

## Contexte

Deux decaissements simultanes sur un portefeuille qui ne peut en financer qu'un : un seul
doit passer. Reste a decider **ou** la regle vit, et **comment** elle tient sous
concurrence.

## Decision

La regle vit dans `payment-service`. Le solde est lu et l'engagement pose **sous un verrou
consultatif PostgreSQL** derive de la reference du portefeuille :
`pg_advisory_xact_lock(hashtextextended(portefeuille, graine))`.

## Alternative ecartee (a) — un garde-fou dans le grand livre

Le grand livre **enregistre, il ne juge pas**. Un controle de decouvert cote comptabilite
l'empecherait un jour d'enregistrer un solde negatif parfaitement legitime : des frais
appliques apres coup, une regularisation, une correction. Ce n'est pas son role de decider
ce qui a le droit d'exister.

## Alternative ecartee (b) — un verrou en memoire

`synchronized` ou `ReentrantLock` fonctionnent **sur une instance** et echouent **en
silence** des qu'il y en a deux. Le mode de defaillance est le pire possible : la regle
parait tenir en developpement, sur une seule replique, et cede en production sans rien
signaler.

## Pourquoi un verrou consultatif plutot que `SELECT ... FOR UPDATE`

Le portefeuille n'a pas de ligne a lui dans `payment-db` : ce n'est qu'une reference vers un
compte du grand livre. Verrouiller une ligne demanderait d'inventer une table dont le seul
role serait d'etre verrouillee. Un verrou consultatif derive de la reference obtient la meme
serialisation sans ce detour.

Sa portee est la **transaction** : il est relache au commit **comme au rollback**. Un verrou
de session laisse par une transaction annulee bloquerait le portefeuille jusqu'au
redemarrage.

Le verrou refuse de se poser hors transaction, et le dit : demande ainsi, il serait relache
immediatement et ne protegerait rien.

## Consequences

- Les decaissements d'un meme portefeuille se serialisent ; ceux de portefeuilles
  differents ne se genent pas.
- Verifie sous concurrence reelle, sur le modele des trente-deux requetes simultanees de
  `ConcurrentIdempotencyIT` : deux decaissements sur un portefeuille qui ne peut en financer
  qu'un, un seul passe. Sans ce test, la regle ne serait qu'une intention.

## Ce qui ferait revenir sur cette decision

Que les portefeuilles acquierent une ligne propre dans `payment-db`. `SELECT ... FOR UPDATE`
sur cette ligne serait alors plus lisible qu'un hachage, et surtout **visible dans un plan
d'execution** — ce qu'un verrou consultatif n'est pas.
