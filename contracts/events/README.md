# Contrats d'evenements

Ces schemas sont la **source de verite** des messages qui circulent sur Kafka. Ils sont
versionnes avec le code et valides en CI : un evenement produit qui ne respecte plus son
schema fait echouer le build, pas la production.

## Convention de nommage des topics

`ocb.<kind>.<domaine>.v<majeure>` ou `kind` vaut :

- `cmd` — une **intention** adressee a un service precis. L'emetteur attend qu'il agisse.
- `evt` — un **fait accompli**. L'emetteur ne sait pas qui ecoute et ne s'en soucie pas.

| Topic | Producteur | Consommateurs | Cle de partition |
|---|---|---|---|
| `ocb.cmd.provider.v1` | payment | provider | `transactionId` |
| `ocb.evt.payment.v1` | payment | notification, reconciliation | `transactionId` |
| `ocb.evt.provider.v1` | provider | payment | `transactionId` |
| `ocb.evt.ledger.v1` | ledger | notification, reconciliation | `accountId` |

Un topic **par agregat**, pas par type d'evenement. Les evenements d'une meme transaction
partagent donc la meme cle de partition et restent ordonnes entre eux. Le type est porte
par le champ `eventType` et par l'en-tete `ce_type`.

## Enveloppe

Tout message respecte [envelope.schema.json](envelope.schema.json). Trois champs meritent
une explication :

- **`eventId`** est la cle de deduplication cote consommateur. C'est lui qui va dans
  `processed_message`. Kafka garantit une livraison *au moins une fois* : sans cet
  identifiant stable, un consommateur ne peut pas distinguer un doublon d'un nouvel
  evenement.
- **`correlationId`** traverse tout le flux, du premier appel REST a la notification
  finale. C'est ce qui rend une transaction suivable a travers quatre services.
- **`causationId`** designe l'evenement qui a cause celui-ci. Il permet de reconstruire
  l'arbre causal d'une transaction, ce qu'un simple horodatage ne permet pas quand
  plusieurs evenements partagent la meme milliseconde.

## Regles de compatibilite

Dans une version majeure : **ajout de champ optionnel uniquement**. Jamais de suppression,
jamais de renommage, jamais de changement de type, jamais de resserrement d'enumeration.

La raison est operationnelle : producteurs et consommateurs ne sont pas deployes en meme
temps. Pendant la fenetre de deploiement, une version ancienne et une version nouvelle
coexistent et doivent se comprendre dans les deux sens.

Un changement incompatible cree un nouveau topic `...v2`. Le producteur publie sur les
deux le temps que les consommateurs migrent, puis `v1` est retire.

## Donnees personnelles

Le MSISDN complet ne circule que sur `ocb.cmd.provider.v1`, parce que `provider-service`
en a besoin pour appeler l'operateur. Partout ailleurs il est masque (`+2376****1234`).
Aucun autre attribut personnel ne transite. En Kubernetes, des ACL Kafka restreindront la
lecture de ce topic au seul groupe de consommateurs de `provider-service`.

## Comment ces schemas sont opposables

L'enveloppe a son propre fichier ; les charges utiles sont regroupees dans
[payloads.schema.json](payloads.schema.json), une entree `$defs` par `eventType`, pour que
la surface evenementielle complete se lise d'un coup d'oeil.

Les payloads sont ecrits a la main en Java, dans `platform/common-events`, et non generes.
Un test (`EventContractTest`) serialise une instance de chaque payload et la valide contre
son schema. Une divergence entre le code et le contrat fait echouer le build.

Ce choix — valider plutot que generer — evite d'ajouter un generateur au build pour des
structures de quelques champs, tout en gardant le schema opposable. Si le nombre
d'evenements grandit au point que l'ecriture manuelle devienne penible, la generation
redeviendra le bon compromis.
