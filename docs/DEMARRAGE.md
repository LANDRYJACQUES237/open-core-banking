# Demarrage

Trois facons d'aborder ce depot, de la plus rapide a la plus convaincante.

| | Ce que ca demande | Ce que ca montre |
|---|---|---|
| `./mvnw test` | Rien | Le domaine comptable, les proprietes generees, les regles d'architecture |
| `./mvnw verify` | Un demon Docker | Tout le reste : contraintes differees, droits PostgreSQL, consommateurs Kafka |
| `./deploy/parcours.sh` | La pile complete | Que les garanties tiennent **sur la plateforme assemblee**, pas seulement en test |

---

## Les tests, sans rien installer

Maven n'est pas necessaire : le depot embarque le wrapper.

```bash
./mvnw test
```

Domaine comptable, type `Money`, proprietes generees par jqwik, dix-sept regles ArchUnit.
Aucune infrastructure : ces tests ne demarrent rien.

```bash
./mvnw verify
```

Ajoute les tests d'integration. Ils demandent un demon Docker, parce qu'ils demarrent de
**vrais** PostgreSQL et de **vrais** Kafka par Testcontainers. Une contrainte differee
evaluee au `COMMIT`, un `GRANT` refuse, un rebalancement de consommateurs : rien de tout
cela n'a de sens face a une doublure.

Le developpement local se fait sur JDK 25 avec `maven.compiler.release=21` ; l'integration
continue compile **et execute** sur JDK 21, qui est la cible.

---

## La plateforme complete

```bash
docker compose -f deploy/docker/docker-compose.yml up -d --build
```

Quatre services, quatre bases, un courtier, un fournisseur d'identite avec son realm. La
premiere construction est longue — le reacteur Maven est compile dans l'image, une seule
fois pour les quatre services.

Un poste qui heberge deja quelque chose sur ces ports n'a pas a modifier le fichier :

```bash
LEDGER_HOST_PORT=18081 PAYMENT_HOST_PORT=18082 docker compose -f deploy/docker/docker-compose.yml up -d
```

Les details — separation des utilisateurs de base, emetteur et cles a deux adresses,
conception du realm — sont dans [deploy/README.md](../deploy/README.md).

---

## Le parcours

```bash
./deploy/parcours.sh
```

Ou, si vous avez decale les ports :

```bash
LEDGER_URL=http://localhost:18081 PAYMENT_URL=http://localhost:18082 ./deploy/parcours.sh
```

**Ce script n'est pas une demonstration, c'est une suite d'assertions.** Chaque etape
echoue bruyamment si la plateforme cesse de se comporter comme ce guide le decrit, et
l'integration continue l'execute a chaque push contre une pile fraichement construite.

C'est ce qui empeche ce document de mentir dans six mois. Un guide de demarrage que
personne n'execute decrit le systeme tel qu'il etait le jour ou il a ete ecrit.

### Ce que chaque etape prouve

**1. Les quatre services repondent, et le realm est importe.** Rien de remarquable,
sinon que la sonde de *disponibilite* inclut la base et le courtier, la ou la sonde de
*vivacite* ne depend d'aucun systeme externe. Une vivacite qui dependrait de la base ferait
redemarrer en boucle des processus sains pendant une coupure — une panne ajoutee a une
panne.

L'etape attend aussi Keycloak, qui importe son realm et met plus longtemps que les
services. C'est un ajout tardif : sur une pile deja chaude, il repondait toujours ; au
premier demarrage a froid, l'etape suivante partait dans le vide. Un parcours qui ne passe
que sur une plateforme deja rodee ne verifie pas grand-chose.

**2. L'audience est portee par la portee, pas par le client.** Le jeton du marchand porte
`aud: payment-service` ; celui du compte de service porte `aud: ledger-service`. Aucun des
deux ne l'a demande : l'audience vient des portees accordees. Declarer les audiences par
client obligerait a y penser deux fois a chaque ajout, et l'oubli ne se verrait qu'a
l'execution, par un `401` dont la cause serait ailleurs qu'on la chercherait.

Le parcours verifie aussi que le marchand **ne porte pas** `ledger:post`. Une portee de trop
ne provoque aucune erreur ; elle ouvre simplement une porte.

**3. Un jeton valide pour un service est refuse par un autre.** Le jeton du marchand,
parfaitement signe et non expire, recoit `401` du grand livre : mauvaise audience.

L'exploitation, elle, porte `ledger:read`. Elle a donc la bonne audience et lit sans
probleme — mais recoit `403` en ecriture, faute de `ledger:post`. C'est la distinction
entre « je ne sais pas qui tu es » et « je sais qui tu es, et non », et elle se voit dans
le code de retour.

**4. Un encaissement traverse la plateforme.** `202`, frais appliques, et **le numero du
payeur masque**. Le parcours verifie explicitement que la forme en clair n'apparait nulle
part dans la reponse : le numero n'est pas chiffre, il n'est pas conserve.

**5. Le rejeu ne cree pas de seconde transaction.** Meme cle, meme corps : `200` et non
`202`, avec la transaction d'origine. La difference de code est le contrat — `202` signifie
« prise en charge », `200` signifie « vous me l'avez deja demandee ».

**6. Deux marchands, la meme cle, deux transactions.** C'est l'etape la plus importante du
parcours, et la plus facile a rater.

