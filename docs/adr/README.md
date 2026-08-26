# Decisions d'architecture

Dix decisions, celles qui avaient une **tension reelle** : une alternative defendable a ete
ecartee, et l'ecarter a coute quelque chose.

Il n'y a pas d'ADR par fonctionnalite. Un document qui enregistre un choix sans alternative
n'enregistre rien.

## La regle

Chaque ADR doit nommer trois choses. S'il ne peut pas, il n'est pas ecrit.

1. **L'alternative reellement ecartee** — pas un homme de paille.
2. **Ce que la decision coute**, et non seulement ce qu'elle apporte.
3. **Ce qui la ferait revenir.** Une decision sans condition de retour est un dogme.

Chaque ADR pointe aussi le **commit** ou la decision a pris effet, et le **test** qui la
verifie.

## Une reserve, posee franchement

Ces ADR ont ete rediges en Phase 6, **apres** les decisions. Le document de cadrage de la
Phase 0 mettait en garde contre exactement cela, et il avait raison : un ADR ecrit apres
coup justifie au lieu d'enregistrer.

Deux choses limitent la derive, sans l'annuler. Les **messages de commit** sont
contemporains des decisions et portent deja le raisonnement — chaque ADR renvoie au sien, et
`git show` est plus difficile a reecrire qu'un document. Et l'obligation de nommer une
condition de retour interdit la forme la plus commode de la justification retrospective,
celle qui presente un choix comme evident.

Les ADR a venir seront ecrits au moment de la decision.

## Les dix

| | Decision | Date |
|---|---|---|
| [ADR-0001](0001-grand-livre-sans-donnee-personnelle.md) | Le grand livre ne detient aucune donnee personnelle | 2026-08-21 |
| [ADR-0002](0002-immuabilite-a-deux-couches.md) | L'immuabilite vit a deux couches, et celui qui migre n'est pas celui qui sert | 2026-08-21 |
| [ADR-0003](0003-idempotence-cloisonnee-par-appelant.md) | L'idempotence est cloisonnee par appelant | 2026-08-24 |
| [ADR-0004](0004-appel-synchrone-vers-le-grand-livre.md) | payment-service appelle le grand livre en REST synchrone | 2026-08-22 |
| [ADR-0005](0005-outbox-transactionnel-relais-mono-instance.md) | Les evenements sortent par un outbox, relaye en une seule instance | 2026-08-22 |
| [ADR-0006](0006-le-transfert-n-est-pas-une-saga.md) | Le transfert de portefeuille a portefeuille n'est pas une saga | 2026-08-25 |
| [ADR-0007](0007-decouvert-interdit-dans-payment-service.md) | L'interdiction de decouvert vit dans payment-service, sous verrou de base | 2026-08-25 |
| [ADR-0008](0008-un-timeout-ne-conclut-rien.md) | Un timeout ne conclut rien : UNRESOLVED n'est pas FAILED | 2026-08-24 |
| [ADR-0009](0009-la-saga-n-a-pas-de-branche-en-cas-de-doute.md) | La saga n'a pas de branche « en cas de doute », et 1900 est son registre | 2026-08-25 |
| [ADR-0010](0010-identifiant-derive-de-la-cle.md) | L'identifiant de transaction est derive de la cle d'idempotence | 2026-08-25 |

## Ou lire le reste

- [Architecture du systeme construit](../ARCHITECTURE.md) — niveaux C4, machine a etats,
  decaissement de bout en bout
- [Demarrage](../DEMARRAGE.md) — et le parcours qui verifie ces decisions sur la plateforme
  assemblee
- [Document de cadrage de la Phase 0](../00-architecture-phase0.md) — historique, conserve
  tel qu'il a ete ecrit
- [Deploiement](../../deploy/README.md) — images, Compose, chart Helm
