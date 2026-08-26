# ADR-0008 — Un timeout ne conclut rien : UNRESOLVED n'est pas FAILED

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-24 |
| **Prise d'effet** | commit `e52cea6` |
| **Verifie par** | `CollectionExecutionIT` |

## Contexte

L'operateur ne repond pas. La question n'est pas « la requete a-t-elle echoue » mais
« l'argent est-il parti ». Ce sont deux questions differentes, et le silence ne repond ni a
l'une ni a l'autre.

## Decision

Un delai depasse produit l'etat **`UNRESOLVED`**. Cet etat est resolu par **interrogation de
statut**, avec un budget borne. Budget epuise sans reponse concluante : `MANUAL_REVIEW`.

**Jamais `FAILED`.**

Une erreur `5xx` de l'operateur suit exactement le meme chemin : elle n'est pas un refus.
Seul un refus **explicite** en est un.

## Alternative ecartee

Traiter le silence comme un echec, et liberer les fonds.

Elle a ete ecartee parce que le cout d'une erreur est **asymetrique**. Considerer a tort
qu'un decaissement a echoue conduit a le compenser, donc a rembourser un client qui a
peut-etre deja recu l'argent : on paie deux fois. Considerer a tort qu'il a reussi laisse
une transaction ouverte qu'un humain finira par regarder.

Entre une perte seche et une question en attente, on choisit la question.

## Consequences

- Deux chemins de resolution coexistent : les callbacks signes et l'interrogation de
  statut. Aucun n'est fiable seul — un callback peut ne jamais arriver, une interrogation
  peut rester sans reponse exploitable.
- `MANUAL_REVIEW` est un etat de premiere classe, avec ses quatre sorties : un humain peut
  conclure dans les quatre sens.
- Les tests portent l'argument dans leurs noms : `operatorHangsAndNothingIsConcluded`,
  `serverErrorIsNotARejection`, `exhaustedBudgetYieldsUnresolvedNotFailure`.

## Ce qui ferait revenir sur cette decision

Un operateur offrant une API de statut a coherence forte, sans limite de debit et sans
fenetre d'incertitude. Le budget d'interrogation pourrait alors devenir illimite, et
`MANUAL_REVIEW` ne serait plus atteint par epuisement. Ni MTN ni Orange ne l'offrent, et
c'est precisement pour cela que cet etat existe.
