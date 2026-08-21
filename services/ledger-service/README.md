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

## Limites assumees en Phase 1

Elles sont listees ici plutot que decouvertes plus tard.

- **Aucune authentification.** Le service est ouvert. La securite (Keycloak, portees
  `ledger:read` / `ledger:post`) arrive en Phase 5. La couche est prevue, elle n'est pas
  cablee.
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
