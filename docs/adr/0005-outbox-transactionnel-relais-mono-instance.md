# ADR-0005 — Les evenements sortent par un outbox, relaye en une seule instance

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-22 |
| **Prise d'effet** | commit `cc7d2a0` |
| **Verifie par** | `OutboxAtomicityIT` |

## Contexte

Ecrire en base **et** publier dans Kafka dans le meme geste, c'est le dual-write : deux
systemes, aucune transaction commune, et un arret entre les deux qui laisse l'un sans
l'autre.

## Decision

La transaction metier ecrit une **ligne d'outbox**, rien de plus. Un relais separe publie
ensuite, dans l'ordre de `seq`, en envoi **synchrone**, et ne marque `published_at`
qu'apres succes.

## Alternatives ecartees

**Publier apres le commit.** Un arret entre le commit et l'envoi perd l'evenement, sans
trace. C'est une livraison *au plus une fois* : une perte silencieuse, inacceptable pour un
mouvement d'argent.

**Marquer publie avant d'envoyer.** Meme defaut, deplace.

**Envoi asynchrone dans le relais.** Le marquage deviendrait independant de la reussite
reelle : le dual-write reintroduit a l'interieur meme du relais.

## Consequences assumees

- **Livraison au moins une fois.** Un arret entre l'envoi et le marquage republie
  l'evenement. C'est attendu : le meme `eventId` repart, et les consommateurs idempotents
  l'absorbent.
- **Un echec interrompt le lot** plutot que de passer au suivant : publier l'evenement 6
  alors que le 5 n'est pas parti livrerait les faits dans le desordre a un consommateur qui
  n'a aucun moyen de le savoir.
- **Le relais tourne en une seule instance.** `FOR UPDATE SKIP LOCKED` permettrait a
  plusieurs relais de travailler en parallele, mais alors rien ne garantirait l'ordre par
  agregat. Le chart Helm en tire `replicas: 1` et une strategie `Recreate` pour les deux
  services concernes — un `RollingUpdate` ferait coexister deux relais le temps d'une
  bascule.

## Ce qui ferait revenir sur cette decision

Le jour ou un seul relais ne suivrait plus, la sortie **n'est pas** d'augmenter les
repliques. Ce serait, dans l'ordre : extraire le relais du service, lui donner une election
de leader, ou passer au CDC. La table respecte deja la convention de l'Outbox Event Router
de Debezium — ce serait un remplacement de composant, pas une reecriture.
