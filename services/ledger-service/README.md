# ledger-service

Grand livre en partie double, immuable. Coeur de verite financiere de la plateforme.

Ce document couvre l'exploitation du service. Les decisions d'architecture qui le
justifient sont dans [docs/00-architecture-phase0.md](../../docs/00-architecture-phase0.md).

---

## Ce que le service garantit

| Garantie | Comment elle est tenue | Ou c'est verifie |
|---|---|---|
| Aucun champ solde n'est jamais ecrit | Le solde est la somme des ecritures, accelere par un instantane reconstructible | `BalanceSnapshotIT` |
| Somme des debits = somme des credits | Controle dans le domaine **et** contrainte differee evaluee par PostgreSQL au COMMIT | `JournalEntryDraftTest`, `DoubleEntryPropertyTest`, `DeferredBalanceConstraintIT` |
| Aucune ecriture n'est modifiable | Triggers `BEFORE UPDATE OR DELETE OR TRUNCATE` **et** droits limites a `SELECT, INSERT` | `ImmutabilityIT` |
| Une cle d'idempotence ne produit qu'un mouvement | Contrainte `UNIQUE` en base + `ON CONFLICT DO NOTHING` | `ConcurrentIdempotencyIT` |
| Une ecriture ne se contre-passe qu'une fois | `UNIQUE (reverses_entry_id)` | `ReversalIT` |
| `BigDecimal` partout, jamais `double` | Type `Money`, echelle imposee par la devise | `MoneyTest`, regle ArchUnit |
| Journal d'audit append-only | Table en insertion seule, scellement par chainage de hachage | `AuditTrailIT` |

---

## Pourquoi JdbcClient et pas JPA

C'est le choix technique le plus discutable du service, donc celui qui merite d'etre
argumente. Trois raisons, dans cet ordre d'importance.

**1. Le dirty checking de JPA est incompatible avec des tables immuables.**

Une entite chargee dans un contexte de persistance est surveillee : toute modification de
ses champs, meme involontaire — un mapper, un setter appele par erreur, une methode qui
normalise une valeur — produit un `UPDATE` au flush. Sur `journal_entry` et
`posting_line`, ce `UPDATE` se heurterait au trigger d'immuabilite et deviendrait une
exception a l'execution, sur un chemin de code qui n'aurait jamais du exister.

L'objection habituelle est `@Immutable` de Hibernate. Elle rend l'entite non modifiable,
mais c'est une garantie de la couche de mapping, pas une propriete du systeme : elle
disparait des qu'on ecrit une requete native, qu'on passe par un autre contexte, ou qu'on
change de fournisseur JPA. Ici, l'absence d'un chemin d'ecriture mutable est structurelle.

**2. Le schema repose sur des constructions que JPA modelise mal.**

Colonne generee (`signed_amount`), colonne identite (`entry_seq`), contrainte differee
evaluee au COMMIT, `INSERT ... ON CONFLICT DO NOTHING RETURNING`, fonctions de fenetrage
pour le solde progressif. Chacune obligerait a du SQL natif ou a des annotations de
contournement. Au bout du compte, on ecrirait du SQL derriere une abstraction qui ne
sert plus a rien, tout en payant son cout.

**3. Le controle du moment des ecritures est ici une exigence, pas un detail.**

L'idempotence repose sur `ON CONFLICT DO NOTHING`, dont le comportement bloquant sous
concurrence est ce qui rend l'operation sure. La validation par la base repose sur un
`SET CONSTRAINTS ALL IMMEDIATE` emis a un instant precis. Ces deux mecanismes supposent
de savoir exactement quelle instruction part et quand — ce que le flush automatique de
JPA rend justement imprevisible.

**Ce que ce choix coute.** Pas de cache de premier niveau, pas de navigation par
associations, du mapping ligne a ligne ecrit a la main. Sur un service dont les
agregats sont petits, plats et lus par identifiant, ces fonctionnalites n'auraient
pas servi. Sur un service metier riche, la conclusion serait probablement inverse — et
ce n'est pas une regle generale du projet : `payment-service` sera evalue separement.

---

## Prerequis

- JDK 21 (le projet cible `release 21`)
- Docker, pour PostgreSQL et pour les tests d'integration
- Maven n'est pas necessaire : utilisez le wrapper (`./mvnw`)

