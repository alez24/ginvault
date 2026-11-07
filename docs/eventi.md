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
- I topic delle lettere scartate (dead letter) arrivano con i consumer, al passo 3.

## Formato dei messaggi

Chiave e valore sono testo UTF-8. Il valore è un JSON con una **busta** uguale
per tutti gli eventi e il contenuto specifico in `payload`:

```json
{
  "eventId": "6f1c2e8a-3b0d-4c55-9a7e-2d4f1b9c0e17",
  "tipo": "ConsegnaRegistrata",
  "versione": 1,
  "occorsoIl": "2026-07-18T08:30:00Z",
  "payload": {
    "botanica": "lavanda",
    "kgOrdinati": 12,
    "kgArrivati": 10.8
  }
}
```

| Campo | Significato |
|---|---|
| `eventId` | UUID creato insieme all'evento, nella stessa transazione della riga di outbox. Se l'evento viene pubblicato due volte (un nuovo tentativo dopo un errore), l'id è lo stesso: il consumer lo riconosce e non lo elabora di nuovo. |
| `tipo` | Il nome dell'evento di dominio, al passato: `ConsegnaRegistrata`. Il consumer sceglie cosa fare in base a questo campo. |
| `versione` | La versione del formato di `payload` per quel `tipo`, da 1. |
| `occorsoIl` | Quando il fatto è successo nel dominio, in UTC (ISO-8601 con `Z`). Non è l'ora di pubblicazione: con l'outbox un evento può partire più tardi, per esempio quando Kafka torna disponibile. |
| `payload` | I dati dell'evento. Importi in kg come numeri decimali, id come stringhe. |

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

## Vedere i messaggi

Con Docker Compose:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic magazzino.eventi --from-beginning --formatter-property print.key=true --formatter-property print.partition=true
```

Su Kubernetes gli stessi comandi, con `kubectl exec -it -n ginvault kafka-0 --`
al posto di `docker compose exec kafka`. Il consumer resta in ascolto: Ctrl+C per uscire.