`commande-1` est un nom que tout le monde trouve. Si la cle d'idempotence n'etait pas
cloisonnee par appelant, le second marchand recevrait la transaction du premier — son
montant, sa reference, son portefeuille. Le parcours demande explicitement que les deux
identifiants **different**.

Cette regle a reellement manque : la contrainte `UNIQUE (scope, key)` existait depuis la
Phase 2, mais le controleur passait une constante. Le schema portait la regle, le code la
contournait, et rien n'echouait. Voir
[ADR-0003](adr/0003-idempotence-cloisonnee-par-appelant.md).

**7. L'outbox a publie, et l'operateur a recu la commande.** Le parcours attend que la
table d'outbox se vide, puis verifie que `provider-service` a cree l'operation
correspondante. C'est la chaine complete : transaction locale, ligne d'outbox, relais,
Kafka, consommateur idempotent.

**8. Le grand livre ne peut etre reecrit par personne.** Deux tentatives de `DELETE`, deux
refus **de nature differente** :

- l'utilisateur applicatif recoit `permission denied` — les droits ne lui donnent pas de
  quoi essayer ;
- le proprietaire du schema recoit `LEDGER_IMMUTABLE` — le declencheur le refuse, avec un
  message qui dit quoi faire a la place.

Ni l'une ni l'autre couche ne suffirait seule. Les droits n'arretent pas le proprietaire, et
c'est lui qui migre ; le declencheur n'arrete pas un `DROP TRIGGER`, et c'est encore lui.
Voir [ADR-0002](adr/0002-immuabilite-a-deux-couches.md).

Une ecriture desequilibree est refusee au passage : `422`.

**9. Un portefeuille ne peut pas financer deux decaissements qu'il ne couvre qu'une fois.**
Un portefeuille approvisionne de 10 000 recoit **deux demandes simultanees de 6 000**. Le
parcours exige exactement un `202` et exactement un `422`, puis verifie que le solde n'est
jamais passe en negatif.

C'est la seule etape reellement concurrente, et c'est voulu : un verrou en memoire
passerait ce test sur une instance et echouerait **en silence** sur deux. Le verrou vit dans
la base. Voir [ADR-0007](adr/0007-decouvert-interdit-dans-payment-service.md).

**10. Un service redemarre pendant une panne du fournisseur d'identite.** Le parcours
**arrete Keycloak**, redemarre `payment-service`, et exige qu'il devienne disponible. Puis
il rallume Keycloak et verifie qu'un jeton neuf est accepte **sans nouveau redemarrage**.

C'est la contrepartie de deux details qui n'ont l'air de rien : `jwk-set-uri` renseigne
explicitement, ce qui rend la resolution des cles paresseuse, et **aucun service ne declare
`depends_on: keycloak`**. Un service qui ne demarre pas pendant une panne d'identite ne
redemarre pas au moment precis ou l'on en a besoin.

---

## Casser le systeme exprès

La meilleure facon de croire une garantie est d'essayer de la mettre en defaut.

**Retirer le verrou de portefeuille.** Commentez l'appel a `walletLock.lockForUpdate` dans
`DisbursementService`, relancez `DisbursementConcurrencyIT`. Le test doit echouer. S'il
passe, c'est le test qui est faux, pas la regle qui est superflue.

**Confondre les deux `Secret` du chart Helm.** Le chart doit refuser de se generer :

```bash
helm template ocb deploy/helm/open-core-banking --set services.ledger.migration.secretName=ocb-ledger-app
```

**Remettre une cle d'idempotence globale.** Faites renvoyer une constante par
`CallerIdentity`. `PaymentSecurityIT` doit echouer sur le cloisonnement, et l'etape 6 du
parcours avec lui.

---

## Depannage

**Un service ne devient jamais disponible.** Regardez sa sonde plutot que ses journaux :
`curl localhost:8082/actuator/health/readiness` nomme le composant en cause — `db` ou
`kafka`.

**Les migrations echouent.** Les conteneurs `*-migrate` s'arretent apres leur travail ;
leurs journaux survivent :

```bash
docker compose -f deploy/docker/docker-compose.yml logs ledger-migrate
```

Un code de sortie autre que `0` empeche le service correspondant de demarrer — c'est voulu.

**Keycloak n'a pas le bon realm.** Il n'importe que si le realm n'existe pas encore. Apres
une modification de `realm-ocb.json` :

```bash
docker compose -f deploy/docker/docker-compose.yml rm -sf keycloak
docker compose -f deploy/docker/docker-compose.yml up -d keycloak
```

**Tout reprendre a zero**, volumes compris :

```bash
docker compose -f deploy/docker/docker-compose.yml down -v
```

---

## Ou aller ensuite

- [Architecture](ARCHITECTURE.md) — niveaux C4, machine a etats, decaissement de bout en bout
- [Decisions](adr/README.md) — dix ADR, chacun avec son alternative ecartee et sa condition de retour
- [Deploiement](../deploy/README.md) — images, Compose, chart Helm
- Les README de service :
  [ledger](../services/ledger-service/README.md) ·
  [payment](../services/payment-service/README.md) ·
  [provider](../services/provider-service/README.md) ·
  [notification](../services/notification-service/README.md)
