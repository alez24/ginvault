# GinVault

Progetto d'esame per il corso TAASS: backend a microservizi con API Gateway,
Kafka e un database PostgreSQL per ogni servizio, avviato con Docker Compose
e, in parte, su Kubernetes (Minikube).
Il piano completo è in [`docs/piano.md`](docs/piano.md).

> **Stato: fondamenta, sicurezza OAuth2/JWT, Kubernetes e la storia di Marco.**
> Ogni servizio si avvia, ha `GET /health`, il proprio database e un endpoint di
> prova protetto da ruolo. Ricette ed Edizioni hanno la logica di dominio:
> formulazioni con prove di assaggio, edizioni validate da Ricette via REST,
> tiratura e ricalcolo.
> Il front-end React ha il back-office di Marco e l'area soci di Giulia (con dati
> finti finché non ci sono le prenotazioni); è servito da nginx su
> `http://localhost` ([`frontend/README.md`](frontend/README.md)).
> Su Minikube girano front-end, gateway, edizioni, ricette e i loro database
> dietro un Ingress, con NetworkPolicy che isolano database, front-end e gateway
> ([`k8s/README.md`](k8s/README.md)).
> Mancano ancora Kafka tra i servizi, gRPC e gli altri servizi su Kubernetes.

## Architettura

```
                    browser
                       │  http://localhost (:80): pagine, API, login
                       │  cookie SESSION (il JWT non arriva mai qui)
                 ┌─────▼─────┐
                 │ frontend  │  nginx: l'app React e, per /api /auth /oauth2
                 └─────┬─────┘  /login /logout, proxy verso il gateway
   rete "frontend-net" │
                 ┌─────▼─────┐   login OAuth2   ┌─────────────────┐
                 │  gateway  │ ◀──────────────▶ │ Google / GitHub │
                 └─────┬─────┘                  └─────────────────┘
                       │  (anche su :8080 dall'host, per curl e per il dev server di Vite)
   rete "backend"      │  /api/<servizio>/**  + Authorization: Bearer <JWT GinVault>
 ┌──────────┬──────────┼──────────┬──────────┬───────────┐
 edizioni   ricette    produzione magazzino  pagamenti   notifiche
  :8081      :8082      :8083      :8084      :8085       :8086
   │          │          │          │          │         (NestJS)
 edizioni-db ricette-db produzione-db magazzino-db pagamenti-db
   (ognuno su una rete privata condivisa SOLO con il proprio servizio)

 rete "kafka-net": kafka :9092 (KRaft) con edizioni, produzione e magazzino
   topic edizioni.eventi, magazzino.eventi, produzione.eventi (docs/eventi.md)

 gRPC produzione ──▶ magazzino :9084  servizio Giacenze (Disponibilita, RiservaLotto,
   AnnullaRiserva), deadline 2 s, JWT dell'utente nel metadata (docs/grpc.md)
```

| Servizio    | Tecnologia                  | Porta | Database (porta in sviluppo) | `GET /prova` richiede |
|-------------|-----------------------------|-------|------------------------------|-----------------------|
| frontend    | React 19 + Vite 7, nginx 1.30 | 80 (nginx: 8080) | –                | –                     |
| gateway     | Spring Cloud Gateway        | 8080  | –                            | –                     |
| edizioni    | Spring Boot 3.5 / Java 21   | 8081  | `edizioni-db` (5441)         | SOCIO                 |
| ricette     | Spring Boot 3.5 / Java 21   | 8082  | `ricette-db` (5442)          | DISTILLATORE          |
| produzione  | Spring Boot 3.5 / Java 21   | 8083  | `produzione-db` (5443)       | DISTILLATORE          |
| magazzino   | Spring Boot 3.5 / Java 21   | 8084 (gRPC 9084) | `magazzino-db` (5444) | RESP_ACQUISTI      |
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
├── giacenze-api/                 # modulo Maven condiviso: il contratto gRPC (giacenze.proto)
├── gateway/                      # Spring Cloud Gateway: login, sessione, JWT
├── notifiche/                    # NestJS
├── k8s/                          # manifest Kubernetes (Minikube): vedi k8s/README.md
├── frontend/                     # React + Vite, servito da nginx: vedi frontend/README.md
└── docs/                         # piano.md, eventi.md (topic ed eventi), grpc.md, stato-lavori.md, demo
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
   - URI di reindirizzamento autorizzati: `http://localhost/login/oauth2/code/google`
     (front-end con Compose e Ingress di Kubernetes) e
     `http://localhost:8080/login/oauth2/code/google` (dev server di Vite e login
     diretto sul gateway)
   - Se la schermata di consenso è in modalità *Testing*, aggiungi gli account
     del gruppo tra gli *utenti di test*.
   - Copia ID client e secret in `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET`.
