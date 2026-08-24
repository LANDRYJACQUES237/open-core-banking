# provider-service

L'abstraction des operateurs Mobile Money — MTN MoMo, Orange Money. C'est le seul service
qui parle a un systeme que nous ne controlons pas, et tout son interet tient dans cette
phrase.

---

## Le principe : un delai n'est pas un echec

C'est la doctrine centrale du service, et la raison pour laquelle il existe separement.

Quand nous demandons un encaissement a un operateur et que rien ne revient — le fil reste
ouvert, le delai de lecture expire — **nous ne savons pas ce qui s'est passe**. La demande
peut n'etre jamais arrivee. Elle peut etre arrivee, avoir declenche une notification sur le
telephone du client, et avoir ete acceptee pendant que nous concluions a l'echec.

Traiter ce silence comme un echec, c'est accepter de perdre de l'argent : le client est
debite chez l'operateur, et nous avons enregistre une transaction echouee. Le rapprochement
se fera des semaines plus tard, a la main, sur un releve.

Le service tient donc une regle stricte :

> Un delai d'attente ne produit aucun evenement. Il enregistre une tentative et ne conclut
> rien.

Cela se lit dans le code. Le port `ProviderClient` **n'a pas de statut « inconnu »** :

```java
ProviderStatus initiateCollection(CollectionRequest request);   // une reponse
// ... ou une ProviderUnavailableException
```

L'absence de reponse n'est pas une valeur de retour, c'est une exception. Le type rend
l'erreur difficile a commettre : on ne peut pas traiter par megarde un silence comme un
refus, parce qu'il n'existe aucune valeur qui le represente. Un `UNKNOWN` dans l'enumeration
aurait fini, tot ou tard, dans un `switch` par defaut.

La distinction est portee jusqu'au transport :

| Ce que fait l'operateur | Ce que nous en concluons |
|---|---|
| `4xx` avec un code d'erreur | Un **refus**. L'operateur a compris et a dit non. |
| `5xx` | Rien. L'operateur est en panne. |
| Delai de lecture expire | Rien. C'est le cas qui compte. |
| Connexion refusee | Rien. |

Seule la premiere ligne est une reponse.

---

## Deux façons d'apprendre l'issue, et les deux sont necessaires

Ne pas conclure impose de savoir attendre. Le service utilise deux mecanismes qui se
recouvrent volontairement.

**Les rappels (webhooks).** L'operateur nous notifie. C'est rapide, et c'est le chemin
normal. Mais un rappel peut se perdre, arriver deux fois, arriver dans le desordre, ou
n'arriver jamais parce que notre ingress etait indisponible pendant trente secondes.

**La relance (polling).** Nous redemandons l'etat. C'est lent, mais cela ne depend de la
bonne sante de personne d'autre que nous.

Un systeme qui ne ferait que l'un des deux serait defaillant : sur les rappels seuls, un
message perdu laisse une transaction en attente pour toujours ; sur la relance seule, le
delai de resolution devient inutilement long. Ils ne sont pas redondants — ils echouent
pour des raisons differentes.

Les deux convergent sur le meme point d'entree, et le premier arrive gagne. Un rappel qui
resout une operation met `next_poll_at` a `NULL` : **c'est ainsi qu'un rappel annule la
relance**, sans coordination entre les deux mecanismes.

### Le calendrier de relance

`PollSchedule` est une fonction pure — aucune horloge, aucune base — donc entierement
testable sans infrastructure.

| Tentative | Delai |
|---|---|
| 1 a 6 | 5 s, 15 s, 45 s, 2 min, 5 min, 15 min |
| au-dela | toutes les heures |

Les premiers pas sont serres parce qu'un paiement Mobile Money se resout generalement en
quelques dizaines de secondes ; l'espacement ensuite evite de marteler l'operateur pour une
operation qui n'aboutira visiblement pas vite.

**Le budget est de 24 heures, mesure depuis la premiere emission** — pas depuis la derniere
tentative, sinon un incident de relance prolongerait le budget indefiniment.

### Quand le budget est epuise : `UNRESOLVED`

L'operation passe a `UNRESOLVED`. Ce n'est **pas** un echec, et la nuance est tout le
propos : c'est une ignorance declaree. Nous avons demande pendant 24 heures et nous ne
savons toujours pas. Le dossier part en arbitrage humain, avec toutes les tentatives
consignees.

Conclure `FAILED` a la place serait inventer une information que nous n'avons pas — et
c'est exactement l'erreur que tout le service est construit pour eviter. `rescheduleOrExhaust`
est le seul endroit du code qui produit cet etat.

---

## Les etats d'une operation

```
PENDING ──▶ ACCEPTED ──▶ SUCCEEDED
   │           │
   │           ├──────▶ FAILED
   │           │
   │           └──────▶ UNRESOLVED   (budget epuise)
   │
   └──▶ FAILED   (refus immediat de l'operateur)
```

`SUCCEEDED`, `FAILED` et `UNRESOLVED` sont definitifs. Un rappel tardif qui contredirait un
etat definitif est journalise et refuse, jamais applique.

---

## La securite des rappels

Le point d'entree des webhooks est la seule surface du systeme exposee a Internet. Elle est
traitee en consequence.

**Signature HMAC-SHA256 sur les octets bruts**, avec l'horodatage dans le message signe :

