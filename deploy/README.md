# Deploiement

Quatre services, quatre bases, un courtier, un fournisseur d'identite.

---

## Lancer la plateforme en local

```bash
docker compose -f deploy/docker/docker-compose.yml up --build
```

La premiere construction est longue : le reacteur Maven est compile dans l'image. Les
suivantes reutilisent les couches. Un seul fichier `Dockerfile` sert les quatre services,
choisi par l'argument `SERVICE` — l'etape de construction etant identique, BuildKit ne
compile le reacteur qu'une fois pour les quatre images.

| Service | Port publie | Variable | Base |
|---|---|---|---|
| `ledger-service` | 8081 | `LEDGER_HOST_PORT` | interne |
| `payment-service` | 8082 | `PAYMENT_HOST_PORT` | interne |
| `provider-service` | 8083 | `PROVIDER_HOST_PORT` | interne |
| `notification-service` | 8084 | `NOTIFICATION_HOST_PORT` | interne |
| Keycloak | 8090 | `KEYCLOAK_HOST_PORT` | — |
| Kafka | 29092 | `KAFKA_HOST_PORT` | — |

Les ports **a l'interieur** des conteneurs ne changent jamais : ce sont ceux que les
services utilisent pour se parler, et les figer est ce qui rend le reseau interne
previsible. Les ports **publies** sur le poste, eux, sont configurables — un poste de
developpement heberge souvent deja quelque chose sur 8081, et personne ne devrait avoir a
modifier un fichier versionne pour cela.

Seuls les services et Keycloak sont exposes. **Aucune base n'est publiee sur le poste** :
une base de donnees accessible depuis l'exterieur est une porte ouverte qu'aucun test
n'emprunte jamais, donc que personne ne remarque.

---

## Verifier que la plateforme tient ses promesses

```bash
./parcours.sh
```

`parcours.sh` n'est pas une demonstration : c'est une **suite d'assertions** sur la
plateforme demarree. Dix etapes — audience refusee, rejeu idempotent, cloisonnement entre
deux marchands, grand livre irreecrivable par ses deux utilisateurs, deux decaissements
concurrents dont un seul passe, et un service qui redemarre pendant une panne du
fournisseur d'identite.

L'integration continue l'execute a chaque push contre une pile fraichement construite. Un
guide de demarrage que personne n'execute decrit le systeme tel qu'il etait le jour ou il a
ete ecrit ; celui-ci echoue si la plateforme cesse de se comporter comme il l'annonce.

Il l'a deja prouve : c'est ce parcours qui a trouve la portee `ledger:read` manquante du
compte de service, un defaut invisible en test unitaire comme en test d'integration, parce
qu'il ne vivait que dans la configuration du realm.

[docs/DEMARRAGE.md](../docs/DEMARRAGE.md) explique ce que chaque etape prouve.

---

## Ce qui migre n'est pas ce qui sert

C'est la decision structurante de ce dossier.

Depuis la Phase 1, deux utilisateurs PostgreSQL coexistent : celui qui **possede** le
schema et applique les migrations, et celui qui **fait tourner** l'application — lequel ne
recoit que `SELECT` et `INSERT` sur les journaux, et ne peut donc ni reecrire ni supprimer
une ecriture comptable.

Tant que Flyway s'executait dans le processus applicatif, cette separation etait
**theorique** : le conteneur de l'application devait detenir le mot de passe du
proprietaire pour migrer a son demarrage. Autant dire qu'elle n'existait pas.

Les migrations tournent desormais dans une **image distincte** — `Dockerfile.migrate`, qui
ne contient que les fichiers SQL. Pas de jar, pas de serveur HTTP, pas de consommateur
Kafka, pas une ligne de code metier. Cette image ne peut rien faire d'autre que migrer, et
l'image du service ne connait pas le mot de passe du proprietaire.

Trois consequences pratiques :

- l'application demarre avec `SPRING_FLYWAY_ENABLED=false` ;
- en Compose, l'ordonnancement vient de `service_completed_successfully` ;
- en Kubernetes, il viendra d'un `Job` en hook `pre-upgrade` : **si la migration echoue,
  la release echoue et les nouveaux pods ne sont jamais deployes.** C'est la garantie
  reelle, et elle vaut mieux qu'une verification au demarrage de chaque replique.