3. **GitHub**: *Settings* → *Developer settings* → *OAuth Apps* → *New OAuth App*.
   - Homepage URL: `http://localhost`
   - Authorization callback URL: `http://localhost/login/oauth2/code/github`
   - Copia Client ID e un nuovo Client secret in `GITHUB_CLIENT_ID` e `GITHUB_CLIENT_SECRET`.
   - Un'OAuth App ha **un solo** callback URL. Per gli indirizzi di loopback
     GitHub accetta un `redirect_uri` con una porta diversa da quella del
     callback (documentato per `127.0.0.1`): con il callback sulla porta 80
     funziona anche il dev server di Vite, che passa dalla 8080.
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

Poi si apre **http://localhost**: è il front-end (nginx), che inoltra al gateway
API, login e logout. Il front-end parte per ultimo, quando il gateway è pronto,
quindi `--wait` termina quando tutto il sistema risponde.

> **Porta 80.** È la stessa di `minikube tunnel` (Kubernetes): non tenere aperti
> Compose e il tunnel insieme. Se la porta 80 è occupata, `docker compose up` si
> ferma con `bind: address already in use` (o `ports are not available`).

### Su Kubernetes (Minikube)

Front-end, gateway, edizioni, ricette e i loro database, esposti con un Ingress
su `http://localhost`: le pagine vanno al front-end (2 repliche), `/api`,
`/auth`, `/oauth2`, `/login` e `/logout` al gateway. Prerequisiti, avvio,
spiegazione di ogni oggetto, la **Demo completa** (dal `minikube start` alla
storia di Marco dal front-end) e le demo d'esame sono in
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

1. `http://localhost` → **Accedi con Google** o **Accedi con GitHub**.
2. Dopo il login torni alla home del front-end, con il tuo nome e il menu dei tuoi
   ruoli (Formulazioni ed Edizioni per DISTILLATORE, Area soci per SOCIO).
3. `http://localhost/auth/me`: identità e ruoli in sessione.
4. `http://localhost/api/edizioni/me`: cosa legge il servizio dal JWT.
5. `http://localhost/api/edizioni/prova`: 200 se sei SOCIO, altrimenti 403.
   Stessa cosa per `/api/<servizio>/prova` con i ruoli della tabella in alto.
6. `http://localhost:8080/gateway/routes`: route del gateway (solo ADMIN). Questo
   path il front-end non lo inoltra: si apre direttamente sul gateway, e la
   sessione vale anche lì, perché i browser non distinguono i cookie per porta.
7. **Esci** nel menu: `POST /logout` con il token CSRF, poi di nuovo la home.

### Verifiche senza login

