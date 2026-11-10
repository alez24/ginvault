# GinVault – frontend

React 19 + Vite 7 + TypeScript 5.9, con React Router 7 e motion per le
animazioni. Font EB Garamond (titoli) e Inter Tight (testo) dai pacchetti
`@fontsource`, quindi dentro la build e senza CDN.

## Come parla con il backend (BFF)

```
browser ──▶ http://localhost:5173  (dev server di Vite)
              ├── /, /profilo, /soci, ...                → l'app React
              └── /api /auth /login /oauth2 /logout      → proxy → gateway :8080
                                                                   │ JWT
                                                                   ▼
                                                              microservizi
```

- **Nessun token nel browser.** La sessione è il cookie `SESSION` del gateway
  (HttpOnly: JavaScript non lo vede). Il JWT verso i servizi lo aggiunge il gateway.
- **CSRF**: in POST, PUT e DELETE l'header `X-XSRF-TOKEN` riporta il valore del
  cookie `XSRF-TOKEN` ([`src/api/http.ts`](src/api/http.ts)), come la funzione
  `api` di [`docs/demo-storia-marco.md`](../docs/demo-storia-marco.md).
- **Chi sono**: `GET /auth/me` all'avvio ([`src/sessione/Sessione.tsx`](src/sessione/Sessione.tsx)).
  Menu e pagine protette dipendono dai ruoli
  ([`src/navigazione.ts`](src/navigazione.ts)). È solo comodità: la sicurezza vera
  resta nei servizi, che rispondono 403 a chi non ha il ruolo.
- **Login**: i pulsanti sono link a `/oauth2/authorization/google|github`. Dopo
  il login il gateway riporta il browser a `FRONTEND_URL`. Il front-end riapre poi
  la pagina che l'utente voleva vedere, salvata in `sessionStorage`.
- **Logout**: `POST /logout` con l'header CSRF.
- **Stessa origine**: in sviluppo l'unica origine la dà il proxy di Vite
  ([`vite.config.ts`](vite.config.ts)), con Docker Compose nginx
  ([`nginx/default.conf`](nginx/default.conf)), su Kubernetes l'Ingress. Per
  questo non serve CORS.

## Avvio in sviluppo, contro Minikube

Procedura verificata, login compreso. Il backend è quello del cluster; in locale
gira solo il dev server di Vite.

Prerequisiti:

- Node 20.19+ o 22.12+;
- il cluster avviato come in [`k8s/README.md`](../k8s/README.md), con le
  immagini `ginvault/gateway:0.3`, `ginvault/ricette:0.2`, `ginvault/edizioni:0.5`,
  `ginvault/magazzino:0.3` e `ginvault/produzione:0.3`.

1. **Terminale 1: il gateway del cluster su `localhost:8080`.** Lascialo aperto.

   ```powershell
   kubectl port-forward -n ginvault svc/gateway 8080:8080
   ```

   `port-forward` apre la porta 8080 del portatile e la collega al Service
   `gateway`, cioè a uno dei suoi pod. Se il pod viene sostituito (nuova
   immagine, `kubectl set env`, riavvio) il collegamento cade: rilancia il comando.

2. **Una volta sola: dopo login e logout il gateway rimanda al dev server.**

   ```powershell
   kubectl set env deployment/gateway -n ginvault FRONTEND_URL=http://localhost:5173
   ```

   Il comando cambia il Deployment, quindi il pod del gateway riparte: rilancia
   il port-forward del punto 1. Le sessioni sono in memoria nel gateway e si
   perdono: rifai il login.

   **Quando hai finito con Vite, togli l'override**
   (`kubectl set env deployment/gateway -n ginvault FRONTEND_URL-`): il
   front-end del cluster, su `http://localhost` con `minikube tunnel`, vuole
   `FRONTEND_URL` = `/`, altrimenti dopo il login il browser finisce su
   `localhost:5173`. Anche `kubectl apply -k k8s/` non lo toglie: la variabile
   aggiunta con `set env` non è nel file, e l'apply lascia stare quello che il
   file non nomina.