**Contrepartie assumee.** La version de Flyway de l'image de migration est independante de
celle qu'embarque Spring Boot. Elle est epinglee sur la meme majeure, et une montee de
version de Spring Boot demande de verifier `Dockerfile.migrate`.

---

## Emetteur et cles ne pointent pas au meme endroit

Deux variables distinctes, et ce n'est pas de la redondance :

```
OIDC_ISSUER_URI   = http://localhost:8090/realms/ocb                              # ce qui est verifie
OIDC_JWK_SET_URI  = http://keycloak:8080/realms/ocb/protocol/openid-connect/certs # ce qui est appele
```

L'**emetteur** est l'adresse vue de l'exterieur, parce que c'est celle que Keycloak inscrit
dans la revendication `iss` des jetons. Les **cles**, elles, sont recuperees par le reseau
interne : un service qui irait chercher `localhost:8090` depuis son propre conteneur
s'appellerait lui-meme.

Renseigner `jwk-set-uri` a une seconde vertu, independante : la resolution devient
**paresseuse**. Le service ne contacte le fournisseur d'identite qu'au premier jeton recu,
et non a son demarrage. Un Keycloak indisponible n'empeche donc pas un service de
demarrer — donc de redemarrer au moment precis ou on en aurait besoin.

C'est pour cette raison qu'**aucun service ne declare `depends_on: keycloak`**. L'absence
est deliberee et se verifie : arretez Keycloak, redemarrez un service, il demarre.

`KC_HOSTNAME` est fige cote Keycloak. Sans cela, l'emetteur inscrit dans le jeton depend de
l'adresse par laquelle on l'a demande : un jeton obtenu depuis le poste porterait un `iss`
different d'un jeton obtenu depuis le reseau interne, et la validation echouerait selon le
chemin emprunte.

---

## Le realm

`keycloak/realm-ocb.json` declare six portees et trois clients.

| Client | Portees | Role |
|---|---|---|
| `payment-service` | `ledger:post`, `ledger:read` | Compte de service du moteur de paiement, **seul detenteur** du droit d'ecrire au grand livre |
| `merchant-demo` | `payment:initiate`, `payment:read` | Marchand : demande des operations, ne touche jamais au grand livre |
| `merchant-second` | `payment:initiate`, `payment:read` | Second marchand. Existe pour une seule raison : prouver que deux appelants qui choisissent la meme cle d'idempotence obtiennent deux transactions distinctes |
| `ops-console` | `ledger:read`, `provider:read`, `notification:read` | Exploitation, lecture seule |

`payment-service` porte aussi `ledger:read`, et pas seulement `ledger:post` : l'interdiction
de decouvert exige de **lire le solde** du portefeuille avant d'engager les fonds. Cette
portee manquait, et rien ne le signalait — Keycloak refuse la demande de jeton entiere avec
`invalid_scope`, si bien que l'echec se presentait comme un `500` au moment du decaissement,
loin de sa cause. C'est le parcours de verification qui l'a trouve : l'encaissement, lui,
n'appelle le grand livre qu'a la confirmation de l'operateur, et ne touchait donc pas ce
chemin.

**L'audience est attachee a la portee, pas au client.** Chaque portee porte un mappeur qui
ajoute l'audience du service qu'elle ouvre : un client qui gagne `ledger:post` gagne
automatiquement l'audience `ledger-service`. Declarer les audiences par client obligerait a
penser aux deux a chaque ajout, et l'oubli ne se verrait qu'a l'execution — par un `401`
dont la cause serait ailleurs que la ou on la chercherait.

Sans ces mappeurs, Keycloak n'emet aucune audience utile et **tous** les jetons seraient
refuses. Ce qui a au moins le merite d'echouer bruyamment.

Aucun flux interactif n'est active : ces clients sont des programmes, pas des navigateurs.

### Obtenir un jeton

```bash
curl -s -d grant_type=client_credentials -d client_id=merchant-demo -d client_secret=dev-only-merchant-demo http://localhost:8090/realms/ocb/protocol/openid-connect/token
```

---

## Secrets : ce que ce dossier fait, et ce qu'il ne fait pas