```bash
curl -I http://localhost/                             # front-end: 200, Content-Security-Policy...
curl -i http://localhost/auth/me                      # 401, passando da nginx al gateway
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
  quote deve essere uguale alla tiratura, altrimenti 422. Serve anche
  l'**origine** della botanica di stagione (`origineBotanica`, es. "Alta Val
  Varaita (Alpi Cozie)"): l'edizione resta fedele a quella, e Produzione non
  conta i lotti di un'altra origine. L'edizione creata pubblica
  `EdizioneCreata` su `edizioni.eventi`, con la stessa transactional outbox
  di Magazzino (vedi sotto).
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

Se la tiratura **sale** (lotto integrato, altra lavanda arrivata), le
bottiglie in più vanno tutte a ONLINE, fino a 600.

**Il ricalcolo arriva da solo, da Produzione.** Edizioni consuma
`ResaRicalcolata` da `produzione.eventi` (consumer group `edizioni`) e
applica la stessa regola del ricalcolo manuale. Nessuna azione dopo la
consegna di Elena:

```
produzione.eventi  ResaRicalcolata 10,8 kg ──▶ 600 → 540 ──▶ edizioni.eventi  TiraturaRidotta
produzione.eventi  ResaRicalcolata 12 kg   ──▶ 540 → 600 ──▶ edizioni.eventi  TiraturaAumentata
produzione.eventi  ResaRicalcolata 10,8 kg ──▶ ONLINE e BAR tutte prenotate:
                                               l'edizione resta com'è ──▶ edizioni.eventi  RicalcoloRifiutato
