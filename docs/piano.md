# GinVault – Piano per il 30 e lode (TAASS 26-27, 9 CFU, gruppo da 4)

Alessandro Olivero · Andrea Franco · Giovanni Grillo · Samuele Iudice

Questo piano mette in fila tutto quello che le linee guida chiedono per arrivare al 30
e lode, e lo traduce in pezzi concreti di GinVault. È una proposta da discutere nel
gruppo: le scelte segnate come *da decidere* vanno confermate prima di iniziare.

Regola d'oro delle linee guida: **un requisito conta solo se alla discussione lo
sapete spiegare** (perché la configurazione è quella, dove si vede in Docker/K8s,
cosa fa ogni impostazione). Vale per tutti e quattro, su tutto il progetto.

---

## 1. Cosa serve, in una tabella

| Livello | Requisito | Come lo copriamo in GinVault |
|---|---|---|
| Imprescindibile | ≥ 4 microservizi, ≥ 3 di dominio, ≤ 1 tecnico | 5 di dominio + 1 tecnico (sezione 2) |
| Imprescindibile (gruppo da 4) | **3 user story** implementate | Giulia, Marco, Elena: già scritte e collegate da un unico evento |
| Imprescindibile | REST sincrono + asincrono (RabbitMQ o Kafka) | REST Edizioni → Ricette; **Kafka** per la catena di eventi |
| Imprescindibile | Docker Compose in production mode | Tutto il sistema, con gateway e front-end |
| Imprescindibile (gruppo da 4) | **Kubernetes / Minikube** (anche parziale) | Tutto o un sottoinsieme, con Ingress |
| Imprescindibile | Spring Security, JWT/OAuth2, 2 provider esterni | Login con **Google e GitHub** |
| Imprescindibile | Ruoli multipli con endpoint riservati | SOCIO, CLIENTE, DISTILLATORE, RESP_ACQUISTI, ADMIN |
| Imprescindibile | Presentazione + UML + diagramma del broker | Sezione 6 |
| Addizionale | Terzo meccanismo di comunicazione | **gRPC** Produzione ↔ Magazzino |
| Addizionale | Kubernetes | Già obbligatorio per noi |
| Addizionale | Servizio di terze parti non visto a lezione | **Stripe** in modalità test dentro Pagamenti (*da verificare che non sia stato visto a lezione*) |
| Addizionale (gruppo da 4) | Tactical DDD di un bounded context | **Edizioni**: già nella relazione (aggregato, VO, factory, repository, eventi) |
| Plus | Quattro, uno per persona | Sezione 4 |

---

## 2. Microservizi

| Servizio | Tipo | Tecnologia | Storia | Stato |
|---|---|---|---|---|
| **Edizioni** | dominio, Core | Spring Boot | Giulia, Marco | implementato |
| **Ricette** | dominio, Core | Spring Boot | Marco | implementato |
| **Produzione** | dominio | Spring Boot | Marco, Elena | implementato |
| **Magazzino** | dominio | Spring Boot | Elena | implementato |
| **Pagamenti** | dominio, Generic | Spring Boot + Stripe (test) | Giulia | implementato |
| **Notifiche** | tecnico | **NestJS** + WebSocket | Giulia, Marco | implementato |
| Esperienze & Club | dominio | `@Service` mock dentro Edizioni | Giulia | mock |
| Vendite, Fiscale | — | — | — | ignorati (fuori iterazione) |
| Gateway / Ingress | infrastruttura | Spring Cloud Gateway (Compose), Ingress (K8s) | tutte | implementato |
| Front-end | — | area soci + portale distillatore | tutte | implementato |

Il mock del Club è ammesso esplicitamente dalle linee guida (come il MenuService
dentro *ordini* negli esempi del corso): verifica se un utente è socio leggendo dati
preimpostati.

Ogni servizio ha il **proprio database**: nessuna tabella condivisa, solo riferimenti
per id (es. Edizioni conserva `riferimentoFormulazione`, non la formulazione).

---

## 3. Comunicazione

### Il filo che lega le tre storie

Un raccolto scarso attraversa quattro servizi. È la funzionalità da mostrare in demo,
perché tocca tutte e tre le user story e tutti i meccanismi di comunicazione.

```
Elena registra la consegna ridotta (10,8 kg invece di 12)     [Magazzino]
   │  Kafka  magazzino.eventi      ConsegnaRegistrata   key = botanica
   ▼
Produzione ricalcola la resa: 540 bottiglie                    [Produzione]
   │  gRPC   Produzione → Magazzino: lotti disponibili / riserva
   │  Kafka  produzione.eventi     ResaRicalcolata      key = idEdizione
   ▼
Edizioni riduce la quota online, prelazione intatta            [Edizioni]
   │  Kafka  edizioni.eventi       TiraturaRidotta      key = idEdizione
   ▼
Notifiche manda il push a Giulia e alla lista d'attesa         [Notifiche]
   │  WebSocket → front-end (il nostro mockup dell'area soci)
```

