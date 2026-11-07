# syntax=docker/dockerfile:1
#
# Front-end React, build "multi-stage" come i servizi:
#   1) "build": Node installa le dipendenze, esegue i test e la build di Vite
#      -> dist/ (HTML, JavaScript, CSS, font e immagini)
#   2) finale: solo nginx con i file di dist/. Niente Node, niente sorgenti,
#      niente node_modules.

# ---------- Stage 1: build ----------
FROM node:24-alpine AS build
WORKDIR /app

# Prima solo package*.json: finché le dipendenze non cambiano, Docker riusa dalla
# cache il layer di "npm ci" anche se modifichiamo il codice.
COPY package.json package-lock.json ./
# npm ci = esattamente le versioni di package-lock.json (build riproducibile).
# La cache di npm è una cache di BuildKit: sopravvive tra una build e l'altra.
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
# I test (Vitest, meno di un secondo) prima della build: se un test fallisce
# l'immagine non si costruisce
RUN npm test
# tsc -b (controllo dei tipi) + vite build -> /app/dist
RUN npm run build

# ---------- Stage 2: runtime ----------
# nginx "unprivileged": gira come utente nginx (uid 101) e non come root, per
# questo ascolta sulla 8080 invece che sulla 80. Ramo stable 1.30.
FROM nginxinc/nginx-unprivileged:1.30-alpine

# La nostra configurazione al posto di quella di esempio
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/intestazioni.conf /etc/nginx/ginvault/intestazioni.conf

# "worker_processes auto" avvia un processo per ogni CPU che nginx vede: sul nodo
# di Minikube (e in Docker Desktop) sono 32, anche se il container ne può usare
# una frazione. Per servire file statici ne bastano 2. Il grep ferma la build se
# la riga dell'immagine base cambia e la sostituzione non avviene.
RUN sed -i -E 's/^worker_processes[[:space:]]+auto;/worker_processes 2;/' /etc/nginx/nginx.conf \
 && grep -q '^worker_processes 2;' /etc/nginx/nginx.conf

# Dallo stage "build" solo il risultato della build
COPY --from=build /app/dist /usr/share/nginx/html

# Solo documentazione: la porta su cui ascolta nginx
EXPOSE 8080

# L'immagine base avvia già "nginx -g 'daemon off;'" in primo piano (PID 1):
# riceve direttamente i segnali di stop