```

- `RicalcoloRifiutato` dice perché (`QUOTE_NON_RIDUCIBILI` o
  `BOTANICA_INSUFFICIENTE`, se i kg non bastano per una bottiglia), con la
  spiegazione in italiano e le quote con le prenotate. Il messaggio è
  elaborato lo stesso: un rifiuto non è un errore;
- gli eventi di dominio (`TiraturaRidotta`, `TiraturaAumentata`,
  `RicalcoloRifiutato`) li registra l'aggregato `Edizione`, Spring Data li
  pubblica al `save()` (`@DomainEvents`) e `ScritturaOutbox` li scrive
  nell'outbox **nello stesso commit** dell'edizione, come `EdizioneCreata`.
  Hanno la `causa` (il motivo della resa) e l'`eventId` della resa;
- anche il ricalcolo manuale di Marco pubblica `TiraturaRidotta` o
  `TiraturaAumentata`; se non si può, resta un 409 per lui e non parte niente;
- il consumer è **idempotente**, con **retry** (1, 2, 4, 8 s) e **dead
  letter** `edizioni.dlt`, come quello di Produzione. Una resa per
  un'edizione che non esiste va subito nel dead letter: l'edizione nasce
  prima della resa, quindi non arriverà più.

`TiraturaRidotta` la leggerà **Notifiche** (storia di Giulia), per avvisare
chi aveva prenotato. Formato degli eventi in [docs/eventi.md](docs/eventi.md).

```bash
# Gli eventi di Edizioni in uscita e le rese già applicate
docker compose exec edizioni-db psql -U edizioni -d edizioni -c "select tipo, chiave, pubblicato_il, tentativi from outbox order by creato_il"
docker compose exec edizioni-db psql -U edizioni -d edizioni -c "select event_id, tipo, esito, partizione, posizione, elaborato_il from evento_elaborato order by elaborato_il"
```

## Magazzino: approvvigionamento e transactional outbox

Il subdomain **Approvvigionamento**: Elena (RESP_ACQUISTI) ordina le botaniche
ai fornitori e registra le consegne. Dal gateway: `/api/magazzino/...`.

| Endpoint | Ruolo | Cosa fa |
|---|---|---|
| `POST /ordini` | RESP_ACQUISTI | ordine APERTO: fornitore, botanica, kg, consegna attesa, edizione |
| `POST /ordini/{id}/consegne` | RESP_ACQUISTI | registra la consegna, anche ridotta: chiude l'ordine, crea il lotto, aggiorna lo storico del fornitore. 201 + `Location` del lotto |
| `GET /ordini`, `GET /ordini/{id}` | RESP_ACQUISTI, DISTILLATORE, ADMIN | ordini, con la consegna (`ridotta`, `kgMancanti`, `inRitardo`, `giorniDiRitardo`) |
| `GET /lotti?botanica=lavanda`, `GET /lotti/{id}` | RESP_ACQUISTI, DISTILLATORE, ADMIN | lotti con origine, scadenza, esito qualità, `utilizzabile` |
| `GET /fornitori`, `GET /fornitori/{id}` | RESP_ACQUISTI, DISTILLATORE, ADMIN | scheda del fornitore: riepilogo (puntuali, in ritardo, ridotte) e storico, dalla consegna più recente |

Regole della consegna (aggregato `OrdineDiAcquisto`):
- **una consegna per ordine**: quella che arriva chiude l'ordine, anche se
  ridotta. Una seconda consegna → 409;
- kg arrivati maggiori di zero e **non più di quelli ordinati** (422);
- data di consegna non nel futuro, scadenza dopo la consegna (422);
- *ridotta* = arrivati meno kg degli ordinati; *in ritardo* = consegnata dopo
  la data attesa. La consegna di Elena è tutte e due: 10,8 kg su 12, sei
  giorni dopo il 31 luglio;
- l'**origine** del lotto è quella del fornitore: non si scrive a mano, quindi
  non ha errori di battitura. È il dato che Produzione confronterà con
  l'origine richiesta dall'edizione;
- la `nota` della consegna (es. "pioggia durante la fioritura") finisce nello
  storico del fornitore.

All'avvio `DatiIniziali` crea, se mancano, i due fornitori della storia
(Raccoglitori dell'Alta Val Varaita e Campi GinVault) e il **lotto dei campi**:
2 kg di lavanda di origine diversa, quello che Elena proporrà e Marco rifiuterà.
Id fissi, in `DatiIniziali.java`.

**Transactional outbox.** Registrare la consegna e pubblicare
`ConsegnaRegistrata` su Kafka sono due scritture su due sistemi diversi. Se
fossero separate, un crash o Kafka spento in mezzo lascerebbe una consegna
senza evento (l'edizione non scende mai a 540) o un evento senza consegna.
Per questo:

1. nella **stessa transazione** della consegna, `ScritturaOutbox`
   (`@TransactionalEventListener(phase = BEFORE_COMMIT)`) scrive l'evento,
   già in JSON, nella tabella `outbox` del database di magazzino. Commit di
   tutto o di niente: se la riga non si può scrivere, salta anche la consegna;
2. `PubblicatoreOutbox`, ogni secondo, prende le righe non pubblicate in ordine
   di `numero`, le manda su Kafka e aspetta la conferma (`acks=all`). Poi
   segna `pubblicato_il`;
3. se Kafka non risponde, la riga resta lì con `tentativi` e `ultimo_errore`,
   e il giro si ferma per non cambiare l'ordine degli eventi. Al giro dopo si
   riprova, finché Kafka torna. La richiesta HTTP di Elena non aspetta mai Kafka;
4. garanzia **almeno una volta**: se la conferma si perde, lo stesso evento
   (stesso `eventId`) può partire due volte. Il consumer di Produzione scarta
   gli `eventId` già elaborati (vedi sotto).

Un blocco *advisory* di PostgreSQL (`pg_try_advisory_xact_lock`) fa lavorare
un solo publisher alla volta anche con più repliche di magazzino.

```bash
# La tabella outbox: cosa è partito, cosa aspetta e perché
docker compose exec magazzino-db psql -U magazzino -d magazzino -c "select numero, tipo, chiave, tentativi, pubblicato_il, ultimo_errore from outbox order by numero"
# Il contenuto di un evento (la busta JSON di docs/eventi.md)
docker compose exec magazzino-db psql -U magazzino -d magazzino -c "select jsonb_pretty(messaggio::jsonb) from outbox order by numero desc limit 1"
```

Prova senza front-end, dalla console del browser dopo il login con un utente in
`RUOLI_RESP_ACQUISTI` (la funzione `api` è in
[docs/demo-storia-marco.md](docs/demo-storia-marco.md#1-login-e-console-del-browser)):

```js
const fornitore = 'f0000000-0000-4000-8000-000000000001';   // Alta Val Varaita
const ordine = await api('POST', '/api/magazzino/ordini', {
  fornitoreId: fornitore, botanica: 'lavanda', kgOrdinati: 12,
  consegnaAttesa: '2026-07-31', edizioneId: crypto.randomUUID() });
