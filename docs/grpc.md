# gRPC tra Produzione e Magazzino

Quasi tutto in GinVault viaggia con gli eventi Kafka ([eventi.md](eventi.md)):
consegne, rese, tirature. C'è un punto solo dove serve una risposta **subito**.
Dopo la consegna scarsa, Elena propone a Marco un lotto per coprire i kg
mancanti. Marco prova a integrarlo nel piano e deve sapere adesso se il
lotto va bene e se i kg sono suoi. Lì Produzione chiama Magazzino in modo
sincrono, con gRPC.

È una relazione di **partnership** (DDD, context mapping): i due contesti
condividono un contratto e lo cambiano insieme. Il contratto è il modulo
[`giacenze-api`](../giacenze-api).

## Il contratto: servizio `Giacenze`

[`giacenze-api/src/main/proto/giacenze.proto`](../giacenze-api/src/main/proto/giacenze.proto),
package `ginvault.giacenze.v1`:

| Metodo | Cosa fa | Ruoli |
|---|---|---|
| `Disponibilita(botanica)` | i lotti utilizzabili della botanica (conformi, non scaduti) con origine e kg liberi | DISTILLATORE, RESP_ACQUISTI, ADMIN |
| `RiservaLotto(lotto_id, piano_id, kg)` | mette da parte kg di un lotto per un piano; **idempotente** su (lotto, piano) | DISTILLATORE, ADMIN |
| `AnnullaRiserva(lotto_id, piano_id)` | toglie la riserva del piano e libera i kg: la **compensazione** di Produzione; **idempotente** (senza riserva risponde OK con `nessuna_riserva`) | DISTILLATORE, ADMIN |

Regole del contratto:

- i kg sono **stringhe decimali** con il punto (`"1.200"`). Né `float` né
  `double`, che non sanno scrivere 10,8 esatto;
- gli id sono UUID testuali, le date `yyyy-MM-dd`;
- ogni chiamata porta il JWT dell'utente nel metadata `authorization`;
- i numeri dei campi non si riusano: un campo tolto diventa `reserved`, uno
  nuovo è facoltativo per definizione (proto3), quindi i client vecchi
  continuano a funzionare.

Il modulo è una libreria Maven: il `protobuf-maven-plugin` compila il `.proto`
con `protoc` e genera i messaggi, lo stub client e la classe base del server.
Magazzino e Produzione dipendono dal jar `it.ginvault:giacenze-api:0.2.0`.

## Il caso d'uso: `POST /piani/{id}/integrazioni`

Marco (DISTILLATORE) manda `{"lottoId": "...", "kg": 1.2}`. Produzione
(`ServizioIntegrazioni`) fa così:

```
Marco ──POST──▶ Produzione                                   Magazzino
                 1. legge il piano (transazione breve)
                 2. Disponibilita("lavanda") ───── gRPC, 2 s ──▶ lotti utilizzabili
                 3. regola di dominio (PianoDiProduzione.verificaProposta):
                    origine diversa? ──▶ 409, e il magazzino NON riserva niente
                 4. RiservaLotto(lotto, piano, 1.200) ── gRPC, 2 s ──▶ riserva (idempotente)
                 5. il piano integra il lotto e ricalcola la resa:
                    piano + riga di outbox di ResaRicalcolata nello stesso commit
                    il commit fallisce? ──▶ AnnullaRiserva(lotto, piano) ── gRPC, 2 s ──▶ kg di nuovo liberi
         ◀── 201 {esito, piano}
```

- **La regola viene prima della riserva.** Il lotto dei campi (origine «Campi
  GinVault (Saluzzo, 400 m)») viene rifiutato senza essere riservato, quindi
  non c'è niente da annullare.
- **Le chiamate gRPC stanno fuori dalle transazioni.** Con il magazzino lento,
  una connessione al database resterebbe occupata fino alla deadline.
- **L'origine non è una regola del magazzino.** Il magazzino riserva un lotto
  dei campi a chiunque. È Produzione a sapere da dove deve venire la
  botanica dell'edizione.
- **Lo stesso lotto con gli stessi kg rimandato** (Marco preme due volte)
  risponde 200 `GIA_INTEGRATO`, senza chiamare il magazzino.

