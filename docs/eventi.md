# Eventi di GinVault su Kafka

Il contratto tra i servizi che si parlano in modo asincrono: quali topic, con
quale chiave, in che formato. È il *linguaggio pubblicato* dei bounded context.
Ogni servizio scrive le proprie classi Java (o TypeScript, per Notifiche) a
partire da questo documento: nessuna libreria condivisa, così un servizio può
cambiare il suo modello interno senza toccare gli altri.

## Il broker

Kafka 4.3 in modalità **KRaft**, cioè senza ZooKeeper: un solo nodo che fa da
broker e da controller.

| | Docker Compose | Kubernetes |
|---|---|---|
| Dove | servizio `kafka`, rete `kafka-net` | StatefulSet `kafka` (pod `kafka-0`), [`k8s/kafka.yaml`](../k8s/kafka.yaml) |
| Indirizzo per i servizi | `kafka:9092` | `kafka:9092` (Service headless) |
| Chi lo raggiunge | i servizi sulla rete `kafka-net`: edizioni, produzione, magazzino | i pod con la label `ginvault/usa-kafka: "true"` ([`networkpolicy-kafka.yaml`](../k8s/networkpolicy-kafka.yaml)) |
| Dati | volume `kafka-data` | PVC `dati-kafka-0` (1 GiB) |
| Heap | 512 MB al massimo | 512 MB al massimo, container con limite di 1 GiB |
| Creazione dei topic | servizio `kafka-topic` (parte, crea, termina) | Job `kafka-topic` |

La creazione automatica dei topic è **spenta** (`auto.create.topics.enable=false`).
Un errore di battitura nel nome (`edizioni.evnti`) fa fallire il produttore
con `UnknownTopicOrPartitionException`. Con la creazione automatica nascerebbe
invece un topic nuovo, con 1 partizione, che nessuno legge.

## Topic

| Topic | Produttore (unico) | Chiave | Eventi | Consumer group |
|---|---|---|---|---|
| `edizioni.eventi` | Edizioni | id dell'edizione | `EdizioneCreata`, `TiraturaRidotta`, `RicalcoloRifiutato` | `produzione`; più avanti `notifiche` |
| `magazzino.eventi` | Magazzino | nome della botanica (`lavanda`) | `ConsegnaRegistrata` | `produzione` |
| `produzione.eventi` | Produzione | id dell'edizione | `ResaRicalcolata` | `edizioni` |
| `produzione.dlt` | Produzione (dead letter) | quella del messaggio originale | i messaggi che Produzione non riesce a elaborare, così come sono arrivati | nessuno: si leggono a mano |

- **Un topic per bounded context**, con nome `<contesto>.eventi`: chi lo
  possiede è l'unico che ci scrive. I nomi usano il punto e mai il trattino
  basso: Kafka avverte che `.` e `_` nei nomi dei topic possono confondersi
  nelle metriche (il WARNING che stampa `kafka-topics.sh --create`).
