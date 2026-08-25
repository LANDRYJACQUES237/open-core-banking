# notification-service

Prevenir le client de ce qui est arrive a son argent.

C'est le seul service **purement consommateur** de la plateforme : il n'expose aucune
ecriture, ne produit aucun evenement, et ne detient aucune verite financiere. Il detient
une trace — celle des messages emis — et cette trace est append-only comme les autres
journaux du projet.

---

## Ce qu'il demontre

Deux exigences du cahier des charges, qui ne se verifient qu'a travers un courtier :

| Exigence | Comment | Ou c'est verifie |
|---|---|---|
| Consommateur idempotent | `processed_message`, insere **dans la meme transaction que l'effet, et avant lui** | `NotificationConsumptionIT` |
| File de rebut | Politique heritee de `common-kafka`, topic `.dlq` declare explicitement | `NotificationConsumptionIT` |

Il sert aussi de verification d'une chose moins visible : `common-kafka` a ete extrait
**avant** que ce service existe. Ce service n'ecrit pas une ligne de politique de
retentative ni de deduplication, et il en beneficie entierement. C'est ce qui distingue une
extraction reussie d'un simple deplacement de code.

---

## Le probleme du destinataire

**Ce service sait qui prevenir, pas comment le joindre.** Ce n'est pas un defaut : c'est la
consequence directe d'une decision prise en Phase 2.

`payment-service` ne conserve jamais le numero de telephone en clair — il n'en garde que la
forme masquee, `+2376****0001`. Les evenements qu'il publie portent donc un numero masque,
et **un numero masque ne permet de joindre personne**.

Le destinataire est par consequent designe par une **reference de portefeuille**. Resoudre
cette reference en canal joignable appartient a l'adaptateur d'envoi, qui interrogerait un
annuaire client — lequel n'existe pas dans ce projet, et c'est une limite assumee plutot
qu'un oubli.

L'alternative aurait ete de faire circuler le numero complet dans les evenements. Elle
aurait dissemine une donnee personnelle dans un topic Kafka lu par plusieurs services et
conserve selon la retention du courtier. Le choix inverse coute une resolution en plus ; il
la vaut.

---

## Aucun envoi reel, et ce que cela evite

`NotificationSender` est un port. La seule implementation journalise.

Brancher une passerelle SMS n'ajouterait rien a la demonstration et rendrait le service
intestable sans compte operateur. Mais surtout, cela **reintroduirait la double ecriture**
que l'outbox resout ailleurs : un envoi reel est un appel reseau ; le message part, la
transaction locale echoue, et plus personne ne sait si le client a ete prevenu.

Il faudrait alors enregistrer l'intention dans la transaction et confier la remise a un
relais — exactement le motif deja construit pour les evenements. L'implementation actuelle
echappe au probleme parce qu'elle n'appelle personne, et le jour ou elle appellera, la
solution est connue.

Le message lui-meme n'est pas journalise : il est deja en base, consultable et immuable. Le
repeter dans les journaux le disperserait vers des systemes d'agregation dont la retention
et les droits d'acces ne sont pas ceux de la base. Un montant est une donnee personnelle
des lors qu'il est rattachable a un compte.

---

## Tout evenement ne merite pas un message

| Evenement | Message | Canal |
|---|---|---|
| `payment.collection.completed` | « Vous avez recu … » | client |
| `payment.collection.failed` | « … n'a pas abouti. Aucun montant n'a ete debite. » | client |
| `payment.disbursement.completed` | « … ont ete envoyes » | client |
| `payment.disbursement.reversed` | « … a ete recredite sur votre compte » | client |
| `payment.transfer.completed` | « Votre transfert … a ete effectue » | client |
| `payment.transaction.manual_review_required` | dossier a arbitrer | **exploitation** |
| `payment.disbursement.requested` | *aucun* | — |

Trois choix meritent d'etre expliques.

**Une revue manuelle ne part jamais vers le client.** Annoncer « nous ne savons pas ou est
votre argent » sans pouvoir donner de suite inquiete sans rien resoudre. C'est
l'exploitation qui doit agir, donc c'est elle qu'on previent. Le canal est decide par le
**type**, jamais par l'appelant : une nouvelle branche de code ne peut pas envoyer un
incident interne au porteur du compte par inattention.

**Un decaissement compense annonce le remboursement, pas seulement l'echec.** Le client a
vu son portefeuille debite. Lui dire « echec » le laisserait chercher son argent, et la
compensation n'aurait servi qu'a equilibrer un bilan.

**Une demande de decaissement ne notifie rien.** Le client vient de la formuler ; le lui
annoncer n'ajoute aucune information.

Aucun message client ne contient d'identifiant technique — ni identifiant de transaction,
ni reference d'ecriture. Ce qui n'a pas de sens pour le destinataire n'a rien a faire dans
un texte qu'il va lire, et un identifiant expose est une surface d'attaque de plus. Un test
parametre le verifie sur **chaque** type.

---

## Deux lecteurs du meme flux

`ocb.evt.payment.v1` est lu par ce service **et** par personne d'autre aujourd'hui — mais
`processed_message` a une cle composite `(groupe, evenement)` pour que cela reste vrai
demain. Sans cette composition, le premier a consommer masquerait l'evenement au second.

C'est aussi ce qui a permis d'ajouter ce service sans toucher a `payment-service` : un
evenement est un fait publie pour qui veut l'entendre, pas une commande adressee a
quelqu'un.

---

## Prerequis

- JDK 21 ou superieur
- Docker (base de donnees, tests d'integration)

## Lancer une base locale

```bash
docker run -d --name ocb-notification-db -e POSTGRES_USER=notification_owner -e POSTGRES_PASSWORD=owner-secret -e POSTGRES_DB=notification -p 5435:5432 postgres:16-alpine
```

```bash
docker exec ocb-notification-db psql -U notification_owner -d notification -c "CREATE ROLE notification_app LOGIN PASSWORD 'app-secret';"
```

Le role qui execute ne recoit ni `UPDATE` ni `DELETE` sur les notifications : la trace ne
se retouche pas. Le declencheur d'immuabilite le refuserait de toute facon, mais une
defense qui tient a un seul mecanisme tient a peu de chose.

## Demarrer le service

```bash
./mvnw -pl services/notification-service spring-boot:run
```

## Tester

```bash
./mvnw -pl services/notification-service verify
```

`NotificationConsumptionIT` demande un courtier ; les autres se contentent de PostgreSQL.
La separation est deliberee : rediger un message, choisir son canal et l'inscrire dans une
trace immuable se verifient sans bus, et les faire passer par un courtier ajouterait de
l'asynchronisme donc de l'intermittence, sans rien prouver de plus.

---

## Limites assumees

- **Aucun canal de remise reel.** Voir plus haut : le brancher demanderait un relais, sur
  le modele de l'outbox.
- **Aucun annuaire client.** La reference de portefeuille n'est resolue en rien.
- **Aucune preference de notification.** Un client ne peut pas se desabonner, et le
  destinataire d'un transfert n'est pas prevenu — savoir s'il le souhaite est une question
  de consentement que ce service n'a pas les moyens de trancher.
- **Aucune reprise depuis le rebut.** Les messages y sont visibles et denombrables ; les
  rejouer apres correction reste une operation manuelle.
