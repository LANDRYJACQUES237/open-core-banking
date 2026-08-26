# ADR-0004 — payment-service appelle le grand livre en REST synchrone

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-22 |
| **Prise d'effet** | commit `cc7d2a0` |
| **Verifie par** | `CollectionFlowIT` |

## Contexte

C'est la seule liaison directe entre deux services de la plateforme. Tout le reste passe
par Kafka.

## Decision

`payment-service` appelle `ledger-service` en **REST synchrone**, avec une cle
d'idempotence, sous un jeton obtenu en `client_credentials`.

## Alternative ecartee

Une commande Kafka, et un evenement de reponse.

Elle a ete ecartee parce que le grand livre **rend un verdict** : le solde suffit-il,
l'ecriture est-elle equilibree, le compte est-il imputable. En asynchrone, il faudrait
repondre `202` au marchand, puis lui apprendre plus tard qu'il ne pouvait pas payer — et
gerer entre-temps une transaction dont on ne sait pas si elle existera.

Le decouplage aurait ete reel. Il aurait ete paye par une API qui ment sur ce qu'elle sait.

## Consequences

- Un appel reseau au milieu d'une transaction metier, donc un couplage temporel assume :
  grand livre indisponible, plus de paiement.
- Rendu acceptable par l'idempotence de l'appel : un rejeu ne produit pas de seconde
  ecriture. Les delais sont courts volontairement — mieux vaut laisser un message etre
  redelivre que tenir une transaction et un verrou de ligne ouverts.
- **Un cout qui n'avait pas ete vu :** l'ecriture distante est validee *avant* la
  transaction locale. Une panne entre les deux ouvre une fenetre de double debit. Voir
  [ADR-0010](0010-identifiant-derive-de-la-cle.md).

## Ce qui ferait revenir sur cette decision

Que le verdict du grand livre cesse d'etre necessaire a la reponse — par exemple si la
verification de solde migrait dans `payment-service` sur une replique de lecture, le grand
livre ne servant plus qu'a enregistrer. L'appel pourrait alors devenir une commande
asynchrone. Ce serait un deplacement de responsabilite, pas un simple changement de
transport.