await api('POST', `/api/magazzino/ordini/${ordine.id}/consegne`, {
  kgArrivati: 10.8, consegnataIl: '2026-08-06', scadenza: '2027-08-31',
  esitoQualita: 'CONFORME', nota: 'pioggia durante la fioritura' });
await api('GET', `/api/magazzino/fornitori/${fornitore}`);
await api('GET', '/api/magazzino/lotti?botanica=lavanda');
```

Con un `edizioneId` inventato, come qui, Produzione non trova il piano: dopo i
tentativi la consegna finisce in `produzione.dlt` (vedi
[Produzione](#produzione-piani-consumer-idempotenti-e-dead-letter)). Con
l'id di un'edizione vera il piano scende a 10,8 kg.

Per vedere l'outbox al lavoro: `docker compose stop kafka`, registra un'altra
consegna (risponde 201 lo stesso), guarda la riga in attesa con `tentativi`
che sale, poi `docker compose start kafka`: dopo qualche secondo la riga ha
`pubblicato_il` e il messaggio è nel topic. Su Kubernetes la stessa prova è in
[k8s/README.md](k8s/README.md#transactional-outbox-con-kafka-spento).

## Produzione: piani, consumer idempotenti e dead letter

Produzione non ha moduli da compilare: i suoi **piani di produzione** nascono
e cambiano con gli eventi. L'unica azione a mano è di Marco: integrare un
lotto del magazzino, via gRPC. Dal gateway: `/api/produzione/...`.

```
edizioni.eventi   EdizioneCreata      ──▶ nuovo piano: 12 kg di lavanda, Alta Val Varaita, 20 g, 600 bottiglie
magazzino.eventi  ConsegnaRegistrata  ──▶ piano a 10,8 kg (540 bottiglie)
                                      ──▶ produzione.eventi  ResaRicalcolata (kgDisponibili 10,8)
                                          ──▶ Edizioni: 600 → 540, TiraturaRidotta
