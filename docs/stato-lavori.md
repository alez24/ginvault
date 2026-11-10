# GinVault: stato dei lavori

Aggiornato al 10 novembre 2026. Tutto quello che è stato fatto finora, in ordine,
e il punto esatto in cui siamo arrivati.

## In breve: dove siamo

- **Branch:** `storia-elena` (creato da `main` aggiornato, `ed2c054`).
- **Storia di Elena, passi 1–3 committati** su `storia-elena`, **senza push**.
  Il passo 3 (Produzione) è nel commit "Produzione: piani, consumer idempotenti,
  retry e dead letter", insieme a questo file.
- **Passo 4 (gRPC Produzione ↔ Magazzino): in corso.**
- La regola dei kg disponibili è approvata, con una correzione (vedi
  [La regola dei kg disponibili](#la-regola-dei-kg-disponibili)).
- Sul cluster gira ancora l'immagine `produzione:0.1` con la regola di prima:
  si aggiorna con il passo 4. Dopo un riavvio del PC servono di nuovo Docker
  Desktop e `minikube start`.

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
| (il commit con questo file) | 10 nov | **Elena, passo 3: Produzione** (vedi sotto) |

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
| 3 | Servizio produzione (8083): piani, consumer idempotenti, retry, DLT, `ResaRicalcolata` | ✅ committato |
| 4 | gRPC Produzione ↔ Magazzino: `Giacenze`, integrazione di un lotto, 409 per origine diversa, 503 | ⏳ in corso |
| 5 | Edizioni consuma `ResaRicalcolata` → `TiraturaRidotta` / `RicalcoloRifiutato` | da fare |
| 6 | Frontend per Elena (area acquisti) e integrazione del lotto per il distillatore | da fare |
| 7 | Gateway, compose, K8s, README, `docs/demo-storia-elena.md` | da fare |

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

### Passo 3: Produzione (committato)

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

### Prossimi passi (4–7)

- **Passo 4, gRPC:**
  - Magazzino espone il servizio `Giacenze` con `Disponibilita(botanica)` e
    `RiservaLotto(lottoId, pianoId, kg)`, con i file `.proto` in un modulo condiviso;
  - Produzione espone `POST /piani/{id}/integrazioni` (DISTILLATORE). Se il lotto
    ha un'origine diversa risponde 409 ("origine diversa: l'edizione deve restare
    fedele alla sua origine"); se è compatibile lo riserva e ricalcola la resa;
  - deadline di 2 s: se magazzino non risponde, 503;
  - NetworkPolicy: la porta gRPC di magazzino si apre solo a produzione.
- **Passo 5:** Edizioni consuma `ResaRicalcolata` e applica il ricalcolo
  (ONLINE → BAR, mai SOCI). Poi pubblica `TiraturaRidotta`, oppure
  `RicalcoloRifiutato` se non si può, sempre via outbox.
- **Passo 6:** area acquisti nel frontend, cioè ordini, consegna con l'avviso
  "consegna ridotta: 10,8 su 12 kg", lotti e scheda fornitore. Nella pagina del
  piano, l'integrazione di un lotto con il rifiuto spiegato.
- **Passo 7:**
  - gateway, compose, K8s e README;
  - `docs/demo-storia-elena.md`, con la catena 600 → 540 senza azioni manuali,
    il rifiuto del lotto dei campi, i messaggi nei topic, la prova
    dell'idempotenza e la prova dell'outbox con Kafka spento.

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

# Test di un servizio (sostituire "produzione" con edizioni, magazzino, ...)
docker run --rm -v "${PWD}/produzione:/workspace" -v ginvault-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9-eclipse-temurin-21 mvn -B test

# Cluster
minikube start
kubectl get pods -n ginvault
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group produzione
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