3. **Terminale 2: il dev server.**

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

4. Apri **http://localhost:5173** e scegli **Accedi con GitHub** (l'account con
   il ruolo DISTILLATORE, o RESP_ACQUISTI per l'area acquisti). Dopo il login
   torni alla home, con il tuo nome in alto e il menu del tuo ruolo.

Cosa succede durante il login:

```
http://localhost:5173/oauth2/authorization/github
   │  proxy di Vite (Host: localhost:8080)
   ▼
gateway  ──302──▶  github.com  (redirect_uri = http://localhost:8080/login/oauth2/code/github)
                       │  l'utente autorizza GinVault
                       ▼
http://localhost:8080/login/oauth2/code/github  ── port-forward ──▶ gateway
   │  crea la sessione (cookie SESSION) e risponde 302 verso FRONTEND_URL
   ▼
http://localhost:5173/   ── GET /auth/me (proxy) ──▶ nome e ruoli
```

Il cookie `SESSION` viene impostato da `localhost:8080` ma vale anche per
`localhost:5173`: i browser non distinguono i cookie per porta.

Altri comandi utili:

- per tornare alla configurazione normale (`FRONTEND_URL` = `/`):
  `kubectl set env deployment/gateway -n ginvault FRONTEND_URL-`
- per controllare l'override: `kubectl set env deployment/gateway -n ginvault --list`
- con Docker Compose al posto di Minikube il gateway è già sulla 8080:
  `$env:FRONTEND_URL="http://localhost:5173"; docker compose up -d --build --wait`
- per passare dall'Ingress (porta 80) invece che dal port-forward, con
  `minikube tunnel` aperto: `$env:GINVAULT_GATEWAY="http://localhost"; npm run dev`

## In produzione: immagine Docker con nginx

Il [`Dockerfile`](Dockerfile) ha due stadi. Il primo, con Node 24, esegue
`npm ci`, i test e la build. L'immagine finale è nginx
(`nginxinc/nginx-unprivileged:1.30-alpine`) con la sola cartella `dist/`: niente
Node, niente sorgenti. nginx gira come utente `nginx` (uid 101), non come root,
e per questo ascolta sulla 8080.

Cosa risponde nginx ([`nginx/default.conf`](nginx/default.conf)):

| Path | Risposta |
|---|---|
| `/assets/...` | il file, in cache per un anno: il nome contiene l'impronta del contenuto (`index-CbeMtITL.js`), quindi non cambia mai. Se manca, 404 |
| `/api/...`, `/auth/...`, `/oauth2/...`, `/login`, `/logout` | proxy verso `gateway:8080`, con l'`Host` del browser (il gateway lo usa per il `redirect_uri` OAuth) e l'upgrade per i WebSocket. Serve con Docker Compose: su Kubernetes questi path li manda al gateway l'Ingress |
| tutto il resto (`/`, `/soci`, `/distilleria/...`) | `index.html`, senza cache: le pagine sono route di React Router |

Le pagine hanno alcune intestazioni di sicurezza
([`nginx/intestazioni.conf`](nginx/intestazioni.conf)), che le risposte del
gateway non ricevono:

- `Content-Security-Policy`: script, stili, font e immagini solo da questa
  origine, richieste (fetch e WebSocket) solo verso questa origine, niente
  `'unsafe-inline'`, e la pagina non si apre dentro l'iframe di un altro sito;
- `X-Content-Type-Options: nosniff` e `Referrer-Policy`.

Con Docker Compose, dalla cartella principale del repository:

```powershell
docker compose -f docker-compose.yml up -d --build --wait
```

poi si apre **http://localhost**. Il container `frontend` pubblica la porta 80
(dentro ascolta sulla 8080) e sta su una rete, `frontend-net`, condivisa solo
con il gateway: da nginx i servizi e i database non si raggiungono.

Per costruire solo l'immagine: `docker build -t ginvault/frontend:prova frontend`.