```

| Endpoint | Ruolo | Cosa fa |
|---|---|---|
| `GET /piani`, `GET /piani?edizione={id}` | DISTILLATORE, RESP_ACQUISTI, ADMIN | i piani, dal più recente (o quello di un'edizione) |
| `GET /piani/{id}` | DISTILLATORE, RESP_ACQUISTI, ADMIN | un piano: kg previsti, disponibili e mancanti, `bottiglieProducibili`, le consegne con i kg utilizzabili e il motivo, i lotti integrati |
| `GET /piani/{id}/lotti` | DISTILLATORE, RESP_ACQUISTI, ADMIN | i lotti della botanica in magazzino (gRPC `Disponibilita`), con `stessaOrigine` |
| `POST /piani/{id}/integrazioni` | DISTILLATORE, ADMIN | `{"lottoId", "kg"}`: integra un lotto proposto da Elena. 201, oppure 409 con il motivo (`ORIGINE_DIVERSA`: "origine diversa: l'edizione deve restare fedele alla sua origine"), 503 se il magazzino non risponde in 2 secondi |

Regole del piano (aggregato `PianoDiProduzione`):
- conta solo la **botanica di stagione** dell'edizione: le altre consegne si ignorano;
- i kg di una consegna sono **utilizzabili** solo se il lotto è CONFORME e ha
  l'**origine richiesta** dall'edizione; altrimenti zero;
- `disponibili = min(previsti, max(0, previsti − Σ ordinati con l'origine richiesta) + Σ utilizzabili)`.
  Produzione vede un ordine solo quando arriva la consegna: gli ordini ancora
  aperti sono nei kg previsti non coperti. Esempi:
  - la consegna di Elena dà `0 + 10,8 = 10,8`;
  - due ordini da 6 kg, uno consegnato: `6 + 6 = 12`;
  - 2 kg dai campi (altra origine) non tolgono niente;
  - un riordino dall'Alta Val Varaita riporta a 12;
- ogni ordine conta una volta; `ResaRicalcolata` parte solo se il valore cambia;
- un **lotto integrato** deve avere l'origine richiesta e non superare i kg
  mancanti. I suoi kg, riservati in magazzino con `RiservaLotto`, si sommano
  agli utilizzabili: `10,8 + 1,2 = 12`.

### gRPC con il Magazzino

La partnership Produzione → Magazzino è l'unica chiamata sincrona tra i due
(dettagli in [docs/grpc.md](docs/grpc.md)):

- il contratto è il modulo condiviso [`giacenze-api`](giacenze-api), con
  `giacenze.proto`;
- Magazzino espone il servizio `Giacenze` sulla porta 9084;
- Produzione lo chiama con una deadline di 2 secondi e inoltra il JWT di Marco
  nel metadata;
- la regola dell'origine si applica **prima** della riserva: il lotto dei
  campi è rifiutato senza toccare il magazzino;
- se il piano non si salva dopo la riserva, Produzione la annulla con
  `AnnullaRiserva` (compensazione best effort, con log);
- su Kubernetes solo produzione può aprire la 9084 (NetworkPolicy);
- **in Compose la porta 9084 è protetta solo dal JWT.** Non è pubblicata
  sull'host, ma le reti di Compose non filtrano per porta: ogni container
  della rete `backend` può collegarsi, e senza un JWT con il ruolo giusto
  riceve `UNAUTHENTICATED` o `PERMISSION_DENIED`.

```bash
# Le riserve in magazzino e le integrazioni nei piani
docker compose exec magazzino-db psql -U magazzino -d magazzino -c "select lotto_id, piano_id, kg, riservata_da, riservata_il from lotto_riserva"
docker compose exec produzione-db psql -U produzione -d produzione -c "select piano_id, lotto_id, origine, kg, motivo from piano_integrazione"
```

I consumer (dettagli in [docs/eventi.md](docs/eventi.md#consumer-idempotenza-retry-e-dead-letter)):
- **idempotenti**: in una transazione sola si controlla la tabella
  `evento_elaborato`, si applica l'evento, si scrive l'outbox e l'`eventId`.
  Lo stesso evento rimandato si scarta;
- **retry con backoff**: 1, 2, 4, 8 secondi. Serve per esempio se la
  consegna arriva prima dell'edizione (i due topic non hanno un ordine tra loro);
- **dead letter topic** `produzione.dlt`: ci va il messaggio dopo i tentativi,
  o subito se riprovare non serve (JSON illeggibile, versione non supportata).
  Gli header dicono da dove veniva e perché è stato scartato;
- due listener, uno per topic, 3 consumer ciascuno: i tentativi su
  `magazzino.eventi` non fermano la lettura di `edizioni.eventi`.

```bash
# Piani e consegne applicate
docker compose exec produzione-db psql -U produzione -d produzione -c "select edizione_id, botanica, kg_previsti, kg_disponibili from piano_di_produzione"
docker compose exec produzione-db psql -U produzione -d produzione -c "select ordine_id, kg_ordinati, kg_arrivati, kg_utilizzabili, motivo from piano_consegna"
# Gli eventi già elaborati (la difesa contro i doppioni)
docker compose exec produzione-db psql -U produzione -d produzione -c "select event_id, tipo, topic, partizione, posizione, elaborato_il from evento_elaborato order by elaborato_il"
```

## Test

Ogni servizio Spring ha test di sicurezza (401/403/200, token scaduto, firma
falsa, issuer sbagliato). Il gateway ha test sul flusso BFF: JWT inoltrato,
header falsi rimossi, pagina di login, JWKS, ruoli.
Ricette, Edizioni, Magazzino e Produzione hanno anche:
- test di dominio;
- test web (`@WebMvcTest`);
- test di integrazione con un PostgreSQL vero, avviato da
  [Testcontainers](https://testcontainers.com/).

In Edizioni la parte di Ricette la fa [WireMock](https://wiremock.org/): un
server HTTP vero, con cui si provano 404, 409, timeout e il JWT inoltrato.

Magazzino usa anche un **Kafka vero** (`apache/kafka:4.3.0`, come in Compose).
Il test di integrazione legge il topic con un consumer e verifica chiave, busta
JSON e partizione. Poi prova l'outbox:
- con il container di Kafka **in pausa** la consegna risponde 201, e la riga
  resta in attesa con i tentativi falliti;
- quando Kafka riprende, l'evento parte da solo;
- se la riga di outbox non si può scrivere (un trigger di prova la rifiuta),
  non si salva nemmeno la consegna.

Anche Edizioni ha Kafka nei test: `EdizioneCreata` arriva su `edizioni.eventi`
dall'outbox. `ResaRicalcolataTest` fa la parte di Produzione scrivendo su
`produzione.eventi`, e verifica:
- la consegna di Elena (10,8 kg) porta l'edizione a 540 e `TiraturaRidotta`
  esce su `edizioni.eventi`; il lotto integrato (12 kg) la riporta a 600 con
  `TiraturaAumentata`;
- con ONLINE e BAR tutte prenotate parte `RicalcoloRifiutato` e l'edizione
  non cambia;
- la stessa resa rimandata si scarta; il database che rifiuta per un momento
  fa riprovare; un messaggio illeggibile e una resa per un'edizione che non
  esiste vanno subito in `edizioni.dlt`.

Produzione fa la parte di Edizioni e Magazzino scrivendo sui loro topic, e
verifica:
- la catena: `EdizioneCreata` → piano da 12 kg; `ConsegnaRegistrata` da 10,8 →
  piano a 10,8 e `ResaRicalcolata` su `produzione.eventi`;
- **idempotenza**: lo stesso messaggio rimandato, e un messaggio con lo stesso
  `eventId` ma un altro ordine, non cambiano niente. Il test aspetta che il
  consumer group abbia superato il loro offset, poi controlla il piano;
- **retry**: una consegna arrivata prima della sua edizione passa al nuovo tentativo;
- **dead letter**: un messaggio non JSON e uno in versione 2 ci vanno subito,
  e la consegna buona dopo di loro passa; una consegna per un'edizione che non
  esiste ci va dopo i tentativi, nella stessa partizione, con gli header
  `kafka_dlt-*`.

Il gRPC tra Produzione e Magazzino si prova da tutte e due le parti:
- `GiacenzeGrpcTest` (magazzino) avvia il server vero su una porta libera e
  lo chiama con un client come quello di Produzione;
- `IntegrazioneLottiTest` (produzione) usa un magazzino finto con lo stesso
  contratto, su una porta vera. Prova il rifiuto per origine diversa, la
  deadline di 2 secondi, il magazzino lento o spento (503), il JWT inoltrato
  e la compensazione: se il piano non si salva, la riserva si annulla con
  `AnnullaRiserva`.

Si possono lanciare da IntelliJ (tasto destro sulla cartella `src/test` → *Run*)
oppure senza Maven installato, in un container. Docker Desktop deve essere
avviato: il container di Maven usa il Docker del portatile per avviare i
container di Testcontainers.

```bash
# PowerShell, dalla cartella ginvault (sostituire "edizioni" con il servizio voluto)
docker run --rm -v "${PWD}/edizioni:/workspace" -v ginvault-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9-eclipse-temurin-21 mvn -B test

# Magazzino e produzione dipendono da giacenze-api: si monta tutto il repository e lo si installa prima
docker run --rm -v "${PWD}:/repo" -v ginvault-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /repo maven:3.9-eclipse-temurin-21 sh -c 'mvn -B -q -f giacenze-api/pom.xml install && mvn -B -f magazzino/pom.xml test && mvn -B -f produzione/pom.xml test'
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

# I topic, con partizioni e leader (li crea il servizio kafka-topic, che poi termina)
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
docker compose logs kafka-topic

# I messaggi di un topic, con chiave e partizione (Ctrl+C per uscire)
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic magazzino.eventi --from-beginning --formatter-property print.key=true --formatter-property print.partition=true

# Il consumer group di Produzione: chi legge quale partizione, offset e lag
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group produzione
# Il dead letter topic, con gli header che dicono perché (Ctrl+C per uscire)
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic produzione.dlt --from-beginning --formatter-property print.headers=true
```

Topic, chiavi e formato JSON degli eventi sono in [`docs/eventi.md`](docs/eventi.md).
I topic esistono solo se li crea `kafka-topic`: la creazione automatica è spenta,
e un nome sbagliato fa fallire il produttore invece di creare un topic nuovo.

> **Git Bash su Windows:** i percorsi che iniziano con `/` (es. `/opt/kafka/...`)
> vengono convertiti in percorsi Windows. Anteporre `MSYS_NO_PATHCONV=1` al
> comando, oppure usare PowerShell.

### Isolamento

```bash
# Un servizio non vede il DB di un altro
docker compose exec ricette sh -c "nc -z -w 2 edizioni-db 5432"     # → nc: bad address 'edizioni-db'
# nginx (front-end) vede solo il gateway: i servizi non sono sulla sua rete
docker compose exec frontend sh -c "nc -z -w 2 edizioni 8081"       # → nc: bad address 'edizioni'
# Kafka lo vedono solo i servizi che usano gli eventi (rete kafka-net)
docker compose exec ricette sh -c "nc -z -w 2 kafka 9092"           # → nc: bad address 'kafka'
docker compose exec edizioni sh -c "nc -z -w 2 kafka 9092 && echo raggiungibile"   # → raggiungibile
# Un servizio chiamato senza passare dal gateway (quindi senza JWT) rifiuta la richiesta
docker compose exec gateway sh -c "wget -S -qO /dev/null http://edizioni:8081/me"   # → 401
```

## Sviluppo in IntelliJ

Ogni servizio è un progetto Maven a sé. Per aprirlo: tasto destro su
`<servizio>/pom.xml` → *Add as Maven Project*. Per magazzino e produzione va
aggiunto anche `giacenze-api/pom.xml`, e installato una volta (*Maven* →
`giacenze-api` → *Lifecycle* → `install`) prima di compilarli.

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
- **WebSocket di notifiche.** Il gateway inoltra il JWT anche sull'handshake
  WebSocket. Resta da far verificare il token alla connessione, con
  `VerificaJwtService`, e da controllare l'header `Origin`.
- **Aumento di tiratura tutto su ONLINE:** scelta provvisoria, da discutere (vedi
  [Edizione: tiratura, quote e ricalcolo](#edizione-tiratura-quote-e-ricalcolo)).
- **Outbox (magazzino, edizioni, produzione) ed eventi elaborati:**
  - le righe pubblicate restano nella tabella `outbox`, e quelle di
    `evento_elaborato` in Produzione. Andranno cancellate dopo qualche giorno
    (Kafka tiene i messaggi 7), con un job periodico;
  - un evento che non può partire mai (per esempio un topic che non esiste)
    blocca quelli dopo di lui, per non cambiarne l'ordine. Si vede da
    `tentativi` e `ultimo_errore` nella tabella `outbox`.
- **gRPC Produzione → Magazzino** (vedi [docs/grpc.md](docs/grpc.md#limiti-noti)):
  - se il piano non si salva dopo la riserva e anche `AnnullaRiserva`
    fallisce, i kg restano riservati finché Marco non ripete la richiesta
    (idempotente). C'è un log ERROR, ma nessun job che riprova da solo;
  - il bilanciamento è per connessione, e va bene con una replica;
  - in Compose la porta 9084 non è filtrata per porta: la protegge solo il JWT.
- **Edizioni → Ricette non è transazionale** e non ha circuit breaker: vedi
  [Edizioni chiama Ricette](#edizioni-chiama-ricette-validazione-rest).
  Arriveranno la SAGA con Kafka (`EdizioneCreata`) e il circuit breaker con Istio.
