# ADR-0006 — Le transfert de portefeuille a portefeuille n'est pas une saga

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-25 |
| **Prise d'effet** | commit `5999185` |
| **Verifie par** | `TransferIT` |

## Contexte

Le decoupage initialement propose placait la saga avec compensation sur le **transfert**.
C'est l'exemple canonique des cours d'architecture : debiter ici, crediter la, compenser si
le second echoue.

## Decision

Le transfert est **une seule ecriture equilibree** dans **une seule base**, sous une
transaction ACID. Aucune saga, aucun etat intermediaire, aucune compensation.

## Alternative ecartee

La saga avec compensation.

Elle a ete ecartee parce que les deux portefeuilles sont des comptes du **meme** grand
livre, dans la **meme** base. Il n'y a pas de frontiere transactionnelle a franchir. Y
mettre une saga ajouterait des etats intermediaires, une compensation a ecrire et a tester,
et une fenetre pendant laquelle l'argent n'est nulle part — pour resoudre un probleme qui
n'existe pas.

Une saga n'est pas un signe de maturite architecturale. C'est le prix qu'on paie quand
aucune transaction n'est possible.

## Ce que cette absence rend visible

C'est le vrai interet de la decision. En livrant le transfert **sans** saga a cote du
decaissement **avec** saga, le depot montre que la saga du decaissement n'est pas un
reflexe : elle est la consequence d'une frontiere reelle — un systeme externe qui ne
participe a aucune transaction et qui peut ne pas repondre.

Ce qui reste commun aux deux : l'interdiction de decouvert, la cle d'idempotence, la
machine a etats. Le transfert passe simplement de `CREATED` a `POSTING` sans jamais voir un
operateur.

## Ce qui ferait revenir sur cette decision

Que les portefeuilles cessent de vivre dans le meme grand livre — un partitionnement par
region ou par devise, par exemple. La transaction ACID disparaitrait, et la saga
deviendrait necessaire. Elle serait alors justifiee par une contrainte, pas par un modele.