Il Dockerfile cambia anche `worker_processes auto` in `worker_processes 2`: con
`auto` nginx avvia un processo per ogni CPU che vede, e in Docker Desktop e
Minikube sono 32. Per dei file statici ne bastano 2.

### Su Kubernetes

La stessa immagine, costruita nel nodo con
`minikube image build -t ginvault/frontend:0.2 .\frontend`, gira su Minikube
([`k8s/frontend.yaml`](../k8s/frontend.yaml)):

- **Deployment a 2 repliche**: il front-end non ha stato, se un pod cade
  risponde l'altro; gli aggiornamenti tolgono un pod vecchio solo quando il nuovo
  è Ready. Il filesystem del container è in sola lettura (nginx scrive solo in
  `/tmp`, un volume `emptyDir`), senza privilegi né capability;
- **Service `frontend`** (porta 80 → 8080 dei pod);
- l'**Ingress** manda `/api`, `/auth`, `/oauth2`, `/login` e `/logout` al
  gateway e tutto il resto al front-end: è lui a fare lo smistamento che con
  Compose fa nginx, quindi il proxy di nginx su Kubernetes non riceve traffico;
- una **NetworkPolicy** fa entrare nei pod del front-end solo il controller
  dell'Ingress.

Avvio, verifica e la storia di Marco dal front-end, passo per passo, sono nella
"Demo completa" di [`k8s/README.md`](../k8s/README.md#demo-completa).

## Back-office del distillatore (storia di Marco)

Pagine per il ruolo DISTILLATORE, con le API vere di Ricette ed Edizioni.

| Pagina | Cosa si fa | Chiamate (dal gateway) |
|---|---|---|
| `/distilleria/formulazioni` | elenco, filtro per stato (`?stato=SCELTA`) | `GET /api/ricette/formulazioni[?stato=]` |
| `/distilleria/formulazioni/nuova` | nome, botanica di stagione, dosi della prima prova | `POST /api/ricette/formulazioni` |
| `/distilleria/formulazioni/{id}` | prove con le dosi, nuova prova, scelta del panel, creazione dell'edizione (da SCELTA) | `GET …/{id}`, `POST …/{id}/versioni`, `POST …/{id}/scelta`, `POST /api/edizioni/edizioni` |
| `/distilleria/edizioni` | elenco con le quote in bottiglie | `GET /api/edizioni/edizioni` (+ nomi da `GET …/formulazioni?stato=USATA`) |
| `/distilleria/edizioni/{id}` | tiratura, quote in bottiglie, ricalcolo con il prima e il dopo (600 → 540) | `GET …/{id}`, `POST …/{id}/ricalcolo` |

- **Anteprime.** Mentre si scrive, la tiratura e il ricalcolo si calcolano con le stesse
  regole del dominio ([`src/utilita/regole.ts`](src/utilita/regole.ts)): kg × 1000 / grammi,
  al massimo 600; ONLINE cala per primo, poi BAR, SOCI mai. Decide comunque il servizio:
  un ricalcolo impossibile si può inviare lo stesso e Edizioni spiega perché (409).
- **Errori.** Titolo e dettaglio del Problem Detail del servizio, così come arrivano
  ([`src/componenti/ErroreOperazione.tsx`](src/componenti/ErroreOperazione.tsx)): 409
  «Tiratura non riducibile», 422 «Edizione non valida», 503 «Ricette non disponibile»
  (con Riprova), 400 con l'elenco dei campi. Dopo un 409 «Modifica concorrente» si
  possono ricaricare i dati; dopo un 401 si rientra e si torna alla stessa pagina.
- **Numeri.** Si scrivono con la virgola (`10,8`) e si mostrano all'italiana.
- **Se Ricette è fermo** le pagine delle edizioni si aprono lo stesso: al posto del
  nome della formulazione compare la botanica.
- **Quote in bottiglie.** Ogni bottiglia disegnata ne vale 10
  ([`src/componenti/Bottiglie.tsx`](src/componenti/Bottiglie.tsx)). Dopo un ricalcolo
  le bottiglie tolte si svuotano una dopo l'altra e accanto al canale compare lo scarto
  (−60); con «riduci movimento» attivo nel sistema si vede subito il risultato.

## Area acquisti e piano di produzione (storia di Elena)

Pagine con le API vere di Magazzino e Produzione, dal gateway (`/api/magazzino/**`,
`/api/produzione/**`).

Per Elena (RESP_ACQUISTI) c'è la voce **Acquisti**, con tre schede: ordini, lotti, fornitori.

| Pagina | Cosa si fa | Chiamate (dal gateway) |
|---|---|---|
| `/acquisti` | ordini da ricevere e consegnati, con la barra dei kg (pieni quelli arrivati, tratteggiati quelli che mancano) e le etichette: atteso da N giorni, ridotta, in ritardo | `GET /api/magazzino/ordini` |
| `/acquisti/ordini/nuovo` | edizione (scelta tra i piani di produzione), fornitore, kg, consegna attesa; avvisa se il fornitore ha un'origine diversa da quella dell'edizione | `GET /api/produzione/piani`, `GET /api/magazzino/fornitori`, `POST /api/magazzino/ordini` |
| `/acquisti/ordini/{id}` | registrazione della consegna: kg arrivati, giorno, scadenza del lotto, esito del controllo qualità, nota. Mentre si scrive compare l'avviso «Consegna ridotta: 10,8 su 12 kg», con i kg che non arriveranno e i giorni di ritardo | `GET …/ordini/{id}`, `POST …/ordini/{id}/consegne` |
| `/acquisti/lotti`, `/acquisti/lotti/{id}` | lotti con origine, scadenza, esito qualità e kg riservati per la produzione; filtro per botanica (`?botanica=lavanda`) | `GET /api/magazzino/lotti`, `GET …/lotti/{id}` |
| `/acquisti/fornitori`, `/acquisti/fornitori/{id}` | scheda del fornitore: consegne puntuali, in ritardo e ridotte, e lo storico dalla più recente | `GET /api/magazzino/fornitori`, `GET …/fornitori/{id}` |

Per Marco (DISTILLATORE) c'è la voce **Produzione**:

| Pagina | Cosa si fa | Chiamate (dal gateway) |
|---|---|---|
| `/distilleria/piani` | un piano per edizione, con la barra dei kg: dalle consegne, integrati, attesi, mancanti | `GET /api/produzione/piani` |
| `/distilleria/piani/{id}` | kg previsti, disponibili e mancanti, bottiglie producibili, consegne, integrazioni e **Integra un lotto** | `GET …/piani/{id}`, `GET …/piani/{id}/lotti`, `POST …/piani/{id}/integrazioni` |

- **Quale edizione.** Elena non può leggere le edizioni (`GET /api/edizioni/...` è per
  DISTILLATORE e ADMIN). L'edizione di un ordine quindi si sceglie tra i piani di
  produzione, che hanno botanica, origine e kg mancanti. Il nome («Edizione d'estate
  2026») viene dalla stagione in cui è nato il piano.
- **Il rifiuto spiegato.** Un lotto di un'altra origine si può scegliere: ha il bordo
  tratteggiato e l'anteprima dice già che Produzione lo rifiuterà. Il 409 «Integrazione
  rifiutata» porta il `motivo`. Con `ORIGINE_DIVERSA` la pagina mostra le due origini e
  dice che nessun kg è stato riservato: Produzione controlla la regola prima di chiamare
  il magazzino. Gli altri rifiuti (kg che non bastano, riserva rifiutata dal magazzino) e
  il 503 si vedono come gli altri errori.
- **Il lotto della consegna non si propone.** I lotti arrivati con le consegne del piano
  sono già nei suoi kg: Produzione non li restituisce in `GET …/lotti` e li rifiuta con
  `LOTTO_DELLA_CONSEGNA`.
- **Anteprime.** Le regole di consegna e integrazione sono in
  [`src/utilita/regole.ts`](src/utilita/regole.ts), come quelle del ricalcolo:
  - kg più di zero e non più di quelli ordinati;
  - la consegna non nel futuro (giorno di Roma) e la scadenza dopo la consegna;
  - la nota al massimo di 500 caratteri;
  - le origini confrontate senza maiuscole e spazi, come `Origini.stessa` di Produzione.
- **Dopo la consegna** non serve altro: piano e tiratura cambiano con gli eventi. La
  pagina dell'edizione porta al suo piano.

## Area soci (storia di Giulia)

Pagine per il ruolo SOCIO. Le prenotazioni non esistono ancora nei servizi: per ora
i dati sono finti, in un modulo a parte, già nella forma delle risposte vere.

| Pagina | Cosa si vede |
|---|---|
| `/soci` | gli avvisi del club («La tiratura è scesa da 600 a 540, la tua prenotazione è confermata»), le prenotazioni con lo stato e i numeri delle bottiglie, le regole della prelazione |
| `/soci/edizioni/{id}` | l'edizione vista dal socio: l'avviso, le bottiglie n. 112 e 113 con il cartellino, il ritiro alla masterclass d'agosto, la tracciabilità (formulazione → lavanda → controllo qualità), le quote da 600 a 540 |

- **Dati finti.** [`src/mock/soci.ts`](src/mock/soci.ts) racconta l'edizione d'estate
  2026 (la cronologia è in testa al file): 12 kg di lavanda annunciati, 10,8 arrivati,
  tiratura da 600 a 540, cala solo la vendita online. Le risposte arrivano dopo 250 ms,
  come dalla rete, e un'edizione che non c'è risponde 404, come farà il servizio. I test
  ([`src/mock/soci.test.ts`](src/mock/soci.test.ts)) controllano che i dati rispettino
  le regole del dominio: tiratura, quote, al massimo 2 bottiglie per socio, numeri
  dentro la tiratura, tracciabilità in ordine.
- **Come sostituirli.** Le pagine importano solo [`src/api/soci.ts`](src/api/soci.ts):
  i tipi (`Prenotazione`, `EdizioneSoci`, `Notifica`, `Tappa`…) e l'oggetto `soci`, con
  `area()` ed `edizione(id)`. Quando Edizioni esporrà le prenotazioni si sostituisce
  `datiFinti` con le chiamate `api('GET', …)` in quel file e si cancella `src/mock/`:
  pagine e tipi restano uguali.
- **Avvisi.** Oggi arrivano con `area()`. Poi li manderà il servizio Notifiche
  (NestJS), via WebSocket, leggendo `TiraturaRidotta` che Edizioni pubblica già su
  `edizioni.eventi`: la forma di
  `Notifica` (evento, titolo, testo, edizione) è già quella. Nella pagina dell'edizione
  l'avviso si ricava dai dati: tiratura sotto quella annunciata e prenotazione confermata.
- **Movimento.** Le quote partono da quelle annunciate e calano quando arrivano sullo
  schermo; con «riduci movimento» si vede subito il risultato.

## Stile

Un gin di montagna, luminoso: «classico moderno», con il paesaggio al posto di
riquadri e righe. Il back-office ha lo stesso marchio, più sobrio.

- **Colori.** Fondo neve `#F7F5F0` con sezioni avorio `#EFE9DC` alterne. Verde abete
  `#2F4A3A` solo per titoli, pulsanti e piè di pagina; salvia `#9DB4A0` e cielo alpino
  `#C9D8E3` per le montagne; ottone `#B08A4A` per linee e dettagli. La lavanda
  `#B9A9F5` è riservata all'edizione d'estate. Nessun fondo scuro a tutta pagina,
  tranne il piè di pagina.
- **Contrasto.** Il testo è sempre almeno 4,5:1. Per questo l'ottone diventa
  `#7A5B26` quando è testo (5,7:1 sulla neve) e il testo secondario è `#56685C`
  (5,4:1 sulla neve, 4,9:1 sull'avorio). Nel piè di pagina il testo è `#C9D3CB`
  sul verde (6,3:1).
- **Caratteri.** Titoli e numeri in EB Garamond, come il logo; testo in Inter Tight.
- **Montagne.** Tra una sezione e l'altra ci sono tre crinali in SVG (cielo, salvia
  e il colore della sezione che segue), in [`src/componenti/Montagne.tsx`](src/componenti/Montagne.tsx).
  Le sagome le calcola `crinale()` in
  [`geometria.ts`](src/componenti/illustrazioni/geometria.ts): piramidi con versanti
  di pendenza diversa, più un frastaglio, con un caso a seme fisso. Sono quindi sempre
  uguali. Sul telefono il disegno resta largo e se ne vede il centro, così le cime
  non si schiacciano.
- **Home.** Paesaggio a tutto schermo con un velo chiaro. Titolo e bottiglia stanno
  dentro il paesaggio, e il crinale più vicino ne copre la base. Seguono:
  - i numeri (600 bottiglie, 7 giorni, 3 canali);
  - l'edizione d'estate, con la bottiglia, la lavanda e la tiratura a bottigliette;
  - la striscia delle botaniche (ginepro, lavanda, genziana, pino mugo);
  - come nasce un'edizione, in tre passi lungo un sentiero;
  - il club dei soci.
- **Back-office.** Ogni pagina ha in cima una fascia avorio che scende sulla neve con
  un crinale basso ([`TestataPagina.tsx`](src/componenti/TestataPagina.tsx)). Sotto ci
  sono schede bianche con un'ombra leggera e le quote a bottigliette (SOCI abete, BAR
  ottone, ONLINE cielo). Nella pagina dell'edizione la bottiglia sta nella fascia, con
  il cartellino che prende la stagione e la tiratura dall'edizione. L'alone è di
  lavanda solo d'estate.