**Le realm contient des secrets de developpement, volontairement reconnaissables comme
tels** — `dev-only-merchant-demo` et consorts. Ils sont versionnes parce qu'ils ne
protegent rien : ce realm sert a faire tourner la plateforme sur un poste. Un deploiement
reel utilise un realm provisionne separement, avec des secrets qui ne passent jamais par un
depot.

**Un `Secret` Kubernetes est du base64, pas du chiffrement.** Le nom de l'objet induit en
erreur. Ce que ce projet fait : `envFrom`, aucune valeur dans `values.yaml`, aucun secret
dans une image. Ce qu'il ne fait pas : chiffrer au repos. Pour cela il faut un operateur
dedie — External Secrets Operator adosse a un coffre, ou SOPS — et c'est un choix
d'infrastructure qui depend du cluster, pas de l'application.

Annoncer « secrets chiffres » parce qu'on utilise l'objet nomme `Secret` serait exactement
le genre d'approximation que ce projet evite partout ailleurs.

---

## Kubernetes : un chart, quatre services

`helm/open-core-banking/` est un chart unique, pas quatre sous-charts. Les quatre services
ont exactement la meme forme — un `Deployment`, un `Service`, un `Job` de migration — et ce
qui les distingue tient dans des valeurs. Quatre copies du meme gabarit divergeraient en
silence : une sonde corrigee ici et pas la, un contexte de securite oublie sur le dernier
service ajoute.

```bash
helm upgrade --install ocb deploy/helm/open-core-banking -n ocb --create-namespace
```

### Le `Job` de migration en hook, et ce qu'il garantit

Le `Job` porte les annotations `pre-install,pre-upgrade` et un poids negatif : il passe
avant tout le reste de la release.

**Si la migration echoue, la release echoue, et les nouveaux pods ne sont jamais
deployes.** Une verification au demarrage de chaque replique ne donne pas cela — elle donne
N repliques qui echouent chacune de leur cote pendant que les anciennes tournent encore,
sur un schema a moitie migre.

Trois choix meritent d'etre expliques :

- **`hook-delete-policy: before-hook-creation`, et surtout pas `hook-succeeded`.** La
  seconde effacerait le `Job` des sa reussite, donc ses journaux avec lui. Le jour ou une
  migration se comporte etrangement sans echouer, il ne resterait rien a lire.
- **`backoffLimit: 0`.** Flyway laisse une migration echouee inscrite dans son historique ;
  relancer produirait un second echec, au message plus obscur que le premier, et
  retarderait le seul geste utile — qu'un humain regarde.
- **`activeDeadlineSeconds`.** Une migration qui attend un verrou indefiniment tiendrait la
  release en echec sans jamais rendre la main.

### Deux Secret par service, verifie par le chart

Le `Job` recoit les identifiants du **proprietaire du schema** ; les `Deployment` recoivent
ceux du **role applicatif**, qui n'a que `SELECT` et `INSERT` sur les journaux. Deux
`Secret` distincts, deux pods distincts, deux images distinctes.

Les pointer vers le meme `Secret` annulerait toute la separation, et **l'erreur ne se
verrait nulle part** : la release s'installerait, les pods demarreraient, la garantie
n'existerait plus. Le chart refuse donc de se generer dans ce cas :

```
services.ledger : secretName et migration.secretName designent le meme Secret.
Le pod applicatif detiendrait alors le mot de passe du proprietaire du schema, et
pourrait retirer les declencheurs d'immuabilite du grand livre.
```

L'integration continue tente cette configuration a chaque push et exige que le chart la
refuse. Un garde-fou qu'on ne cherche jamais a declencher n'est qu'un commentaire.

### Le relais d'outbox ne monte pas en repliques

`payment-service` et `provider-service` hebergent le relais d'outbox. Ce relais garantit
l'ordre par agregat en lisant `ORDER BY seq` dans **une seule** instance ; deux relais
concurrents, avec `FOR UPDATE SKIP LOCKED`, publieraient l'evenement 2 pendant que l'autre
tient encore le 1. Un consommateur verrait les faits dans le desordre, sans aucun moyen de
s'en apercevoir.

