# ADR-0001 — Le grand livre ne detient aucune donnee personnelle

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-21 |
| **Prise d'effet** | commit `ab0703a` |
| **Verifie par** | `LedgerArchitectureTest` |

## Contexte

Un compte doit designer son titulaire. Le decoupage initialement propose confiait a
`ledger-service` les « comptes clients et portefeuilles electroniques » — donc le numero de
telephone, l'identite, le statut de connaissance client.

## Decision

Un compte du grand livre designe son titulaire par une **reference opaque**. Ni numero, ni
nom, ni identite, ni statut KYC. Le grand livre ne sait pas qui est `WALLET-8F3A`, et n'a
pas a le savoir.

## Alternative ecartee

Faire porter la donnee client par le grand livre, et economiser un service.

Elle a ete ecartee pour une raison qui n'est pas l'esthetique du decoupage : **une donnee
personnelle dans un journal immuable est une contradiction insoluble.** Le grand livre
refuse `UPDATE` et `DELETE` par construction (voir [ADR-0002](0002-immuabilite-a-deux-couches.md)) ;
une demande d'effacement se heurterait alors a une table concue pour ne jamais ceder. Il
faudrait choisir entre trahir l'immuabilite et ignorer l'obligation.

Le probleme ne se pose pas si la donnee n'est jamais entree.

## Consequences

- Afficher un releve nominatif demande une jointure avec un service qui detient l'identite.
  Ce service n'existe pas encore ; sa place est reservee, et son extraction ne touchera pas
  la comptabilite.
- `payment-service` masque le numero du payeur dans ses reponses et ne le conserve pas.
- Une purge de donnees personnelles n'a aucun effet sur les ecritures : il n'y en a pas.

## Ce qui ferait revenir sur cette decision

Une obligation reglementaire imposant que **l'ecriture comptable elle-meme** porte
l'identite du titulaire — certaines juridictions l'exigent pour la piste d'audit. Il
faudrait alors chiffrer ce champ avec une cle destructible, de sorte que detruire la cle
vaille effacement sans toucher a la ligne. C'est une mecanique lourde, et c'est
precisement ce que cette decision evite tant qu'elle n'est pas imposee.