---

## Lancer une base locale

Le service attend deux utilisateurs distincts : celui qui migre possede le schema,
celui qui execute recoit le minimum.

```bash
docker run -d --name ocb-ledger-db \
  -e POSTGRES_USER=ledger_owner \
  -e POSTGRES_PASSWORD=owner-secret \
  -e POSTGRES_DB=ledger \
  -p 5432:5432 postgres:16-alpine
```

```bash
docker exec ocb-ledger-db psql -U ledger_owner -d ledger -c "CREATE ROLE ledger_app LOGIN PASSWORD 'app-secret';"
```

La migration `V5` accorde ensuite les droits a ce role. Si le role n'existe pas, la
migration passe sans rien faire plutot que d'echouer, afin qu'un poste de developpement
mono-utilisateur reste demarrable.

---

## Demarrer le service

```bash
cp .env.example .env
```

```bash
./mvnw -pl services/ledger-service -am spring-boot:run
```

Le service ecoute sur `http://localhost:8081`. Sondes : `/actuator/health/liveness` et
`/actuator/health/readiness`.

---

## Tester

Les deux suites sont separees, et la separation est utile : `test` reste executable sur
une machine sans Docker, et un echec d'infrastructure ne se deguise jamais en echec de
logique metier.

```bash
./mvnw test
```

```bash
./mvnw verify
```

`test` execute les tests unitaires (domaine, `Money`, proprietes generees, regles
ArchUnit). `verify` ajoute les tests d'integration, qui demandent un daemon Docker.

---

## Exemple complet : un encaissement

Ouvrir un portefeuille client sous le compte de regroupement `2100` :

```bash
curl -X POST http://localhost:8081/v1/accounts -H 'Content-Type: application/json' -H 'Idempotency-Key: open-wallet-c-001' -d '{"accountNumber":"2100.wallet-c","accountType":"LIABILITY","currency":"XAF","ownerRef":"wallet-c","name":"Portefeuille client C"}'
```

Le client envoie 10 000 XAF. La plateforme prend 100 de frais, MTN preleve 150 de
commission : le client recoit 9 900, notre float n'est credite que de 9 850.

```bash
curl -X POST http://localhost:8081/v1/journal-entries -H 'Content-Type: application/json' -H 'Idempotency-Key: collection-001' -d '{"description":"Encaissement MTN 10000 XAF","transactionRef":"TX-001","lines":[{"accountNumber":"1100","direction":"DR","amount":"9850","currency":"XAF"},{"accountNumber":"5100","direction":"DR","amount":"150","currency":"XAF"},{"accountNumber":"2100.wallet-c","direction":"CR","amount":"9900","currency":"XAF"},{"accountNumber":"4100","direction":"CR","amount":"100","currency":"XAF"}]}'
```

Rejouer exactement la meme commande renvoie un `200` et la meme ecriture, sans second
mouvement d'argent.

```bash
curl http://localhost:8081/v1/accounts/2100.wallet-c/balance
```

Un portefeuille client est un compte de **passif** : le solde retourne vaut `9900`,
c'est-a-dire la dette de la plateforme envers ce client.

```bash
curl 'http://localhost:8081/v1/accounts/2100.wallet-c/entries?page=0&size=50'
```

---

## Plan de comptes initial

| Code | Libelle | Type | Cote normal |
|---|---|---|---|
| `1100` | Float MTN Mobile Money | ASSET | DR |
| `1101` | Float Orange Money | ASSET | DR |
| `1200` | Compte bancaire de reglement | ASSET | DR |
| `1900` | Compte de passage decaissements | ASSET | DR |
| `2100` | Portefeuilles clients (regroupement, non postable) | LIABILITY | CR |
| `2900` | Encaissements non affectes | LIABILITY | CR |
| `4100` | Produits de commissions | REVENUE | CR |
| `5100` | Charges de commissions operateur | EXPENSE | DR |

Les portefeuilles individuels `2100.xxx` sont ouverts a la demande. Une ecriture directe
sur `2100` est refusee : elle doit designer un portefeuille precis.

---

## Codes d'erreur

Les reponses d'erreur suivent la RFC 7807 et portent un champ `code` stable, destine au
code appelant plutot qu'a un humain.