Il rifiuto della storia:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "title": "Integrazione rifiutata",
  "status": 409,
  "detail": "origine diversa: l'edizione deve restare fedele alla sua origine. Il piano chiede lavanda da «Alta Val Varaita (Alpi Cozie)», il lotto viene da «Campi GinVault (Saluzzo, 400 m)»",
  "motivo": "ORIGINE_DIVERSA",
  "origineRichiesta": "Alta Val Varaita (Alpi Cozie)",
  "origineLotto": "Campi GinVault (Saluzzo, 400 m)"
}
```

Altri motivi del piano (sempre 409): `LOTTO_NON_DISPONIBILE` (non è tra i
lotti utilizzabili della botanica), `ALTRA_BOTANICA`, `LOTTO_GIA_INTEGRATO`,
`PIANO_COMPLETO` (non mancano kg), `KG_ECCESSIVI` (più di quelli che mancano),
`KG_NON_VALIDI`.

## La compensazione: `AnnullaRiserva`

Il passo 5 può fallire dopo che il magazzino ha già riservato: il database di
Produzione è giù, oppure il piano è stato cambiato nello stesso momento da
un'altra richiesta (vincolo UNIQUE, `@Version`). Le due scritture stanno in due
database diversi, quindi non c'è un commit unico: si **compensa**.
`ServizioIntegrazioni` intercetta l'errore del passo 5 e:

1. **rilegge il piano.** Se il lotto c'è già, l'ha integrato un'altra
   richiesta nello stesso momento (Marco ha premuto due volte e l'altra ha
   vinto). La riserva è usata: non si tocca, e Marco riceve 200 `GIA_INTEGRATO`;
2. **altrimenti chiama `AnnullaRiserva(lotto, piano)`**, con lo stesso JWT e la
   stessa deadline di 2 secondi. I kg tornano liberi e si scrive un log WARN
   `Compensazione: il piano ... non si è salvato (...), riserva del lotto ... annullata`;
3. **restituisce l'errore di partenza** (409 "Modifica concorrente", 500 per
   il database giù): l'annullamento non lo nasconde.

È **best effort**. Se anche `AnnullaRiserva` fallisce (magazzino giù proprio
in quel momento), l'errore si aggiunge come *suppressed* e si scrive un log
ERROR `Compensazione NON riuscita ... (riserva orfana)`. Si sistema ripetendo
l'integrazione: `RiservaLotto` è idempotente, restituisce la riserva che c'è
già e il piano si allinea. Se il piano non si riesce nemmeno a rileggere
(database giù), si annulla lo stesso: quasi sempre vuol dire che il piano non
è stato salvato.

`AnnullaRiserva` è idempotente, come `RiservaLotto`: la seconda volta risponde
OK con `nessuna_riserva: true` e `kg_liberati: "0.000"`. Si può annullare anche
la riserva di un lotto scaduto o non conforme: liberare kg non fa danni. Lato
magazzino non controlla se chi ha chiamato ha già smesso di aspettare, al
contrario di `RiservaLotto`: liberare i kg serve comunque.

È una saga minima con un solo passo di compensazione. L'ordine dei passi
aiuta: la regola dell'origine viene prima della riserva, quindi un lotto
rifiutato non va mai annullato.

C'è anche `GET /piani/{id}/lotti` (DISTILLATORE, RESP_ACQUISTI, ADMIN). Chiama
`Disponibilita` e restituisce i lotti con `stessaOrigine`: la pagina del piano
(passo 6) li mostra prima che Marco provi.

## Errori: dallo status gRPC alla risposta HTTP

| Magazzino risponde | Significa | Produzione risponde |
|---|---|---|
| `OK` | riservato (o già riservato) | 201 / 200 |
| `DEADLINE_EXCEEDED` | nessuna risposta in 2 secondi | **503** + `Retry-After: 5` |
| `UNAVAILABLE` | magazzino spento o irraggiungibile | **503** + `Retry-After: 5` |
| `FAILED_PRECONDITION` | kg insufficienti, lotto non conforme o scaduto | 409 `RISERVA_RIFIUTATA` |
| `ALREADY_EXISTS` | il piano ha già riservato altri kg di quel lotto | 409 `RISERVA_RIFIUTATA` |
| `NOT_FOUND` | il lotto non esiste più | 409 `RISERVA_RIFIUTATA` |
| `ABORTED` | un'altra riserva sullo stesso lotto nello stesso istante | 409 `RISERVA_RIFIUTATA` (si ripete) |
| `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `INTERNAL`... | errore tra i due servizi, non di Marco | 502 |