- la verification a lieu dans un **filtre, avant que Jackson ne parse quoi que ce soit**.
  Signer le JSON re-serialise comparerait notre interpretation du message, pas le message.
  Un attaquant pourrait alors jouer sur les differences d'analyse syntaxique ;
- la comparaison est a **temps constant** (`MessageDigest.isEqual`). Un `equals` classique
  s'arrete au premier octet different et laisse deviner la signature octet par octet ;
- une **fenetre de rejeu de 5 minutes**, symetrique, borne la duree de vie d'un message
  intercepte ;
- lire le corps dans le filtre le consommerait pour le controleur — d'ou
  `CachedBodyHttpServletRequest`.

**Aucun secret par defaut.** Un secret vide validerait toute signature calculee avec une
chaine vide : une protection affichee mais inexistante. L'absence de configuration fait
echouer la verification, et un test le verifie.

### Deux regimes d'authentification

C'est le seul service de la plateforme a en avoir deux, et la frontiere merite d'etre
explicite :

| Surface | Authentification |
|---|---|
| `/webhooks/**` | Signature HMAC de l'operateur |
| `/v1/operations/**` | Jeton OIDC, portee `provider:read` |
| Sondes de sante | Aucune |

Dans la configuration Spring, `/webhooks/**` apparait en `permitAll`, ce qui ressemble a
s'y meprendre a une ouverture. **Cela signifie « authentifie autrement »**, pas « ouvert » :
un operateur Mobile Money ne dispose d'aucune identite dans notre fournisseur d'identite et
n'en disposera jamais.

Comme cette distinction est invisible a la lecture, elle est verrouillee par un test :
**un jeton valide ne remplace pas une signature**. Quelqu'un disposant d'un jeton legitime
— un service voisin, une console — ne doit pas pouvoir injecter de faux rappels
d'operateur. Les deux mecanismes protegent des choses differentes.

---

## Deduplication

Un rappel identique peut arriver plusieurs fois ; l'operateur reessaie s'il ne recoit pas
notre `200`.

- **Technique** : `provider_callback` porte une contrainte d'unicite sur le `providerEventId`.
  Le meme message rejoue est reconnu et repond `200` avec `duplicate: true` — repondre en
  erreur ferait reessayer l'operateur indefiniment.
- **Logique** : deux messages d'identifiants differents annoncant le meme fait sont
  neutralises par les etats definitifs.

Cote commandes, `ux_operation_transaction` garantit qu'une transaction ne produit qu'une
seule operation, meme si la commande Kafka est redelivree.

---

## Prerequis

- JDK 21 ou superieur
- Docker (base de donnees, tests d'integration)

## Lancer une base locale

```bash
docker run -d --name ocb-provider-db -e POSTGRES_USER=provider_owner -e POSTGRES_PASSWORD=owner-secret -e POSTGRES_DB=provider -p 5434:5432 postgres:16-alpine
```

```bash
docker exec ocb-provider-db psql -U provider_owner -d provider -c "CREATE ROLE provider_app LOGIN PASSWORD 'app-secret';"
```

Comme pour le grand livre, deux utilisateurs distincts : celui qui applique les migrations
possede le schema, celui qui fait tourner l'application ne recoit que ce dont elle a besoin.

## Demarrer le service

Les secrets viennent de l'environnement, jamais du depot. Voir `.env.example` a la racine.

```bash
./mvnw -pl services/provider-service spring-boot:run
```

## Tester

```bash
./mvnw -pl services/provider-service verify
```

Les tests unitaires (`*Test`) ne demandent aucune infrastructure : `PollScheduleTest` et
`WebhookSignatureTest` portent sur des fonctions pures.

Les tests d'integration (`*IT`) demandent Docker. `CollectionExecutionIT` merite une
mention : l'operateur y est un **vrai serveur HTTP** (WireMock), pas un objet simule. Un
objet en processus qui leverait une exception ne testerait que le bloc `catch` ; il ne
dirait rien de ce qui arrive quand un serveur accepte la connexion, garde le fil ouvert et
ne repond jamais. Or c'est precisement le cas que ce service existe pour traiter.

---

## Codes d'erreur

| Code | HTTP | Sens |
|---|---|---|
| `PROVIDER_INVALID_SIGNATURE` | 401 | Signature absente ou incorrecte |
| `PROVIDER_SIGNATURE_EXPIRED` | 401 | Hors de la fenetre de rejeu |
| `PROVIDER_UNKNOWN` | 401 | Operateur inconnu ou sans secret configure |
| `PROVIDER_OPERATION_NOT_FOUND` | 404 | Operation inconnue |

Les rappels invalides repondent `401` sans reveler si l'operation existe : un attaquant ne
doit rien apprendre en pechant des references.

---

## Limites assumees

- **La relance tourne en une seule instance.** `FOR UPDATE SKIP LOCKED` rend le passage a
  plusieurs instances sans danger, mais cela n'a pas ete exerce.
- **Pas de disjoncteur.** Un operateur durablement en panne est reinterroge selon le
  calendrier de relance, sans coupure franche du trafic sortant.
- **Le budget est global**, identique pour tous les operateurs et tous les montants. Un
  encaissement de 500 XAF et un de 500 000 XAF meritent probablement des patiences
  differentes.
- **Aucune reconciliation de releve.** Comparer le releve quotidien de l'operateur a nos
  ecritures est le filet de securite naturel sous `UNRESOLVED`. C'est un service separe,
  prevu apres la Phase 5.