- **Movimento**, con la libreria [motion](https://motion.dev)
  ([`Movimento.tsx`](src/componenti/Movimento.tsx)):
  - parallasse leggero su foto, crinali e bottiglia dell'apertura;
  - testi e immagini che entrano con una dissolvenza la prima volta che arrivano
    sullo schermo;
  - numeri che contano;
  - la striscia delle botaniche che scorre. Si ferma fuori dallo schermo, sotto il
    puntatore e con il pulsante «Ferma le botaniche».

  Si animano solo `opacity` e `transform`, e `LazyMotion` carica solo le funzioni
  usate. Lo svuotarsi delle bottiglie nel ricalcolo è un'animazione CSS. Con «riduci
  movimento» attivo nel sistema tutto resta fermo: le botaniche diventano una griglia
  e i numeri compaiono già contati.

## Immagini

- **Logo e bottiglia.** Partono dalle immagini in [`docs/`](../docs/), con lo sfondo
  tolto da uno script. Ogni immagine è in WebP, più un PNG di riserva per i browser
  senza WebP (`<picture>`). Le misure sono il doppio di come appaiono, per gli schermi
  retina:
  - `logo.webp` e `logo.png` sono larghi 184 px, per l'intestazione (dove il logo è
    largo al massimo 92 px);
  - `logo-chiaro.webp` e `logo-chiaro.png` sono larghi 400 px e color crema, per il
    piè di pagina (al massimo 200 px);
  - per la bottiglia ci sono `bottiglia-262.webp` e `bottiglia-524.webp` (`srcset`);
    il PNG di riserva `bottiglia.png` è largo 262 px e conserva tutti i colori,
    perché la riduzione a 256 colori macchia il vetro.

  Nessuna immagine supera i 200 kB, tranne il PNG di riserva della bottiglia
  (270 kB), che scaricano solo i browser senza WebP.
