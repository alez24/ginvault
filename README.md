# GinVault

Progetto d'esame per il corso TAASS: backend a microservizi con API Gateway,
Kafka e un database PostgreSQL per ogni servizio, avviato con Docker Compose
e, in parte, su Kubernetes (Minikube).
Il piano completo è in [`docs/piano.md`](docs/piano.md).

> **Stato: fondamenta, sicurezza OAuth2/JWT, Kubernetes e la storia di Marco.**
> Ogni servizio si avvia, ha `GET /health`, il proprio database e un endpoint di
> prova protetto da ruolo. Ricette ed Edizioni hanno la logica di dominio:
> formulazioni con prove di assaggio, edizioni validate da Ricette via REST,
> tiratura e ricalcolo. Su Minikube girano gateway, edizioni, ricette e i loro
> database dietro un Ingress, con i database isolati da NetworkPolicy
> ([`k8s/README.md`](k8s/README.md)).
> Mancano ancora Kafka tra i servizi, gRPC e gli altri servizi su Kubernetes.

## Architettura

```
                    browser
                       │  cookie SESSION (il JWT non arriva mai qui)
                       │  :8080  (unica porta esposta)
                 ┌─────▼─────┐   login OAuth2   ┌─────────────────┐
                 │  gateway  │ ◀──────────────▶ │ Google / GitHub │
                 └─────┬─────┘                  └─────────────────┘
   rete "backend"      │  /api/<servizio>/**  + Authorization: Bearer <JWT GinVault>
 ┌──────────┬──────────┼──────────┬──────────┬───────────┬───────────┐
 edizioni   ricette    produzione magazzino  pagamenti   notifiche   kafka
  :8081      :8082      :8083      :8084      :8085       :8086      :9092
   │          │          │          │          │         (NestJS)   (KRaft)
 edizioni-db ricette-db produzione-db magazzino-db pagamenti-db
   (ognuno su una rete privata condivisa SOLO con il proprio servizio)
```

| Servizio    | Tecnologia                  | Porta | Database (porta in sviluppo) | `GET /prova` richiede |
|-------------|-----------------------------|-------|------------------------------|-----------------------|
| gateway     | Spring Cloud Gateway        | 8080  | –                            | –                     |
| edizioni    | Spring Boot 3.5 / Java 21   | 8081  | `edizioni-db` (5441)         | SOCIO                 |
| ricette     | Spring Boot 3.5 / Java 21   | 8082  | `ricette-db` (5442)          | DISTILLATORE          |
| produzione  | Spring Boot 3.5 / Java 21   | 8083  | `produzione-db` (5443)       | DISTILLATORE          |
| magazzino   | Spring Boot 3.5 / Java 21   | 8084  | `magazzino-db` (5444)        | RESP_ACQUISTI         |
| pagamenti   | Spring Boot 3.5 / Java 21   | 8085  | `pagamenti-db` (5445)        | CLIENTE               |
| notifiche   | NestJS 12 / Node 24         | 8086  | –                            | ADMIN                 |
| kafka       | Apache Kafka 4.3 (KRaft)    | 9092  | –                            | –                     |

## Struttura del repository

```
ginvault/
├── docker-compose.yml            # tutto il sistema (production mode)
├── docker-compose.override.yml   # solo sviluppo: porte dei DB sul portatile
├── .env.example                  # modello del file .env (segreti OAuth, ruoli)
├── edizioni/                     # un progetto Maven indipendente per servizio
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/{main,test}/...
├── ricette/  produzione/  magazzino/  pagamenti/   # stessa struttura
├── gateway/                      # Spring Cloud Gateway: login, sessione, JWT
├── notifiche/                    # NestJS
├── k8s/                          # manifest Kubernetes (Minikube): vedi k8s/README.md
├── frontend/                     # (vuoto per ora)
└── docs/piano.md
```

## Prerequisiti

- **Docker Desktop** avviato. Tutto lo stack occupa circa **2,1 GB di RAM**.
- Non servono né Java né Maven né Node installati: la compilazione avviene
  dentro i container (Dockerfile multi-stage).
- **Un file `.env`** con le credenziali OAuth (vedi sotto). Senza `.env`,
  `docker compose` si ferma subito con il messaggio
  `required variable GOOGLE_CLIENT_ID is missing a value`.
  Vale per **ogni** comando `docker compose`, anche `logs` ed `exec`.

### Configurare il login (una volta sola)