| Code | Statut | Signification |
|---|---|---|
| `LEDGER_UNBALANCED_ENTRY` | 422 | Somme des debits differente de la somme des credits |
| `LEDGER_TOO_FEW_LINES` | 422 | Moins de deux lignes |
| `LEDGER_MIXED_CURRENCY` | 422 | Plusieurs devises dans une meme ecriture |
| `LEDGER_IDEMPOTENCY_KEY_REUSED` | 422 | Meme cle, contenu different — bug appelant, pas un rejeu |
| `LEDGER_ALREADY_REVERSED` | 422 | Ecriture deja contre-passee |
| `LEDGER_CANNOT_REVERSE_REVERSAL` | 422 | Contre-passer une contre-passation |
| `LEDGER_ACCOUNT_NOT_POSTABLE` | 422 | Ecriture visant un compte de regroupement |
| `LEDGER_ACCOUNT_NOT_ACTIVE` | 422 | Compte gele ou cloture |
| `LEDGER_ACCOUNT_CURRENCY_MISMATCH` | 422 | Devise incompatible avec le compte |
| `MONEY_INVALID_SCALE` | 422 | Echelle incompatible avec la devise (le XAF n'a aucune decimale) |
| `LEDGER_ACCOUNT_NOT_FOUND` | 404 | Compte inconnu |
| `LEDGER_ENTRY_NOT_FOUND` | 404 | Ecriture inconnue |
| `LEDGER_ACCOUNT_NUMBER_TAKEN` | 409 | Numero de compte deja pris |

---

## Qui peut ecrire dans le grand livre

Le service est un serveur de ressources OIDC. Les jetons sont valides localement contre le
JWKS du fournisseur : aucun appel reseau au fournisseur d'identite sur le chemin de
l'argent, donc pas de point de defaillance unique sur une operation comptable.

| Portee | Ce qu'elle ouvre |
|---|---|
| `ledger:post` | Passer une ecriture, ouvrir un compte |
| `ledger:read` | Consulter comptes, ecritures et releves |

**`ledger:post` n'est accordee qu'au compte de service de `payment-service`.** Aucun
marchand ne la detient, et c'est la decision de securite la plus importante du service : un
appelant capable de passer ses propres ecritures contournerait la machine a etats,
l'idempotence et le calcul des frais — et pourrait se crediter lui-meme.

L'**audience** est verifiee en plus de la signature. Sans cela, la securite se resumerait a
« la signature est bonne » : un jeton legitimement emis pour une console d'administration
passerait ici avec toutes ses portees.

Seules les sondes `/actuator/health/liveness` et `/readiness` sont ouvertes — Kubernetes ne
presente pas de jeton. Le reste de l'actuator ne l'est pas : le volume et le montant des
ecritures se lisent dans les metriques. Tout le reste est en `denyAll` par defaut, de sorte
qu'un point d'entree ajoute plus tard soit ferme tant que personne ne l'a ouvert
explicitement.

Quatorze tests d'integration couvrent ces regles, y compris les cas ou seul un element du
jeton est faux : signature etrangere, emetteur inattendu, audience d'un autre service,
jeton expire, et portee de lecture tentant d'ecrire.

---

## Limites assumees

Elles sont listees ici plutot que decouvertes plus tard.

- **Pagination du releve par decalage.** Le solde progressif est calcule par fonction de
  fenetrage sur tout l'historique du compte, puis la page est decoupee : chaque page relit
  l'historique complet. A l'echelle, il faudra partir de l'instantane et paginer par cle.
- **Consolidation des instantanes avec retard.** `entry_seq` est attribue a l'insertion,
  pas au commit : une ecriture numerotee 5 peut valider apres une ecriture numerotee 6.
  La consolidation ne prend donc que les ecritures plus anciennes que
  `ledger.snapshot.lag-seconds`, ce qui suppose qu'aucune transaction d'ecriture ne dure
  plus longtemps — garanti par `idle_in_transaction_session_timeout`. Un controle
  (`verifySnapshots`) transforme une eventuelle derive en anomalie detectable.
- **Pas de conversion de devise.** Le modele est multidevise, mais une ecriture reste
  mono-devise : la v1 n'a pas de comptes de position de change.
- **Pas de Kafka.** L'outbox arrive en Phase 2 avec `payment-service`.
