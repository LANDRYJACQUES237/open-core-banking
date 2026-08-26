{{/*
Nom d'une ressource de service. Prefixe par la release, pour que deux installations dans
un meme namespace ne se marchent pas dessus.
*/}}
{{- define "ocb.name" -}}
{{- printf "%s-%s" .root.Release.Name .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Etiquettes communes. app.kubernetes.io/component distingue les quatre services d'une meme
release ; sans lui, tous les pods porteraient les memes etiquettes et aucun selecteur ne
saurait les separer.
*/}}
{{- define "ocb.labels" -}}
helm.sh/chart: {{ .root.Chart.Name }}-{{ .root.Chart.Version }}
app.kubernetes.io/name: {{ .root.Chart.Name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: open-core-banking
app.kubernetes.io/component: {{ .name }}
{{- end -}}

{{- define "ocb.selectorLabels" -}}
app.kubernetes.io/name: {{ .root.Chart.Name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .name }}
{{- end -}}

{{/*
Reference d'image. L'etiquette retombe sur appVersion quand image.tag est vide, pour
qu'une livraison ne demande pas de modifier deux endroits.
*/}}
{{- define "ocb.image" -}}
{{- $tag := .root.Values.image.tag | default .root.Chart.AppVersion -}}
{{- printf "%s/%s:%s" .root.Values.image.registry .repository $tag -}}
{{- end -}}

{{/*
Contexte de securite du conteneur, identique pour tous.

readOnlyRootFilesystem impose le volume ephemere sur /tmp declare dans le Deployment :
Tomcat et la JVM y ecrivent, et une racine en lecture seule sans ce volume produit un
echec tardif dont le message ne designe pas la cause.

runAsUser reprend l'UID inscrit dans l'image. Kubernetes ne sait pas resoudre un nom
d'utilisateur : sans UID numerique, runAsNonRoot ne peut rien verifier et refuse de
demarrer le conteneur.
*/}}
{{- define "ocb.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
capabilities:
  drop: [ALL]
seccompProfile:
  type: RuntimeDefault
{{- end -}}
