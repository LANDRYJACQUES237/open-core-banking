#!/usr/bin/env bash
#
# =====================================================================================
# Parcours de verification de la plateforme assemblee.
#
# Ce script n'est pas une demonstration : c'est une SUITE D'ASSERTIONS. Chaque etape
# echoue bruyamment si la plateforme ne se comporte plus comme docs/DEMARRAGE.md le
# decrit. C'est ce qui empeche ce guide de mentir dans six mois.
#
# Il suppose la pile deja demarree :
#
#     docker compose -f deploy/docker/docker-compose.yml up -d --build
#     ./deploy/parcours.sh
#
# Les adresses sont surchargeables, pour un poste qui heberge deja autre chose :
#
#     LEDGER_URL=http://localhost:18081 PAYMENT_URL=http://localhost:18082 ./deploy/parcours.sh
# =====================================================================================

set -euo pipefail

LEDGER_URL="${LEDGER_URL:-http://localhost:8081}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
PROVIDER_URL="${PROVIDER_URL:-http://localhost:8083}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8084}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
COMPOSE="${COMPOSE:-docker compose -f deploy/docker/docker-compose.yml}"

TOKEN_URL="$KEYCLOAK_URL/realms/ocb/protocol/openid-connect/token"

# Suffixe unique : le parcours doit pouvoir etre rejoue sur une pile deja utilisee sans
# heurter les cles d'idempotence de l'execution precedente.
RUN="${RUN_ID:-$(date +%s)}"

# Un runner Ubuntu n'expose que python3 ; Git Bash sous Windows n'expose souvent que
# python. Resoudre l'interpreteur une fois evite un echec qui ne dirait pas sa cause.
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
    printf 'Python est requis pour lire les reponses JSON et decoder les jetons.\n' >&2
    exit 2
fi

etape=0
echecs=0

titre() {
    etape=$((etape + 1))
    printf '\n\033[1m--- %d. %s\033[0m\n' "$etape" "$1"
}

ok() { printf '  \033[32mOK\033[0m   %s\n' "$1"; }

echec() {
    printf '  \033[31mECHEC\033[0m %s\n' "$1"
    echecs=$((echecs + 1))
}

# attendu_recu <attendu> <recu> <description>
verifier() {
    if [ "$1" = "$2" ]; then ok "$3 ($2)"; else echec "$3 : attendu $1, recu $2"; fi
}

contient() {
    if printf '%s' "$1" | grep -q -- "$2"; then ok "$3"; else echec "$3 — absent de la reponse"; fi
}

ne_contient_pas() {
    if printf '%s' "$1" | grep -q -- "$2"; then echec "$3 — present alors qu'il ne devrait pas"; else ok "$3"; fi
}

# Retourne le jeton, ou echoue en disant ce que le fournisseur d'identite a repondu.
#
# Sans ce garde-fou, un Keycloak pas encore la fait mourir le script sur une trace Python
# qui parle de JSON — un message qui ne designe pas la cause.
jeton() {
    reponse=$(curl -sS -m 15 -d grant_type=client_credentials \
        -d "client_id=$1" -d "client_secret=$2" "$TOKEN_URL" 2>/dev/null || true)
    extrait=$(printf '%s' "$reponse" | "$PY" -c \
        'import sys,json;print(json.load(sys.stdin)["access_token"])' 2>/dev/null || true)
    if [ -z "$extrait" ]; then
        printf 'Impossible d obtenir un jeton pour %s.\nReponse du fournisseur : %s\n' \
            "$1" "${reponse:-<vide>}" >&2
        exit 2
    fi
    printf '%s' "$extrait"
}

revendication() {
    printf '%s' "$1" | "$PY" -c '
import sys, json, base64
t = sys.stdin.read().strip().split(".")[1]
t += "=" * (-len(t) % 4)
c = json.loads(base64.urlsafe_b64decode(t))
v = c.get(sys.argv[1], "")
print(" ".join(v) if isinstance(v, list) else v)
' "$2"
}

code() { curl -sS -m 20 -o /dev/null -w '%{http_code}' "$@" 2>/dev/null; }

sql() {
    # shellcheck disable=SC2086
    $COMPOSE exec -T "$@" 2>&1
}

# =====================================================================================

titre "Les quatre services sont prets"

for couple in "grand livre|$LEDGER_URL" "paiement|$PAYMENT_URL" "operateurs|$PROVIDER_URL" "notification|$NOTIFICATION_URL"; do
    nom="${couple%%|*}"; url="${couple##*|}"
    for _ in $(seq 1 60); do
        c=$(code "$url/actuator/health/readiness" || true)
        [ "$c" = "200" ] && break
        sleep 2
    done
    verifier 200 "${c:-000}" "$nom repond a la sonde de disponibilite"
