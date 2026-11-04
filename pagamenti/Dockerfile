# syntax=docker/dockerfile:1
#
# Build "multi-stage": due immagini in sequenza.
#   1) "build": contiene JDK + Maven, compila e produce il jar
#   2) finale: contiene solo il JRE + il jar  -> immagine piccola, senza
#      compilatore né sorgenti (meno superficie d'attacco)

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

# --mount=type=cache: la cartella ~/.m2 (dipendenze scaricate) è una cache di
# BuildKit che sopravvive tra una build e l'altra -> niente riscaricamento
# delle dipendenze a ogni modifica del codice.
# -B = batch mode (log senza colori/animazioni), -DskipTests = test esclusi
# dalla build dell'immagine.
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Utente non-root: se l'applicazione venisse compromessa, non è root nel container
RUN addgroup -S spring && adduser -S spring -G spring

# Prendiamo dallo stage "build" solo il jar eseguibile
COPY --from=build /workspace/target/*.jar app.jar

USER spring

# Solo documentazione: la porta su cui ascolta l'app (server.port)
EXPOSE 8085

# Forma "exec" (array JSON): java è il processo PID 1 e riceve direttamente
# i segnali di stop (SIGTERM) -> spegnimento pulito con docker stop
ENTRYPOINT ["java", "-jar", "app.jar"]