Le chart en tire deux consequences, automatiquement, a partir du seul indicateur
`outboxRelay: true` :

- `replicas: 1`, quelle que soit la valeur demandee ;
- **strategie `Recreate`.** Un `RollingUpdate`, meme a `maxSurge: 1`, fait coexister deux
  pods le temps de la bascule — donc deux relais.

**Contrepartie assumee :** un deploiement de ces deux services coupe brievement leur API.
La sortie de ce compromis est un relais extrait du service, ou une election de leader ; ce
n'est pas un `maxSurge` plus permissif.

### Sondes et ressources

- **`startupProbe`** couvre le demarrage d'une JVM Spring, qui prend plusieurs dizaines de
  secondes. Sans elle, il faudrait relacher `livenessProbe` au point de la rendre inutile
  en regime etabli.
- **`livenessProbe` ne depend d'aucun systeme externe.** Elle fait redemarrer le pod ; si
  elle dependait de la base ou du courtier, une coupure de ceux-ci ferait redemarrer en
  boucle des processus parfaitement sains, ajoutant une panne a une panne.
- **`readinessProbe` inclut les dependances** : un pod qui ne peut pas travailler doit
  sortir du service sans etre tue.
- **Memoire : requete egale limite.** La JVM dimensionne son tas sur la limite du cgroup ;
  une limite superieure a la requete l'inciterait a prendre une place que le noeud ne lui
  garantit pas.
- **Processeur : requete sans limite.** Une limite CPU se traduit par du throttling pendant
  les pics — dont le demarrage de la JVM — sans rien proteger que la requete ne protege
  deja.

### Ce que le chart ne fait pas

- **Il ne cree aucun `Secret`.** Les huit objets attendus sont crees hors du chart ; un mot
  de passe passe en `--set` finit dans l'historique du shell et dans `helm get values`.
- **Il ne redeploie pas les pods quand un `Secret` change.** Une somme de controle ne peut
  pas porter sur un objet que le chart ne possede pas. Apres une rotation, il faut un
  `kubectl rollout restart`, ou un operateur qui le fasse — Reloader, par exemple.
- **Il ne deploie ni PostgreSQL, ni Kafka, ni Keycloak.** Ce sont des systemes avec etat,
  dont l'exploitation ne ressemble en rien a celle d'un service sans etat. Les embarquer
  dans le meme chart donnerait l'illusion qu'un `helm upgrade` les traite comme le reste.

### Verification

Le chart n'a pas de compilateur. Ce qui l'exerce, a chaque push :

```bash
helm lint deploy/helm/open-core-banking
helm template ocb deploy/helm/open-core-banking | kubeconform -strict -kubernetes-version 1.30.0
```

`helm lint` valide la syntaxe des gabarits, pas le YAML produit. C'est `kubeconform` qui a
trouve une clef `app.kubernetes.io/component` en double dans les quatre `Job` — du YAML que
Helm generait sans broncher et que l'API Kubernetes aurait refuse.

L'`Ingress` etant desactive par defaut, une seconde generation l'active explicitement : un
gabarit jamais rendu n'est jamais verifie.

---

## L'image applicative

- **Multi-etages** : construction avec Maven, execution sur un JRE seul. L'image finale ne
  contient ni compilateur, ni sources, ni depot Maven.
- **Utilisateur sans privileges, avec un UID numerique.** La difference entre
  « l'attaquant lit les fichiers de l'application » et « l'attaquant controle le
  conteneur » tient a cette ligne. L'UID est fixe a `10001` plutot que laisse au hasard
  d'`adduser`, parce que Kubernetes ne sait pas resoudre un nom d'utilisateur : avec un
  `USER` nomme, la garde `runAsNonRoot` ne peut rien verifier et refuse de demarrer le
  conteneur. Un nom suffit sous Docker et echoue sous Kubernetes — exactement le genre
  d'ecart qui ne se decouvre qu'au deploiement.
- **Aucun `-Xmx` code en dur.** Depuis JDK 10, la JVM lit les limites du cgroup ; un
  maximum fige ici ignorerait la limite reelle du conteneur.
- **Les tests ne tournent pas dans l'image.** Ils demandent un demon Docker, et
  l'integration continue les a deja executes.