done

# Keycloak importe son realm au demarrage et met plus longtemps que les services. Rien de
# ce qui suit ne peut se passer de lui : l'attendre ici evite un echec quelques lignes plus
# loin, dont la cause serait ailleurs que la ou on la chercherait.
kc=000
for _ in $(seq 1 90); do
    kc=$(code "$KEYCLOAK_URL/realms/ocb/.well-known/openid-configuration" || true)
    [ "$kc" = "200" ] && break
    sleep 2
done
verifier 200 "$kc" "le fournisseur d'identite a importe son realm"

# -------------------------------------------------------------------------------------
titre "L'audience est portee par la portee, pas par le client"

TM=$(jeton merchant-demo dev-only-merchant-demo)
TM2=$(jeton merchant-second dev-only-merchant-second)
TP=$(jeton payment-service dev-only-payment-service)
TO=$(jeton ops-console dev-only-ops-console)

verifier "payment-service" "$(revendication "$TM" aud)" "le marchand recoit l'audience du service de paiement"
verifier "ledger-service" "$(revendication "$TP" aud)" "le compte de service recoit l'audience du grand livre"
contient "$(revendication "$TM" scope)" "payment:initiate" "le marchand porte la portee d'initiation"
ne_contient_pas "$(revendication "$TM" scope)" "ledger:post" "le marchand ne porte aucune portee d'ecriture comptable"

# -------------------------------------------------------------------------------------
titre "Un jeton valide pour un service est refuse par un autre"

verifier 401 "$(code -X POST "$LEDGER_URL/v1/journal-entries" -H "Authorization: Bearer $TM" -H 'Content-Type: application/json' -d '{}')" \
    "le jeton marchand est refuse par le grand livre"
verifier 401 "$(code -X POST "$PAYMENT_URL/v1/collections" -H 'Content-Type: application/json' -d '{}')" \
    "aucun jeton, aucun acces"
# L'exploitation porte ledger:read : bonne audience, donc authentifiee ; pas
# ledger:post, donc refusee a l'ecriture. C'est la difference entre « je ne sais pas
# qui tu es » (401) et « je sais qui tu es, et non » (403).
verifier 200 "$(code "$LEDGER_URL/v1/accounts/1100" -H "Authorization: Bearer $TO")" \
    "l'exploitation, qui porte ledger:read, peut lire"
verifier 403 "$(code -X POST "$LEDGER_URL/v1/journal-entries" -H "Authorization: Bearer $TO" -H 'Content-Type: application/json' -d '{}')" \
    "l'exploitation, authentifiee mais sans ledger:post, ne peut pas ecrire"

# -------------------------------------------------------------------------------------
titre "Un encaissement traverse la plateforme"