- **Fotografie.** Due foto da [Unsplash](https://unsplash.com), entrambe con la
  [Unsplash License](https://unsplash.com/license): uso libero, anche commerciale,
  senza obbligo di attribuzione (la diamo lo stesso). Sono in
  [`src/assets/foto/`](src/assets/foto/), in WebP in più larghezze (scaricate da Unsplash
  già alla misura e alla qualità giusta, sotto i 200 kB);
  [`foto.ts`](src/assets/foto/foto.ts) le descrive, con il testo alternativo.

| Dove | Foto | Autore |
|---|---|---|
| Home (apertura), accesso, area soci | [Una valle tra le montagne](https://unsplash.com/photos/a-valley-between-mountains-YC-FvqEmKhI), Titlis, Engelberg (Svizzera) | [Laurin Vontobel](https://unsplash.com/@loraeng) |
| Home, club dei soci | [Fiori di campo in un pascolo di montagna](https://unsplash.com/photos/wildflowers-in-a-mountain-meadow-under-a-clear-blue-sky-bT6TDi4kYnk), Kals am Großglockner (Austria) | [the iop](https://unsplash.com/@the_iop) |

La foto della valle ha due tagli (`<picture>`): orizzontale da tablet in su,
verticale sul telefono.

## Comandi

| Comando | Cosa fa |
|---|---|
| `npm run dev` | Dev server su http://localhost:5173, con il proxy verso il gateway |
| `npm test` | Test con Vitest: CSRF ed errori di `api` (con i motivi del rifiuto di un'integrazione), menu per ruolo, ritorno dopo il login, regole di tiratura e ricalcolo, controllo delle dosi, regole di ordine, consegna e integrazione con i dati di Elena, numeri e date all'italiana, schede dell'area acquisti, dati finti dell'area soci |
| `npm run build` | Controllo dei tipi (`tsc -b`) e build di produzione in `dist/` |

## Struttura

```
Dockerfile              build con Node e immagine finale con nginx
nginx/                  default.conf (pagine, cache, proxy verso il gateway) e
                        intestazioni.conf (Content-Security-Policy e altre intestazioni)
src/
  api/                  http.ts (CSRF, JSON, ErroreApi), ricette.ts, edizioni.ts, magazzino.ts,
                        produzione.ts, errori.ts, soci.ts (tipi e dati dell'area soci)
  mock/                 soci.ts: i dati finti dell'area soci, finché non ci sono le prenotazioni
  sessione/             utente da /auth/me, login, logout, pagina da riaprire dopo il login
  navigazione.ts        pagine e ruoli: un solo posto per menu e route protette
  componenti/           intestazione, layout, avvisi, route protetta, errori, editor delle dosi,
                        bottiglie delle quote, ricette con i puntini, Bottiglia (foto e
                        cartellino), Montagne (divisori e crinali), Movimento (dissolvenze e
                        contatori), StrisciaBotaniche, TestataPagina (fascia e indicatori),
                        Acquisti (schede, etichette, barra dei kg, avviso di consegna ridotta)
  componenti/illustrazioni/  ginepro, lavanda, genziana e pino mugo al tratto, crinali delle
                        montagne (geometria.ts li calcola)
  assets/               logo, logo chiaro e bottiglia
  assets/foto/          le fotografie in WebP e foto.ts (srcset e testi alternativi)
  pagine/               home, accesso, profilo, pagina non trovata
  pagine/soci/          area soci ed edizione vista dal socio
  pagine/distilleria/   formulazioni, edizioni e piani di produzione (back-office del distillatore)
  pagine/acquisti/      ordini, consegne, lotti e fornitori (area acquisti)
  utilita/              numeri all'italiana, regole per le anteprime, useCaricamento
  stile.css             palette, tipografia, intestazione, montagne, home, accesso, piè di
                        pagina (si parte dal telefono)
  stile-distilleria.css fascia avorio, indicatori, schede, ricette, moduli e bottiglie del
                        back-office; barra dei kg, scelte e storico dell'area acquisti
  stile-soci.css        area soci: prenotazioni, regole, bottiglie numerate, foglio del
                        ritiro, tappe della tracciabilità
```

## Versioni

Le dipendenze sono fissate alle major React 19, Vite 7, React Router 7,
TypeScript 5.9 e Vitest 4. Esistono già Vite 8, TypeScript 7 e React Router 8:
l'aggiornamento si può fare in un secondo momento.