Dal lato di Magazzino la traduzione è in `ServizioGiacenze`. Dal lato di
Produzione è in `ClientGiacenze`, poi in `GestoreErrori`. Per `AnnullaRiserva`
la tabella vale solo per il log: Marco riceve sempre l'errore del passo 5.

### La deadline di 2 secondi

`ClientGiacenze` mette la deadline su **ogni** chiamata
(`withDeadlineAfter(2 s)`). La deadline è assoluta, quindi non si può fissare
una volta per tutte sullo stub. Viaggia nell'header `grpc-timeout`: il
magazzino sa quanto tempo resta e, se la chiamata viene annullata, smette di
lavorarci (`RiservaLotto` controlla `Context.isCancelled()` prima di
riservare).

Se la deadline scade proprio mentre il magazzino riserva, la riserva può
esserci anche se Marco riceve un 503. Ripetendo la richiesta, `RiservaLotto`
(idempotente) restituisce la riserva che c'è già e il piano si allinea.

## Sicurezza

- **Token relay.** `InoltroJwt` (client interceptor di Produzione) mette nel
  metadata lo stesso JWT arrivato dal gateway, come fa Edizioni verso Ricette.
- **Resource server anche su gRPC.** `AutenticazioneGrpc` (server
  interceptor di Magazzino) usa lo stesso `JwtDecoder` e lo stesso
  `JwtAuthenticationConverter` delle chiamate REST, quindi stessa JWKS,
  stesso issuer e stesso claim `roles`:
  - JWT mancante o non valido → `UNAUTHENTICATED`;
  - ruolo non ammesso per il metodo → `PERMISSION_DENIED`;
  - metodo senza regola → negato;
  - JWKS del gateway irraggiungibile → `UNAVAILABLE`.
- **Rete.** In chiaro (HTTP/2 senza TLS), solo sulla rete interna:
  - Compose: rete `backend`, porta 9084 non pubblicata sull'host. **In Compose
    la 9084 è protetta solo dal JWT**: le reti di Compose non filtrano per
    porta, quindi tutti i container della rete `backend` (gateway, edizioni,
    ricette, pagamenti, notifiche) possono aprire la connessione. Senza un JWT
    valido con il ruolo giusto, però, ricevono `UNAUTHENTICATED` o
    `PERMISSION_DENIED`;
  - Kubernetes: la NetworkPolicy `magazzino-grpc-solo-da-produzione`
    ([k8s/networkpolicy-magazzino.yaml](../k8s/networkpolicy-magazzino.yaml))
    apre la 9084 solo ai pod di produzione. L'8084 resta aperta come prima,
    per il gateway e le probe.

  La policy dice chi può collegarsi, il JWT dice con che ruolo.

## Dove gira

| | Magazzino (server) | Produzione (client) |
|---|---|---|
| Porta / indirizzo | `magazzino.grpc.porta` = 9084 (`GRPC_PORT`) | `produzione.magazzino.indirizzo` = `MAGAZZINO_GRPC` (`magazzino:9084`) |
| Classi | `ServerGiacenze` (SmartLifecycle), `ServizioGiacenze`, `AutenticazioneGrpc` | `ConfigurazioneGiacenze` (un `ManagedChannel`), `ClientGiacenze`, `InoltroJwt` |
| Trasporto | `grpc-netty-shaded` 1.85.1 | `grpc-netty-shaded` 1.85.1 |

Il server parte e si ferma con Spring: allo spegnimento lascia finire le
chiamate in corso per al massimo 5 secondi. Il canale si collega alla prima
chiamata, quindi Produzione parte anche con il magazzino spento; se il
magazzino cade, il canale si ricollega da solo.

## Provarlo

Test automatici (Docker in esecuzione; il modulo condiviso si installa prima):

