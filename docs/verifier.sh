#!/usr/bin/env bash
#
# =====================================================================================
# Verifie la documentation.
#
# Deux choses qu'aucun compilateur ne verifie et qui pourrissent en silence :
#
#   - les liens relatifs entre fichiers Markdown, casses par un simple renommage ;
#   - les diagrammes Mermaid, dont une syntaxe invalide ne se voit qu'une fois la page
#     publiee, sous la forme d'une boite d'erreur rouge.
#
#     ./docs/verifier.sh
#
# La validation Mermaid demande Docker. Sans lui, elle est ignoree avec un avertissement
# plutot que de faire echouer la verification des liens, qui elle ne demande rien.
# =====================================================================================

set -euo pipefail

cd "$(dirname "$0")/.."

echec=0

printf '\n\033[1m--- Liens relatifs des fichiers Markdown\033[0m\n'

python3 - <<'PY' || echec=1
import io, os, re, sys

fichiers = []
for dirpath, dirnames, filenames in os.walk('.'):
    dirnames[:] = [d for d in dirnames if d not in ('.git', 'target', 'node_modules')]
    fichiers += [os.path.join(dirpath, f) for f in filenames if f.endswith('.md')]

motif = re.compile(r'\[[^\]]*\]\(([^)]+)\)')
casses = verifies = 0
for md in sorted(fichiers):
    for cible in motif.findall(io.open(md, encoding='utf-8').read()):
        if cible.startswith(('http://', 'https://', 'mailto:', '#')):
            continue
        chemin = cible.split('#')[0]
        if not chemin:
            continue
        verifies += 1
        if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(md), chemin))):
            casses += 1
            print('  CASSE  %s -> %s' % (md.replace(os.sep, '/'), cible))

print('  %d liens verifies, %d casses' % (verifies, casses))
sys.exit(1 if casses else 0)
PY

printf '\n\033[1m--- Diagrammes Mermaid\033[0m\n'

if ! command -v docker > /dev/null 2>&1; then
    printf '  Docker absent : validation Mermaid ignoree.\n'
    exit "$echec"
fi

travail=$(mktemp -d)
trap 'rm -rf "$travail"' EXIT

python3 - "$travail" <<'PY'
import io, os, re, sys

sortie = sys.argv[1]
n = 0
for dirpath, dirnames, filenames in os.walk('.'):
    dirnames[:] = [d for d in dirnames if d not in ('.git', 'target', 'node_modules')]
    for f in sorted(filenames):
        if not f.endswith('.md'):
            continue
        md = os.path.join(dirpath, f)
        blocs = re.findall(r'```mermaid\n(.*?)```', io.open(md, encoding='utf-8').read(), re.S)
        for i, b in enumerate(blocs, 1):
            n += 1
            nom = '%03d_%s_%d.mmd' % (n, os.path.basename(md).replace('.md', ''), i)
            io.open(os.path.join(sortie, nom), 'w', encoding='utf-8', newline='\n').write(b)
            print('  %s  (%s)' % (nom, md.replace(os.sep, '/')))
print('  %d diagramme(s) extrait(s)' % n)
PY

# Sous Git Bash, MSYS reecrit les chemins qui commencent par une barre oblique : /data
# devient C:/Program Files/Git/data, et le conteneur ne trouve rien. MSYS_NO_PATHCONV
# desactive cette reecriture, et cygpath donne au demon Docker un chemin hote qu'il
# comprend. Sur Linux, ni l'une ni l'autre n'a d'effet.
export MSYS_NO_PATHCONV=1
if command -v cygpath > /dev/null 2>&1; then
    monte=$(cygpath -m "$travail")
else
    monte="$travail"
fi

for f in "$travail"/*.mmd; do
    [ -e "$f" ] || break
    nom=$(basename "$f")
    if docker run --rm -v "$monte:/data" minlag/mermaid-cli:latest \
            -i "/data/$nom" -o "/data/$nom.svg" > /dev/null 2>&1 && [ -s "$f.svg" ]; then
        printf '  \033[32mOK\033[0m    %s\n' "$nom"
    else
        printf '  \033[31mECHEC\033[0m %s — Mermaid refuse ce diagramme\n' "$nom"
        echec=1
    fi
done

printf '\n'
if [ "$echec" -eq 0 ]; then
    printf '\033[32mDocumentation verifiee.\033[0m\n'
else
    printf '\033[31mDocumentation en echec.\033[0m\n'
fi
exit "$echec"