### Meccanismi

| Meccanismo | Dove | Perché lì |
|---|---|---|
| **REST** (sincrono) | front-end → gateway → servizi; **Edizioni → Ricette** all'annuncio: "la formulazione è approvata e mai usata?" | È una validazione che deve rispondere subito prima di proseguire: è il caso "Es. 2" delle linee guida. Nella context map è Customer/Supplier. |
| **Kafka** (asincrono) | la catena di eventi sopra; `LottoImbottigliato` → Edizioni assegna i numeri | Eventi di dominio pubblicati da un contesto e consumati da altri, senza accoppiamento. |
| **gRPC** (terzo meccanismo) | Produzione → Magazzino: lotti disponibili, riserva e prelievo delle botaniche | Nella context map è una Partnership: contratto tipizzato (`.proto`) condiviso dai due team, chiamate frequenti e interne. |

### Topic Kafka (per il diagramma obbligatorio)

Il contratto completo (busta JSON, versioni, regole) è in [`eventi.md`](eventi.md).

| Topic | Produttore | Eventi | Chiave | Consumer group |
|---|---|---|---|---|
| `magazzino.eventi` | Magazzino | ConsegnaRegistrata (anche ridotta); più avanti LottoScartato | botanica | `produzione` |
| `produzione.eventi` | Produzione | ResaRicalcolata; più avanti LottoImbottigliato | idEdizione | `edizioni` |
| `edizioni.eventi` | Edizioni | EdizioneCreata, TiraturaRidotta, RicalcoloRifiutato; più avanti PrelazioneAperta, PrenotazioneConfermata, NumeriAssegnati | idEdizione | `produzione`, `notifiche` |
| `pagamenti.comandi` / `pagamenti.esiti` | Edizioni / Pagamenti | RichiestaPagamento / PagamentoRiuscito, PagamentoFallito | idPrenotazione | `pagamenti` / `edizioni` |

---

## 4. I quattro plus (uno per persona)

Le linee guida accettano il plus se tutti lo conoscono **oppure** se ognuno ha il
proprio da presentare. Ne scegliamo quattro, ognuno motivato dal dominio: non per
"mettere roba", ma perché risolvono un problema reale di GinVault.

| # | Plus | Dove | Perché ha senso per GinVault |
|---|---|---|---|
| 1 | **SAGA** (orchestrata) | Prenotazione: Edizioni ↔ Pagamenti | La prenotazione tocca due servizi con DB separati: si riservano le bottiglie nella quota, poi si paga con Stripe. Se il pagamento fallisce o scade, la **compensazione** rilascia le bottiglie. Senza SAGA la quota potrebbe restare occupata da prenotazioni mai pagate, o venire superata. Si collega alla regola dell'aggregato "le prenotazioni non superano mai la tiratura". |
| 2 | **Consumer Kafka scalato e partizionato per chiave** | Edizioni (consumer di `produzione.eventi`) | Gli eventi della stessa edizione devono arrivare in ordine: prima `ResaRicalcolata`, poi `LottoImbottigliato`, altrimenti si assegnano numeri su una tiratura vecchia. Con chiave = idEdizione e più repliche, l'ordine per edizione resta garantito mentre il carico si divide. |
| 3 | **Servizio non Spring + WebSocket** | Notifiche in NestJS, push al front-end | L'avviso "la tiratura scende a 540, la tua prenotazione è confermata" deve arrivare a Giulia mentre è nell'area soci: è esattamente il nostro mockup. |
| 4 | **Istio + Circuit Breaker** | Su Kubernetes, chiamate verso Pagamenti / Stripe | È il requisito non funzionale già scritto nella relazione: "un guasto del provider di pagamento non deve bloccare le funzioni interne". Si simula Stripe giù e si mostra il circuito che si apre. |

**Riserve**, solo se avanza tempo e fatte bene: gRPC in *server streaming* (Magazzino
invia a Produzione i lotti disponibili man mano), Transactional Outbox su Edizioni
(salvataggio e pubblicazione di `TiraturaRidotta` atomici), osservabilità (da
precisare dopo la lezione dedicata). Meglio quattro plus solidi che sette fragili.

---

## 5. Security

- Login OAuth2 con **Google** e **GitHub**; il gateway emette/valida il **JWT** che
  arriva ai servizi.
- Ruoli e permessi, con `@PreAuthorize` sugli endpoint:

