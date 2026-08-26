# ADR-0002 — L'immuabilite vit a deux couches, et celui qui migre n'est pas celui qui sert

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-21 |
| **Prise d'effet** | commit `ab0703a` |
| **Verifie par** | `ImmutabilityIT` |

## Contexte

Un grand livre corrigible n'est pas un grand livre. Une correction se fait par
contre-passation — une ecriture qui en annule une autre — jamais par mutation de la ligne
d'origine.

Reste a le garantir autrement que par la discipline du code appelant.

## Decision

Deux couches independantes, plus une separation d'identite.

1. **Des declencheurs PostgreSQL** refusent `UPDATE` et `DELETE` sur `journal_entry` et
   `posting_line`, avec un message qui dit quoi faire a la place.
2. **Des droits** : l'utilisateur qui fait tourner l'application ne recoit que `SELECT` et
   `INSERT`. Il n'a pas de quoi essayer.
3. **Les migrations tournent sous un autre utilisateur** — le proprietaire du schema — dans
   une autre image, et en Kubernetes dans un autre pod.

## Alternative ecartee

**Une seule couche.** Chacune prise seule laisse un trou :

- Les droits seuls n'arretent pas le proprietaire du schema, et c'est lui qui migre.
- Les declencheurs seuls n'arretent personne qui puisse faire `DROP TRIGGER` — c'est-a-dire
  le proprietaire, encore.

C'est la troisieme mesure qui ferme la boucle : puisque l'application ne s'execute jamais
sous le proprietaire, elle ne peut ni contourner les declencheurs ni les retirer.

**L'immuabilite au niveau applicatif seulement** a ete ecartee pour une raison plus simple :
elle ne protege que du code que l'on ecrit, pas d'un `psql` ouvert un soir d'incident.

## Consequences

- Flyway **ne peut pas** tourner dans le processus applicatif : il lui faudrait le mot de
  passe du proprietaire. C'est cette contrainte qui a produit l'image de migration separee
  et le `Job` Helm en hook.
- Le chart refuse de se generer si les deux `Secret` designent le meme objet.
- Verifie sur la plateforme assemblee : l'utilisateur applicatif recoit
  `permission denied`, le proprietaire recoit `LEDGER_IMMUTABLE`.

## Ce qui ferait revenir sur cette decision

Une obligation de retention imposant d'effacer des ecritures apres N annees. La reponse ne
serait pas d'assouplir les droits d'execution, mais une procedure d'archivage documentee et
auditee, executee sous le proprietaire — donc hors de portee de l'application, ce que cette
decision garantit deja.
