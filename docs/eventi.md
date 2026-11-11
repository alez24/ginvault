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
| Chi lo raggiunge | i servizi sulla rete `kafka-net`: edizioni, produzione, magazzino, pagamenti, notifiche | i pod con la label `ginvault/usa-kafka: "true"` ([`networkpolicy-kafka.yaml`](../k8s/networkpolicy-kafka.yaml)) |
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
| `edizioni.eventi` | Edizioni | id dell'edizione | `EdizioneCreata`, `TiraturaRidotta`, `TiraturaAumentata`, `RicalcoloRifiutato`, `PrenotazioneConfermata`, `PrenotazioneAnnullata` | `produzione`, `notifiche` |
| `magazzino.eventi` | Magazzino | nome della botanica (`lavanda`) | `ConsegnaRegistrata` | `produzione` |
| `produzione.eventi` | Produzione | id dell'edizione | `ResaRicalcolata` | `edizioni` |
| `produzione.dlt` | Produzione (dead letter) | quella del messaggio originale | i messaggi che Produzione non riesce a elaborare, così come sono arrivati | nessuno: si leggono a mano |
| `edizioni.dlt` | Edizioni (dead letter) | quella del messaggio originale | i messaggi che Edizioni non riesce a elaborare | nessuno: si leggono a mano |
| `pagamenti.comandi` | Edizioni | id della prenotazione | i comandi `RichiestaPagamento`, `RichiestaRimborso` | `pagamenti` |
| `pagamenti.esiti` | Pagamenti | id della prenotazione | `PagamentoRiuscito`, `PagamentoFallito`, `RimborsoEseguito` | `edizioni`, `notifiche` |
| `pagamenti.dlt` | Pagamenti (dead letter) | quella del comando originale | i comandi che Pagamenti non riesce a elaborare | nessuno: si leggono a mano |
| `notifiche.dlt` | Notifiche (dead letter) | quella del messaggio originale | i messaggi che Notifiche non riesce a elaborare | nessuno: si leggono a mano |

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
  d'origine (lo dicono gli header). `edizioni.dlt` è quello di Edizioni,
  `pagamenti.dlt` quello di Pagamenti, `notifiche.dlt` quello di Notifiche. Vedi
  [Consumer](#consumer-idempotenza-retry-e-dead-letter).
- **I comandi hanno il loro topic, e lo possiede chi li RICEVE**:
  `pagamenti.comandi` è di Pagamenti, che ne fissa il formato; ci scrive
  Edizioni. Le risposte vanno su `pagamenti.esiti` invece che su
  `pagamenti.eventi`: lì ci sono solo gli esiti dei comandi. Un evento
  racconta un fatto a chiunque; un comando chiede una cosa a un servizio
  preciso, che risponde con un esito.

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
| `TiraturaRidotta` | `edizioni.eventi` | Edizioni applica il ricalcolo (600 → 540) | edizione, tiratura prima e dopo, nuove quote, causa |
| `TiraturaAumentata` | `edizioni.eventi` | la resa risale (lotto integrato: 540 → 600) | come `TiraturaRidotta` |
| `RicalcoloRifiutato` | `edizioni.eventi` | il ricalcolo toglierebbe bottiglie già prenotate, o i kg non bastano per una bottiglia | edizione, kg richiesti, motivo, spiegazione, quote con le prenotate |

La catena: `ConsegnaRegistrata` → Produzione → `ResaRicalcolata` → Edizioni →
`TiraturaRidotta`, senza nessuna azione manuale dopo la registrazione della
consegna. Con il lotto integrato da Marco: `ResaRicalcolata` (12 kg) →
Edizioni → `TiraturaAumentata`.

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
| `botanicaDiStagione` | minuscola, come in Ricette (`lavanda`). Notifiche la usa per il nome dell'edizione negli avvisi |
| `grammiPerBottiglia` | la dose della botanica di stagione |
| `origineBotanica` | da dove deve venire la botanica: un lotto di un'altra origine non conta per l'edizione |
| `kgPrevisti` | i kg con cui è stata calcolata la tiratura |
| `tiratura`, `quote` | le bottiglie e la loro divisione per canale |

### `ResaRicalcolata` v1 (produzione.eventi)

Chiave: l'id dell'edizione. Lo pubblica Produzione quando una consegna, o un
lotto integrato da Marco ([docs/grpc.md](grpc.md)), cambia i kg di botanica di
stagione su cui il piano può contare. Edizioni lo usa per ricalcolare la
tiratura: 10,8 kg a 20 g = 540 bottiglie (vedi
[`TiraturaRidotta`](#tiraturaridotta-e-tiraturaaumentata-v1-edizionieventi)).

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
può anche **salire** (10,8 → 12): Edizioni allora rimette le bottiglie su
ONLINE e pubblica `TiraturaAumentata`.

### `TiraturaRidotta` e `TiraturaAumentata` v1 (edizioni.eventi)

Chiave: l'id dell'edizione. Li pubblica Edizioni quando la tiratura cambia,
nella stessa transazione dell'edizione. Quasi sempre nascono da una
`ResaRicalcolata`, e allora entrano nello stesso commit della riga di
`evento_elaborato`. Nascono anche dal ricalcolo manuale del distillatore
(`POST /edizioni/{id}/ricalcolo`). Il payload è lo stesso, cambia il `tipo`:

```json
{
  "eventId": "d4e5f6a7-1b2c-4d3e-8f9a-0b1c2d3e4f5a",
  "tipo": "TiraturaRidotta",
  "versione": 1,
  "occorsoIl": "2026-08-06T08:30:02Z",
  "payload": {
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "tiraturaPrima": 600,
    "tiratura": 540,
    "quote": { "SOCI": 300, "BAR": 150, "ONLINE": 90 },
    "kgDisponibili": 10.800,
    "causa": "consegna ridotta: 10,8 su 12 kg",
    "resaRicalcolataId": "8c1f4e2a-0d3b-4a5c-9e7f-1a2b3c4d5e6f"
  }
}
```

| Campo | Significato |
|---|---|
| `tiraturaPrima`, `tiratura` | le bottiglie prima e dopo |
| `quote` | le quote **nuove** per canale. Si scende togliendo da ONLINE, poi da BAR, mai da SOCI e mai sotto le prenotate. Si risale con le bottiglie in più tutte su ONLINE, mai oltre 600 |
| `kgDisponibili` | i kg su cui è calcolata la tiratura nuova |
| `causa` | in italiano: il `motivo` della `ResaRicalcolata`, oppure `ricalcolo manuale del distillatore` |
| `resaRicalcolataId` | l'`eventId` della `ResaRicalcolata` che l'ha causato; `null` per un ricalcolo manuale |

Con il lotto integrato da Marco la resa torna a 12 kg: `TiraturaAumentata` con
`tiraturaPrima` 540, `tiratura` 600 e ONLINE di nuovo a 150. Con 15 kg la
tiratura resta 600: è il tetto di ogni edizione.

Notifiche legge `TiraturaRidotta` e `TiraturaAumentata` per avvisare chi ha
bottiglie confermate dell'edizione (vedi
[Il consumer di Notifiche](#il-consumer-di-notifiche)).

### `RicalcoloRifiutato` v1 (edizioni.eventi)

Chiave: l'id dell'edizione. Lo pubblica Edizioni quando una `ResaRicalcolata`
non si può applicare. L'edizione resta com'era, e il rifiuto non si perde:
parte come evento, con l'`eventId` della resa, e il messaggio di Produzione
risulta elaborato (non va nel dead letter).

```json
{
  "eventId": "0f9e8d7c-6b5a-4c3d-9e2f-1a0b9c8d7e6f",
  "tipo": "RicalcoloRifiutato",
  "versione": 1,
  "occorsoIl": "2026-08-06T08:30:02Z",
  "payload": {
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "motivo": "QUOTE_NON_RIDUCIBILI",
    "spiegazione": "Con 10.8 kg di lavanda la tiratura scenderebbe da 600 a 540 bottiglie: ne vanno tolte 60, ma ONLINE e BAR ne hanno solo 50 non prenotate (ONLINE 100 prenotate su 150, BAR 150 su 150): nessuna quota può scendere sotto le bottiglie già prenotate. L'edizione non è stata modificata.",
    "tiratura": 600,
    "tiraturaRichiesta": 540,
    "kgDisponibili": 12.000,
    "kgRichiesti": 10.800,
    "quote": {
      "SOCI":   { "bottiglie": 300, "prenotate": 0 },
      "BAR":    { "bottiglie": 150, "prenotate": 150 },
      "ONLINE": { "bottiglie": 150, "prenotate": 100 }
    },
    "causa": "consegna ridotta: 10,8 su 12 kg",
    "resaRicalcolataId": "8c1f4e2a-0d3b-4a5c-9e7f-1a2b3c4d5e6f"
  }
}
```

| Campo | Significato |
|---|---|
| `motivo` | `QUOTE_NON_RIDUCIBILI`: bisognerebbe togliere da SOCI o scendere sotto le prenotate. `BOTANICA_INSUFFICIENTE`: con quei kg non si riempie nemmeno una bottiglia (es. 0 kg utilizzabili) |
| `spiegazione` | in italiano, la stessa del 409 del ricalcolo manuale |
| `tiratura`, `kgDisponibili` | quelli dell'edizione, rimasti com'erano |
| `tiraturaRichiesta`, `kgRichiesti` | quelli della resa rifiutata (0 bottiglie per `BOTANICA_INSUFFICIENTE`) |
| `quote` | le quote attuali con le prenotate: il perché del rifiuto |
| `causa`, `resaRicalcolataId` | come in `TiraturaRidotta`: la resa rifiutata |

Il ricalcolo manuale rifiutato è un 409 per Marco, non un `RicalcoloRifiutato`.
Oggi l'evento non lo legge nessuno, e l'edizione resta indietro finché una
resa nuova non si può applicare. Lo leggerà Notifiche, per avvisare il
distillatore.

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

## I messaggi della storia di Giulia

Giulia, socia, prenota 2 bottiglie dell'edizione d'estate e paga con una carta
di prova scelta da un menu. Il pagamento non parte dalla richiesta HTTP:
Edizioni manda un **comando** a Pagamenti e aspetta l'**esito** (la SAGA,
vedi [sotto](#la-saga-in-edizioni)). Pagamenti addebita con Stripe in
modalità test, dal server.

| Messaggio | Topic | Chi lo scrive | Quando |
|---|---|---|---|
| `RichiestaPagamento` (comando) | `pagamenti.comandi` | Edizioni | Giulia sceglie la carta e chiede di pagare (`POST /prenotazioni/{id}/pagamento`) |
| `RichiestaRimborso` (comando) | `pagamenti.comandi` | Edizioni | un pagamento riuscito è arrivato quando la prenotazione era già annullata (scaduta o rifiutata) |
| `PagamentoRiuscito` | `pagamenti.esiti` | Pagamenti | Stripe ha addebitato l'importo |
| `PagamentoFallito` | `pagamenti.esiti` | Pagamenti | la carta è rifiutata, chiede il 3D Secure o Stripe non accetta la richiesta |
| `RimborsoEseguito` | `pagamenti.esiti` | Pagamenti | Stripe ha restituito l'addebito |
| `PrenotazioneConfermata` | `edizioni.eventi` | Edizioni | dopo `PagamentoRiuscito`: la prenotazione ha i suoi numeri di bottiglia (Giulia: 112 e 113) |
| `PrenotazioneAnnullata` | `edizioni.eventi` | Edizioni | dopo `PagamentoFallito`, o a tempo finito: le bottiglie tornano libere |

La busta è la stessa degli eventi, anche per i comandi: `eventId` è l'id del
messaggio, `tipo` il nome del comando (`Richiesta...`) o dell'evento (al
passato). Chiave di tutti e due i topic: **l'id della prenotazione**, così i
messaggi della stessa prenotazione restano in ordine (il rimborso dopo il suo
pagamento). Gli importi sono interi, **in centesimi**, come li vuole Stripe:
2 bottiglie a 45,00 euro = `9000`.

### `RichiestaPagamento` v1 (pagamenti.comandi)

```json
{
  "eventId": "7d1f0c2e-3b4a-4c5d-8e6f-9a0b1c2d3e4f",
  "tipo": "RichiestaPagamento",
  "versione": 1,
  "occorsoIl": "2026-08-01T09:05:00Z",
  "payload": {
    "prenotazioneId": "a0000000-0000-4000-8000-000000000001",
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "acquirente": "github:1234567",
    "importoCentesimi": 9000,
    "valuta": "eur",
    "metodo": "pm_card_visa",
    "descrizione": "GinVault: 2 bottiglie dell'edizione d'estate"
  }
}
```

| Campo | Significato |
|---|---|
| `acquirente` | il `sub` di chi ha prenotato. Resta in GinVault: a Stripe vanno solo `prenotazioneId` ed `edizioneId` (metadata) |
| `importoCentesimi` | intero maggiore di zero: `9000.5` è un comando non valido |
| `valuta` | codice ISO di 3 lettere, minuscolo (`eur`) |
| `metodo` | il metodo di pagamento di Stripe (`pm_...`): `pm_card_visa` riesce, `pm_card_chargeDeclined` è rifiutata |
| `descrizione` | facoltativa, al più 200 caratteri: compare nella dashboard di Stripe |

### `RichiestaRimborso` v1 (pagamenti.comandi)

```json
{
  "eventId": "8e2a1d3f-4c5b-4d6e-9f70-a1b2c3d4e5f6",
  "tipo": "RichiestaRimborso",
  "versione": 1,
  "occorsoIl": "2026-08-01T09:21:00Z",
  "payload": {
    "prenotazioneId": "a0000000-0000-4000-8000-000000000001",
    "motivo": "pagamento arrivato a prenotazione scaduta"
  }
}
```

Si rimborsa sempre l'intero addebito della prenotazione; `motivo` è
facoltativo.

### `PagamentoRiuscito` v1 (pagamenti.esiti)

```json
{
  "eventId": "9f3b2e40-5d6c-4e7f-a081-b2c3d4e5f607",
  "tipo": "PagamentoRiuscito",
  "versione": 1,
  "occorsoIl": "2026-08-01T09:05:02Z",
  "payload": {
    "prenotazioneId": "a0000000-0000-4000-8000-000000000001",
    "pagamentoId": "c0000000-0000-4000-8000-000000000001",
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "acquirente": "github:1234567",
    "importoCentesimi": 9000,
    "valuta": "eur",
    "riferimentoStripe": "pi_3QxYz..."
  }
}
```

`riferimentoStripe` è l'id del PaymentIntent: lo si cerca nella dashboard di
Stripe. Edizioni conferma la prenotazione e assegna i numeri di bottiglia.

### `PagamentoFallito` v1 (pagamenti.esiti)

Solo il `payload` (la busta è come sopra):

```json
{
  "prenotazioneId": "a0000000-0000-4000-8000-000000000002",
  "pagamentoId": "c0000000-0000-4000-8000-000000000002",
  "edizioneId": "e0000000-0000-4000-8000-000000000001",
  "acquirente": "github:1234567",
  "motivo": "CARTA_RIFIUTATA",
  "codice": "generic_decline",
  "spiegazione": "La banca ha rifiutato la carta."
}
```

| Campo | Significato |
|---|---|
| `motivo` | `CARTA_RIFIUTATA` (402 di Stripe), `AUTENTICAZIONE_RICHIESTA` (la carta chiede il 3D Secure, che un pagamento dal server non può fare), `RICHIESTA_NON_VALIDA` (per esempio un `metodo` che non esiste) |
| `codice` | il codice di Stripe: il `decline_code` per una carta rifiutata, se no il `code` |
| `spiegazione` | in italiano, per l'avviso a chi ha prenotato |

Edizioni annulla la prenotazione e libera le bottiglie. Uno Stripe che non
risponde **non** è un `PagamentoFallito`: il comando si riprova e, finiti i
tentativi, va in `pagamenti.dlt` (vedi sotto).

### `RimborsoEseguito` v1 (pagamenti.esiti)

Solo il `payload`:

```json
{
  "prenotazioneId": "a0000000-0000-4000-8000-000000000001",
  "pagamentoId": "c0000000-0000-4000-8000-000000000001",
  "edizioneId": "e0000000-0000-4000-8000-000000000001",
  "acquirente": "github:1234567",
  "importoCentesimi": 9000,
  "valuta": "eur",
  "riferimentoStripe": "re_3QxYz...",
  "motivo": "pagamento arrivato a prenotazione scaduta"
}
```

Qui `riferimentoStripe` è l'id del rimborso (`re_...`).

**Un pagamento per prenotazione.** Una seconda `RichiestaPagamento` per la
stessa prenotazione (con un altro `eventId`) non richiama Stripe: Pagamenti
ripubblica l'esito che ha già, anche se il secondo comando ha un'altra carta.
Allo stesso modo una seconda `RichiestaRimborso` ripubblica `RimborsoEseguito`.

### `PrenotazioneConfermata` v1 (edizioni.eventi)

Chiave: l'id dell'edizione, come gli altri eventi di Edizioni. Notifiche lo
usa per dire a Giulia i suoi numeri, e li ricorda per gli avvisi della
tiratura.

```json
{
  "eventId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "tipo": "PrenotazioneConfermata",
  "versione": 1,
  "occorsoIl": "2026-08-01T09:05:03Z",
  "payload": {
    "prenotazioneId": "a0000000-0000-4000-8000-000000000001",
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "acquirente": "github:1234567",
    "canale": "SOCI",
    "bottiglie": 2,
    "numeriBottiglia": [112, 113],
    "totale": 90.00,
    "pagamentoId": "c0000000-0000-4000-8000-000000000001"
  }
}
```

| Campo | Significato |
|---|---|
| `numeriBottiglia` | progressivi nell'edizione, in ordine, senza buchi né doppioni: la prima prenotazione confermata ha 1, 2...; un'annullata non ne prende |
| `totale` | in euro, come il prezzo della prenotazione (non in centesimi: è un fatto di Edizioni, non un comando per Stripe) |
| `pagamentoId` | il pagamento in Pagamenti |

### `PrenotazioneAnnullata` v1 (edizioni.eventi)

Solo il `payload`:

```json
{
  "prenotazioneId": "a0000000-0000-4000-8000-000000000002",
  "edizioneId": "e0000000-0000-4000-8000-000000000001",
  "acquirente": "github:1234567",
  "canale": "SOCI",
  "bottiglie": 2,
  "causa": "PAGAMENTO_RIFIUTATO",
  "spiegazione": "La banca ha rifiutato la carta."
}
```

| Campo | Significato |
|---|---|
| `causa` | `PAGAMENTO_RIFIUTATO` (dopo `PagamentoFallito`) o `SCADUTA` (il tempo per pagare è finito) |
| `spiegazione` | in italiano, per chi ha prenotato: quella di Pagamenti, o "Il tempo per pagare è finito il 1 agosto 2026 alle 11:20: le bottiglie sono tornate disponibili." |
| `bottiglie` | quante ne sono tornate libere nella quota del `canale` |

### La SAGA in Edizioni

Una SAGA **orchestrata**: Edizioni decide i passi e manda i comandi, Pagamenti
li esegue. Lo stato della SAGA è la prenotazione stessa (`stato`, più le date
di pagamento, annullamento e rimborso).

```
POST /prenotazioni/{id}/pagamento ──► RichiestaPagamento ──► Pagamenti ──► Stripe
         (202, IN_ATTESA_PAGAMENTO)                               │
                                                                  ▼
  PagamentoRiuscito ──► CONFERMATA, numeri di bottiglia ──► PrenotazioneConfermata
  PagamentoFallito ───► ANNULLATA, bottiglie libere ──────► PrenotazioneAnnullata   (compensazione)
  tempo finito ───────► ANNULLATA, bottiglie libere ──────► PrenotazioneAnnullata   (timeout)
  PagamentoRiuscito a prenotazione annullata ──► RichiestaRimborso ──► RimborsoEseguito
```

- **Compensazione.** La prenotazione toglie subito le bottiglie dalla quota
  (passo 1). Se il pagamento non va, l'annullamento le rimette: la quota
  torna come prima e un altro socio le può prenotare.
- **Timeout.** Ogni 30 secondi (`edizioni.vendita.controllo-scadenze-ms`)
  `ScadenzaPrenotazioni` annulla le prenotazioni in attesa con il tempo finito
  (15 minuti). Così le bottiglie non restano bloccate se Pagamenti è fermo o
  se nessuno paga.
- **Il pagamento arrivato tardi.** Se Pagamenti era fermo, l'addebito può
  arrivare dopo la scadenza. Le bottiglie non ci sono più (forse le ha già un
  altro socio): la prenotazione resta annullata e Edizioni chiede il rimborso
  (`RichiestaRimborso`), una volta sola. Se invece la scadenza non è ancora
  passata, il pagamento conferma: le bottiglie erano ancora tenute.
- **Un pagamento solo.** `POST .../pagamento` una seconda volta → 409
  `PAGAMENTO_GIA_RICHIESTO`; con il tempo finito → 409 `PRENOTAZIONE_SCADUTA`;
  confermata o annullata → 409 `PRENOTAZIONE_CHIUSA`. Le carte sono quelle del
  menu (`edizioni.vendita.metodi-di-pagamento`, in `GET /regole-di-vendita`):
  un'altra → 400.
- **Esiti ripetuti.** Lo stesso `eventId` si scarta (`evento_elaborato`); un
  esito nuovo per una prenotazione già confermata o annullata non cambia
  niente (`GIA_REGISTRATO`).
- **I numeri di bottiglia.** L'edizione tiene il conto delle bottiglie
  numerate (`bottiglie_numerate`): una conferma prende i numeri successivi.
  Ogni passo della SAGA blocca prima la riga dell'edizione
  (`SELECT ... FOR NO KEY UPDATE`, lo stesso blocco delle prenotazioni) e solo
  dopo legge la prenotazione: due conferme nello stesso istante si mettono in
  fila, e la scadenza e un pagamento non vedono mai la stessa prenotazione "in
  attesa". Rete di sicurezza: `UNIQUE (edizione_id, numero)` nella tabella
  `bottiglia_numerata`. Il test di concorrenza: 20 conferme insieme prendono i
  numeri da 1 a 40, due vicini per prenotazione.

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
l'hanno Magazzino (`ConsegnaRegistrata`), Edizioni (`EdizioneCreata`,
`TiraturaRidotta`, `TiraturaAumentata`, `RicalcoloRifiutato`,
`PrenotazioneConfermata`, `PrenotazioneAnnullata` e i comandi
`RichiestaPagamento`, `RichiestaRimborso`), Produzione (`ResaRicalcolata`) e
Pagamenti (`PagamentoRiuscito`, `PagamentoFallito`, `RimborsoEseguito`). Anche
i comandi passano dall'outbox: se Kafka è giù il pagamento parte quando torna,
e non parte mai per una prenotazione che non è stata salvata.

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

Gli eventi di tipo sconosciuto si ignorano e non si scrivono nella tabella: su
`edizioni.eventi` ci sono anche `TiraturaRidotta`, `TiraturaAumentata` e
`RicalcoloRifiutato`, che a Produzione non servono.

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

### Il consumer di Edizioni

Edizioni legge `produzione.eventi` e `pagamenti.esiti` nel consumer group
`edizioni`, con due listener (`edizioni-produzione`, `edizioni-pagamenti`) e 3
consumer ciascuno, uno per partizione. Le stesse regole di Produzione, nelle
stesse classi (`ElaborazioneEventi`, `ConfigurazioneKafka`, `LettoreEventi`):

- **idempotenza:** `ResaRicalcolata` si applica nella transazione che scrive
  la riga di outbox e quella di `evento_elaborato`. Lo stesso `eventId`
  rimandato si scarta. Serve davvero: la resa di Elena (10,8 kg) rimandata
  dopo quella dell'integrazione (12 kg) riporterebbe l'edizione a 540. In
  `evento_elaborato` di Edizioni c'è anche l'`esito`: `RIDOTTA`,
  `AUMENTATA`, `INVARIATA` o `RIFIUTATO` per le rese, `CONFERMATA`,
  `ANNULLATA`, `RIMBORSO_RICHIESTO`, `RIMBORSATA` o `GIA_REGISTRATO` per gli
  esiti di Pagamenti;
- **retry:** 1, 2, 4, 8 secondi (`edizioni.consumer.*`), per esempio con il
  database occupato o con un ricalcolo manuale sulla stessa edizione nello
  stesso istante (`@Version`);
- **dead letter** `edizioni.dlt`, nella stessa partizione, con gli stessi
  header. Ci va subito un messaggio illeggibile (`EventoNonValido`, anche per
  kg negativi o con più di 3 decimali) o una resa per un'edizione che non
  esiste (`EdizioneNonTrovata`). Una resa arriva sempre dopo la sua edizione,
  quindi riprovare non serve. Lo stesso per un esito di Pagamenti con una
  prenotazione che non esiste (`PrenotazioneNonTrovata`): il comando parte
  nello stesso commit della prenotazione;
- **un ricalcolo rifiutato non è un errore:** il messaggio è elaborato, l'offset
  avanza e parte `RicalcoloRifiutato`, con un WARN nel log.

### Il consumer di Pagamenti

Pagamenti legge `pagamenti.comandi` nel consumer group `pagamenti`, con 3
consumer, uno per partizione (`ElaborazioneComandi`, `ConfigurazioneKafka`,
`LettoreComandi`). La differenza con gli altri: in mezzo c'è una chiamata a
Stripe, che **non** sta nella transazione del database (terrebbe una
connessione occupata per secondi, e un rollback non annullerebbe un addebito).

1. legge la busta; se è illeggibile, o il `tipo` è sconosciuto → dead letter
   (su un topic di comandi arrivano solo comandi per Pagamenti);
2. se l'`eventId` è in `comando_elaborato` → già elaborato, si scarta senza
   chiamare Stripe;
3. chiama Stripe con la **chiave di idempotenza** della prenotazione
   (`Idempotency-Key: ginvault-pagamento-<prenotazione>`, per il rimborso
   `ginvault-rimborso-<prenotazione>`), solo se la prenotazione non ha già un
   pagamento;
4. in una transazione: il pagamento, la riga di outbox con l'esito e
   l'`eventId` in `comando_elaborato`.

Se il punto 4 fallisce, Kafka ripropone il comando e il punto 3 si ripete:
Stripe riconosce la chiave e restituisce la risposta di prima, senza un
secondo addebito. Terza difesa, nel database: UNIQUE su `prenotazione_id` di
`pagamento`.

- **retry:** 1, 2, 4, 8 secondi (`pagamenti.consumer.*`) quando Stripe non
  dà un esito: rete giù, timeout (2 s per connettersi, 10 s per la risposta),
  errori 5xx, 429, chiave mancante o sbagliata. Ogni tentativo usa la stessa
  chiave di idempotenza;
- **dead letter** `pagamenti.dlt`, con gli stessi header: ci va subito un
  comando illeggibile (`ComandoNonValido`) o un rimborso impossibile
  (`RimborsoNonPossibile`: il pagamento era stato rifiutato, o Stripe non
  accetta il rimborso); dopo i tentativi, un comando con Stripe sempre giù
  (`ProviderNonDisponibile`);
- **una carta rifiutata non è un errore:** il comando è elaborato, l'offset
  avanza e parte `PagamentoFallito`.

### Il consumer di Notifiche

Notifiche (NestJS, con kafkajs) legge `edizioni.eventi` e `pagamenti.esiti`
nel consumer group `notifiche`: un consumer per istanza, che elabora insieme le
3 partizioni di ogni topic e i messaggi di una partizione uno alla volta. Le
regole sono quelle di Produzione, scritte in TypeScript (`ConsumerEventi`,
`ElaborazioneEventi`, `lettore-eventi.ts`):

- **idempotenza:** un messaggio, una transazione. L'`eventId` va in
  `evento_elaborato` per primo, insieme ad `avvisi` (quante notifiche ha
  creato): se c'è già, il messaggio si scarta. Seconda difesa:
  `UNIQUE (evento_id, destinatario)` su `notifica`. L'offset si salva dopo il
  commit;
- **cosa legge:**

  | Evento | Cosa fa Notifiche |
  |---|---|
  | `EdizioneCreata` | salva l'edizione con la sua botanica (tabella `edizione`): serve al nome negli avvisi, "edizione con lavanda" |
  | `PrenotazioneConfermata` | salva i numeri di bottiglia (tabella `prenotazione_confermata`) e avvisa chi ha prenotato |
  | `PrenotazioneAnnullata` | avvisa chi aveva prenotato, con la `spiegazione` di Edizioni |
  | `TiraturaRidotta`, `TiraturaAumentata` | un avviso per ogni acquirente con bottiglie confermate dell'edizione, con tutti i suoi numeri |
  | `RimborsoEseguito` | avvisa chi è stato rimborsato, con l'importo |

  Gli altri tipi (`RicalcoloRifiutato`, `PagamentoRiuscito`,
  `PagamentoFallito`) si ignorano e non si scrivono in `evento_elaborato`. La
  proiezione (`edizione`, `prenotazione_confermata`) evita di chiamare
  Edizioni;
- **ordine:** le conferme e le tirature di un'edizione hanno la stessa chiave,
  quindi stanno nella stessa partizione. L'avviso di `TiraturaRidotta` va a chi
  aveva già confermato, non a chi conferma dopo. `RimborsoEseguito` arriva da
  un altro topic: se l'edizione non è ancora nella proiezione, l'avviso dice
  solo "edizione";
- **retry:** 1, 2, 4, 8 secondi (`NOTIFICHE_TENTATIVI_MS`). Durante l'attesa il
  consumer manda l'heartbeat, così resta nel gruppo;
- **dead letter** `notifiche.dlt`, nella stessa partizione, con gli header di
  Spring. Differenza: `kafka_dlt-original-partition` e `-original-offset` sono
  testo, mentre Spring li scrive come interi binari. Ci va subito un
  `EventoNonValido`: JSON rotto, campo mancante, `versione` diversa da 1,
  numeri di bottiglia diversi dalle bottiglie. Gli altri errori ci vanno dopo i
  tentativi, con la classe dell'errore in `kafka_dlt-exception-fqcn` (per
  esempio `DatabaseError`);
- **Kafka spento**, all'avvio o dopo: REST e WebSocket funzionano, `/health`
  risponde `{"status":"UP","database":"UP","kafka":"NON_COLLEGATO"}` e il
  consumer si ricollega da solo.

Limite noto: chi ha una prenotazione ancora da pagare non riceve l'avviso della
tiratura, perché Edizioni non pubblica un evento quando nasce una prenotazione.

### Gli avvisi in diretta

Il WebSocket di Notifiche. Dal browser è `ws://localhost/api/notifiche/avvisi`:
il gateway toglie `/api/notifiche` e Notifiche ascolta su `/avvisi`. Parla
solo il server; quello che manda il client si ignora. Ogni messaggio è un JSON
con un `tipo`:

| `tipo` | Quando | Contenuto |
|---|---|---|
| `connesso` | appena aperto | `nonLette`: quante notifiche non lette ha l'utente |
| `notifica` | una notifica nuova per l'utente | `notifica`, come in `GET /notifiche` |
| `ping` | ogni 25 secondi | niente: tiene viva la connessione (nginx chiude dopo 60 secondi di silenzio) |

```json
{
  "tipo": "notifica",
  "notifica": {
    "id": "5b1e7c0a-2f3d-4e5a-9b6c-7d8e9f0a1b2c",
    "tipo": "TIRATURA_RIDOTTA",
    "titolo": "La tiratura scende a 540 bottiglie",
    "testo": "L'edizione con lavanda passa da 600 a 540 bottiglie (consegna ridotta: 10,8 su 12 kg). Le tue bottiglie, la 112 e la 113, restano tue.",
    "edizioneId": "e0000000-0000-4000-8000-000000000001",
    "prenotazioneId": null,
    "dati": { "tiraturaPrima": 600, "tiratura": 540, "numeriBottiglia": [112, 113], "causa": "consegna ridotta: 10,8 su 12 kg" },
    "creataIl": "2026-08-06T08:30:02.000Z",
    "lettaIl": null
  }
}
```

Il `tipo` della notifica è `PRENOTAZIONE_CONFERMATA`, `PRENOTAZIONE_ANNULLATA`,
`TIRATURA_RIDOTTA`, `TIRATURA_AUMENTATA` o `RIMBORSO_ESEGUITO`. `creataIl` è
l'`occorsoIl` dell'evento.

- **Autenticazione:** il browser apre il WebSocket con il cookie di sessione e
  il gateway aggiunge il JWT (`Authorization: Bearer`), come per ogni
  richiesta. Notifiche lo verifica all'handshake. I codici di chiusura:
  - `4401`: JWT mancante o non valido;
  - `4001`: il JWT è scaduto (dura 5 minuti). Il client si ricollega e il
    gateway gliene dà uno nuovo; così un logout vale anche per i WebSocket già
    aperti;
  - `1001`: Notifiche si ferma, e al nuovo collegamento risponde un'altra
    replica.
- **Più repliche:** il messaggio di Kafka lo elabora la replica che ha la
  partizione, ma il WebSocket può essere aperto su un'altra. Ogni notifica
  salvata fa `NOTIFY notifiche_nuove` con `{id, destinatario}` nella stessa
  transazione, e il messaggio parte al commit. Ogni replica è in `LISTEN` su una
  connessione sua e consegna la notifica ai WebSocket che ha lei.
- **Il client:** a ogni `connesso` rilegge `GET /api/notifiche/notifiche`, per
  le notifiche arrivate mentre era scollegato, e scarta una `notifica` con un
  `id` che ha già.

REST, con le sole notifiche di chi chiede:

| Richiesta | Risposta |
|---|---|
| `GET /api/notifiche/notifiche` | `{"nonLette": 2, "notifiche": [...]}`: le ultime 50, dalla più recente |
| `POST /api/notifiche/notifiche/{id}/letta` | 204; 404 se non c'è o è di un altro; 400 se l'id non è un UUID |
| `POST /api/notifiche/notifiche/lette` | 204: tutte lette |

Limite noto: `evento_elaborato` (anche quella di Notifiche) e
`comando_elaborato` di Pagamenti crescono con i messaggi. Kafka li tiene 7 giorni, quindi le righe più vecchie si
potrebbero cancellare dopo quel tempo (non lo fa ancora nessun job).

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

Lo stesso per Edizioni: il gruppo `edizioni` su `produzione.eventi` e
`pagamenti.esiti`, i suoi eventi su `edizioni.eventi`, i comandi per
Pagamenti e il suo dead letter topic:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group edizioni
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic pagamenti.comandi --from-beginning --formatter-property print.key=true
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic edizioni.eventi --from-beginning --formatter-property print.key=true
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic edizioni.dlt --from-beginning --formatter-property print.headers=true
```

E per Pagamenti: il gruppo `pagamenti` su `pagamenti.comandi`, gli esiti e il
dead letter topic:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group pagamenti
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic pagamenti.esiti --from-beginning --formatter-property print.key=true
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic pagamenti.dlt --from-beginning --formatter-property print.headers=true
```

E per Notifiche: il gruppo `notifiche` su `edizioni.eventi` e
`pagamenti.esiti`, e il dead letter topic:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notifiche
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic notifiche.dlt --from-beginning --formatter-property print.headers=true
```

Su Kubernetes gli stessi comandi, con `kubectl exec -it -n ginvault kafka-0 --`
al posto di `docker compose exec kafka`. Il consumer resta in ascolto: Ctrl+C per uscire.
