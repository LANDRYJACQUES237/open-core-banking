#!/usr/bin/env bash
#
# =====================================================================================
# Rend les manifestes applicatifs a partir du chart Helm.
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
# $1
# =====================================================================================

EOF
}

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
printf '\n  Namespace cible : %s\n' "$NAMESPACE"
printf '  Aucune annotation de hook ne subsiste : %s\n' \
    "$(grep -c 'helm.sh/hook' deploy/k8s/04-migrations.yaml || true)"