| Ruolo | Può fare (esempi) |
|---|---|
| SOCIO | prenotare durante la prelazione (max 2 bottiglie), vedere le proprie prenotazioni e la tracciabilità |
| CLIENTE | prenotare la quota online dopo la prelazione, entrare in lista d'attesa |
| DISTILLATORE | registrare formulazioni, annunciare edizioni, registrare rese e imbottigliamenti |
| RESP_ACQUISTI | registrare consegne (anche ridotte), gestire i lotti di botaniche e lo storico fornitori |
| ADMIN | gestione utenti e ruoli |

Esempio: `POST /edizioni/{id}/prenotazioni` è riservato a SOCIO in prelazione e a
CLIENTE dopo; `POST /magazzino/consegne` solo a RESP_ACQUISTI.

---

## 6. Documentazione da consegnare

**Presentazione aggiornata**
1. Obiettivi del progetto (dal business della PR1)
2. Le tre user story scelte
3. DDD strategico: subdomain, bounded context, context map, corretti se cambiano
4. Architettura completa con ingress e front-end, con legenda **implementato / mock / ignorato**
5. Tactical DDD di Edizioni (c'è già nella relazione)
6. I quattro plus, ognuno presentato da chi l'ha fatto

**UML: pochi e mirati**

| Diagramma | Cosa mostra |
|---|---|
| Class diagram | Aggregato Edizione (Edizione, Quota, Prenotazione, ListaAttesa + VO); al massimo Lotto di gin |
| Sequence diagram (REST) | Annuncio di un'edizione: Edizioni → Ricette, validazione della formulazione |
| Collaboration diagram (asincrono) | La catena del raccolto scarso (sezione 3) |
| State machine | Prenotazione: IN_ATTESA_PAGAMENTO → CONFERMATA → NUMERATA → RITIRATA, oppure ANNULLATA (compensazione SAGA); Edizione: IN_SVILUPPO → IN_PRELAZIONE → IN_VENDITA → ESAURITA |
| Diagramma Kafka | Topic, chiavi di partizionamento e consumer group (tabella in sezione 3) |
| (consigliato) | Il file `.proto` del servizio gRPC |

---

## 7. Divisione del lavoro (proposta)

Ognuno è responsabile di servizi, plus e diagrammi. Tutti però devono saper
spiegare l'intero sistema: all'orale si può ricevere una domanda su qualunque parte.

| Chi | Servizi | Plus | Trasversale | Diagrammi |
|---|---|---|---|---|
| **Alessandro** | Edizioni, Ricette | SAGA prenotazione | modello di dominio, Tactical DDD | class diagram, state machine |
| **Andrea** | Produzione, Magazzino | Consumer Kafka partizionato | Kafka (topic, chiavi), gRPC | collaboration diagram, diagramma Kafka, `.proto` |
| **Giovanni** | Notifiche (NestJS) | WebSocket + servizio non Spring | front-end (area soci, portale distillatore) | — |
| **Samuele** | Pagamenti (Stripe) | Istio + Circuit Breaker | gateway, Security OAuth2/JWT, Docker Compose, Kubernetes | sequence diagram, architettura |

---

## 8. Ordine di lavoro

1. **Fondamenta**: repository, scheletro dei servizi, Docker Compose con Kafka e DB,
   gateway con login Google/GitHub e ruoli.
2. **Storia di Marco**: Ricette ed Edizioni, annuncio con validazione REST.
3. **Storia di Elena**: Magazzino e Produzione, gRPC, eventi Kafka fino a `ResaRicalcolata`.
4. **Storia di Giulia**: prenotazione con SAGA e Stripe, `TiraturaRidotta`, Notifiche + WebSocket, front-end.
5. **Kubernetes**: manifest, Ingress, poi Istio e circuit breaker.
6. **Plus rimanenti**: repliche del consumer Kafka.
7. **Documentazione e prova generale**: diagrammi, presentazione, checklist dei
   requisiti da compilare prima della discussione, demo del raccolto scarso dall'inizio alla fine.

## 9. Errori da evitare

- **Plus fatti a metà**: valgono come minus. Se uno non regge, meglio toglierlo.
- **Configurazioni copiate senza capirle** (Docker, K8s, Kafka, Istio): all'orale
  chiedono di spiegarle e di mostrarle nelle rispettive interfacce.
- **Troppi servizi**: Vendite e Fiscale restano fuori, Club resta un mock.
- **DB condivisi tra servizi**: rompono il low coupling che le linee guida valutano.
- **Kubernetes troppo pesante per il portatile**: è ammesso un deploy parziale;
  meglio decidere presto quali servizi portare.
