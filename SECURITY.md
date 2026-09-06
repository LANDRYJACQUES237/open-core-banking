# Securite

> **Ce projet est une plateforme de demonstration, pas un systeme en production.** Il
> n'a subi ni test d'intrusion, ni revue de securite externe, ni audit de conformite. Ce
> document dit ce qui est protege, par quel mecanisme, et — surtout — **ce qui ne l'est
> pas**.

La section [Ce qui n'est pas protege](#ce-qui-nest-pas-protege) est plus longue que celle
des controles. C'est voulu : dans un systeme financier, la liste des lacunes connues vaut
mieux qu'une liste de garanties dont certaines seraient approximatives.

---

## Modele de menace

Cinq adversaires ont ete pris au serieux pendant la conception. Le systeme n'a pas ete
concu contre les autres.

| Adversaire | Ce qu'il veut | Ce qu'on lui oppose |
|---|---|---|
| **Un marchand legitime mais curieux** | Lire ou modifier les operations d'un autre marchand | Audience et portees par service, idempotence cloisonnee par appelant |
| **Un service compromis** | Depasser ses propres droits | Chaque service est un serveur de ressources distinct ; `payment-service` seul detient `ledger:post` |
| **Un attaquant reseau** | Rejouer ou forger un callback d'operateur | HMAC-SHA256, comparaison en temps constant, fenetre de rejeu |
| **Un initie disposant d'un acces base** | Reecrire une ecriture comptable | Declencheurs PostgreSQL **et** droits ; le compte applicatif n'a pas de quoi essayer |
| **Un attaquant ayant obtenu un conteneur** | Pivoter dans le cluster | UID non root, racine en lecture seule, capacites retirees, jeton de compte de service non monte |

**Hors modele** : deni de service, compromission de la chaine d'approvisionnement au-dela
de l'analyse de dependances, attaques physiques, compromission du fournisseur d'identite
lui-meme.

---

## Controles

Chaque ligne indique ou le controle vit **et** ce qui le verifie. Un controle sans test
est une intention.

### Authentification et autorisation

- **Serveur de ressources OIDC.** Chaque service valide les jetons **localement** contre
  les cles publiques du fournisseur. Aucun appel au fournisseur sur le chemin d'une
  requete, donc aucun point de defaillance unique sur le chemin de l'argent.
- **L'audience est verifiee**, pas seulement la signature. Un jeton legitime emis pour un
  autre service est refuse — `401`, pas `403`, parce que le jeton n'est pas valide *ici*.
- **L'audience est portee par la portee, pas par le client.** Un client qui gagne
  `ledger:post` gagne l'audience `ledger-service`. Declarer les audiences par client
  obligerait a y penser deux fois a chaque ajout, et l'oubli ne se verrait qu'a
  l'execution.
- **Refus par defaut.** Chaque chaine de securite se termine par `.anyRequest().denyAll()`.
  Une regle par defaut permissive transformerait chaque nouveau point d'entree en
  ouverture involontaire.
- **`payment-service` ne propage jamais le jeton du marchand** vers le grand livre. Il
  s'authentifie en tant que lui-meme, en `client_credentials`.

*Verifie par* `LedgerSecurityIT`, `PaymentSecurityIT`, `ProviderSecurityIT`,
`NotificationSecurityIT`, et les etapes 2 et 3 de `deploy/parcours.sh`.

### Injection SQL

Quatre couches, dont la troisieme est celle qui distingue ce projet.

**1. Requetes parametrees, sans exception.** Aucun ORM — ni Hibernate, ni JPA — mais le
`JdbcClient` de Spring, en SQL explicite. **54 requetes, 176 liaisons de parametres
nommes.** Les seules concatenations de chaines dans du SQL sont `constante + constante` :
un `SELECT` statique suivi d'une clause `WHERE` litterale a parametres. Aucune valeur issue
d'une requete HTTP n'entre dans une chaine SQL, y compris dans le verrou de portefeuille :
`pg_advisory_xact_lock(hashtextextended(:wallet, :seed))`.

La seule interpolation reelle est le **nom de schema**, lu depuis
`@ConfigurationProperties` — donc depuis la configuration de deploiement, jamais depuis une
requete. Elle existe parce qu'un identifiant SQL ne *peut pas* etre parametre, et c'est le
seul cas du depot.

**2. Un contrat d'entree strict, avant le code.** L'API est contract-first : les motifs
sont verifies avant que quoi que ce soit n'atteigne la couche metier — `^[0-9]{4}(\.[A-Za-z0-9_-]{1,64})?$`
pour un numero de compte, `^\+[0-9]{9,15}$` pour un numero de telephone, un motif decimal
pour les montants. Une chaine hostile est rejetee en `400` au bord.

**3. Le privilege n'existe pas.** C'est l'argument principal, et il ne depend pas de la
qualite du code applicatif. Le role qui fait tourner l'application detient exactement :

```sql
GRANT SELECT, INSERT ON ledger.journal_entry
GRANT SELECT, INSERT ON ledger.posting_line
GRANT SELECT, INSERT ON ledger.audit_log
GRANT SELECT, INSERT ON ledger.audit_seal
```

Ni `UPDATE`, ni `DELETE`. **Une injection SQL reussie ne peut donc ni modifier ni effacer
une ecriture comptable : le droit n'existe pas.** Et le mot de passe du proprietaire du
schema n'est jamais dans le conteneur applicatif — image, pod et `Secret` distincts — si
bien qu'une injection ne peut pas non plus s'elever.

**4. L'immuabilite par declencheurs**, decrite dans la section suivante : elle refuse
`UPDATE` et `DELETE` meme au proprietaire du schema.

**Ce que cela ne couvre pas.** Le parametrage protege les **valeurs**, pas les
**identifiants** — d'ou le cas du nom de schema, qui n'est pas controlable par un
attaquant sans acces a la configuration de deploiement. CodeQL tourne en
`security-extended` a chaque poussee et couvre les requetes d'injection Java. En revanche,
**aucun test d'intrusion, aucun DAST, aucune campagne `sqlmap`** : la protection est
structurelle et verifiee statiquement, pas eprouvee offensivement.

*Verifie par* CodeQL pour l'analyse statique d'injection, `ImmutabilityIT` pour la couche
de droits, et l'etape 8 de `deploy/parcours.sh` sur la plateforme assemblee.

**Rien n'interdit mecaniquement d'ecrire un jour une requete concatenee.** La discipline du
parametrage repose sur la relecture et sur CodeQL, pas sur une regle du depot — a la
difference des frontieres entre services, que dix-sept regles ArchUnit font echouer a la
compilation. C'est une asymetrie connue, et le paragraphe 3 ci-dessus est ce qui la rend
tolerable : meme une requete concatenee introduite par inadvertance ne pourrait pas alterer
le grand livre.

### Integrite du grand livre

- **Deux couches independantes.** Des declencheurs PostgreSQL refusent `UPDATE` et
  `DELETE` ; des droits font que le compte applicatif ne recoit que `SELECT` et `INSERT`.
- **Celui qui migre n'est pas celui qui sert.** Les migrations tournent sous le
  proprietaire du schema, dans une image distincte qui ne contient que du SQL, et en
  Kubernetes dans un pod distinct. L'application ne detient jamais le mot de passe du
  proprietaire, donc ne peut pas retirer ses propres garde-fous.
- **Le chart refuse de se generer** si le `Secret` des pods et celui du `Job` de migration
  designent le meme objet.
- **Journal d'audit en ajout seul**, chaine par hachage (`prev_hash`) et scelle
  periodiquement : une suppression au milieu de la chaine se voit.

*Verifie par* `ImmutabilityIT`, `AuditTrailIT`, l'etape 8 de `deploy/parcours.sh`, et une
contre-epreuve en integration continue qui exige que le chart rejette un `Secret` partage.

### Callbacks d'operateurs

Les webhooks sont hors OIDC : un operateur Mobile Money n'a aucune identite chez nous. Le
`permitAll` de Spring signifie ici « authentifie autrement », pas « ouvert ».

- **HMAC-SHA256** sur `horodatage.corps`, ce qui lie la signature au corps exact.
- **Comparaison en temps constant** (`MessageDigest.isEqual`) : une comparaison naive
  fuirait la signature attendue octet par octet.
- **Fenetre de rejeu symetrique** — une horloge d'operateur peut avancer comme retarder.
- **Aucun secret par defaut.** Un secret vide validerait toute signature calculee avec une
  chaine vide : une protection affichee mais inexistante. L'absence de configuration fait
  **echouer** la verification.
- **Un rejet est laconique** : il ne dit pas *pourquoi*, pour ne pas servir d'oracle.

*Verifie par* `WebhookIT` — `invalidSignatureIsRejected`, `tamperedBodyIsRejected`,
`expiredSignatureIsRejected`, `providerWithoutSecretIsRejected`, `rejectionIsLaconic`.

### Donnees personnelles

- **Le numero de telephone n'est jamais conserve en clair.** Seule sa forme masquee est
  stockee, et les messages destines aux clients n'en contiennent pas.
- **Le grand livre ne detient aucune donnee personnelle.** Un compte designe son titulaire
  par une reference opaque. Ce n'est pas de l'hygiene : une donnee personnelle dans un
  journal immuable est une contradiction insoluble — voir
  [ADR-0001](docs/adr/0001-grand-livre-sans-donnee-personnelle.md).
- **Rien de sensible dans les journaux applicatifs** : ni numero, ni jeton, ni contenu de
  message.

*Verifie par* `MsisdnTest`, `NotificationComposerTest`, et l'etape 4 de
`deploy/parcours.sh`, qui exige que la forme en clair n'apparaisse nulle part dans la
reponse.

### Surface exposee

- **Un seul point d'entree public sans jeton** : `/actuator/health/**`, avec
  `show-details: never`. Il ne renvoie que `{"status":"UP"}`.
- `/actuator/metrics` et `/actuator/prometheus` **ne sont pas publics** : ils tombent sur
  le refus par defaut.
- **Sans etat et sans CSRF, ensemble.** Desactiver la protection CSRF sans etre reellement
  sans etat serait une faute ; aucun cookie de session n'est emis.

### Conteneurs et execution

- Utilisateur **non root avec un UID numerique** (`10001`). Un `USER` nomme empeche
  Kubernetes de verifier `runAsNonRoot` et bloque le demarrage.
- **Racine en lecture seule**, `/tmp` sur un volume ephemere.
- `allowPrivilegeEscalation: false`, **toutes les capacites retirees**, profil seccomp par
  defaut du runtime.
- **Le jeton de compte de service n'est pas monte** : aucun service n'appelle l'API
  Kubernetes, et le monter offrirait des identifiants de cluster a qui prendrait la main
  sur un conteneur.
- L'image finale ne contient **ni compilateur, ni sources, ni depot Maven**.

---

## Secrets

**Regles tenues :**

- Aucun secret dans le code, aucun dans une image, aucun dans `values.yaml`.
- Tout arrive par l'environnement, depuis un `Secret` cree hors du chart.
- Le `Job` de migration et les pods applicatifs recoivent des **identifiants differents**,
  et le chart refuse de se generer si on les confond.

**Ce que le depot contient volontairement :** `deploy/docker/keycloak/realm-ocb.json`
porte des secrets de developpement, nommes pour etre reconnaissables — `dev-only-*`. Ils
sont versionnes parce qu'ils ne protegent rien : ce realm fait tourner la plateforme sur un
poste.

> **Ils ne doivent jamais servir a une instance accessible depuis un reseau.** Le depot
> etant public, ces valeurs sont connues de tous. Un deploiement reel provisionne un realm
> separe, avec des secrets generes qui ne passent par aucun depot.

**Ce que ce projet ne fait pas :** un `Secret` Kubernetes est du **base64, pas du
chiffrement**. Le nom de l'objet induit en erreur. Chiffrer au repos demande un operateur
dedie — External Secrets Operator adosse a un coffre, ou SOPS — et c'est un choix
d'infrastructure qui depend du cluster. Annoncer « secrets chiffres » parce qu'on utilise
l'objet nomme `Secret` serait exactement l'approximation que ce projet evite ailleurs.

---

## Ce qui n'est pas protege

Ces lacunes sont connues et assumees. Aucune n'est masquee ailleurs dans la documentation.

**Aucune limitation de debit, nulle part.** Ni sur les points d'entree metier, ni sur les
webhooks, ni contre la force brute. Un systeme reel en aurait besoin avant toute autre
chose.

**Pas de TLS par defaut.** La pile Docker Compose parle en clair — c'est une pile locale.
Le chart Helm propose un `Ingress` avec TLS, **desactive par defaut**, et le trafic entre
services dans le cluster n'est pas chiffre : ni mTLS, ni maillage de services.

**Aucune `NetworkPolicy`.** Rien ne restreint quel pod peut parler a quel autre. Sur un
cluster reel, un refus par defaut serait le minimum.

**Keycloak tourne en mode `start-dev` avec une base H2** dans la pile Compose. Ce mode
n'est pas destine a autre chose qu'un poste de developpement.

**Helm n'a jamais installe ce chart.** Les manifestes qu'il produit tournent sur un
cluster reel, mais appliques un par un : la garantie du hook `pre-install` — migration
echouee, release echouee, aucun pod deploye — n'a donc pas ete exercee, seulement son
equivalent manuel, qui repose sur l'operateur.

**Ce deploiement n'expose rien** : ni `Ingress`, ni TLS, ni `NetworkPolicy`. Il demontre
que la plateforme fonctionne sous Kubernetes, pas qu'elle est prete a etre exposee.

**L'authentification locale de PostgreSQL est en `trust`.** C'est le defaut de l'image :
les connexions par le reseau exigent bien un mot de passe, mais quiconque obtient un
`exec` dans le pod de la base s'y connecte en superutilisateur sans identifiant. Dans un
namespace partage, cela signifie que toute personne disposant de `pods/exec` sur ce
namespace a un acces total aux donnees.

**Le chart ne redeploie pas les pods quand un `Secret` change.** Une somme de controle ne
peut pas porter sur un objet que le chart ne possede pas. Apres une rotation, il faut un
`kubectl rollout restart`, ou un operateur qui s'en charge.

**Aucune supervision de securite.** Les services exposent des metriques metier ; rien ne
les collecte, aucune trace distribuee n'existe, et aucun evenement n'est envoye vers un
systeme de detection.

**Les operateurs sont simules.** Aucune integration reelle avec MTN ou Orange, donc aucune
manipulation d'identifiants d'operateur reels n'a ete eprouvee.

**Aucun test d'intrusion, aucune revue externe, aucun audit de conformite** — ni PCI DSS,
ni exigence d'un regulateur.

---

## Signaler une vulnerabilite

Utiliser le **signalement prive de GitHub** — onglet *Security*, *Report a vulnerability* —
plutot qu'une issue publique.

Ce projet n'a pas d'utilisateurs en production : il n'y a donc ni engagement de delai, ni
programme de recompense. Tout rapport serieux sera neanmoins traite, et corrige ou
documente ici comme lacune connue.
