# Demo: la storia di Elena su Minikube

Elena è la responsabile acquisti, Marco il mastro distillatore. In questa demo:

1. Marco crea l'edizione d'autunno con **12 kg** di lavanda dell'Alta Val
   Varaita → **600 bottiglie**;
2. Elena ordina **12 kg** ai Raccoglitori dell'Alta Val Varaita;
3. per la pioggia ne arrivano **10,8**: Elena registra la consegna ridotta;
4. la catena di eventi porta l'edizione a **540 bottiglie**, senza che nessuno
   tocchi l'edizione;
5. per coprire i kg che mancano Elena propone il **lotto dei campi** dell'azienda;
6. Marco prova a integrarlo nel piano di produzione e Produzione lo
   **rifiuta**: l'origine è diversa.

Poi tre prove su quello che è successo dietro:
- i messaggi nei topic;
- l'**idempotenza**: lo stesso evento letto due volte non ricalcola due volte;
- l'**outbox**: con Kafka spento la consegna si registra lo stesso, e l'evento
  parte quando Kafka torna.

La catena dei passi 1–4:

```
Marco: Crea l'edizione ──► edizioni   outbox ──► edizioni.eventi    EdizioneCreata      (chiave: id dell'edizione)
                                                     └─► produzione: nuovo piano, 12 kg previsti (attesi)
Elena: Registra la consegna ──► magazzino outbox ──► magazzino.eventi   ConsegnaRegistrata  (chiave: lavanda)
                                                     └─► produzione: il piano passa a 10,8 kg
                                     produzione outbox ──► produzione.eventi  ResaRicalcolata  (chiave: id dell'edizione)
                                                     └─► edizioni: tiratura da 600 a 540
                                       edizioni outbox ──► edizioni.eventi    TiraturaRidotta
```

Ogni servizio scrive i suoi eventi nella tabella `outbox` del suo database,
nella stessa transazione del cambiamento, e un publisher li manda a Kafka. Ogni
consumer segna gli eventi già elaborati nella tabella `evento_elaborato`.
I formati sono in [`eventi.md`](eventi.md), la chiamata gRPC dell'integrazione
in [`grpc.md`](grpc.md).

## Prerequisiti

- Stack Compose spento (`docker compose down`): il front-end di Compose e
  `minikube tunnel` vogliono entrambi la porta 80.
