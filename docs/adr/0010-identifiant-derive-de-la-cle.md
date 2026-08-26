# ADR-0010 — L'identifiant de transaction est derive de la cle d'idempotence

| | |
|---|---|
| **Statut** | Accepte |
| **Date** | 2026-08-25 |
| **Prise d'effet** | commit `d25dec4` |
| **Verifie par** | `DisbursementCrashRecoveryIT` |

## Contexte

Consequence directe de [ADR-0004](0004-appel-synchrone-vers-le-grand-livre.md) : l'ecriture
au grand livre est validee **a distance**, avant que la transaction locale de
`payment-service` ne le soit.

Le decaissement engage les fonds avant d'appeler l'operateur. Sa sequence est donc :
reserver la cle, poser le verrou, lire le solde, **ecrire au grand livre**, puis enregistrer
la transaction localement.

## Le defaut

Avec un identifiant tire au hasard, un arret entre l'ecriture distante et la validation
locale laisse :

- au grand livre, une reservation de fonds bien reelle, sous un identifiant que plus
  personne ne connait ;
- a `payment-service`, aucune trace — la transaction locale a ete annulee.

Le client reessaie avec la meme cle. Rien ne s'y oppose : la reservation d'idempotence est
partie avec le rollback. Une **seconde** reservation de fonds est posee. Le portefeuille est
debite deux fois pour un decaissement.

## Decision

L'identifiant de transaction est **derive** de `clientId + cle d'idempotence`, par SHA-256,
dont les seize premiers octets sont mis en forme d'UUID.

Le meme couple produit donc toujours le meme identifiant, et les references d'ecriture qui
en decoulent. Le rejeu retombe sur la reservation deja posee au lieu d'en creer une seconde.

## Alternatives ecartees

**Une transaction distribuee (2PC).** Indisponible sur HTTP, et elle echangerait une fenetre
de panne rare contre un coordinateur qui bloque en permanence. Le remede est pire.

**Enregistrer l'identifiant localement avant l'appel distant.** Cela reintroduirait un
dual-write, dans l'autre sens : une ligne locale referencant une ecriture qui n'existe
peut-etre pas.

## Consequence a connaitre

L'identifiant devient **deductible** d'un client et d'une cle. Ce n'est pas un secret et **ne
doit jamais en devenir un** : il n'autorise rien. L'acces a une transaction se controle par
le jeton, pas par l'ignorance de son identifiant.

## Ce qui ferait revenir sur cette decision

Que l'identifiant doive porter une garantie d'imprevisibilite — s'il servait de reference
publique dans un contexte ou le deviner ouvrirait quelque chose. Il faudrait alors le tirer
au hasard **et** rendre la reservation locale anterieure a l'appel distant, ce qui
deplacerait la fenetre au lieu de la fermer. La derivation resout le probleme ; elle ne le
cache pas.
