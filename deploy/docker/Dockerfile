# =====================================================================================
# Image d'un service OCB.
#
# Un seul fichier pour les quatre services, choisi par l'argument SERVICE. L'etape de
# construction est identique d'un service a l'autre, donc BuildKit la reutilise : le
# reacteur Maven n'est compile qu'une fois pour les quatre images.
# =====================================================================================

# --- Construction --------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src

# Les descripteurs d'abord, les sources ensuite.
#
# Une couche qui ne contient que les pom.xml ne change qu'a l'ajout d'une dependance,
# alors que les sources changent a chaque commit. Telechargeer les dependances dans cette
# couche-la evite de recommencer a chaque modification de code.
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY platform/pom.xml platform/
COPY platform/common-domain/pom.xml platform/common-domain/
COPY platform/common-web/pom.xml platform/common-web/
COPY platform/common-events/pom.xml platform/common-events/
COPY platform/common-outbox/pom.xml platform/common-outbox/
COPY platform/common-security/pom.xml platform/common-security/
COPY platform/common-kafka/pom.xml platform/common-kafka/
COPY services/pom.xml services/
COPY services/ledger-service/pom.xml services/ledger-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/provider-service/pom.xml services/provider-service/
COPY services/notification-service/pom.xml services/notification-service/

# MAVEN_CONFIG est neutralise a chaque appel.
#
# L'image maven la positionne a /root/.m2 ; le script mvnw l'ajoute alors telle quelle a
# la ligne de commande, et Maven la prend pour une phase de cycle de vie. Le message
# d'erreur — « Unknown lifecycle phase "/root/.m2" » — ne designe pas la cause.
#
# Le wrapper est conserve plutot que le mvn de l'image : c'est ainsi que la construction
# utilise exactement la version de Maven que l'integration continue utilise.
RUN MAVEN_CONFIG= ./mvnw -B -ntp dependency:go-offline -DskipTests || true

COPY contracts contracts
COPY platform platform
COPY services services

# Les tests ne tournent pas ici : ils demandent Docker, et l'integration continue les a
# deja executes. Les relancer dans une image ne prouverait rien de plus et ferait dependre
# la construction d'un demon Docker imbrique.
RUN MAVEN_CONFIG= ./mvnw -B -ntp -DskipTests package

# --- Execution -----------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG SERVICE
RUN test -n "$SERVICE" || (echo "L'argument SERVICE est obligatoire" && false)

# Utilisateur sans privileges, avec un UID NUMERIQUE explicite.
#
# Un service de paiement compromis ne doit pas s'executer en root : la difference entre
# « l'attaquant lit les fichiers de l'application » et « l'attaquant controle le conteneur »
# tient a cette ligne.
#
# L'UID est fixe a 10001 plutot que laisse au hasard d'adduser, parce que Kubernetes ne
# sait pas resoudre un nom d'utilisateur : avec un USER nomme, la garde runAsNonRoot ne
# peut pas verifier que l'utilisateur n'est pas root et refuse de demarrer le conteneur.
# Un nom suffit sous Docker et echoue sous Kubernetes — exactement le genre d'ecart qui ne
# se decouvre qu'au deploiement.
RUN addgroup -g 10001 -S ocb && adduser -u 10001 -S -G ocb ocb

WORKDIR /app
COPY --from=build --chown=ocb:ocb /src/services/${SERVICE}/target/${SERVICE}-*.jar /app/app.jar

USER 10001:10001

# Le nom du service est inscrit dans l'image plutot que passe au demarrage : une image
# doit savoir ce qu'elle est.
ENV OCB_SERVICE=${SERVICE}

# Pas de -Xmx fixe : depuis JDK 10 la JVM lit les limites du cgroup et dimensionne son tas
# en consequence. Un -Xmx code en dur ici ignorerait la limite reelle du conteneur et
# provoquerait soit du gaspillage, soit un arret par manque de memoire.
# Repertoire temporaire designe explicitement.
#
# Le chart Helm monte la racine en lecture seule et fournit un volume ephemere sur /tmp.
# Sans cette variable, une JVM confrontee a un /tmp non inscriptible echoue tard et pour
# une raison qui ne designe pas la cause.
ENV TMPDIR=/tmp

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