- **3 partizioni, replication factor 1** (c'è un solo broker). In una
  partizione i messaggi restano in ordine; tra partizioni diverse no.
- **La chiave sceglie la partizione**: tutti gli eventi di un'edizione stanno
  nella stessa partizione, quindi arrivano nell'ordine in cui sono stati
  pubblicati (prima `EdizioneCreata`, poi `TiraturaRidotta`). Magazzino usa la
  botanica, perché i suoi eventi riguardano le scorte di una botanica e non
  sempre un'edizione.
- Fino a **3 istanze** dello stesso consumer group leggono in parallelo, una
  per partizione: è il "consumer scalato" del piano.
- I messaggi restano nel topic **7 giorni** (default di Kafka). La fonte di
  verità è il database di ogni servizio, non Kafka.
- `produzione.dlt` è il **dead letter topic** di Produzione: un topic solo per
  tutti i messaggi scartati dai suoi consumer, qualunque sia il topic
  d'origine (lo dicono gli header). Vedi [Consumer](#consumer-idempotenza-retry-e-dead-letter).

## Formato dei messaggi

Chiave e valore sono testo UTF-8. Il valore è un JSON con una **busta** uguale
per tutti gli eventi e il contenuto specifico in `payload`:

```json
{
  "eventId": "6f1c2e8a-3b0d-4c55-9a7e-2d4f1b9c0e17",
  "tipo": "ConsegnaRegistrata",
  "versione": 1,
  "occorsoIl": "2026-08-06T08:30:00Z",
  "payload": { "...": "i dati dell'evento, diversi per ogni tipo" }
}
```

| Campo | Significato |
|---|---|
| `eventId` | UUID creato insieme all'evento, nella stessa transazione della riga di outbox. Se l'evento viene pubblicato due volte (un nuovo tentativo dopo un errore), l'id è lo stesso: il consumer lo riconosce e non lo elabora di nuovo. |
| `tipo` | Il nome dell'evento di dominio, al passato: `ConsegnaRegistrata`. Il consumer sceglie cosa fare in base a questo campo. |
| `versione` | La versione del formato di `payload` per quel `tipo`, da 1. |
| `occorsoIl` | Quando il fatto è successo nel dominio, in UTC (ISO-8601 con `Z`). Non è l'ora di pubblicazione: con l'outbox un evento può partire più tardi, per esempio quando Kafka torna disponibile. |
| `payload` | I dati dell'evento. Pesi in kg come numeri decimali con 3 cifre dopo la virgola (il grammo), id come stringhe, date come `AAAA-MM-GG`. |

Regole:

- **Niente tipi Java nei messaggi**: nessun header `__TypeId__` di Spring
  (`spring.json.add.type.headers=false`). Il contratto è il JSON, che anche
  Notifiche (NestJS) deve leggere.
- **Lettore tollerante**: un consumer ignora i campi che non conosce e i
  `tipo` che non gli interessano. Aggiungere un campo facoltativo non cambia
  la `versione`.
- **Cambiamento incompatibile** (un campo tolto, rinominato o con un altro
  significato): `versione` + 1. Il produttore passa alla nuova versione quando
  tutti i consumer sanno leggerla.

## Gli eventi della storia di Elena

Il contenuto di ogni `payload` viene fissato nel passo che introduce l'evento;
qui c'è cosa deve dire.

| Evento | Topic | Quando | Contenuto |
|---|---|---|---|
| `EdizioneCreata` | `edizioni.eventi` | Marco crea un'edizione | edizione, botanica di stagione, grammi per bottiglia, origine richiesta, kg previsti |
| `ConsegnaRegistrata` | `magazzino.eventi` | Elena registra una consegna, anche ridotta | ordine, botanica, kg ordinati, kg arrivati, origine, edizione |
| `ResaRicalcolata` | `produzione.eventi` | la botanica disponibile per un piano cambia | edizione, kg disponibili |
| `TiraturaRidotta` | `edizioni.eventi` | Edizioni applica il ricalcolo (600 → 540) | edizione, tiratura prima e dopo, nuove quote |
| `RicalcoloRifiutato` | `edizioni.eventi` | il ricalcolo toglierebbe bottiglie già prenotate | edizione, kg disponibili, motivo |

La catena: `ConsegnaRegistrata` → Produzione → `ResaRicalcolata` → Edizioni →
`TiraturaRidotta`, senza nessuna azione manuale dopo la registrazione della consegna.

### `EdizioneCreata` v1 (edizioni.eventi)

Chiave: l'id dell'edizione. Lo pubblica Edizioni quando Marco crea un'edizione,
nella stessa transazione dell'edizione. Produzione ne fa un piano di produzione.

```json
{
  "eventId": "3a7d0c52-8f1e-4b6a-9c2d-5e8f0a1b3c4d",
  "tipo": "EdizioneCreata",
  "versione": 1,
  "occorsoIl": "2026-06-15T09:00:00Z",
  "payload": {
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "formulazione": { "id": "c0000000-0000-4000-8000-000000000001", "versione": 2 },
    "botanicaDiStagione": "lavanda",
    "grammiPerBottiglia": 20.00,
    "origineBotanica": "Alta Val Varaita (Alpi Cozie)",
    "kgPrevisti": 12.000,
    "tiratura": 600,
    "quote": { "SOCI": 300, "BAR": 150, "ONLINE": 150 }
  }
}
```

| Campo | Significato |
|---|---|
| `formulazione` | la ricetta (id e versione in Ricette) da cui nasce l'edizione |
| `botanicaDiStagione` | minuscola, come in Ricette (`lavanda`) |
| `grammiPerBottiglia` | la dose della botanica di stagione |
| `origineBotanica` | da dove deve venire la botanica: un lotto di un'altra origine non conta per l'edizione |
| `kgPrevisti` | i kg con cui è stata calcolata la tiratura |
| `tiratura`, `quote` | le bottiglie e la loro divisione per canale (Notifiche le userà per gli annunci) |

### `ResaRicalcolata` v1 (produzione.eventi)

Chiave: l'id dell'edizione. Lo pubblica Produzione quando una consegna, o un
lotto integrato da Marco ([docs/grpc.md](grpc.md)), cambia i kg di botanica di
stagione su cui il piano può contare. Edizioni lo userà per ricalcolare la
tiratura (passo 5): 10,8 kg a 20 g = 540 bottiglie.

```json
{
  "eventId": "8c1f4e2a-0d3b-4a5c-9e7f-1a2b3c4d5e6f",
  "tipo": "ResaRicalcolata",
  "versione": 1,
  "occorsoIl": "2026-08-06T08:30:01Z",
  "payload": {
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "pianoId": "90000000-0000-4000-8000-000000000001",
    "botanica": "lavanda",
    "kgPrevisti": 12.000,
    "kgDisponibiliPrima": 12.000,
    "kgDisponibili": 10.800,
    "ordineId": "0b6e1f9a-2c4d-4e8f-9a1b-3c5d7e9f1a2b",
    "lottoId": null,
    "motivo": "consegna ridotta: 10,8 su 12 kg"
  }
}
```

| Campo | Significato |
|---|---|
| `kgDisponibili` | i kg su cui contare adesso: è il dato che serve a Edizioni |
| `kgDisponibiliPrima`, `kgPrevisti` | per i log e per chi legge il topic: da quanto si scende (o si risale), rispetto a cosa era previsto |
| `ordineId` | la consegna che ha cambiato la resa; `null` per un'integrazione |
| `lottoId` | il lotto integrato da Marco che ha cambiato la resa; `null` per una consegna |
| `motivo` | in italiano: `consegna ridotta: 10,8 su 12 kg`, `origine diversa (...)`, `lotto non conforme ...`, `integrazione: 1,2 kg dal lotto ...` |

`lottoId` è arrivato con il passo 4 (gRPC). È un campo facoltativo in più, quindi
l'evento resta alla versione 1: chi legge solo `ordineId` continua a funzionare.
Uno dei due è sempre `null`.

Come Produzione calcola i kg disponibili, consegna dopo consegna:

    disponibili = min(previsti, max(0, previsti − Σ kg ordinati con l'origine richiesta) + Σ kg utilizzabili)

I kg utilizzabili di una consegna sono quelli arrivati, ma solo se il lotto è
`CONFORME` e ha l'origine richiesta dall'edizione (si confrontano le parole,
senza badare a maiuscole e spazi); altrimenti zero. Una consegna di un'altra
botanica si ignora.

Produzione vede un ordine solo quando arriva la sua `ConsegnaRegistrata`. Gli
ordini ancora aperti stanno nei kg previsti non coperti, cioè si aspettano
ancora. Un ordine di un'altra origine non copre i kg previsti, quindi non entra
in Σ ordinati.

| Caso | Conto | Disponibili |
|---|---|---|
| La consegna di Elena: 10,8 su 12 kg | `max(0, 12 − 12) + 10,8` | 10,8 |
| Due ordini da 6 kg, uno consegnato completo | `max(0, 12 − 6) + 6` | 12 |
| 2 kg dai campi (altra origine) prima dell'ordine da 12 | `max(0, 12 − 0) + 0` | 12 |
| Dopo Elena, Marco integra 1,2 kg dell'Alta Val Varaita | `max(0, 12 − 12) + 10,8 + 1,2` | 12 |

I kg di un lotto integrato (riservati in magazzino con `RiservaLotto`) sono
utilizzabili ma non sono un ordine: entrano in Σ utilizzabili, non in Σ
ordinati. Un lotto di un'altra origine non si integra mai: il piano lo rifiuta
prima di riservarlo.

`ResaRicalcolata` parte solo se il valore cambia. Con un'integrazione il valore
può anche **salire** (10,8 → 12): Edizioni ne terrà conto nel passo 5.

### `ConsegnaRegistrata` v1 (magazzino.eventi)

Chiave: la botanica (`lavanda`). Lo pubblica Magazzino quando Elena registra
una consegna, anche ridotta. Ogni ordine ha una sola consegna, quindi un solo
evento per `ordineId`.

```json
{
  "eventId": "20eefcec-a6e1-4a92-a120-bcd2cd1fbfe8",
  "tipo": "ConsegnaRegistrata",
  "versione": 1,
  "occorsoIl": "2026-08-06T08:30:00Z",
  "payload": {
    "ordineId": "0b6e1f9a-2c4d-4e8f-9a1b-3c5d7e9f1a2b",
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "lottoId": "5d2c8e4f-6a1b-4c3d-8e9f-0a1b2c3d4e5f",
    "fornitoreId": "f0000000-0000-4000-8000-000000000001",
    "botanica": "lavanda",
    "origine": "Alta Val Varaita (Alpi Cozie)",
    "kgOrdinati": 12.000,
    "kgArrivati": 10.800,
    "esitoQualita": "CONFORME",
    "consegnataIl": "2026-08-06"
  }
}
```

| Campo | Significato |
|---|---|
| `ordineId` | l'ordine di acquisto, ora CONSEGNATO |
| `edizioneId` | l'edizione per cui era stato fatto l'ordine. Magazzino non la controlla: la collega al piano di produzione chi legge l'evento |
| `lottoId` | il lotto di botanica nato dalla consegna |
| `fornitoreId` | il fornitore |
| `botanica` | nome normalizzato: minuscolo, spazi singoli (`pino mugo`) |
| `origine` | l'origine del fornitore, che diventa quella del lotto |
| `kgOrdinati`, `kgArrivati` | consegna ridotta se `kgArrivati < kgOrdinati`; mai `kgArrivati > kgOrdinati` |
| `esitoQualita` | `CONFORME` o `NON_CONFORME`. Un lotto non conforme resta tracciato ma non si usa per produrre |
| `consegnataIl` | il giorno di arrivo (fuso di Roma) |

## Transactional outbox

Un evento non parte dalla richiesta HTTP: il servizio lo scrive nella sua
tabella `outbox`, **nella stessa transazione** del cambiamento che racconta.
Un publisher la svuota verso Kafka. Così:

- se Kafka è spento, la richiesta va a buon fine lo stesso e l'evento parte
  quando Kafka torna;
- se la transazione fallisce, non resta un evento "orfano" di un cambiamento
  mai salvato;
- l'`eventId` nasce con la riga di outbox: un nuovo tentativo manda lo stesso id.

| Colonna | Significato |
|---|---|
| `numero` | ordine di scrittura: gli eventi partono in questo ordine |
| `event_id` | l'`eventId` della busta (UNIQUE) |
| `topic`, `chiave`, `tipo` | dove va il messaggio, con quale chiave, che evento è |
| `messaggio` | la busta JSON, già pronta: è il valore del messaggio Kafka |
| `creato_il` | quando è stato scritto |
| `pubblicato_il` | quando Kafka ha confermato; `null` = ancora da mandare |
| `tentativi`, `ultimo_errore` | quante volte si è provato, e perché l'ultimo tentativo è fallito |

Il publisher gira ogni secondo e manda gli eventi uno alla volta, aspettando la
conferma di Kafka (`acks=all`, producer idempotente). Al primo errore si ferma:
gli eventi dopo aspettano, così l'ordine non cambia. La garanzia è **almeno una
volta**: i consumer devono scartare gli `eventId` già visti. L'outbox ce
l'hanno Magazzino (`ConsegnaRegistrata`), Edizioni (`EdizioneCreata`) e
Produzione (`ResaRicalcolata`).

## Consumer: idempotenza, retry e dead letter

Produzione legge `edizioni.eventi` e `magazzino.eventi` nel consumer group
`produzione`, con due listener separati (uno per topic) e 3 consumer ciascuno,
uno per partizione.

**Idempotenza.** Ogni messaggio si elabora in **una transazione** del database:

1. legge la busta; se è illeggibile → dead letter;
2. se l'`eventId` è nella tabella `evento_elaborato` → già elaborato, si scarta;
3. applica l'evento (crea il piano, o ricalcola la resa e scrive la riga di
   outbox di `ResaRicalcolata`);
4. scrive l'`eventId` in `evento_elaborato` (chiave primaria).

Tutto nello stesso commit: se c'è la riga in `evento_elaborato`, c'è anche
l'effetto. L'offset si salva **dopo** il commit (`enable-auto-commit: false`,
`ack-mode: record`): se Produzione cade nel mezzo, Kafka ripropone il
messaggio e il passo 2 lo scarta. Seconda difesa, nel dominio: una consegna
dello stesso `ordineId` conta una volta sola; una seconda `EdizioneCreata`
della stessa edizione non crea un altro piano (UNIQUE su `edizione_id`).

| Colonna di `evento_elaborato` | Significato |
|---|---|
| `event_id` | l'`eventId` della busta (chiave primaria) |
| `tipo` | `EdizioneCreata` o `ConsegnaRegistrata` |
| `topic`, `partizione`, `posizione` | da dove è arrivato (posizione = offset) |
| `elaborato_il` | quando |

Gli eventi di tipo sconosciuto si ignorano e non si scrivono nella tabella (su
`edizioni.eventi` arriveranno anche `TiraturaRidotta` e `RicalcoloRifiutato`).

**Retry con backoff.** Un errore che può passare (il piano non c'è ancora
perché `EdizioneCreata` arriva dopo la consegna: i due topic non hanno un
ordine tra loro; il database occupato) si riprova dopo 1, 2, 4 e 8 secondi
(`produzione.consumer.*` in `application.yml`). Mentre si riprova, gli altri
messaggi della stessa partizione aspettano: l'ordine non cambia.

**Dead letter.** Finiti i tentativi, o subito per gli errori che riprovare non
risolve (JSON non valido, campo obbligatorio mancante, `versione` non
supportata: `EventoNonValido`; dati fuori dalle regole del piano:
`PianoNonValido`), il messaggio va in `produzione.dlt`, **nella stessa
partizione**, con questi header:

| Header | Contenuto |
|---|---|
| `kafka_dlt-original-topic`, `-partition`, `-offset` | dove stava il messaggio |
| `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-cause-fqcn` | l'eccezione (es. `...PianoNonTrovato`) |
| `kafka_dlt-exception-message` | il messaggio d'errore, in italiano |

Poi l'offset avanza e i messaggi dopo ripartono: un messaggio sbagliato non
blocca la partizione. Nel log un WARN per ogni tentativo e un ERROR quando
il messaggio va nel dead letter.

Limite noto: `evento_elaborato` cresce con gli eventi. Kafka tiene i messaggi
7 giorni, quindi le righe più vecchie si potrebbero cancellare dopo quel
tempo (non lo fa ancora nessun job).

## Vedere i messaggi

Con Docker Compose:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic magazzino.eventi --from-beginning --formatter-property print.key=true --formatter-property print.partition=true
```

Il consumer group di Produzione (a quale offset è arrivato, per partizione, e
chi legge cosa) e il dead letter topic con gli header:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group produzione
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic produzione.dlt --from-beginning --formatter-property print.headers=true
```

Su Kubernetes gli stessi comandi, con `kubectl exec -it -n ginvault kafka-0 --`
al posto di `docker compose exec kafka`. Il consumer resta in ascolto: Ctrl+C per uscire.
