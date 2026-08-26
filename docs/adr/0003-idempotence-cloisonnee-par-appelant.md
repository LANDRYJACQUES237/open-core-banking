# ADR-0003 — L'idempotence est cloisonnee par appelant

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-24 |
| **Prise d'effet** | commit `13dfacc` |
| **Verifie par** | `PaymentSecurityIT`, `ConcurrentIdempotencyIT` |

## Contexte

La cle d'idempotence est **choisie par le client**. Rien n'empeche deux marchands de
choisir la meme : `commande-1` est un nom que tout le monde trouve.

## Decision

La contrainte d'unicite porte sur `(scope, key)`, ou `scope` est le **sujet du jeton**.
Deux marchands qui presentent la meme cle obtiennent deux transactions distinctes ; le meme
marchand qui rejoue sa cle retrouve la sienne.

## Alternative ecartee

Une cle globale, unique pour toute la plateforme. Elle transforme une collision de nommage
entre clients etrangers en **fuite de transaction** : le second appelant recoit la
transaction du premier, avec son montant et sa reference.

## Ce que cet ADR enregistre vraiment

La contrainte `UNIQUE (scope, key)` existait depuis la Phase 2. Le controleur, lui, passait
une constante — `anonymous`. Le schema portait la regle, le code la contournait, et rien
n'echouait.

C'est la forme la plus trompeuse qu'un garde-fou puisse prendre : present dans la structure,
absent du chemin d'execution, invisible a la relecture. Le test qui manquait n'etait pas
« la meme cle deux fois renvoie la meme transaction » — il passait — mais **« deux appelants
differents avec la meme cle obtiennent deux transactions »**.

## Consequences

- Toute requete idempotente exige un jeton : sans sujet, pas de portee.
  `CallerIdentity` leve plutot que de retomber sur une valeur par defaut.
- La contre-epreuve fait partie de la suite : meme cle, deux sujets, deux transactions.

## Ce qui ferait revenir sur cette decision

Un client legitime devant rejouer une requete depuis **deux identites techniques
differentes** — une rotation de compte de service en cours de rejeu, par exemple. Il
faudrait alors une portee explicite portee par la requete, plutot que deduite du jeton. Ce
serait un elargissement de la regle, pas son abandon.
