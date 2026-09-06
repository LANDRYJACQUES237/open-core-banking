#!/usr/bin/env bash
#
# =====================================================================================
# Verifie la plateforme deployee dans Kubernetes.
#
# A executer DEPUIS le pod ocb-verification, dont le terminal est accessible par
# l'interface. Coller le script entier, ou le recuperer par :
#
#     curl -sSL https://raw.githubusercontent.com/LANDRYJACQUES237/open-core-banking/master/deploy/k8s/verifier-le-cluster.sh | sh
#
# Ce que ce script NE verifie pas, faute de pouvoir le faire depuis ici : l'immuabilite du
# grand livre au niveau de la base. Elle se verifie depuis le pod PostgreSQL, avec psql —
# les deux commandes sont donnees a la fin de la sortie.
#
# C'est un sous-ensemble de deploy/parcours.sh, limite aux etapes qui ne demandent pas
# Docker Compose.
# =====================================================================================

set -u

KEYCLOAK=${KEYCLOAK:-http://ocb-keycloak:8080}
LEDGER=${LEDGER:-http://ocb-ledger:8081}
PAYMENT=${PAYMENT:-http://ocb-payment:8082}
PROVIDER=${PROVIDER:-http://ocb-provider:8083}
NOTIFICATION=${NOTIFICATION:-http://ocb-notification:8084}

TOKEN_URL="$KEYCLOAK/realms/ocb/protocol/openid-connect/token"

etape=0
echecs=0

titre() { etape=$((etape + 1)); printf '\n\033[1m--- %d. %s\033[0m\n' "$etape" "$1"; }
ok()    { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
echec() { printf '  \033[31mECHEC\033[0m %s\n' "$1"; echecs=$((echecs + 1)); }

verifier() {
    if [ "$1" = "$2" ]; then ok "$3 ($2)"; else echec "$3 : attendu $1, recu $2"; fi
}

code() { curl -sS -m 20 -o /dev/null -w '%{http_code}' "$@" 2>/dev/null; }

jeton() {
    reponse=$(curl -sS -m 15 -d grant_type=client_credentials \
        -d "client_id=$1" -d "client_secret=$2" "$TOKEN_URL" 2>/dev/null)
    extrait=$(printf '%s' "$reponse" | jq -r '.access_token // empty')
    if [ -z "$extrait" ]; then
        printf '  \033[31mECHEC\033[0m jeton %s : %s\n' "$1" \
            "$(printf '%s' "$reponse" | jq -r '.error_description // .error // "reponse vide"')" >&2
        return 1
    fi
    printf '%s' "$extrait"
}

# Decode la charge utile d'un jeton. Le remplissage base64url est reconstitue : sans lui,
# base64 refuse une longueur non multiple de quatre.
revendication() {
    corps=$(printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+')
    case $(( ${#corps} % 4 )) in 2) corps="$corps==" ;; 3) corps="$corps=" ;; esac
    printf '%s' "$corps" | base64 -d 2>/dev/null | jq -r "$2"
}

# =====================================================================================

titre "Les quatre services repondent"
for couple in "grand livre|$LEDGER" "paiement|$PAYMENT" "operateurs|$PROVIDER" "notification|$NOTIFICATION"; do
    nom=${couple%%|*}; url=${couple##*|}
    verifier 200 "$(code "$url/actuator/health/readiness")" "$nom, sonde de disponibilite"
done

titre "La sonde publique ne revele rien"
detail=$(curl -sS -m 10 "$LEDGER/actuator/health" 2>/dev/null)
if [ "$detail" = '{"status":"UP"}' ]; then
    ok "elle ne renvoie que l'etat, sans detail des composants"
else
    echec "elle expose plus que l'etat : $detail"
fi

titre "L'audience est portee par la portee, pas par le client"
TO=$(jeton ops-console "${OPS_SECRET:?definir OPS_SECRET avant de lancer}") || exit 1
verifier "ledger-service" "$(revendication "$TO" '.aud | if type=="array" then .[0] else . end')" \
    "l'exploitation recoit l'audience du grand livre"
if revendication "$TO" '.scope' | grep -q 'ledger:read'; then
    ok "elle porte ledger:read"
else
    echec "ledger:read absent de ses portees"
fi
if revendication "$TO" '.scope' | grep -q 'ledger:post'; then
    echec "elle porte ledger:post, ce qu'un compte de lecture ne devrait pas"
else
    ok "elle ne porte aucune portee d'ecriture"
fi

titre "Authentifie ne veut pas dire autorise"
verifier 401 "$(code -X POST "$LEDGER/v1/journal-entries" -H 'Content-Type: application/json' -d '{}')" \
    "sans jeton, le grand livre refuse"
verifier 200 "$(code "$LEDGER/v1/accounts/1100" -H "Authorization: Bearer $TO")" \
    "avec ledger:read, la lecture passe"
verifier 403 "$(code -X POST "$LEDGER/v1/journal-entries" -H "Authorization: Bearer $TO" -H 'Content-Type: application/json' -d '{}')" \
    "sans ledger:post, l'ecriture est refusee"

titre "Le plan de comptes a bien ete cree par les migrations"
comptes=$(curl -sS -m 10 "$LEDGER/v1/accounts/1900" -H "Authorization: Bearer $TO" 2>/dev/null | jq -r '.accountNumber // empty')
verifier "1900" "$comptes" "le compte de passage des decaissements existe"

printf '\n'
if [ "$echecs" -eq 0 ]; then
    printf '\033[32mVerification HTTP complete : %d etapes, aucune assertion en echec.\033[0m\n' "$etape"
else
    printf '\033[31m%d assertion(s) en echec sur %d etapes.\033[0m\n' "$echecs" "$etape"
fi

cat <<'RESTE'

  Il reste l'immuabilite du grand livre, qui se verifie depuis le pod ocb-postgres-0.
  Ouvrir son terminal et executer :

    # Premiere couche : le role applicatif n'a pas de quoi essayer.
    PGPASSWORD="<LEDGER_DB_PASSWORD du Secret ocb-ledger-app>" \
      psql -h localhost -U ledger_app -d ledger -c "DELETE FROM ledger.posting_line"
    # attendu : ERROR: permission denied for table posting_line

    # Seconde couche : meme le proprietaire du schema est refuse.
    PGPASSWORD="<FLYWAY_PASSWORD du Secret ocb-ledger-migration>" \
      psql -h localhost -U ledger_owner -d ledger -c "DELETE FROM ledger.posting_line"
    # attendu : ERROR: LEDGER_IMMUTABLE: DELETE refuse sur ledger.posting_line

    # Et le cloisonnement entre bases :
    PGPASSWORD="<LEDGER_DB_PASSWORD>" \
      psql -h localhost -U ledger_app -d payment -c "SELECT 1"
    # attendu : FATAL: permission denied for database "payment"

RESTE
exit "$echecs"