```powershell
docker run --rm -v "${PWD}:/repo" -v ginvault-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /repo maven:3.9-eclipse-temurin-21 sh -c 'mvn -B -q -f giacenze-api/pom.xml install && mvn -B -f magazzino/pom.xml test && mvn -B -f produzione/pom.xml test'
```

- `magazzino` → `GiacenzeGrpcTest`: il server vero su una porta libera, con
  PostgreSQL. Copre disponibilità, riserva, idempotenza, kg insufficienti,
  due riserve nello stesso istante e i JWT mancanti, falsi o con il ruolo
  sbagliato.
  Copre anche `AnnullaRiserva`: i kg liberati, la seconda volta senza
  errore, il lotto inesistente, il ruolo.
- `produzione` → `IntegrazioneLottiTest`: un magazzino finto con lo stesso
  contratto, su una porta vera. Copre il rifiuto per origine diversa senza
  riserva, l'integrazione che riporta il piano a 12 kg e 600 bottiglie, il JWT
  e la deadline sul filo, il magazzino lento o spento (503) e il rifiuto del
  magazzino (409). Per la **compensazione**, un vincolo `CHECK` messo dal test
  fa fallire il salvataggio del piano dopo la riserva:
  - `AnnullaRiserva` arriva al magazzino con il JWT di Marco, il piano resta
    a 10,8 kg, e riprovando l'integrazione riesce;
  - con il magazzino che non risponde all'annullamento resta l'errore di
    partenza, con il log ERROR della riserva orfana.
- `produzione` → `ServizioIntegrazioniTest` (senza database): i due casi che
  con un database vero non si provocano a comando. Un'altra richiesta ha già
  integrato il lotto, quindi la riserva non si annulla. Il database è giù,
  quindi si annulla senza rileggere il piano.

A mano sul cluster, con [grpcurl](https://github.com/fullstorydev/grpcurl). Il
port-forward passa dal kubelet, quindi la NetworkPolicy non si applica:

```powershell
kubectl -n ginvault port-forward svc/magazzino 19084:9084      # in un'altra finestra

# Il contratto, letto dal .proto (il server non espone la reflection)
docker run --rm -v "${PWD}\giacenze-api\src\main\proto:/proto" fullstorydev/grpcurl:v1.9.3 -import-path /proto -proto giacenze.proto describe ginvault.giacenze.v1.Giacenze

# Senza JWT: Code Unauthenticated, "Serve il JWT dell'utente nel metadata authorization"
docker run --rm -v "${PWD}\giacenze-api\src\main\proto:/proto" fullstorydev/grpcurl:v1.9.3 -plaintext -import-path /proto -proto giacenze.proto -d '{\"botanica\": \"lavanda\"}' host.docker.internal:19084 ginvault.giacenze.v1.Giacenze/Disponibilita
```

## Limiti noti

- **Riserva orfana.** Resta solo se il piano non si salva e anche
  `AnnullaRiserva` fallisce (log ERROR). Si sistema ripetendo la richiesta
  (idempotente). Non c'è un job che ritenta da solo l'annullamento.
- **Commit perso.** Se il database salva il piano ma la risposta si perde, e
  poi il piano non si rilegge, la compensazione annulla una riserva che il
  piano usa. È un caso raro, e nessun test lo copre.
- **Una replica.** Una connessione gRPC dura a lungo e resta sul pod scelto
  all'apertura. Con più repliche di magazzino servirebbe un bilanciamento per
  chiamata: Service headless e `round_robin` nel client.
- **Compose** non può filtrare per porta: lì la 9084 è raggiungibile da tutta
  la rete `backend`, e **la protegge solo il JWT** (vedi [Sicurezza](#sicurezza)).
- **Il rifiuto non torna a Produzione.** Dopo un'integrazione la resa risale
  (10,8 → 12) ed Edizioni rimette le bottiglie a ONLINE (`TiraturaAumentata`,
  vedi [docs/eventi.md](eventi.md)). Una resa che scende sotto le prenotate,
  invece, Edizioni la rifiuta con `RicalcoloRifiutato`: per ora nessuno lo
  consuma, e il piano di produzione resta con i suoi kg.
