# syntax=docker/dockerfile:1
#
# Stessa idea multi-stage dei servizi Spring:
#   1) "build": installa TUTTE le dipendenze (anche TypeScript e Nest CLI) e
#      compila src/*.ts in dist/*.js
#   2) finale: solo le dipendenze di produzione + il JavaScript compilato

# ---------- Stage 1: build ----------
FROM node:24-alpine AS build
WORKDIR /app

# Prima solo package*.json: finché non cambiano le dipendenze, Docker riusa dalla
# cache il layer di "npm ci" anche se modifichiamo il codice.
COPY package.json package-lock.json ./
# npm ci = installa esattamente le versioni di package-lock.json (build riproducibile)
RUN npm ci

COPY tsconfig.json tsconfig.build.json nest-cli.json ./
COPY src ./src
RUN npm run build

# ---------- Stage 2: runtime ----------
FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production

COPY package.json package-lock.json ./
# Solo "dependencies", niente "devDependencies" (TypeScript, CLI...)
RUN npm ci --omit=dev

COPY --from=build /app/dist ./dist

# L'immagine node ha già un utente non-root chiamato "node"
USER node

EXPOSE 8086

# Avviamo node direttamente (non "npm start"): node è PID 1 e riceve SIGTERM.
# --disable-warning: kafkajs 2.2.4 chiama setTimeout con un ritardo negativo
# (il suo "throttledUntil" parte da -1); Node 24 lo tratta come 1 ms, come
# prima, ma a ogni volta stampa un TimeoutNegativeWarning. Nient'altro.
CMD ["node", "--disable-warning=TimeoutNegativeWarning", "dist/main.js"]
