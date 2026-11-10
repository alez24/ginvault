# GinVault: stato dei lavori

Aggiornato al 10 novembre 2026. Tutto quello che è stato fatto finora, in ordine,
e il punto esatto in cui siamo arrivati.

## In breve: dove siamo

- **Branch:** `storia-elena` (creato da `main` aggiornato, `ed2c054`).
- **Storia di Elena, passi 1–6 committati** su `storia-elena`, **senza push**.
  `AnnullaRiserva` (la compensazione di Produzione) e il passo 5 (Edizioni
  consuma `ResaRicalcolata`) sono in `080310f`; il passo 6 (frontend per
  Elena) e la correzione di Produzione sul lotto della consegna in `2c5b712`.
- **Passo 7** (integrazione e demo) è nel commit con questo file:
  `docs/demo-storia-elena.md`, README, `k8s/README.md`, immagini nuove sul
  cluster. La storia di Elena è finita.
- **Prossimo:** da decidere (vedi [Dopo la storia di Elena](#dopo-la-storia-di-elena-dal-piano-docspianomd)).
  Il giro della demo dal browser sul cluster è ancora da fare.
- Sul cluster girano `frontend:0.3`, `gateway:0.3`, `edizioni:0.5`,
  `ricette:0.2`, `magazzino:0.3` e `produzione:0.4`.
  Dopo un riavvio del PC servono di nuovo Docker Desktop e `minikube start`.

## Cronologia dei commit

| Commit | Data | Cosa |
|---|---|---|
| `a623f5b` | 4 nov | **Fondamenta**: 5 microservizi Spring Boot 3.5 (edizioni, ricette, produzione, magazzino, pagamenti) con `/health` e un PostgreSQL ciascuno; gateway Spring Cloud Gateway con route `/api/<servizio>/**`; notifiche in NestJS; Docker Compose con reti private e Kafka 4.3 KRaft |
| `f8f725f` | 4 nov | **Sicurezza**: login OAuth2 Google/GitHub sul gateway (pattern BFF, sessione con cookie), ruoli dal `.env`; il gateway firma un JWT RS256 di 5 minuti e pubblica la JWKS; i servizi sono resource server (`@PreAuthorize`); test di sicurezza |
| `0954acb` (PR #1) | 6 nov | **Kubernetes minimo**: namespace, gateway, edizioni ed edizioni-db su Minikube con Ingress, probe, `crea-secret.ps1` (Secret dal `.env` senza valori nel repository), `k8s/README.md` |
| `5706f72` (PR #2) | 6 nov | **Storia di Marco**: Ricette (formulazioni con prove di assaggio, scelta del panel); Edizioni validate chiamando Ricette via REST (timeout 2 s, ACL `CatalogoRicette`); tiratura al massimo 600 e quote per canale; ricalcolo che toglie da ONLINE, poi BAR, mai SOCI né sotto le prenotate; CSRF sul gateway; NetworkPolicy per ogni DB; Testcontainers e WireMock |
| `3cb57a8` (PR #3) | 7 nov | **Frontend React**: login, back-office del distillatore, tema "gin di montagna" con animazioni |
| `524e350` (PR #3) | 7 nov | **Storia di Giulia (solo frontend)**: area soci con dati finti (prenotazioni, tiratura da 600 a 540) |
| `c9806ed` (PR #3) | 7 nov | Frontend in un container nginx, in Compose su `http://localhost` |
| `dfa6bb9` (PR #3) | 7 nov | Frontend su Kubernetes: 2 repliche, Ingress per path, NetworkPolicy per front-end e gateway |
| `86fe672` | 7 nov | **Elena, passo 1: Kafka** (vedi sotto) |
| `830a03f` | 8 nov | **Elena, passo 2: Magazzino** (vedi sotto) |
| `0298ebb` | 10 nov | **Elena, passo 3: Produzione** (vedi sotto) |
| `497fd93` | 10 nov | **Elena, passo 4: gRPC Produzione ↔ Magazzino** (vedi sotto) |
| `080310f` | 10 nov | **Elena, `AnnullaRiserva` e passo 5: Edizioni consuma `ResaRicalcolata`** (vedi sotto) |
| `2c5b712` | 10 nov | **Elena, passo 6: frontend per Elena**, più la correzione di Produzione sul lotto della consegna (vedi sotto) |
| (il commit con questo file) | 10 nov | **Elena, passo 7: integrazione e demo** (vedi sotto) |

Le PR #1, #2 e #3 sono unite in `main` con merge commit.

## La storia di Elena

> Elena, responsabile acquisti, ordina 12 kg di lavanda per l'edizione
> d'estate. Per la pioggia ne arrivano 10,8. Prova a coprire la differenza con
> un lotto dei campi dell'azienda, ma Marco lo rifiuta: ha un'origine diversa.
> Elena registra la consegna ridotta, e la riduzione arriva da sola fino
> all'edizione: 600 → 540 bottiglie.

I sette passi:

| # | Passo | Stato |
|---|---|---|
| 1 | Kafka 4.x KRaft in Compose e su K8s, 3 topic | ✅ committato `86fe672` |
| 2 | Servizio magazzino (8084): ordini, consegne, lotti, fornitori, outbox | ✅ committato `830a03f` |
| 3 | Servizio produzione (8083): piani, consumer idempotenti, retry, DLT, `ResaRicalcolata` | ✅ committato `0298ebb` |
| 4 | gRPC Produzione ↔ Magazzino: `Giacenze`, integrazione di un lotto, 409 per origine diversa, 503 | ✅ committato `497fd93` |
| 4+ | `AnnullaRiserva`: compensazione se il piano non si salva dopo la riserva | ✅ committato `080310f` |
| 5 | Edizioni consuma `ResaRicalcolata` → `TiraturaRidotta` / `TiraturaAumentata` / `RicalcoloRifiutato` | ✅ committato `080310f` |
| 6 | Frontend per Elena (area acquisti) e integrazione del lotto per il distillatore | ✅ committato `2c5b712` |
| 7 | Gateway, compose, K8s, README, `docs/demo-storia-elena.md` | ✅ committato |

### Passo 1: Kafka (`86fe672`)

- **Compose:**
  - Kafka 4.3 in modalità KRaft, broker singolo, sulla rete dedicata
    `kafka-net` (condivisa solo con edizioni, produzione e magazzino);
  - heap massimo 512 MB;
  - creazione automatica dei topic spenta.
- **Topic:** `edizioni.eventi`, `magazzino.eventi`, `produzione.eventi`, con 3
  partizioni ciascuno. Li crea il servizio `kafka-topic`, che poi termina; i
  servizi dipendono da lui con `service_completed_successfully`.
- **Kubernetes** (`k8s/kafka.yaml`):
  - Service headless;
  - StatefulSet `kafka-0` con PVC;
  - Job `kafka-topic` che si cancella da solo dopo un'ora;
  - NetworkPolicy: Kafka accetta solo i pod con la label `ginvault/usa-kafka: "true"`.
- **Contratto degli eventi** in `docs/eventi.md`:
  - una busta JSON `{eventId, tipo, versione, occorsoIl, payload}`;
  - la chiave del messaggio è l'id dell'edizione o la botanica;
  - nessun header `__TypeId__`.

### Passo 2: Magazzino (`830a03f`)

- **Servizio Spring Boot sulla porta 8084**, con il suo DB `magazzino-db`.
  Subdomain Approvvigionamento.
- **Endpoint** (ruolo RESP_ACQUISTI; in lettura anche DISTILLATORE e ADMIN):
  - `POST /ordini`;
  - `POST /ordini/{id}/consegne`: registra la consegna, anche ridotta, crea il
    lotto e aggiorna lo storico del fornitore;
  - `GET /lotti?botanica=`;
  - `GET /fornitori/{id}`.
- **Regole:**
  - una consegna per ordine;
  - non più kg di quelli ordinati;
  - l'origine del lotto è quella del fornitore;
  - una consegna può essere "ridotta" e "in ritardo" insieme.
- **Transactional outbox:**
  - `ConsegnaRegistrata` si scrive nella tabella `outbox` nella stessa
    transazione della consegna;
  - un publisher, ogni secondo, la manda su Kafka;
  - se Kafka è giù la consegna si registra lo stesso e l'evento parte quando
    Kafka torna, nello stesso ordine.
- **Dati iniziali:**
  - fornitori "Raccoglitori dell'Alta Val Varaita" (`f0000000-…-0001`) e
    "Campi GinVault" (`…-0002`);
  - il lotto dei campi (`a0000000-…-0001`, 2 kg, origine diversa).
- **Su K8s:** `magazzino.yaml`, `magazzino-db.yaml` e la sua NetworkPolicy.
  Provato anche l'outbox con Kafka spento, su un topic usa e getta.

### Passo 3: Produzione (`0298ebb`)

**Edizioni**
- **Origine della botanica:** creare un'edizione richiede `origineBotanica`
  (per esempio "Alta Val Varaita (Alpi Cozie)"). Senza, la risposta è 400.
- **Evento:** l'edizione creata pubblica `EdizioneCreata` su `edizioni.eventi`
  passando dall'outbox (stesso schema di magazzino).
- **Immagine:** `ginvault/edizioni:0.4`.

**Produzione (porta 8083, DB `produzione-db`)**
- **Piano di produzione:** da `EdizioneCreata` nasce un `PianoDiProduzione`
  (edizione, botanica, grammi per bottiglia, origine richiesta, kg previsti).
- **Consegne:** con `ConsegnaRegistrata` il piano ricalcola i kg disponibili.
  Se cambiano, parte `ResaRicalcolata` su `produzione.eventi`, via outbox.
- **API** (DISTILLATORE, RESP_ACQUISTI, ADMIN):
  - `GET /piani?edizione=`;
  - `GET /piani/{id}`, con `bottiglieProducibili` (10,8 kg a 20 g = 540).
- **Consumer idempotenti:** la tabella `evento_elaborato` è scritta nella stessa
  transazione dell'effetto, quindi lo stesso evento rimandato si scarta. In più,
  ogni ordine conta una volta sola.
- **Retry con backoff:** nuovi tentativi dopo 1, 2, 4 e 8 secondi. Servono per
  esempio se la consegna arriva prima della sua edizione.
- **Dead letter topic** `produzione.dlt`: il messaggio ci va finiti i tentativi,
  oppure subito se riprovare non serve (JSON illeggibile, versione non
  supportata). Finisce nella stessa partizione, con gli header `kafka_dlt-*`.
- **Due listener separati** (uno per topic, 3 consumer ciascuno) nel gruppo
  `produzione`: i tentativi su una consegna non bloccano la lettura delle edizioni.

**Frontend** (`ginvault/frontend:0.2`)
- Campo "Origine della lavanda" nel modulo di creazione dell'edizione.
- L'origine compare anche nel dettaglio dell'edizione.

**Compose e Kubernetes**
- **Compose:** `KAFKA_BOOTSTRAP` per edizioni e produzione; topic `produzione.dlt`.
- **Manifest nuovi:** `k8s/produzione.yaml` e `k8s/produzione-db.yaml`, più la
  policy `produzione-db-solo-da-produzione`.
- **Manifest aggiornati:**
  - `produzione-db` aggiunto a `crea-secret.ps1` e a `kustomization.yaml`;
  - `produzione.dlt` aggiunto al Job dei topic.

**Documentazione**
- `docs/eventi.md`:
  - i payload di `EdizioneCreata` v1 e `ResaRicalcolata` v1;
  - la sezione su idempotenza, retry e dead letter.
- `README.md`: sezione Produzione e sezione test.
- `k8s/README.md`: sezione "Consumer di produzione", dodici pod, sette NetworkPolicy.

**Verifiche fatte**
- **Test:**

  | Servizio | Test superati |
  |---|---|
  | produzione | 34/34 |
  | edizioni | 70/70 |
  | frontend | 63/63 |

  I test di produzione, con Postgres e Kafka veri (Testcontainers), provano:
  - la catena dall'edizione alla resa;
  - l'idempotenza (lo stesso evento rimandato non ricalcola due volte);
  - il retry, nel caso in cui la consegna arriva prima dell'edizione;
  - il dead letter, subito o dopo i tentativi.
- **Cluster:**
  - 12 pod Ready;
  - il gruppo `produzione` ha 6 consumer, uno per partizione;
  - `produzione.dlt` ha 3 partizioni;
  - test busybox: database e Kafka bloccati da un pod qualsiasi; produzione
    raggiunge Kafka, il suo DB e la JWKS, ma non `magazzino-db`.
- **Non provato sul cluster:** la catena con messaggi veri, perché i messaggi
  di prova non vanno mai sui topic veri. Lì la catena è coperta dai test, e la
  mostreremo nella demo del passo 7.

**File del passo 3:**
- **modificati:**
  - `README.md`, `docker-compose.yml`, `docs/eventi.md`, `frontend/README.md`;
  - edizioni: `pom.xml`, `EdizioniController`, `Richieste`, `EdizioneVista`,
    `ServizioEdizioni`, `Edizione`, `application.yml` e i tre test;
  - frontend: `api/edizioni.ts`, `api/errori.ts`, `DettaglioEdizione.tsx`,
    `ModuloEdizione.tsx`, `regole.test.ts`;
  - k8s: `README.md`, `crea-secret.ps1`, `edizioni.yaml`, `frontend.yaml`,
    `kafka.yaml`, `kustomization.yaml`, `networkpolicy-db.yaml`;
  - produzione: `pom.xml`, `application.yml`, `GatewayDiProva`;
- **nuovi:**
  - edizioni: `Configurazione`, `EdizioneCreata`, le quattro classi
    dell'outbox in `infrastruttura/eventi`;
  - k8s: `produzione.yaml`, `produzione-db.yaml`;
  - produzione: tutto `api/`, `applicazione/`, `dominio/`,
    `infrastruttura/eventi/`, `Configurazione` e i tre test nuovi.

### La regola dei kg disponibili

**Com'è nata.**
1. La prima versione era "previsti meno kg mancanti": un ordine *in più* di
   un'altra origine toglieva kg all'edizione.
2. La seconda calcolava sulle consegne; l'hai approvata con una correzione.
3. La correzione: una consegna di un'altra origine non deve coprire i kg
   previsti. Prima 2 kg arrivati dai campi davano 12 − 2 = 10 kg, anche con
   l'ordine da 12 kg dall'Alta Val Varaita ancora aperto.

**La regola approvata:**

    disponibili = min(previsti, max(0, previsti − Σ kg ordinati con l'origine richiesta) + Σ kg utilizzabili)

- **Kg utilizzabili:** quelli arrivati, ma solo se il lotto è conforme e ha
  l'origine richiesta.
- **Ordini aperti:** Produzione vede un ordine solo quando arriva la sua
  consegna. Gli ordini ancora aperti stanno quindi nei kg previsti non
  coperti, cioè si aspettano ancora.

| Caso | Conto | Kg disponibili |
|---|---|---|
| La consegna di Elena | 0 + 10,8 | 10,8 (540 bottiglie) |
| Due ordini da 6 kg, uno consegnato completo | (12 − 6) + 6 | 12 (test) |
| Ordine da 12 aperto, prima arrivano 2 kg dai campi | (12 − 0) + 0 | 12 (test); poi l'ordine da 12 arriva ridotto: 10,8 |
| Un ordine in più dai campi dopo Elena | non toglie niente | resta 10,8 |
| Un riordino dall'Alta Val Varaita | | torna a 12 |

### Passo 4: gRPC Produzione ↔ Magazzino (`497fd93`)

Tutto spiegato in [docs/grpc.md](grpc.md). Scelte confermate: la regola
dell'origine prima della riserva, `GET /piani/{id}/lotti`, `lottoId` facoltativo
in `ResaRicalcolata` v1, immagini con `docker build` + `minikube image load`.

- **Modulo condiviso `giacenze-api`:**
  - contiene `giacenze.proto`, servizio `Giacenze` con `Disponibilita` e
    `RiservaLotto`;
  - grpc-java 1.85.1 e protoc 3.25.9, le versioni dell'esempio ufficiale;
  - kg come stringhe decimali;
  - i Dockerfile lo ricevono come secondo build context con nome
    (`--build-context giacenze-api=giacenze-api`, in Compose `additional_contexts`).
- **Magazzino (server, porta 9084):**
  - `ServerGiacenze` (SmartLifecycle accanto a Tomcat), `ServizioGiacenze`
    (status gRPC), `AutenticazioneGrpc` (stesso JwtDecoder e ruoli delle REST);
  - le riserve stanno nell'aggregato `LottoDiBotanica` (tabella `lotto_riserva`):
    idempotenti su (lotto, piano), mai oltre i kg liberi, solo su lotti
    utilizzabili, con `@Version` per le riserve contemporanee;
  - `LottoVista` ha in più `kgDisponibili`.
- **Produzione (client):**
  - `POST /piani/{id}/integrazioni` (DISTILLATORE, ADMIN) e `GET /piani/{id}/lotti`;
  - flusso: piano → `Disponibilita` → regola di dominio (origine prima di
    tutto) → `RiservaLotto` → integrazione e `ResaRicalcolata` via outbox;
  - deadline 2 s su ogni chiamata, JWT inoltrato nel metadata;
  - 409 con `motivo` (`ORIGINE_DIVERSA`...), 503 + `Retry-After` se magazzino
    è lento o spento, 502 per gli errori inattesi;
  - lo stesso lotto rimandato con gli stessi kg risponde 200 `GIA_INTEGRATO`;
  - `ResaRicalcolata` v1 ha in più il campo facoltativo `lottoId`.
- **Compose:** `additional_contexts`, `MAGAZZINO_GRPC: magazzino:9084`; la 9084
  non è pubblicata.
- **K8s:**
  - immagini `magazzino:0.2` e `produzione:0.2`, costruite con `docker build`
    e caricate con `minikube image load`;
  - Service magazzino con la porta `grpc`;
  - NetworkPolicy `magazzino-grpc-solo-da-produzione`
    (`networkpolicy-magazzino.yaml`).
- **Test:**
  - magazzino 61/61 (nuovi `GiacenzeGrpcTest`, `LottoDiBotanicaTest`);
  - produzione 59/59 (nuovo `IntegrazioneLottiTest` con un magazzino gRPC
    finto; più test di dominio e del controller).
- **Verificato sul cluster (10 nov):**
  - pod 0.2 Ready, "Server gRPC Giacenze in ascolto sulla porta 9084";
  - busybox: 9084 BLOCCATO da un pod qualsiasi, da edizioni e dal gateway,
    APERTO da produzione; 8084 sempre APERTO; DB, front-end, gateway e Kafka
    BLOCCATI come prima;
  - grpcurl via port-forward senza JWT → `Unauthenticated`;
  - le tabelle `lotto_riserva` e `piano_integrazione` sono create.
  Non è stata fatta un'integrazione vera sul cluster: serve il JWT di un
  login OAuth (si farà nella demo del passo 7).
- **Deciso nel passo 5:** con un'integrazione la resa risale (10,8 → 12), e
  le bottiglie in più tornano a ONLINE (`TiraturaAumentata`).

### `AnnullaRiserva`: la compensazione (`080310f`)

Dettagli in [docs/grpc.md](grpc.md#la-compensazione-annullariserva).

- **Contratto:** `giacenze-api` 0.2.0, `rpc AnnullaRiserva(lotto_id,
  piano_id)`. Risponde con i kg liberati e `nessuna_riserva`.
- **Magazzino:**
  - `LottoDiBotanica.annullaRiserva(pianoId)` toglie la riserva del piano;
  - è idempotente: senza riserva risponde OK con `nessuna_riserva = true` e
    0 kg liberati;
  - ruoli DISTILLATORE e ADMIN;
  - lotto sconosciuto → `NOT_FOUND`, modifica concorrente (`@Version`) →
    `ABORTED`.
- **Produzione** (`ServizioIntegrazioni`), se il salvataggio del piano fallisce
  dopo la riserva:
  1. rilegge il piano: se il lotto è già integrato (ha vinto una richiesta
     uguale, per esempio un doppio clic), risponde `GIA_INTEGRATO` e non annulla;
  2. altrimenti chiama `AnnullaRiserva` e scrive un WARN "Compensazione: ...";
  3. se anche l'annullamento fallisce, scrive un ERROR "riserva orfana" e lo
     aggiunge all'errore di partenza (`addSuppressed`);
  4. Marco riceve sempre l'errore di partenza (409 "Modifica concorrente" nei
     test).
- **Compose:** la 9084 è protetta solo dal JWT (annotato in `docs/grpc.md` e
  nel README).
- **Immagini:** `magazzino:0.3` e `produzione:0.3`.
- **Test:**
  - magazzino 64/64: annullare libera i kg, la seconda volta non cambia niente,
    lotto sconosciuto e ruolo sbagliato;
  - produzione 64/64:
    - il piano che non si salva, con un CHECK di prova sul DB, fa annullare la
      riserva;
    - l'annullamento che fallisce lascia l'errore di partenza e il log della
      riserva orfana;
    - un test unitario per la rilettura (richiesta concorrente vincente).

### Passo 5: Edizioni consuma `ResaRicalcolata` (`080310f`)

Dettagli in [docs/eventi.md](eventi.md#il-consumer-di-edizioni).

- **Consumer:**
  - Edizioni legge `produzione.eventi` nel gruppo `edizioni`: un listener, 3
    consumer, `ack-mode: record`, niente auto-commit;
  - stesse classi di Produzione: `ConsumerEventi`, `ElaborazioneEventi`,
    `LettoreEventi`, `ConfigurazioneKafka`, `EventoElaborato`;
  - idempotente con `evento_elaborato`, che qui ha anche l'`esito`;
  - retry 1, 2, 4, 8 s;
  - dead letter `edizioni.dlt` nella stessa partizione. Ci vanno subito
    `EventoNonValido` ed `EdizioneNonTrovata`.
- **Dominio** (`Edizione.applicaResa`):
  - se la tiratura scende, il ricalcolo esistente (ONLINE → BAR, mai SOCI, mai
    sotto le prenotate) → `TiraturaRidotta`;
  - se sale, le bottiglie in più vanno a ONLINE, fino a 600 →
    `TiraturaAumentata`;
  - se non si può → `RicalcoloRifiutato`, e l'edizione resta com'era. Motivi:
    `QUOTE_NON_RIDUCIBILI`, oppure `BOTANICA_INSUFFICIENTE` se i kg non
    bastano per una bottiglia (anche 0 kg);
  - stessa tiratura → nessun evento (esito `INVARIATA`).
- **Eventi** (tutti via outbox, nel commit dell'edizione):
  - `TiraturaRidotta` e `TiraturaAumentata` hanno in più `causa` (il motivo
    della resa) e `resaRicalcolataId` (l'`eventId` della resa, `null` per il
    ricalcolo manuale);
  - `RicalcoloRifiutato` ha anche la spiegazione in italiano e le quote con le
    prenotate.
- **Ricalcolo manuale di Marco:** ora pubblica anche lui `TiraturaRidotta` o
  `TiraturaAumentata`. Il rifiuto resta un 409, senza evento.
- **Tolto** `InoltroTiratureRidotte`, che scriveva `TiraturaRidotta` solo nel
  log: ora l'evento va nell'outbox.
- **Compose e K8s:**
  - topic `edizioni.dlt` nel servizio e nel Job `kafka-topic`;
  - immagine `edizioni:0.5`;
  - nessuna NetworkPolicy nuova: edizioni aveva già la label
    `ginvault/usa-kafka`.
- **Test:** edizioni 82/82.
  - `EdizioneTest`: 540 con la causa, ritorno a 600 dopo il lotto integrato,
    rifiuto sotto le prenotate e con 0 kg, nessun evento a tiratura invariata;
  - `ResaRicalcolataTest` (Postgres e Kafka veri): la catena 600 → 540 →
    600, `RicalcoloRifiutato`, l'idempotenza, il retry (un CHECK di prova su
    `evento_elaborato`), il dead letter subito per un messaggio illeggibile e
    per un'edizione che non esiste.
- **Documentazione:** `docs/eventi.md` (payload dei tre eventi, il consumer di
  Edizioni), `README.md`, `docs/grpc.md` (limiti), `k8s/README.md` (sezione
  "Consumer di edizioni"), `docs/demo-storia-marco.md` (il ricalcolo di
  Marco ora si vede nell'outbox e nel topic, non più solo nel log).
- **Verificato sul cluster (10 nov):**
  - `edizioni:0.5`, `magazzino:0.3` e `produzione:0.3` Ready, 13 pod;
    kafka-0 non è ripartito;
  - il Job `kafka-topic` ricreato ha creato `edizioni.dlt`, con 3 partizioni;
  - il gruppo `edizioni` ha 3 consumer, uno per partizione di
    `produzione.eventi`; `produzione` ne ha sempre 6;
  - `evento_elaborato` di edizioni ha la colonna `esito` (`not null`, messo a
    mano con un `alter` sulla tabella ancora vuota);
  - "Server gRPC Giacenze in ascolto sulla porta 9084" in magazzino.
  Nessun messaggio sui topic veri e nessuna NetworkPolicy cambiata (edizioni
  aveva già la label `ginvault/usa-kafka`), quindi niente test busybox. La
  catena 600 → 540 sul cluster si vedrà nella demo del passo 7.

### Passo 6: frontend per Elena (`2c5b712`)

Il frontend, la documentazione e una correzione di Produzione (in fondo). Le
pagine usano le API vere di Magazzino e Produzione; le route del gateway
c'erano già. Il dettaglio è in
[`frontend/README.md`](../frontend/README.md#area-acquisti-e-piano-di-produzione-storia-di-elena).

- **Area acquisti** (voce di menu «Acquisti», ruolo RESP_ACQUISTI), con tre schede:
  - **Ordini** (`/acquisti`): da ricevere e consegnati, barra dei kg, etichette
    (atteso da N giorni, ridotta, in ritardo).
  - **Nuovo ordine**: l'edizione si sceglie tra i piani di produzione, perché
    Elena non legge le edizioni. Se il fornitore ha un'altra origine, la pagina
    lo avvisa.
  - **Consegna** (`/acquisti/ordini/{id}`): kg, giorno, scadenza, esito del
    controllo qualità e nota. Mentre si scrive compare l'avviso «Consegna
    ridotta: 10,8 su 12 kg».
  - **Lotti**, con il filtro per botanica.
  - **Scheda del fornitore**, con lo storico.
- **Piano di produzione** (voce «Produzione», ruolo DISTILLATORE): l'elenco dei
  piani e la pagina del piano con **Integra un lotto**.
  - L'anteprima avvisa già quando un lotto ha un'origine diversa.
  - Il 409 `ORIGINE_DIVERSA` è spiegato con le due origini; nessun kg è
    riservato.
  - La pagina dell'edizione porta al suo piano.
- **Regole delle anteprime** in `regole.ts`, come quelle del ricalcolo. Le
  stesse dei servizi: consegna, nuovo ordine, confronto delle origini, kg
  dell'integrazione.
- **Test:** `npx tsc -b` pulito e `npm test` con 9 file e 93 test.
  Nuovi: `regole-elena.test.ts`, `Acquisti.test.ts`; ampliati i test di numeri,
  errori e navigazione.
- **Verifica visiva:** 13 schermate, desktop e telefono, con un finto gateway e
  Chrome headless nello scratchpad (non nel repository).
- **Correzione di Produzione: il lotto della consegna.** Prima Produzione
  proponeva e accettava il lotto arrivato con una consegna dello stesso piano.
  Integrare 1,2 kg dal lotto da 10,8 di Elena portava il piano a 12 kg,
  contando due volte la stessa lavanda. Adesso:
  - `PianoDiProduzione` lo rifiuta con il nuovo motivo `LOTTO_DELLA_CONSEGNA`
    (409, con `ordineId`), prima della riserva in magazzino;
  - `GET /piani/{id}/lotti` non lo restituisce più;
  - se l'evento della consegna arriva dopo che il suo lotto è stato integrato,
    nel conto dei kg quel lotto vale una volta sola;
  - test: due nuovi in `PianoDiProduzioneTest`, uno in `IntegrazioneLottiTest`.

  Resta un limite, scritto in `docs/grpc.md`: il lotto della consegna di
  un'altra edizione, con la stessa botanica e la stessa origine, si può ancora
  integrare. Servirebbe che la consegna riservasse il lotto per l'edizione
  dell'ordine.

### Passo 7: integrazione e demo (nel commit con questo file)

- **Gateway e Compose:** niente da cambiare.
  - Le route `/api/magazzino/**` e `/api/produzione/**` c'erano dal primo
    commit, con `StripPrefix=2`.
  - In Compose i due servizi avevano già JWKS, Kafka, gRPC e healthcheck.
- **Kubernetes:**
  - immagini nuove: `produzione:0.4` (`docker build --build-context
    giacenze-api=giacenze-api` + `minikube image load`) e `frontend:0.3`
    (`minikube image build`);
  - i tag aggiornati in `k8s/produzione.yaml`, `k8s/frontend.yaml`,
    `k8s/README.md` e `frontend/README.md`;
  - `kubectl apply -k k8s/`: rollout di produzione e frontend, Job
    `kafka-topic` ricreato, kafka-0 non è ripartito;
  - nessuna NetworkPolicy cambiata, quindi niente test busybox.
- **`docs/demo-storia-elena.md`:** la storia dal front-end, in nove passi.
  - Marco crea l'edizione (600). Elena ordina 12 kg e registra 10,8. La catena
    porta l'edizione a 540. Il lotto dei campi viene rifiutato con
    `ORIGINE_DIVERSA`.
  - I messaggi dei tre topic con `kafka-console-consumer`, con partizione,
    offset e chiave.
  - L'idempotenza: produzione spento, offset del gruppo `produzione` indietro
    di uno solo su `magazzino.eventi:1`, riavvio, e nel log `già elaborato,
    scartato`. Nessun messaggio scritto nei topic veri.
  - L'outbox: Kafka spento (`scale statefulset kafka --replicas=0`), Elena
    riordina 1,2 kg e registra la consegna (201), la riga aspetta con i
    tentativi. Kafka torna, l'evento parte, l'edizione torna a 600 con
    `TiraturaAumentata`.
  - La stessa storia dalla console del browser, i comandi equivalenti per
    Compose, il riepilogo dei casi.
- **README:** il riquadro «Stato» e la sezione Kubernetes aggiornati, con i
  link alle due demo. In `k8s/README.md` i rimandi alla demo di Elena.
- **Verificato (10 nov):**
  - **la storia intera, con le immagini del cluster, in uno stack Compose a
    parte** (`-p ginvault-prova`). Al posto del gateway c'era una JWKS di prova
    con token firmati in locale, solo nello scratchpad:
    - piano creato 1,2 s dopo `EdizioneCreata`;
    - edizione a 540 (ONLINE 90) 2,1 s dopo la consegna;
    - `GET …/lotti` con il solo lotto dei campi;
    - 409 `ORIGINE_DIVERSA` con le due origini;
    - i messaggi nei topic come nella demo;
    - la `ConsegnaRegistrata` riletta e scartata;
    - con Kafka spento la consegna risponde 201, poi l'evento parte al
      tentativo 6, 5 s dopo il ritorno di Kafka. Piano a 12 kg, edizione a 600;
  - **su Minikube, senza login:**
    - `/api/{edizioni,ricette,produzione,magazzino}/health` 200 dal gateway;
    - `/api/magazzino/ordini` e `/api/produzione/piani` 401;
    - i gruppi `produzione` (6 consumer) ed `edizioni` (3);
    - le tabelle e le colonne usate nelle query della demo.
  - **Non verificato:** il giro dal browser sul cluster. Richiede il login
    OAuth con un account che abbia DISTILLATORE e RESP_ACQUISTI.

## Regole di lavoro concordate

- Un passo alla volta. Alla fine di ognuno: un resoconto breve con i comandi
  per verificare, poi stop in attesa dell'ok.
- **Niente commit finché non lo dici**, e niente push se non richiesto.
- Nei commit e nelle PR nessuna riga "Co-Authored-By: Claude".
- **Il `.env` non si legge né si stampa.** I segreti li leggono Compose e
  `kubectl` (`crea-secret.ps1`), e si verificano dentro i container.
- Si seguono le convenzioni di edizioni e ricette: resource server, test con
  Testcontainers, Dockerfile, manifest K8s con NetworkPolicy.
- **Kubernetes:**
  - si spiegano gli oggetti, con i comandi `kubectl` (PowerShell 5.1) e il
    percorso nella dashboard;
  - ogni DB ha la sua policy in `networkpolicy-db.yaml`;
  - i servizi che scaricano la JWKS hanno la label
    `app.kubernetes.io/component: microservizio`;
  - quelli che usano Kafka hanno la label `ginvault/usa-kafka: "true"`;
  - dopo ogni modifica alle policy si ripete il test busybox.
- **I messaggi di prova vanno solo su topic usa e getta** (`prova.chiavi`,
  `prova.outbox`), mai sui topic veri.
- **Compose e `minikube tunnel` usano tutti e due la porta 80:** mai insieme.

## Comandi per riprendere

PowerShell, dalla cartella `ginvault`:

```powershell
# Stato del lavoro
git status
git log --oneline -5

# Test di un servizio senza giacenze-api (edizioni, ricette)
docker run --rm -v "${PWD}/edizioni:/workspace" -v ginvault-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9-eclipse-temurin-21 mvn -B test
# Magazzino e produzione: prima il modulo condiviso
docker run --rm -v "${PWD}:/repo" -v ginvault-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /repo maven:3.9-eclipse-temurin-21 sh -c 'mvn -B -q -f giacenze-api/pom.xml install && mvn -B -f magazzino/pom.xml test && mvn -B -f produzione/pom.xml test'

# Cluster
minikube start
kubectl get pods -n ginvault
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group produzione
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group edizioni
kubectl logs -n ginvault deploy/produzione --tail=20
```

Per vedere il frontend su Minikube serve `minikube tunnel`, aperto in un
PowerShell come amministratore, con Compose spento.

## Dopo la storia di Elena (dal piano, `docs/piano.md`)

- **Storia di Giulia:**
  - prenotazione con SAGA e Stripe;
  - `TiraturaRidotta` consumata da Notifiche, con WebSocket;
  - l'area soci con i dati veri al posto di quelli finti.
- **Kubernetes:** Istio e circuit breaker.
- **Plus rimanenti:** repliche del consumer Kafka.
- **Documentazione e prova generale:** diagrammi, presentazione, checklist.
