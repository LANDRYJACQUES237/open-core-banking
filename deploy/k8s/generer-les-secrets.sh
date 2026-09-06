#!/usr/bin/env bash
#
# =====================================================================================
# Genere les Secret Kubernetes d'un deploiement.
#
#     ./deploy/k8s/generer-les-secrets.sh test > /chemin/prive/secrets-ocb.yaml
#
# Le fichier produit contient des mots de passe en clair. Il n'a pas sa place dans le
# depot, ni dans une conversation, ni dans un historique de shell — d'ou l'ecriture sur la
# sortie standard plutot que dans un fichier choisi par le script : c'est a l'appelant de
# decider ou il atterrit, et de le supprimer ensuite.
#
# `.gitignore` couvre `*secrets*.yaml`, mais ne s'y fier serait une protection de plus a
# oublier le jour ou le nom change.
#
# ------------------------------------------------------------------------------------
# **Pourquoi trois Secret par service et non un seul**
#
# `ocb-<service>-app`        : mot de passe du role applicatif. SELECT et INSERT.
# `ocb-<service>-migration`  : mot de passe du proprietaire du schema. Migre, et lui seul.
# `ocb-postgres-init`        : tous les mots de passe, monte dans la seule base.
#
# Les deux premiers ne se croisent jamais : le pod applicatif ne detient pas de quoi
# retirer les declencheurs qui rendent le grand livre immuable. Le chart Helm refuse
# d'ailleurs de se generer si on les confond.
#
# Le troisieme les contient tous, ce qui est sans consequence : c'est la base de donnees,
# elle connait par construction les identifiants qu'elle a crees.
# =====================================================================================

set -euo pipefail

NAMESPACE="${1:-}"
if [ -z "$NAMESPACE" ]; then
    printf 'Usage : %s <namespace> > secrets.yaml\n' "$0" >&2
    exit 2
fi

# openssl est prefere a $RANDOM, qui n'est pas un generateur cryptographique et produirait
# des mots de passe devinables.
if ! command -v openssl > /dev/null 2>&1; then
    printf 'openssl est requis pour generer des valeurs aleatoires sures.\n' >&2
    exit 2
fi

mdp() { openssl rand -base64 24 | tr -d '\n=+/' | cut -c1-28; }

PG_SUPER=$(mdp)
KC_ADMIN=$(mdp)
KC_DB=$(mdp)

LEDGER_APP=$(mdp);       LEDGER_OWNER=$(mdp)
PAYMENT_APP=$(mdp);      PAYMENT_OWNER=$(mdp)
PROVIDER_APP=$(mdp);     PROVIDER_OWNER=$(mdp)
NOTIFICATION_APP=$(mdp); NOTIFICATION_OWNER=$(mdp)

# Secrets clients du realm. Ils remplacent les valeurs `dev-only-*` du depot, qui sont
# publiques et ne doivent jamais servir a une instance joignable depuis un reseau.
CLIENT_PAYMENT=$(mdp)
CLIENT_OPS=$(mdp)

# Secrets de signature des webhooks. Un secret vide validerait toute signature calculee
# avec une chaine vide : une protection affichee mais inexistante.
MTN_WEBHOOK=$(mdp)
ORANGE_WEBHOOK=$(mdp)

cat <<YAML
# =====================================================================================
# Secrets generes le $(date -u +%Y-%m-%dT%H:%M:%SZ) pour le namespace "$NAMESPACE".
#
# A appliquer une seule fois, puis a supprimer de la machine qui l'a produit.
# Regenerer ce fichier produit de nouvelles valeurs : il faut alors redeployer les pods,
# qui ne relisent pas un Secret modifie.
# =====================================================================================

apiVersion: v1
kind: Secret
metadata:
  name: ocb-postgres-init
  namespace: $NAMESPACE
type: Opaque
stringData:
  POSTGRES_PASSWORD: "$PG_SUPER"
  LEDGER_APP_PASSWORD: "$LEDGER_APP"
  LEDGER_OWNER_PASSWORD: "$LEDGER_OWNER"
  PAYMENT_APP_PASSWORD: "$PAYMENT_APP"
  PAYMENT_OWNER_PASSWORD: "$PAYMENT_OWNER"
  PROVIDER_APP_PASSWORD: "$PROVIDER_APP"
  PROVIDER_OWNER_PASSWORD: "$PROVIDER_OWNER"
  NOTIFICATION_APP_PASSWORD: "$NOTIFICATION_APP"
  NOTIFICATION_OWNER_PASSWORD: "$NOTIFICATION_OWNER"
  KEYCLOAK_DB_PASSWORD: "$KC_DB"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-keycloak
  namespace: $NAMESPACE
type: Opaque
stringData:
  KC_BOOTSTRAP_ADMIN_PASSWORD: "$KC_ADMIN"
  KC_DB_PASSWORD: "$KC_DB"
  OCB_CLIENT_SECRET_PAYMENT: "$CLIENT_PAYMENT"
  OCB_CLIENT_SECRET_OPS: "$CLIENT_OPS"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-ledger-app
  namespace: $NAMESPACE
type: Opaque
stringData:
  LEDGER_DB_PASSWORD: "$LEDGER_APP"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-ledger-migration
  namespace: $NAMESPACE
type: Opaque
stringData:
  FLYWAY_PASSWORD: "$LEDGER_OWNER"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-payment-app
  namespace: $NAMESPACE
type: Opaque
stringData:
  PAYMENT_DB_PASSWORD: "$PAYMENT_APP"
  LEDGER_CLIENT_SECRET: "$CLIENT_PAYMENT"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-payment-migration
  namespace: $NAMESPACE
type: Opaque
stringData:
  FLYWAY_PASSWORD: "$PAYMENT_OWNER"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-provider-app
  namespace: $NAMESPACE
type: Opaque
stringData:
  PROVIDER_DB_PASSWORD: "$PROVIDER_APP"
  MTN_WEBHOOK_SECRET: "$MTN_WEBHOOK"
  ORANGE_WEBHOOK_SECRET: "$ORANGE_WEBHOOK"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-provider-migration
  namespace: $NAMESPACE
type: Opaque
stringData:
  FLYWAY_PASSWORD: "$PROVIDER_OWNER"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-notification-app
  namespace: $NAMESPACE
type: Opaque
stringData:
  NOTIFICATION_DB_PASSWORD: "$NOTIFICATION_APP"

---
apiVersion: v1
kind: Secret
metadata:
  name: ocb-notification-migration
  namespace: $NAMESPACE
type: Opaque
stringData:
  FLYWAY_PASSWORD: "$NOTIFICATION_OWNER"
YAML

# Les identifiants dont l'humain a besoin partent sur l'erreur standard : ils ne polluent
# pas le fichier de manifestes, et restent visibles meme quand la sortie est redirigee.
cat >&2 <<RESUME

  Secrets generes pour le namespace "$NAMESPACE".

  A conserver hors du depot, le temps du deploiement :

    Console Keycloak    utilisateur "admin", mot de passe : $KC_ADMIN
    Client ops-console  secret : $CLIENT_OPS

  Le second est celui a transmettre a qui doit consulter la plateforme en lecture seule.
  Il ne porte que ledger:read, provider:read et notification:read — aucune ecriture.

RESUME