1. Copia il modello: `cp .env.example .env` (PowerShell: `Copy-Item .env.example .env`).
   Il file `.env` non viene committato (è nel `.gitignore`).
2. **Google**: [Cloud Console](https://console.cloud.google.com) → *API e servizi* →
   *Credenziali* → *Crea credenziali* → *ID client OAuth* → tipo *Applicazione web*.
   - URI di reindirizzamento autorizzati: `http://localhost:8080/login/oauth2/code/google`
     (Compose) e `http://localhost/login/oauth2/code/google` (Kubernetes)
   - Se la schermata di consenso è in modalità *Testing*, aggiungi gli account
     del gruppo tra gli *utenti di test*.
   - Copia ID client e secret in `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET`.
3. **GitHub**: *Settings* → *Developer settings* → *OAuth Apps* → *New OAuth App*.
   - Homepage URL: `http://localhost:8080`
   - Authorization callback URL: `http://localhost:8080/login/oauth2/code/github`
   - Copia Client ID e un nuovo Client secret in `GITHUB_CLIENT_ID` e `GITHUB_CLIENT_SECRET`.
   - Un'OAuth App ha **un solo** callback URL. Per gli indirizzi di loopback
     GitHub accetta un `redirect_uri` con una porta diversa da quella del
     callback (documentato per `127.0.0.1`). Con il callback
     `http://localhost/login/oauth2/code/github` il login su Kubernetes è
     verificato; quello di Compose (porta 8080) va riverificato.
4. **Ruoli**: negli elenchi `RUOLI_*` scrivi le identità separate da virgola,
   nella forma `google:<email>` o `github:<login>`. Esempio:
   ```
   RUOLI_SOCIO=google:mario.rossi@gmail.com
   RUOLI_ADMIN=github:mrossi,google:mario.rossi@gmail.com
   ```
   Un utente può avere più ruoli. Chi non è in nessun elenco è **CLIENTE**.
   I ruoli vengono letti al login: dopo averli cambiati bisogna riavviare il
   gateway (`docker compose up -d gateway`) e rifare il login.

## Avvio

```bash
# Sviluppo: legge docker-compose.yml + docker-compose.override.yml (porte dei DB esposte)
docker compose up -d --build --wait

# Production mode (demo d'esame): legge SOLO docker-compose.yml
docker compose -f docker-compose.yml up -d --build --wait
```

Il primo avvio richiede qualche minuto, perché scarica le immagini e le
dipendenze Maven/npm. I successivi sono molto più rapidi grazie alla cache.

### Su Kubernetes (Minikube)

Gateway, edizioni, ricette e i loro database, esposti con un Ingress su `http://localhost`:
prerequisiti, avvio, spiegazione di ogni oggetto e demo d'esame sono in
[`k8s/README.md`](k8s/README.md).

## Sicurezza: come funziona

Il gateway applica il pattern **BFF (Backend For Frontend)**: il browser ha solo
un cookie di sessione, il JWT viaggia esclusivamente tra gateway e servizi.

1. **Login**: `/login` → Google o GitHub (flusso *Authorization Code*). Il gateway
   scambia il `code` con un token del provider usando il `client_secret`, legge
   chi è l'utente e gli assegna i ruoli GinVault dagli elenchi del `.env`.
   Il browser riceve un cookie `SESSION` (HttpOnly).
2. **Ogni chiamata API**: il gateway toglie qualsiasi `Authorization` arrivato dal
   client, firma (RSA, RS256) un JWT nuovo valido 5 minuti con `sub`, `name`,
   `email` e `roles`, e lo inoltra come `Authorization: Bearer ...`.
   Toglie anche il cookie, che ai servizi non serve.
3. **I servizi** (resource server) verificano firma, scadenza e issuer usando la
   chiave pubblica del gateway (`/.well-known/jwks.json`) e controllano i ruoli
   con `@PreAuthorize` (Spring) o `@Ruoli` (NestJS).
4. **CSRF sulle richieste che modificano** (POST, PUT, DELETE). Le risposte delle
   API portano il cookie `XSRF-TOKEN`, leggibile da JavaScript; la richiesta deve
   rimandare lo stesso valore nell'header `X-XSRF-TOKEN`, altrimenti il gateway
   risponde 403. Un sito malevolo può far partire una POST con i cookie
   dell'utente, ma non può leggere il cookie per copiarlo nell'header.
5. **Header `X-Forwarded-*` verso i servizi** (`Host`, `Proto`, `Port`, `Prefix`).
   Così i servizi costruiscono link pubblici giusti, ad esempio
   `Location: http://localhost/api/ricette/formulazioni/{id}` anche se ricevono
   solo `/formulazioni`. Il gateway li accetta e li aggiunge solo se chi si
   collega ha un IP fidato (`trusted-proxies`: loopback e reti private).

| Situazione                                   | Risposta |
|----------------------------------------------|----------|
| API senza login (`/api/**`, `/auth/**`)      | 401      |
| Pagina senza login (es. `/gateway/routes`)   | redirect a `/login` |
| JWT assente, scaduto, firmato da altri, issuer sbagliato | 401 |
| JWT valido ma ruolo mancante                 | 403      |
| POST/PUT/DELETE senza `X-XSRF-TOKEN` uguale al cookie | 403 (dal gateway) |
| `/health` senza login                        | solo `{"status":"UP"}` |
| `/health` con ruolo ADMIN                    | anche i dettagli (DB, disco...) |

Nota di dominio: il ruolo **SOCIO** dà accesso all'area soci. Il diritto di
*prelazione* su un'edizione non è un ruolo: lo verificherà Edizioni con il mock
del Club.

### Provare il login (nel browser)

1. `http://localhost:8080/login` → scegli Google o GitHub.
2. Dopo il login arrivi su `http://localhost:8080/auth/me`: identità e ruoli in sessione.
3. `http://localhost:8080/api/edizioni/me`: cosa legge il servizio dal JWT.
4. `http://localhost:8080/api/edizioni/prova`: 200 se sei SOCIO, altrimenti 403.
   Stessa cosa per `/api/<servizio>/prova` con i ruoli della tabella in alto.
5. `http://localhost:8080/gateway/routes`: route del gateway (solo ADMIN).
6. `http://localhost:8080/logout` → conferma → sessione chiusa.

### Verifiche senza login

```bash
curl http://localhost:8080/health                     # {"status":"UP"} (niente dettagli)
curl http://localhost:8080/api/edizioni/health        # {"status":"UP"}
curl -i http://localhost:8080/api/edizioni/me         # 401
curl http://localhost:8080/.well-known/jwks.json      # chiave pubblica del gateway
```

> **Windows PowerShell:** usare `curl.exe` invece di `curl`, perché `curl` è
> un alias di `Invoke-WebRequest`. In alternativa si può aprire l'URL nel browser.

## Edizioni chiama Ricette: validazione REST

Quando Marco (DISTILLATORE) crea un'edizione con `POST /edizioni`, Edizioni
chiede a Ricette se la formulazione si può usare. La chiamata è diretta, da
servizio a servizio sulla rete interna, e non passa dal gateway.

```
Marco ──POST /api/edizioni/edizioni──▶ gateway ──JWT──▶ edizioni
                                                         │ 1. GET  /formulazioni/{id}           (deve essere SCELTA)
                                                         │ 2. regole dell'edizione, in memoria
                                                         │ 3. POST /formulazioni/{id}/utilizzo  (diventa USATA)
                                                         ▼    stesso JWT di Marco, timeout 2 s
                                                       ricette
                                                         │
                                     4. solo se il 3 va a buon fine: INSERT in edizioni-db
```

- **Anti-Corruption Layer.** Il dominio di Edizioni vede solo l'interfaccia
  `CatalogoRicette` e il suo modello (`FormulazioneInCatalogo`). URL, JSON e
  codici HTTP di Ricette li conosce solo l'adapter `CatalogoRicetteRest`.
- **Cosa conserva l'Edizione.** Della formulazione tiene il riferimento
  (id + versione) e un solo dato: i grammi di botanica di stagione per
  bottiglia, che servono per la tiratura. Le dosi non le copia. Copiare quel
  dato è sicuro e utile:
  - **la versione è immutabile:** in Ricette una versione non si modifica mai
    (se ne crea una nuova), quindi la copia non può diventare vecchia;
  - **Edizioni resta autonomo:** il ricalcolo della tiratura non chiama
    Ricette, e funziona anche se Ricette è giù.
- **Token relay.** Edizioni inoltra a Ricette lo stesso JWT che ha ricevuto,
  quindi Ricette applica le sue regole sui ruoli.
- **Rete di sicurezza.** Nel database di Edizioni c'è un vincolo `unique` su
  (`formulazione_id`, `formulazione_versione`).

| Caso                                                    | Risposta a Marco | Edizione creata? |
|---------------------------------------------------------|------------------|------------------|
| formulazione SCELTA, versione giusta                    | 201 + `Location` | sì               |
| formulazione inesistente, non scelta o versione diversa | 422              | no               |
| già usata (dalla GET, dalla `/utilizzo` o dal vincolo)  | 409              | no               |
| Ricette spento, irraggiungibile o oltre i 2 s           | 503 + `Retry-After`, messaggio chiaro, nessuno stack trace | no |
| nessun JWT / ruolo diverso da DISTILLATORE              | 401 / 403 (Ricette non viene chiamato) | no |

**Limiti, voluti per ora:**

- **Le due chiamate sincrone non sono transazionali.** Ricette può segnare la
  formulazione USATA e poi Edizioni non riuscire a salvare l'edizione: database
  giù, vincolo violato, timeout sulla risposta di `/utilizzo`. In quel caso la
  formulazione resta "usata" da un'edizione che non esiste, e va sistemata a
  mano. Con Kafka diventerà una **SAGA**: Edizioni salva l'edizione e pubblica
  l'evento `EdizioneCreata`, Ricette la segna USATA. Se Ricette non può, pubblica
  un evento che fa annullare l'edizione (compensazione).
- **Niente circuit breaker nel codice: arriverà con Istio.** Timeout, retry e
  *outlier detection* diventeranno configurazione del sidecar Envoy. Per ora
  ogni richiesta prova Ricette e al peggio aspetta 2 secondi.

## Edizione: tiratura, quote e ricalcolo

```
tiratura = min(600, grammi di botanica disponibili / grammi per bottiglia)   sempre tra 1 e 600, per difetto

  12 kg   di lavanda, 20 g per bottiglia  →  600 bottiglie   SOCI 300  BAR 150  ONLINE 150
  10,8 kg (la lavanda nuova arriva scarsa) →  540 bottiglie   SOCI 300  BAR 150  ONLINE  90
```

- **Creazione:** una quota per canale (SOCI, BAR, ONLINE), e la somma delle
  quote deve essere uguale alla tiratura, altrimenti 422.
- **Ricalcolo:** `POST /edizioni/{id}/ricalcolo` (solo DISTILLATORE) con
  `{"botanicaDisponibileKg": 10.8}`. Se la tiratura scende:
  - si toglie prima da ONLINE, poi da BAR, **mai da SOCI**;
  - **nessuna quota scende sotto le bottiglie già prenotate**: se ONLINE è
    prenotato si passa a BAR;
  - se non basta (bisognerebbe toccare SOCI o le prenotate) la risposta è 409
    con la spiegazione, e l'edizione resta com'era.
- **Prenotazioni:** il modello tiene le bottiglie prenotate per canale, ma per
  ora le crea solo `Edizione.prenota()` nei test. L'endpoint arriverà con la
  storia delle prenotazioni.

**Scelta da discutere:** se la tiratura **sale** (es. arriva altra lavanda),
oggi tutte le bottiglie in più vanno a ONLINE. Alternative: rimetterle prima
dove erano state tolte (BAR, poi ONLINE), ripartirle in proporzione, oppure
lasciarle "da assegnare" e farle decidere a Marco.

**Evento di dominio `TiraturaRidotta`** (id edizione, tiratura vecchia e
nuova, quote nuove):
- lo registra l'aggregato `Edizione` quando la tiratura scende;
- Spring Data lo pubblica al `save()` (`@DomainEvents`);
- dopo il commit lo riceve `InoltroTiratureRidotte` (`@TransactionalEventListener`),
  che per ora lo scrive solo nel log, dentro il processo.

Con la storia di Elena passerà su **Kafka** verso **Notifiche**, che avviserà
chi aveva prenotato. Per non perdere eventi se Kafka è giù, si potrà usare una
*transactional outbox*.

## Test

Ogni servizio Spring ha test di sicurezza (401/403/200, token scaduto, firma
falsa, issuer sbagliato). Il gateway ha test sul flusso BFF: JWT inoltrato,
header falsi rimossi, pagina di login, JWKS, ruoli.
Ricette ed Edizioni hanno anche:
- test di dominio;
- test web (`@WebMvcTest`);
- test di integrazione con un PostgreSQL vero, avviato da
  [Testcontainers](https://testcontainers.com/).

In Edizioni la parte di Ricette la fa [WireMock](https://wiremock.org/): un
server HTTP vero, con cui si provano 404, 409, timeout e il JWT inoltrato.

Si possono lanciare da IntelliJ (tasto destro sulla cartella `src/test` → *Run*)
oppure senza Maven installato, in un container. Docker Desktop deve essere
avviato: il container di Maven usa il Docker del portatile per avviare i
container di Testcontainers.

```bash
# PowerShell, dalla cartella ginvault (sostituire "edizioni" con il servizio voluto)
docker run --rm -v "${PWD}/edizioni:/workspace" -v ginvault-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9-eclipse-temurin-21 mvn -B test
```

Il volume `ginvault-m2` conserva le dipendenze Maven tra un'esecuzione e l'altra.

## Comandi utili

```bash
docker compose ps                                  # tutti "Up ... (healthy)"
docker compose logs -f gateway                     # log di un servizio (Ctrl+C per uscire)
docker compose up -d --build --wait edizioni       # ricostruisce e riavvia un solo servizio
docker compose restart edizioni                    # riavvia senza ricostruire
docker compose stop                                # ferma tutto (i container restano)
docker compose down                                # rimuove container e reti (i dati restano nei volumi)
docker compose down -v                             # rimuove anche i volumi: DB e Kafka ripartono vuoti
```

### Database

```bash
# Console psql dentro il container
docker compose exec edizioni-db psql -U edizioni -d edizioni
```

In modalità sviluppo i DB sono raggiungibili anche dal portatile, solo su
`127.0.0.1` (non dalla rete Wi-Fi): `localhost:5441` (edizioni) … `localhost:5445`
(pagamenti), utente/password/database uguali al nome del servizio. Si possono
aprire con il *Database tool* di IntelliJ.

### Kafka

```bash
# Stato del quorum KRaft (il nodo 1 è leader e unico votante)
docker compose exec kafka /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status

# Elenco dei topic
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

> **Git Bash su Windows:** i percorsi che iniziano con `/` (es. `/opt/kafka/...`)
> vengono convertiti in percorsi Windows. Anteporre `MSYS_NO_PATHCONV=1` al
> comando, oppure usare PowerShell.

### Isolamento

```bash
# Un servizio non vede il DB di un altro
docker compose exec ricette sh -c "nc -z -w 2 edizioni-db 5432"     # → nc: bad address 'edizioni-db'
# Un servizio chiamato senza passare dal gateway (quindi senza JWT) rifiuta la richiesta
docker compose exec gateway sh -c "wget -S -qO /dev/null http://edizioni:8081/me"   # → 401
```

## Sviluppo in IntelliJ

Ogni servizio è un progetto Maven a sé. Per aprirlo: tasto destro su
`<servizio>/pom.xml` → *Add as Maven Project*.

Un servizio avviato da IntelliJ usa i default di `application.yml`:
- il suo DB su `localhost:544x`, esposto da `docker-compose.override.yml`;
- la JWKS del gateway su `localhost:8080`.

Il gateway in Docker però inoltra le richieste ai container, non al servizio
avviato in IntelliJ.

## Limiti noti (da affrontare nei passi successivi)

- **Sessione e chiave RSA del gateway sono in memoria.** Se il gateway si riavvia
  bisogna rifare il login. Con più repliche del gateway, la sessione andrà su
  Redis e tutte le repliche dovranno firmare con la stessa chiave, letta da un
  segreto condiviso.
- **CSRF con cookie `XSRF-TOKEN` e header `X-XSRF-TOKEN`**: pronto per la SPA.
  Il front-end deve leggere il cookie e rimandarlo nell'header; Angular lo fa da
  solo.
- **WebSocket di notifiche.** Il gateway inoltra il JWT anche sull'handshake
  WebSocket. Resta da far verificare il token alla connessione, con
  `VerificaJwtService`, e da controllare l'header `Origin`.
- **Aumento di tiratura tutto su ONLINE:** scelta provvisoria, da discutere (vedi
  [Edizione: tiratura, quote e ricalcolo](#edizione-tiratura-quote-e-ricalcolo)).
- **Edizioni → Ricette non è transazionale** e non ha circuit breaker: vedi
  [Edizioni chiama Ricette](#edizioni-chiama-ricette-validazione-rest).
  Arriveranno la SAGA con Kafka (`EdizioneCreata`) e il circuit breaker con Istio.