- Cluster avviato e applicato come nella "Demo completa" di
  [`k8s/README.md`](../k8s/README.md#demo-completa), passi 1–4, con queste immagini:

  ```powershell
  minikube image ls | Select-String ginvault    # frontend:0.3, gateway:0.3, edizioni:0.5, ricette:0.2, magazzino:0.3, produzione:0.4
  ```

- `minikube tunnel` aperto in una PowerShell **da amministratore**.
- Un account con **due ruoli**, DISTILLATORE per Marco e RESP_ACQUISTI per Elena.
  I ruoli si sommano: basta scrivere la stessa identità (ad esempio
  `github:<utente>`) sia in `RUOLI_DISTILLATORE` sia in `RUOLI_RESP_ACQUISTI` del
  `.env`. Poi ricrea il Secret e riavvia il gateway, che legge le variabili solo
  all'avvio:

  ```powershell
  .\k8s\crea-secret.ps1
  kubectl rollout restart deployment/gateway -n ginvault
  kubectl rollout status deployment/gateway -n ginvault
  ```

  In alternativa: due account (ad esempio GitHub per Marco e Google per Elena),
  con **Esci** e un nuovo login quando la storia passa dall'uno all'altra.

Controlli prima di cominciare:

```powershell
kubectl get pods -n ginvault        # 18 pod Running: 2/2 i servizi e il front-end (2 frontend, 2 ricette, 2 notifiche), 1/1 i DB e kafka-0; il Job kafka-topic Completed
curl.exe -s http://localhost/api/magazzino/health     # {"status":"UP"}: la route del gateway verso magazzino
curl.exe -s http://localhost/api/produzione/health    # {"status":"UP"}: la route verso produzione
curl.exe -s -o NUL -w '%{http_code}' http://localhost/api/magazzino/ordini   # 401: senza login niente dati
```

Il gateway inoltra `/api/magazzino/**` a `magazzino:8084` e
`/api/produzione/**` a `produzione:8083`, togliendo il prefisso. I due servizi
verificano il JWT firmato dal gateway, come edizioni e ricette.

Facoltativo, ma rende la catena visibile: in una seconda PowerShell segui i log
dei tre servizi della catena, solo le righe degli eventi.

```powershell
kubectl logs -n ginvault -l 'app.kubernetes.io/name in (magazzino,produzione,edizioni)' --prefix -f --tail=0 | Select-String 'EdizioneCreata|ConsegnaRegistrata|ResaRicalcolata|Tiratura'
```

Va rilanciato dopo ogni `kubectl scale`, perché segue solo i pod che c'erano
quando è partito.

## 1. Login

Apri `http://localhost` (non `127.0.0.1`) → **Accedi** → **Accedi con GitHub**.
**Il mio profilo** mostra i ruoli DISTILLATORE e RESP_ACQUISTI. Il menu ha
**Formulazioni**, **Edizioni** e **Produzione** per Marco, e **Acquisti** per Elena.

## 2. Marco crea l'edizione: 12 kg → 600 bottiglie

1. **Formulazioni** → **Nuova formulazione**. Nome `Gin alla lavanda d'autunno`,
   botanica di stagione `lavanda`, dosi: ginepro 25, lavanda 20, coriandolo 5 →
   **Registra la formulazione**.
2. Sulla versione 1: **Scegli questa versione** → **Sì, scegli questa**.
3. Nel modulo **Crea l'edizione**:
   - **Kg di lavanda disponibili** `12` (l'anteprima dice 600 bottiglie:
     12 000 g / 20 g);
   - **Origine della lavanda** `Alta Val Varaita (Alpi Cozie)`;
   - quote **Metà ai soci, un quarto ai bar, il resto online** (300 / 150 / 150);
   - **Crea l'edizione**.

Atteso:

- la pagina dell'edizione con tiratura **600** e le quote 300 / 150 / 150;
- un secondo dopo, **Produzione** ha il piano dell'edizione: 12 kg previsti,
  tutti **attesi**, 0 mancanti, 600 bottiglie producibili. Finché non arriva
  una consegna, i kg previsti contano come attesi: li toglie solo una consegna
  ridotta. Nessuno ha creato il piano a mano: è arrivato con `EdizioneCreata`.
  Lo stesso piano si apre dal link **piano di produzione** nella pagina
  dell'edizione.

Nei log: `EdizioneCreata <eventId> da edizioni.eventi-<P>@0: piano di produzione
per l'edizione <id> (12 kg di lavanda, Alta Val Varaita (Alpi Cozie))`.

## 3. Elena ordina 12 kg

**Acquisti** → **Nuovo ordine**:

- **Per quale edizione**: «Edizione d'autunno 2026», cioè il piano appena nato,
  con la sua origine e i kg mancanti. Elena non legge le edizioni: le sceglie
  dai piani di produzione;
- **Fornitore**: Raccoglitori dell'Alta Val Varaita (stessa origine
  dell'edizione, quindi nessun avviso). Con **Campi GinVault** la pagina avvisa
  che quell'origine non entrerà nell'edizione;
- **Kg di lavanda da ordinare**: `12` (o il pulsante con i kg mancanti);
- **Consegna attesa**: una data di qualche giorno fa, per vedere anche il ritardo;
- **Registra l'ordine**.

Atteso: risposta **201** e la pagina dell'ordine, da ricevere. In **Acquisti**
l'ordine ha le etichette **Da ricevere** e **Atteso da N giorni**. Il piano non
cambia: Produzione vede un ordine solo quando arriva la sua consegna.

## 4. Elena registra la consegna da 10,8 kg

Nella pagina dell'ordine, **Registra la consegna**:

- **Kg di lavanda arrivati** `10,8`: mentre scrivi compare l'avviso
  **«Consegna ridotta: 10,8 su 12 kg»**, con gli 1,2 kg che non arriveranno e i
  giorni di ritardo;
- **Arrivata il** oggi, **Scadenza del lotto** tra un anno;
- esito del controllo qualità **Conforme**;
- **Nota** `pioggia durante la fioritura`;
- **Registra la consegna**.

Atteso:

- risposta **201** (Network nel browser: `POST /api/magazzino/ordini/<id>/consegne`).
  La pagina mostra la consegna e **il lotto** nuovo: 10,8 kg, origine Alta Val
  Varaita;
- l'ordine passa tra i **consegnati**, con l'etichetta della consegna ridotta;
- la scheda del fornitore (**Acquisti** → **Fornitori**) conta una consegna
  ridotta e in ritardo.

## 5. La catena: l'edizione scende a 540 da sola

Non serve fare niente. Dopo un paio di secondi:

- **Produzione** → il piano: 10,8 kg disponibili (dalle consegne), 1,2
  mancanti, **540 bottiglie** producibili. Sotto, **Le consegne**, con la
  consegna di Elena e il motivo;
- **Edizioni** → l'edizione: tiratura **540**, quote SOCI 300, BAR 150,
  **ONLINE 90**. Le 60 bottiglie in meno escono da ONLINE, come nel ricalcolo
  manuale della storia di Marco. Questa volta però nessuno ha chiamato
  `/ricalcolo`.

Nei log, in ordine:

```
magazzino   Evento ConsegnaRegistrata <id> pubblicato al tentativo 1
produzione  ConsegnaRegistrata <id> da magazzino.eventi-1@0: ordine <id> per l'edizione <id>, 10,8 kg di lavanda su 12 -> ...
produzione  Evento ResaRicalcolata <id> pubblicato al tentativo 1
edizioni    ResaRicalcolata <id> da produzione.eventi-<P>@0: edizione <id> con 10,8 kg (consegna ridotta: 10,8 su 12 kg), tiratura da 600 a 540 -> TiraturaRidotta
edizioni    Evento TiraturaRidotta <id> pubblicato al tentativo 1
```

Nei database:

```powershell
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c 'select edizione_id, kg_previsti, kg_disponibili from piano_di_produzione'
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c 'select ordine_id, kg_ordinati, kg_arrivati, kg_utilizzabili, motivo from piano_consegna'
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c 'select e.tiratura, q.canale, q.bottiglie from edizione e join quota q on q.edizione_id = e.id where e.origine_botanica is not null order by e.creata_il, q.canale'
```

## 6. Elena propone il lotto dei campi, Marco lo prova

Mancano 1,2 kg. **Acquisti** → **Lotti** (filtro lavanda): oltre al lotto della
consegna c'è il lotto dei **Campi GinVault (Saluzzo, 400 m)**, 2 kg, conforme.
Elena lo propone a Marco.

Marco: **Produzione** → il piano → **Integra un lotto**.

- La lista ha solo il lotto dei campi. Il lotto della consegna non c'è: i suoi
  10,8 kg sono già nel piano, e Produzione non lo propone (e lo rifiuterebbe
  con `LOTTO_DELLA_CONSEGNA`).
- Il lotto dei campi ha il bordo tratteggiato e l'anteprima dice già che
  Produzione lo rifiuterà per l'origine. Si può scegliere lo stesso:
  **Kg da integrare** `1,2` → **Integra il lotto**.

Atteso:

- risposta **409** «Integrazione rifiutata», motivo `ORIGINE_DIVERSA`. La pagina
  mostra le due origini: il piano chiede lavanda da «Alta Val Varaita (Alpi
  Cozie)», il lotto viene da «Campi GinVault (Saluzzo, 400 m)». E dice che
  nessun kg è stato riservato;
- il piano resta a 10,8 kg e l'edizione a 540.

Produzione controlla l'origine **prima** di chiamare il magazzino via gRPC,
quindi in magazzino non c'è nessuna riserva da annullare:

```powershell
kubectl exec -n ginvault deploy/magazzino-db -- psql -U magazzino -d magazzino -c 'select lotto_id, piano_id, kg from lotto_riserva'      # 0 righe
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c 'select lotto_id, origine, kg from piano_integrazione' # 0 righe
kubectl exec -n ginvault deploy/magazzino-db -- psql -U magazzino -d magazzino -c 'select origine, kg, ordine_id is not null as da_consegna from lotto_di_botanica'
```

L'ultima query mostra i due lotti: quello dei campi ancora da 2 kg e quello
della consegna da 10,8.

## 7. I messaggi nei topic

Si leggono con il consumer a riga di comando di Kafka, dentro `kafka-0`. Legge e
basta: i messaggi restano dove sono, e i consumer group `produzione` ed
`edizioni` non cambiano (il consumer a riga di comando usa un suo gruppo).
`--timeout-ms 5000` lo chiude dopo 5 secondi senza messaggi nuovi. La riga
`ERROR ... TimeoutException` alla fine è il modo in cui si chiude, non un problema.

```powershell
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic magazzino.eventi --from-beginning --timeout-ms 5000 --formatter-property print.partition=true --formatter-property print.offset=true --formatter-property print.key=true
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic produzione.eventi --from-beginning --timeout-ms 5000 --formatter-property print.partition=true --formatter-property print.offset=true --formatter-property print.key=true
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic edizioni.eventi --from-beginning --timeout-ms 5000 --formatter-property print.partition=true --formatter-property print.offset=true --formatter-property print.key=true
```

Atteso, al primo giro della demo:

| Topic | Partizione, offset | Chiave | Messaggio |
|---|---|---|---|
| `magazzino.eventi` | 1, 0 | `lavanda` | `ConsegnaRegistrata`: ordine, edizione, lotto, origine, `kgOrdinati` 12, `kgArrivati` 10,8, esito qualità |
| `produzione.eventi` | P, 0 | id dell'edizione | `ResaRicalcolata`: `kgDisponibiliPrima` 12, `kgDisponibili` 10,8, motivo «consegna ridotta: 10,8 su 12 kg» |
| `edizioni.eventi` | P, 0 | id dell'edizione | `EdizioneCreata`: formulazione, 20 g per bottiglia, origine, 12 kg, 600 bottiglie |
| `edizioni.eventi` | P, 1 | id dell'edizione | `TiraturaRidotta`: da 600 a 540, le quote nuove, la causa e il `resaRicalcolataId` |

- La chiave sceglie la partizione. Tutte le consegne di lavanda vanno nella
  partizione 1 di `magazzino.eventi`, in ordine.
- Gli eventi di un'edizione hanno tutti come chiave l'id dell'edizione, quindi
  stanno nella stessa partizione P (0, 1 o 2, dipende dall'id). Su
  `edizioni.eventi` `EdizioneCreata` viene sempre prima di `TiraturaRidotta`.
- Ogni messaggio è la busta di [`eventi.md`](eventi.md#formato-dei-messaggi):
  `eventId`, `tipo`, `versione`, `occorsoIl`, `payload`.

Chi ha letto cosa: le colonne CURRENT-OFFSET e LOG-END-OFFSET coincidono (lag 0).

```powershell
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group produzione
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group edizioni
```

Lo stesso messaggio, leggibile, dall'outbox di chi l'ha scritto:

```powershell
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c "select jsonb_pretty(messaggio::jsonb) from outbox where tipo = 'ResaRicalcolata' order by numero desc limit 1"
```

Dashboard: **Workloads → Stateful Sets → kafka** e **Workloads → Pods →
kafka-0** (i log del broker).

## 8. Prova dell'idempotenza: lo stesso evento letto due volte

Kafka garantisce che un evento arrivi **almeno una volta**: dopo un errore lo
stesso messaggio può arrivare di nuovo. Per rifarlo arrivare senza scrivere
niente nei topic, si riporta indietro di uno l'offset del gruppo `produzione`
sulla partizione 1 di `magazzino.eventi`. Al riavvio Produzione rilegge la
`ConsegnaRegistrata` di Elena: stesso messaggio, stesso `eventId`.

```powershell
# 1) Produzione spento: il gruppo deve essere vuoto per spostare gli offset
kubectl scale deployment/produzione -n ginvault --replicas=0
kubectl wait -n ginvault --for=delete pod -l app.kubernetes.io/name=produzione --timeout=90s

# 2) Offset indietro di uno, solo sulla partizione della lavanda: prima la prova, poi davvero
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group produzione --topic magazzino.eventi:1 --reset-offsets --shift-by -1 --dry-run
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group produzione --topic magazzino.eventi:1 --reset-offsets --shift-by -1 --execute

# 3) Produzione riparte e rilegge il messaggio
kubectl scale deployment/produzione -n ginvault --replicas=1
kubectl rollout status deployment/produzione -n ginvault
kubectl logs -n ginvault deploy/produzione | Select-String 'già elaborato'
```

Atteso:

- il `--dry-run` e l'`--execute` mostrano `magazzino.eventi 1` con NEW-OFFSET di
  uno più basso (0 al primo giro);
- nel log di produzione:
  `ConsegnaRegistrata <id> da magazzino.eventi-1@0: già elaborato, scartato (consumer idempotente)`;
- niente è cambiato:
  - il piano è ancora a 10,8 kg, con una sola consegna;
  - in `produzione.eventi` c'è ancora una sola `ResaRicalcolata`;
  - l'edizione è ancora a 540;
- il gruppo è di nuovo in pari: `--describe --group produzione` mostra lag 0.

```powershell
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c 'select event_id, tipo, topic, partizione, posizione from evento_elaborato order by elaborato_il'
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c 'select count(*) as consegne from piano_consegna'
```

Il consumer cerca l'`eventId` in `evento_elaborato` prima di applicare
l'evento. Se c'è già, lo scarta. Se non c'è, applica l'evento e lo segna nella
stessa transazione: o tutte e due le cose o nessuna. Edizioni fa lo stesso con
`ResaRicalcolata`: la prova è identica con
`kubectl scale deployment/edizioni`, `--group edizioni` e
`--topic produzione.eventi:P`, la partizione della tabella del passo 7.

Se il reset risponde che il gruppo non è inattivo, il pod non ha ancora finito
di spegnersi: aspetta qualche secondo e riprova.

Dashboard: **Workloads → Deployments → produzione** (0/0, poi 1/1, e i log).

## 9. Prova dell'outbox: la consegna con Kafka spento

Elena ordina gli 1,2 kg che mancano dallo stesso fornitore, e la consegna arriva
mentre Kafka è spento.

```powershell
# 1) Kafka spento: il pod kafka-0 sparisce, il PVC dati-kafka-0 resta
kubectl scale statefulset kafka -n ginvault --replicas=0
kubectl wait -n ginvault --for=delete pod/kafka-0 --timeout=120s
kubectl get pods -n ginvault        # magazzino, produzione ed edizioni restano 2/2 Ready
```

Dal browser, come Elena:

1. **Acquisti** → **Nuovo ordine**: la stessa edizione (ora mancano 1,2 kg) →
   **Ordina quanto manca: 1,2 kg**, Raccoglitori dell'Alta Val Varaita,
   consegna attesa oggi → **Registra l'ordine**.
2. **Registra la consegna**: 1,2 kg, arrivata oggi, scadenza tra un anno,
   **Conforme** → **Registra la consegna**.

Atteso: risposta **201** e la pagina con il lotto, come con Kafka acceso.
La consegna è salvata, e il suo evento aspetta nell'outbox:

```powershell
kubectl exec -n ginvault deploy/magazzino-db -- psql -U magazzino -d magazzino -c 'select numero, tipo, chiave, tentativi, pubblicato_il, ultimo_errore from outbox order by numero'
kubectl logs -n ginvault deploy/magazzino --tail=20
```

- l'ultima riga, `ConsegnaRegistrata`, ha `pubblicato_il` vuoto e `tentativi`
  che sale a ogni nuova esecuzione della query;
- nel log di magazzino un WARN al primo tentativo e poi uno ogni 30:
  `Kafka non ha confermato l'evento ConsegnaRegistrata <id> (tentativo 1): resta nell'outbox e riparte al prossimo giro. Causa: ...`;
- il piano è ancora a 10,8 kg e l'edizione a 540: l'evento non è ancora partito.

Kafka torna (stesso nome `kafka-0`, stesso disco, un IP nuovo):

```powershell
kubectl scale statefulset kafka -n ginvault --replicas=1
kubectl wait -n ginvault --for=condition=Ready pod/kafka-0 --timeout=180s
kubectl logs -n ginvault deploy/magazzino | Select-String 'pubblicato al tentativo'
```

Atteso, entro una ventina di secondi:

- in magazzino: `Evento ConsegnaRegistrata <id> pubblicato al tentativo N`, e la
  riga dell'outbox ha `pubblicato_il`;
- la catena riparte da sola:
  - il piano sale a **12 kg**, 0 mancanti, 600 bottiglie;
  - `ResaRicalcolata` da 10,8 a 12;
  - l'edizione torna a **600**, ONLINE da 90 a 150. Nel log di edizioni:
    `... con 12 kg (consegna completa: 1,2 su 1,2 kg), tiratura da 540 a 600 -> TiraturaAumentata`;
- in `magazzino.eventi` la nuova consegna è una sola volta, nella partizione 1
  all'offset 1 (stesso comando del passo 7).

Dopo il ritorno di Kafka passano alcuni secondi prima della pubblicazione:
- il broker finisce di partire anche dopo essere Ready;
- il producer prova prima il vecchio IP del pod, ma rinuncia dopo pochi secondi.

Dashboard: **Workloads → Stateful Sets → kafka** (0/0, poi 1/1) e **Workloads →
Deployments → magazzino** (sempre 1/1, i log con i WARN).

## La stessa storia dalla console

Per vedere le richieste REST una per una, al posto delle pagine. La funzione
`api` è in [`demo-storia-marco.md`](demo-storia-marco.md#1-login-e-console-del-browser).
Va incollata nella console dopo il login, con l'account dei due ruoli.

```js
const giorno = (d = 0) => new Date(Date.now() + d * 86400000).toLocaleDateString('sv-SE', { timeZone: 'Europe/Rome' });
const ORIGINE = 'Alta Val Varaita (Alpi Cozie)';

// Marco: formulazione scelta ed edizione da 12 kg → 201, tiratura 600
const f = await api('POST', '/api/ricette/formulazioni', { nome: `Gin alla lavanda ${Date.now()}`, botanicaDiStagione: 'lavanda',
  dosi: [{ botanica: 'ginepro', grammiPerBottiglia: 25 }, { botanica: 'lavanda', grammiPerBottiglia: 20 }, { botanica: 'coriandolo', grammiPerBottiglia: 5 }] });
await api('POST', `/api/ricette/formulazioni/${f.id}/scelta`, { versione: 1 });
const e = await api('POST', '/api/edizioni/edizioni', { formulazione: { id: f.id, versione: 1 },
  botanicaDisponibileKg: 12, origineBotanica: ORIGINE, quote: { SOCI: 300, BAR: 150, ONLINE: 150 } });

// Il piano arriva con EdizioneCreata (un secondo dopo): 12 kg previsti e disponibili, 600 bottiglie
const [piano] = await api('GET', `/api/produzione/piani?edizione=${e.id}`);

// Elena: ordine da 12 kg e consegna da 10,8 → 201 con ordine e lotto
const o = await api('POST', '/api/magazzino/ordini', { fornitoreId: 'f0000000-0000-4000-8000-000000000001',
  botanica: 'lavanda', kgOrdinati: 12, consegnaAttesa: giorno(-6), edizioneId: e.id });
await api('POST', `/api/magazzino/ordini/${o.id}/consegne`, { kgArrivati: 10.8, consegnataIl: giorno(),
  scadenza: giorno(365), esitoQualita: 'CONFORME', nota: 'pioggia durante la fioritura' });

// Un paio di secondi dopo: tiratura 540, ONLINE 90; piano a 10,8 kg
await api('GET', `/api/edizioni/edizioni/${e.id}`);
await api('GET', `/api/produzione/piani/${piano.id}`);

// Il lotto dei campi: l'unico proposto, stessaOrigine false → 409 ORIGINE_DIVERSA
await api('GET', `/api/produzione/piani/${piano.id}/lotti`);
await api('POST', `/api/produzione/piani/${piano.id}/integrazioni`, { lottoId: 'a0000000-0000-4000-8000-000000000001', kg: 1.2 });
```

Il 409 è un Problem Details: titolo «Integrazione rifiutata», `motivo`
`ORIGINE_DIVERSA`, `origineRichiesta` e `origineLotto`, e il dettaglio «origine
diversa: l'edizione deve restare fedele alla sua origine…».

## Con Docker Compose

La storia funziona uguale su `http://localhost` con `docker compose up` (e il
tunnel chiuso). Nei comandi cambia solo il modo di arrivare ai container:

| Su Minikube | Con Compose |
|---|---|
| `kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/...` | `docker compose exec kafka /opt/kafka/bin/...` |
| `kubectl exec -n ginvault deploy/magazzino-db -- psql ...` | `docker compose exec magazzino-db psql ...` |
| `kubectl logs -n ginvault deploy/produzione` | `docker compose logs produzione` |
| `kubectl scale deployment/produzione ... --replicas=0` / `=1` | `docker compose stop produzione` / `start produzione` |
| `kubectl scale statefulset kafka ... --replicas=0` / `=1` | `docker compose stop kafka` / `start kafka` |

In Git Bash `docker compose exec kafka /opt/kafka/...` non funziona: Git Bash
trasforma il percorso in `C:/Program Files/Git/opt/...`. Usa PowerShell.

## Riepilogo dei casi

| Cosa | Risultato |
|---|---|
| Edizione da 12 kg con origine | 201, tiratura 600; il piano nasce da `EdizioneCreata` |
| Ordine da 12 kg | 201, ordine aperto |
| Consegna da 10,8 kg | 201 con il lotto; avviso «Consegna ridotta: 10,8 su 12 kg» |
| Dopo la consegna, senza altre azioni | piano a 10,8 kg; edizione a 540, ONLINE 90 (`ResaRicalcolata` → `TiraturaRidotta`) |
| Lotto dei campi nel piano | 409 `ORIGINE_DIVERSA`, nessuna riserva in magazzino |
| Lotto della consegna nel piano | non proposto; se lo si manda: 409 `LOTTO_DELLA_CONSEGNA` |
| Stessa `ConsegnaRegistrata` riletta | scartata (`già elaborato`), niente ricalcolato |
| Consegna con Kafka spento | 201; evento in outbox con i tentativi; parte quando Kafka torna |
| Consegna degli 1,2 kg che mancano | piano a 12 kg; edizione di nuovo a 600 (`TiraturaAumentata`) |

## Ripetere la demo

Ogni giro crea formulazione, edizione, piano e ordini nuovi, con id nuovi. Cosa
cambia dal secondo giro:

- nei topic ci sono anche i messaggi dei giri prima, e gli offset del passo 7
  sono più alti;
- **Integra un lotto** propone anche i lotti delle consegne dei giri prima.
  Hanno la stessa origine e Produzione li accetterebbe: per vedere il rifiuto
  va scelto il lotto dei **Campi GinVault**. È un limite noto, in
  [`grpc.md`](grpc.md): Produzione esclude solo i lotti delle consegne del suo
  piano, non quelli delle consegne di un'altra edizione;
- il lotto dei campi resta sempre da 2 kg, perché il rifiuto non lo riserva.

Se la sessione scade, le pagine e la console rispondono 401: rifai il login.

## Come è stata verificata

- **Catena completa**, con le immagini `edizioni:0.5`, `ricette:0.2`,
  `magazzino:0.3` e `produzione:0.4`, in Docker Compose, chiamando i servizi con
  JWT di prova firmati da una chiave locale al posto del gateway:
  - piano creato 1,2 s dopo `EdizioneCreata`;
  - edizione a 540 2,1 s dopo la consegna;
  - 409 `ORIGINE_DIVERSA` sul lotto dei campi;
  - messaggi nei topic come nella tabella del passo 7;
  - `già elaborato, scartato` dopo il reset dell'offset;
  - con Kafka spento, consegna salvata (201) ed evento pubblicato al
    tentativo 6, 5 s dopo il ritorno di Kafka; l'edizione è tornata a 600.
- **Su Minikube**, senza login:
  - le route `/api/magazzino/**` e `/api/produzione/**` del gateway (`/health`
    200, API 401);
  - i topic e i consumer group `produzione` ed `edizioni`;
  - le tabelle delle query.

  Il giro dal browser richiede il login OAuth e va fatto con l'account dei due ruoli.
