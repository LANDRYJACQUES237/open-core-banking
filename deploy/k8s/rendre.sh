#!/usr/bin/env bash
#
# =====================================================================================
# Prepare TOUS les manifestes pour un namespace, en deux temps.
#
#   1. Les manifestes ecrits a la main (01 a 03, bastion) sont retargetes sur le
#      namespace demande.
#   2. Les manifestes applicatifs (04, 05) sont rendus depuis le chart Helm.
#
# Une seule commande apres chaque git pull. Le retargetage etait auparavant un sed a
# taper de memoire ; l'oublier depose les ressources dans le mauvais namespace, et
# l'erreur parle d'un droit manquant plutot que d'un namespace errone :
#
#     jobs.batch "..." is forbidden: User "..." cannot update resource "jobs"
#     in API group "batch" in the namespace "ocb"
#
#     ./deploy/k8s/rendre.sh [namespace]
#
# Produit deux fichiers, a appliquer DANS CET ORDRE :
#
#     deploy/k8s/04-migrations.yaml   les quatre Job de migration
#     deploy/k8s/05-services.yaml     les quatre Deployment et leurs Service
#
# ------------------------------------------------------------------------------------
# **Ce que ce detour coute, et il faut le savoir.**
#
# Le chart pose les Job de migration en hook `pre-install,pre-upgrade`. Helm garantit alors
# que si une migration echoue, la release echoue et les nouveaux pods ne sont JAMAIS
# deployes.
#
# Appliquer des manifestes rendus depuis une interface graphique perd cette garantie : rien
# n'empeche d'appliquer 05 avant que 04 n'ait reussi. Elle est remplacee par une procedure
# ordonnee, verifiee a l'oeil — ce qui est plus faible, et n'est pas la meme chose.
#
# Les annotations de hook sont donc RETIREES des manifestes produits. Les laisser
# donnerait a lire une garantie qui n'existe plus : sans Helm, ce ne sont que des
# annotations inertes.
#
# La sortie de ce compromis n'est pas d'ajouter une astuce, c'est d'obtenir un acces qui
# permette `helm upgrade --install`.
# ------------------------------------------------------------------------------------
# =====================================================================================

set -euo pipefail

cd "$(dirname "$0")/../.."

NAMESPACE="${1:-ocb}"
RELEASE=ocb

# Helm par conteneur : ce depot n'exige pas que Helm soit installe, et la version est ainsi
# la meme d'une machine a l'autre.
export MSYS_NO_PATHCONV=1
if command -v cygpath > /dev/null 2>&1; then
    monte=$(cygpath -m "$PWD")
else
    monte="$PWD"
fi

rendre() {
    docker run --rm -v "$monte:/apps" alpine/helm:3.16.2 template "$RELEASE" \
        /apps/deploy/helm/open-core-banking \
        --namespace "$NAMESPACE" \
        --values /apps/deploy/k8s/values-cluster.yaml \
        "$@"
}

# Le retrait des annotations de hook se fait sur le YAML rendu, avec awk plutot qu'avec
# une expression reguliere sur plusieurs lignes : un bloc d'annotations n'a pas de forme
# fixe, et sed le manquerait des qu'une annotation s'ajoute.
sans_hooks() {
    awk '
      /^ *"helm\.sh\/hook/ { next }
      { print }
    '
}

# Un fichier genere doit dire qu'il l'est, sinon quelqu'un le corrigera a la main et
# perdra sa correction au rendu suivant.
entete() {
    cat <<EOF
# =====================================================================================
# FICHIER GENERE — ne pas modifier a la main.
#
# Produit par deploy/k8s/rendre.sh depuis le chart Helm et values-cluster.yaml. Toute
# correction se fait dans le chart ou dans les valeurs, puis on regenere :
#
#     ./deploy/k8s/rendre.sh $NAMESPACE
#
# Les annotations de hook Helm ont ete retirees : sans Helm pour les honorer, elles
# donneraient a lire une garantie d'ordonnancement qui n'existe pas.
#
# REAPPLIQUER UN Job DEJA CREE ECHOUE. Le spec.template d'un Job est immuable : une
# seconde application renvoie
#
#     Job.batch "..." is invalid: spec.template: Invalid value: ...: field is immutable
#
# Il faut supprimer les Job puis les appliquer de nouveau. C'est sans risque : Flyway
# est idempotent, une migration deja appliquee est constatee et non rejouee.
#
# $1
# =====================================================================================

EOF
}

# Retargetage des manifestes ecrits a la main. Le placeholder `ocb` est remplace sur
# place ; relancer le script avec un autre namespace fonctionne, puisque la valeur
# precedente est elle aussi reconnue.
printf 'Retargetage des manifestes ecrits a la main...\n'
for f in deploy/k8s/01-postgres.yaml deploy/k8s/02-kafka.yaml \
         deploy/k8s/03-keycloak.yaml deploy/k8s/bastion.yaml; do
    sed -i -E "s/^  namespace: [A-Za-z0-9-]+$/  namespace: $NAMESPACE/" "$f"
done

printf 'Rendu des migrations...\n'
{
    entete "Appliquer CE fichier AVANT 05-services.yaml, et verifier que les quatre Job
# sont Completed avant de poursuivre."
    rendre --show-only templates/migration-job.yaml | sans_hooks
} > deploy/k8s/04-migrations.yaml

printf 'Rendu des services...\n'
{
    entete "N'appliquer qu'APRES la reussite des quatre Job de 04-migrations.yaml."
    rendre --show-only templates/deployment.yaml
    rendre --show-only templates/service.yaml
} > deploy/k8s/05-services.yaml

printf '\n  %-32s %s ressources\n' "04-migrations.yaml" "$(grep -c '^kind:' deploy/k8s/04-migrations.yaml)"
printf '  %-32s %s ressources\n' "05-services.yaml" "$(grep -c '^kind:' deploy/k8s/05-services.yaml)"
printf '\n  Namespace cible : %s (%s fichiers)\n' "$NAMESPACE" \
    "$(grep -l "namespace: $NAMESPACE" deploy/k8s/*.yaml | wc -l)"
printf '  Aucune annotation de hook ne subsiste : %s\n' \
    "$(grep -c 'helm.sh/hook' deploy/k8s/04-migrations.yaml || true)"