REP=$(curl -sS -m 20 -X POST "$PAYMENT_URL/v1/collections" \
    -H "Authorization: Bearer $TM" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-encaissement" \
    -d "{\"externalRef\":\"PARCOURS-$RUN-C\",\"amount\":\"5000\",\"currency\":\"XAF\",\"payerMsisdn\":\"+237690000001\",\"walletAccountRef\":\"WALLET-PARCOURS-$RUN\",\"providerCode\":\"MTN_MOMO\"}")

contient "$REP" '"status":"PENDING_PROVIDER"' "la transaction attend l'operateur"
contient "$REP" '"platformFee":"50"' "les frais de plateforme sont appliques"
contient "$REP" '\*\*\*\*' "le numero du payeur est masque dans la reponse"
ne_contient_pas "$REP" '690000001' "le numero en clair n'apparait nulle part"

TXID=$(printf '%s' "$REP" | "$PY" -c 'import sys,json;print(json.load(sys.stdin)["transactionId"])')

# -------------------------------------------------------------------------------------
titre "Le rejeu ne cree pas de seconde transaction"

REJEU=$(curl -sS -m 20 -w '\n%{http_code}' -X POST "$PAYMENT_URL/v1/collections" \
    -H "Authorization: Bearer $TM" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-encaissement" \
    -d "{\"externalRef\":\"PARCOURS-$RUN-C\",\"amount\":\"5000\",\"currency\":\"XAF\",\"payerMsisdn\":\"+237690000001\",\"walletAccountRef\":\"WALLET-PARCOURS-$RUN\",\"providerCode\":\"MTN_MOMO\"}")

verifier 200 "$(printf '%s' "$REJEU" | tail -n1)" "le rejeu repond 200, et non 202"
contient "$REJEU" "$TXID" "le rejeu retourne la meme transaction"

# -------------------------------------------------------------------------------------
titre "Deux marchands, la meme cle, deux transactions"

AUTRE=$(curl -sS -m 20 -X POST "$PAYMENT_URL/v1/collections" \
    -H "Authorization: Bearer $TM2" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-encaissement" \
    -d "{\"externalRef\":\"PARCOURS-$RUN-AUTRE\",\"amount\":\"7000\",\"currency\":\"XAF\",\"payerMsisdn\":\"+237690000002\",\"walletAccountRef\":\"WALLET-AUTRE-$RUN\",\"providerCode\":\"MTN_MOMO\"}")

AUTRE_ID=$(printf '%s' "$AUTRE" | "$PY" -c 'import sys,json;print(json.load(sys.stdin)["transactionId"])')

if [ "$AUTRE_ID" != "$TXID" ]; then
    ok "le second marchand obtient sa propre transaction, pas celle du premier"
else
    echec "cloisonnement rompu : les deux marchands partagent la transaction $TXID"
fi

# -------------------------------------------------------------------------------------
titre "L'outbox a publie, et l'operateur a recu la commande"

for _ in $(seq 1 30); do
    RESTE=$(sql payment-db psql -tAU payment_owner -d payment \
        -c "SELECT count(*) FROM payment.outbox_event WHERE published_at IS NULL" | tr -d '[:space:]')
    [ "$RESTE" = "0" ] && break
    sleep 2
done
verifier 0 "${RESTE:-?}" "aucun evenement ne reste en attente dans l'outbox"

for _ in $(seq 1 30); do
    OPS=$(sql provider-db psql -tAU provider_owner -d provider \
        -c "SELECT count(*) FROM provider.provider_operation WHERE external_ref = 'PARCOURS-$RUN-C'" | tr -d '[:space:]')
    [ "$OPS" = "1" ] && break
    sleep 2
done
verifier 1 "${OPS:-?}" "provider-service a cree l'operation correspondante"

# -------------------------------------------------------------------------------------
titre "Le grand livre ne peut etre reecrit par personne"

REF="PARCOURS-$RUN-ECRITURE"
curl -sS -m 20 -o /dev/null -X POST "$LEDGER_URL/v1/accounts" \
    -H "Authorization: Bearer $TP" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-compte" \
    -d "{\"accountNumber\":\"2100.PARCOURS-$RUN\",\"accountType\":\"LIABILITY\",\"currency\":\"XAF\"}" || true

verifier 201 "$(code -X POST "$LEDGER_URL/v1/journal-entries" \
    -H "Authorization: Bearer $TP" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-ecriture" \
    -d "{\"entryRef\":\"$REF\",\"description\":\"Parcours de verification\",\"lines\":[{\"accountNumber\":\"1100\",\"direction\":\"DR\",\"amount\":\"5000\",\"currency\":\"XAF\"},{\"accountNumber\":\"2100.PARCOURS-$RUN\",\"direction\":\"CR\",\"amount\":\"5000\",\"currency\":\"XAF\"}]}")" \
    "une ecriture equilibree est acceptee"

APP=$(sql -e PGPASSWORD="${LEDGER_DB_PASSWORD:-app-secret}" ledger-db \
    psql -U "${LEDGER_APP_ROLE:-ledger_app}" -d ledger -c "DELETE FROM ledger.posting_line" || true)
contient "$APP" "permission denied" "premiere couche : l'utilisateur applicatif n'a pas le droit d'essayer"

PROPRIO=$(sql ledger-db psql -U ledger_owner -d ledger -c "DELETE FROM ledger.posting_line" || true)
contient "$PROPRIO" "LEDGER_IMMUTABLE" "seconde couche : le proprietaire du schema est refuse par le declencheur"

DESEQ=$(curl -sS -m 20 -X POST "$LEDGER_URL/v1/journal-entries" \
    -H "Authorization: Bearer $TP" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-desequilibre" \
    -d "{\"entryRef\":\"$REF-KO\",\"description\":\"Desequilibree\",\"lines\":[{\"accountNumber\":\"1100\",\"direction\":\"DR\",\"amount\":\"5000\",\"currency\":\"XAF\"},{\"accountNumber\":\"2100.PARCOURS-$RUN\",\"direction\":\"CR\",\"amount\":\"4000\",\"currency\":\"XAF\"}]}" \
    -w '\n%{http_code}')
verifier 422 "$(printf '%s' "$DESEQ" | tail -n1)" "une ecriture desequilibree est refusee"

# -------------------------------------------------------------------------------------
titre "Un portefeuille ne peut pas financer deux decaissements qu'il ne couvre qu'une fois"

PORTE="2100.CONCURRENCE-$RUN"
curl -sS -m 20 -o /dev/null -X POST "$LEDGER_URL/v1/accounts" \
    -H "Authorization: Bearer $TP" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-porte" \
    -d "{\"accountNumber\":\"$PORTE\",\"accountType\":\"LIABILITY\",\"currency\":\"XAF\"}"

# Le portefeuille est approvisionne de 10 000. Deux decaissements de 6 000 sont demandes
# en meme temps : un seul est finançable.
curl -sS -m 20 -o /dev/null -X POST "$LEDGER_URL/v1/journal-entries" \
    -H "Authorization: Bearer $TP" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: parcours-$RUN-approvisionnement" \
    -d "{\"entryRef\":\"$REF-FUND\",\"description\":\"Approvisionnement\",\"lines\":[{\"accountNumber\":\"1100\",\"direction\":\"DR\",\"amount\":\"10000\",\"currency\":\"XAF\"},{\"accountNumber\":\"$PORTE\",\"direction\":\"CR\",\"amount\":\"10000\",\"currency\":\"XAF\"}]}"

decaisser() {
    curl -sS -m 30 -o /dev/null -w '%{http_code}' -X POST "$PAYMENT_URL/v1/disbursements" \
        -H "Authorization: Bearer $TM" -H 'Content-Type: application/json' \
        -H "Idempotency-Key: parcours-$RUN-decaissement-$1" \
        -d "{\"externalRef\":\"PARCOURS-$RUN-D$1\",\"amount\":\"6000\",\"currency\":\"XAF\",\"payeeMsisdn\":\"+23767000000$1\",\"walletAccountRef\":\"$PORTE\",\"providerCode\":\"MTN_MOMO\"}"
}

decaisser 1 > "/tmp/parcours-d1-$RUN" &
P1=$!
decaisser 2 > "/tmp/parcours-d2-$RUN" &
P2=$!
wait $P1 $P2 || true
D1=$(cat "/tmp/parcours-d1-$RUN"); D2=$(cat "/tmp/parcours-d2-$RUN")
rm -f "/tmp/parcours-d1-$RUN" "/tmp/parcours-d2-$RUN"

printf '  reponses concurrentes : %s et %s\n' "$D1" "$D2"
ACCEPTES=0
for c in "$D1" "$D2"; do [ "$c" = "202" ] && ACCEPTES=$((ACCEPTES + 1)); done
REFUSES=0
for c in "$D1" "$D2"; do [ "$c" = "422" ] && REFUSES=$((REFUSES + 1)); done

verifier 1 "$ACCEPTES" "un seul decaissement est accepte"
verifier 1 "$REFUSES"  "l'autre est refuse pour fonds insuffisants"

SOLDE=$(sql ledger-db psql -tAU ledger_owner -d ledger -c \
    "SELECT coalesce(sum(CASE WHEN direction='CR' THEN amount ELSE -amount END),0) FROM ledger.posting_line pl JOIN ledger.account a ON a.id = pl.account_id WHERE a.account_number = '$PORTE'" | tr -d '[:space:]')
if printf '%s' "$SOLDE" | grep -qE '^-'; then
    echec "le portefeuille est passe en negatif : $SOLDE"
else
    ok "le portefeuille n'est jamais passe en negatif (solde $SOLDE)"
fi

# -------------------------------------------------------------------------------------
titre "Un service redemarre pendant une panne du fournisseur d'identite"

# shellcheck disable=SC2086
$COMPOSE stop keycloak > /dev/null 2>&1
# shellcheck disable=SC2086
$COMPOSE restart payment-service > /dev/null 2>&1

PRET=000
for _ in $(seq 1 60); do
    PRET=$(code "$PAYMENT_URL/actuator/health/readiness" || true)
    [ "$PRET" = "200" ] && break
    sleep 3
done
verifier 200 "$PRET" "payment-service demarre alors que Keycloak est arrete"

# shellcheck disable=SC2086
$COMPOSE start keycloak > /dev/null 2>&1
for _ in $(seq 1 60); do
    printf '%s' "$($COMPOSE ps keycloak --format '{{.Status}}')" | grep -q healthy && break
    sleep 3
done

TM3=$(jeton merchant-demo dev-only-merchant-demo)
verifier 200 "$(code "$PAYMENT_URL/v1/transactions/$TXID" -H "Authorization: Bearer $TM3")" \
    "le service accepte un jeton neuf sans avoir ete redemarre"

# =====================================================================================

printf '\n'
if [ "$echecs" -eq 0 ]; then
    printf '\033[32mParcours complet : %d etapes, aucune assertion en echec.\033[0m\n' "$etape"
    exit 0
fi
printf '\033[31mParcours en echec : %d assertion(s) non tenue(s) sur %d etapes.\033[0m\n' "$echecs" "$etape"
exit 1
