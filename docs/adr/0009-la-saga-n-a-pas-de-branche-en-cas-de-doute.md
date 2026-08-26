# ADR-0009 — La saga n'a pas de branche « en cas de doute », et 1900 est son registre

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-25 |
| **Prise d'effet** | commit `c4c4746` |
| **Verifie par** | `DisbursementSagaIT` |

## Contexte

Le decaissement engage les fonds **avant** d'appeler l'operateur : on ne peut pas envoyer de
l'argent qui n'a pas ete preleve. Cette inversion est ce qui rend une saga necessaire — et
elle pose la question de savoir **quand** compenser.

## Decision

La compensation ne s'execute que sur un refus **etabli**. `UNRESOLVED` ne compense
**jamais**.

Et il n'existe **aucune table d'etat de saga**. Le compte de passage **1900** porte les
fonds engages et non livres.

## Alternative ecartee (a) — compenser au bout d'un delai

« Au bout de N minutes sans nouvelle, on rembourse, au moins on ne perd pas d'argent. »

On en perd — l'autre fois. Cette branche traite une incertitude comme une certitude, et
c'est exactement ce que [ADR-0008](0008-un-timeout-ne-conclut-rien.md) refuse. Une saga qui
compense dans le doute finit par payer deux fois, systematiquement, sur la queue de
distribution.

## Alternative ecartee (b) — une table d'etat de saga

Un enregistrement `saga_state` a cote des transactions, avec ses etapes et ses
compensations.

Ecartee parce qu'un etat de saga range **a cote** de la comptabilite peut **diverger**
d'elle : deux sources de verite sur la meme question, et rien pour arbitrer quand elles ne
disent pas la meme chose.

## Le compte 1900 comme registre

Tout montant qui stationne en 1900 est une question ouverte, et **son solde est la liste des
decaissements en vol**. Ce registre ne peut pas diverger de la comptabilite, puisqu'il *est*
la comptabilite.

Il se lit avec les memes outils que le reste, se rapproche avec les memes rapprochements, et
un solde anormal se voit dans un bilan sans qu'il faille interroger un systeme
supplementaire.

## Consequences

- Un decaissement dont l'issue reste inconnue laisse son montant en 1900 et passe en
  `MANUAL_REVIEW`. C'est visible, chiffre, et cela ne se resout pas tout seul — c'est voulu.
- Le solde de 1900 est une metrique d'exploitation : il ne devrait jamais croitre
  durablement.

## Ce qui ferait revenir sur cette decision

Qu'une saga comporte des etapes **sans traduction comptable** — une verification externe, un
appel a un service de conformite. 1900 cesserait alors de suffire comme registre, puisqu'une
etape ne s'y inscrirait pas, et une table d'etat deviendrait necessaire. Aucune etape
actuelle n'est dans ce cas.
