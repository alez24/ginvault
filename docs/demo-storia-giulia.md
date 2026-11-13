# Demo: la storia di Giulia su Minikube

> Giulia è socia del club. Marco annuncia l'edizione con la lavanda e Giulia,
> in prelazione, prenota 2 bottiglie e le paga con Stripe. Il raccolto scarso
> di Elena porta la tiratura da 600 a 540: a Giulia arriva l'avviso in diretta,
> e le sue bottiglie, la 112 e la 113, restano sue.

In questa demo:

1. Marco annuncia l'edizione: **12 kg** di lavanda → **600 bottiglie**, con
   **7 giorni di prelazione** per i soci;
2. prima di Giulia altri soci hanno già confermato **111 bottiglie**
   (l'antefatto, scritto nel database);
3. Giulia prenota **2 bottiglie a 45 €** e prova a pagare con la **carta
   rifiutata**: la SAGA annulla la prenotazione e le bottiglie tornano libere;
4. Giulia prenota di nuovo e paga con la **Visa di prova**: la prenotazione si
   conferma con le bottiglie **112 e 113**, e l'avviso arriva in diretta;
5. Elena registra la consegna da **10,8 kg**: la tiratura scende a **540** da
   sola, e a Giulia arriva in diretta «Le tue bottiglie, la 112 e la 113,
   restano tue.».

Poi le prove di quello che succede dietro: i messaggi nei topic, le tabelle,
le due repliche di Notifiche e la scadenza.

La SAGA del pagamento e gli avvisi:

```
Giulia: Paga ──► edizioni  outbox ──► pagamenti.comandi   RichiestaPagamento       (chiave: id della prenotazione)
                                         └─► pagamenti ── HTTPS ──► Stripe (modalità test, pm_card_visa)
                 pagamenti outbox ──► pagamenti.esiti     PagamentoRiuscito / PagamentoFallito
                                         ├─► edizioni: conferma con i numeri 112 e 113, oppure annulla e libera le bottiglie
                                         └─► notifiche: solo RimborsoEseguito (gli altri esiti li ignora)
                  edizioni outbox ──► edizioni.eventi     PrenotazioneConfermata / PrenotazioneAnnullata
                                         └─► notifiche ── NOTIFY ──► i due pod ── WebSocket ──► il browser di Giulia
Elena: consegna ridotta ─► ... ─► edizioni.eventi          TiraturaRidotta (600 → 540)
                                         └─► notifiche: un avviso a chi ha bottiglie confermate, con i suoi numeri
```

I formati sono in [`eventi.md`](eventi.md): la busta, i comandi e gli esiti
di Pagamenti, il consumer di Notifiche e il protocollo del WebSocket.

## Prerequisiti

- Stack Compose spento (`docker compose down`): il front-end di Compose e
  `minikube tunnel` vogliono entrambi la porta 80.
- Cluster avviato e applicato come nella "Demo completa" di
  [`k8s/README.md`](../k8s/README.md#demo-completa), passi 1–4, con queste immagini:

  ```powershell
  minikube image ls | Select-String ginvault    # frontend:0.4, gateway:0.3, edizioni:0.7, ricette:0.2, magazzino:0.3, produzione:0.4, pagamenti:0.1, notifiche:0.1
  ```

- `minikube tunnel` aperto in una PowerShell **da amministratore**.
- **La chiave di test di Stripe nel `.env`**, in una riga sola:
  `STRIPE_SECRET_KEY=sk_test_...` (dashboard di Stripe, modalità test,
  Sviluppatori → Chiavi API → Chiave segreta). Poi:

  ```powershell
  .\k8s\crea-secret.ps1                                        # deve dire "secret/pagamenti-stripe: chiave di test presente"
  kubectl rollout restart deployment/pagamenti -n ginvault
  kubectl rollout status deployment/pagamenti -n ginvault
  # Count 0: pagamenti ha la chiave (altrimenti scrive "STRIPE_SECRET_KEY non impostata")
  kubectl logs -n ginvault deploy/pagamenti | Select-String -SimpleMatch 'STRIPE_SECRET_KEY non impostata' | Measure-Object
  ```

  Senza la chiave la prenotazione resta «Pagamento in corso»: pagamenti riprova
  quattro volte e poi manda il comando in `pagamenti.dlt`.
- Un account con **tre ruoli**: SOCIO per Giulia, DISTILLATORE per Marco e
  RESP_ACQUISTI per Elena. I ruoli si sommano: la stessa identità (ad esempio
  `github:<utente>`) in `RUOLI_SOCIO`, `RUOLI_DISTILLATORE` e
  `RUOLI_RESP_ACQUISTI` del `.env`. Poi Secret e gateway, che legge le
  variabili solo all'avvio:

  ```powershell
  .\k8s\crea-secret.ps1
  kubectl rollout restart deployment/gateway -n ginvault
  kubectl rollout status deployment/gateway -n ginvault
  ```

Controlli prima di cominciare:

```powershell
kubectl get pods -n ginvault        # 18 pod Running: 2/2 i servizi e il front-end (2 frontend, 2 ricette, 2 notifiche), 1/1 i DB e kafka-0; il Job kafka-topic Completed
curl.exe -s http://localhost/api/pagamenti/health     # {"status":"UP"}
curl.exe -s http://localhost/api/notifiche/health     # {"status":"UP","database":"UP","kafka":"IN_ASCOLTO"}
curl.exe -s -o NUL -w '%{http_code}' http://localhost/api/edizioni/prenotazioni   # 401: senza login niente dati
```

Facoltativo, ma rende la SAGA visibile: in una seconda PowerShell segui i log
dei tre servizi, solo le righe dei pagamenti e degli avvisi.

```powershell
kubectl logs -n ginvault -l 'app.kubernetes.io/name in (edizioni,pagamenti,notifiche)' --prefix -f --tail=0 | Select-String 'pagata|PagamentoRiuscito|PagamentoFallito|avvisi|ResaRicalcolata'
```

## 1. Login

Apri `http://localhost` (non `127.0.0.1`) → **Accedi** → **Accedi con GitHub**.
**Il mio profilo** mostra SOCIO, DISTILLATORE e RESP_ACQUISTI. Nel menu c'è
anche **Area soci**.

## 2. Marco annuncia l'edizione

Come al passo 2 della [demo di Elena](demo-storia-elena.md#2-marco-crea-ledizione-12-kg--600-bottiglie):
una formulazione nuova con la lavanda, **Scegli questa versione**, poi
**Crea l'edizione** con **12** kg, origine `Alta Val Varaita (Alpi Cozie)` e le
quote **300 / 150 / 150**.

Atteso:

- la pagina dell'edizione con tiratura **600**;
- l'edizione è **in prelazione** per 7 giorni: solo i soci prenotano, dalla
  loro quota;
- l'id dell'edizione è nell'indirizzo, `/distilleria/edizioni/<id>`: serve al
  passo 3.

Il nome che vede Giulia viene dalla stagione dell'annuncio: a novembre è
«Edizione d'autunno 2026». Il ritiro è una masterclass in distilleria, il primo
sabato dopo sei settimane dall'annuncio: per un annuncio dell'11 novembre,
sabato 26 dicembre. Il colore lavanda è solo per le edizioni d'estate.

## 3. L'antefatto: 111 bottiglie già confermate

Prima di Giulia, 56 soci hanno confermato 111 bottiglie: 55 prenotazioni da 2
e una da 1, con i numeri da 1 a 111. Nel browser c'è un account solo, e un
socio ne prenota al massimo 2, quindi l'antefatto si scrive nel database di
Edizioni con
[`demo-storia-giulia-antefatto.sql`](demo-storia-giulia-antefatto.sql).
Lo script tiene insieme prenotazioni, numeri, quota dei soci e contatore
dell'edizione, come fa `Edizione.conferma`.

```powershell
$ed = '<id dell edizione>'
Get-Content docs\demo-storia-giulia-antefatto.sql | kubectl exec -i -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -v ON_ERROR_STOP=1 -v ed=$ed
```

Atteso: `prenotazioni 56 | bottiglie 111 | primo 1 | ultimo 111`, poi
`COMMIT`. Nella pagina dell'edizione (ricaricata) la quota dei soci ha 111
prenotate.

Saltando l'antefatto la storia funziona uguale, ma le bottiglie di Giulia sono
la 1 e la 2. La numerazione vera, con 20 conferme nello stesso istante, è
provata da `SagaPagamentoTest` di edizioni.

## 4. Giulia prova la carta rifiutata

**Area soci**: in «Da prenotare» c'è l'edizione, «In prelazione fino al …».
Aprila.

1. **Prenota le tue bottiglie**: 2 bottiglie, 90,00 € → **Prenota 2 bottiglie**.
   La nota dice che ci sono 15 minuti per pagare.
2. **Paga la prenotazione**: «2 bottiglie a 45,00 €: 90,00 €. Da pagare entro
   le HH:MM, tra 14 minuti». Scegli **Carta rifiutata** → **Paga 90,00 €**.

Atteso:

- subito «**Pagamento in corso**», con il pallino che respira: la POST ha
  risposto **202** (Network: `POST /api/edizioni/prenotazioni/<id>/pagamento`);
- dopo un paio di secondi «**Pagamento non riuscito** — La banca ha rifiutato
  la carta.», e di nuovo il modulo per prenotare: le 2 bottiglie sono tornate
  libere, e l'annullata non conta nel limite di 2;
- in **Area soci** l'avviso «Pagamento non riuscito» — «La prenotazione di 2
  bottiglie dell'edizione con lavanda è annullata. …», e la prenotazione tra le
  annullate.

Nei log: in pagamenti `prenotazione ... NON pagata, 90,00 ... con
pm_card_chargeDeclined (...)`, in edizioni `PagamentoFallito ... annullata
(PAGAMENTO_RIFIUTATO ...), bottiglie tornate libere`.

## 5. Giulia paga con la Visa di prova: la 112 e la 113

Nella stessa pagina: **Prenota 2 bottiglie** → **Visa di prova** → **Paga 90,00 €**.

Atteso, in un paio di secondi:

- «**Pagamento riuscito** — Hai pagato 90,00 € per 2 bottiglie. Le tue
  bottiglie sono la 112 e la 113.»;
- in alto «Le tue bottiglie: **n. 112 e 113 di 600**», con i due cartellini
  sulle bottiglie, e il ritiro alla masterclass;
- in **Area soci** l'avviso «Prenotazione confermata»: è arrivato dal
  WebSocket, senza ricaricare. Chiuderlo lo segna come letto;
- il modulo non c'è più: «Hai già prenotato 2 bottiglie: è il massimo per
  persona in prelazione.».

Il WebSocket in DevTools: **Network** → filtro **WS** → `avvisi` →
**Messages**. Ci sono `{"tipo":"connesso","nonLette":…}`, le due
`{"tipo":"notifica",…}` e un `{"tipo":"ping"}` ogni 25 secondi. Il JWT del
gateway dura 5 minuti: alla scadenza Notifiche chiude con **4001** e il
front-end si ricollega subito, con un JWT nuovo.

Nella dashboard di Stripe, modalità test, in **Pagamenti**: un pagamento da
90,00 € riuscito e uno rifiutato. Nei metadati ci sono solo gli id della
prenotazione e dell'edizione, nessun dato di Giulia.

## 6. Elena: la consegna da 10,8 kg, e l'avviso a Giulia

Tieni aperta in una scheda la pagina dell'edizione di Giulia. In un'altra
scheda, con lo stesso login, fai i passi 3 e 4 della
[demo di Elena](demo-storia-elena.md#3-elena-ordina-12-kg): ordine da 12 kg ai
Raccoglitori dell'Alta Val Varaita, poi **Registra la consegna** con
**10,8** kg.

Atteso, nella scheda di Giulia e senza ricaricare:

- l'avviso «**La tiratura è scesa da 600 a 540, le tue bottiglie restano
  tue**»: il raccolto ha dato 10,8 kg invece di 12, e le bottiglie in meno
  escono dalla vendita online;
- «Le tue bottiglie: n. 112 e 113 di 540»;
- la tracciabilità: «10,8 kg su 12 previsti»;
- le quote che calano, 600 → 540: ONLINE da 150 a 90, SOCI resta 300;
- in **Area soci** l'avviso «La tiratura scende a 540 bottiglie» — «L'edizione
  con lavanda passa da 600 a 540 bottiglie (consegna ridotta: 10,8 su 12 kg).
  Le tue bottiglie, la 112 e la 113, restano tue.».

Nei log: `ResaRicalcolata ... tiratura da 600 a 540`, poi in uno dei due pod di
notifiche `edizioni.eventi[<P>]@<offset> TiraturaRidotta <eventId>: 1 avvisi`.
L'avviso va solo a chi ha bottiglie confermate e che Notifiche conosce dagli
eventi: i soci dell'antefatto non hanno eventi, Giulia sì.

## 7. Dietro le quinte

```powershell
# I comandi di Edizioni e gli esiti di Pagamenti, con la chiave (l'id della prenotazione). Ctrl+C per uscire
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic pagamenti.comandi --from-beginning --property print.key=true
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic pagamenti.esiti --from-beginning --property print.key=true
# I consumer group: pagamenti su pagamenti.comandi; notifiche su edizioni.eventi e pagamenti.esiti, con le partizioni divise tra i due pod
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group pagamenti
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notifiche
# Le prenotazioni di Giulia, e i numeri: 112 e 113, nessun buco per l'annullata
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c 'select stato, bottiglie, causa_annullamento, riferimento_pagamento from prenotazione where acquirente not like $$antefatto%$$ order by prenotata_il'
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c 'select numero from bottiglia_numerata where numero > 110 order by numero'
# I pagamenti, con il riferimento di Stripe (pi_...)
kubectl exec -n ginvault deploy/pagamenti-db -- psql -U pagamenti -d pagamenti -c 'select * from pagamento'
# Gli avvisi di Giulia, letti e non letti
kubectl exec -n ginvault deploy/notifiche-db -- psql -U notifiche -d notifiche -c 'select tipo, titolo, creata_il, letta_il from notifica order by creata_il'
# I dead letter topic: vuoti (offset 0)
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic pagamenti.dlt
```

## 8. Prova delle due repliche di Notifiche

Il WebSocket di Giulia sta su uno dei due pod di notifiche; l'evento lo legge
il pod che ha la partizione. Si passano gli avvisi con LISTEN/NOTIFY su
`notifiche-db` ([`k8s/README.md`](../k8s/README.md#pagamenti-e-notifiche-storia-di-giulia)).

```powershell
# Quale pod ha quali partizioni
kubectl logs -n ginvault -l app.kubernetes.io/name=notifiche --prefix | Select-String 'Nel gruppo'
# Un pod cancellato con Giulia collegata: il ReplicaSet ne crea un altro
kubectl delete pod -n ginvault <uno dei due pod notifiche>
```

Nella scheda di Giulia, in DevTools, il WebSocket si chiude con **1001**, e
dopo un secondo se ne apre uno nuovo, sull'altro pod. Al `connesso` il
front-end rilegge l'elenco: un avviso nato mentre il WebSocket era chiuso non
si perde. Per vederlo, durante quel secondo, chiudi un avviso o rifai il passo
6 con un'edizione nuova.

## 9. Prova della scadenza (15 minuti)

Su un'edizione nuova, **Prenota** e non pagare. Il conto alla rovescia scende,
e allo scadere la pagina dice «Il tempo per pagare è finito». Entro 30
secondi Edizioni annulla la prenotazione: «**Prenotazione scaduta**», le
bottiglie tornano libere e arriva l'avviso. Se un pagamento arriva dopo la
scadenza, Edizioni chiede il rimborso a Pagamenti (lo provano
`SagaPagamentoTest` e i test di notifiche).

## Riepilogo dei casi

| Cosa | Risultato |
|---|---|
| Edizione appena annunciata | in prelazione per 7 giorni; regole: 2 bottiglie per socio a 45 €, 15 minuti per pagare |
| Prenotazione di 2 bottiglie in prelazione | 201, da pagare, quota SOCI, 90,00 € |
| Una terza bottiglia | il modulo non la offre; l'API risponde 409 `LIMITE_PER_PERSONA` |
| Pagamento con una carta fuori dal menu | 400, con `metodiAccettati` |
| Pagamento con la carta rifiutata | 202, poi annullata (`PAGAMENTO_RIFIUTATO`), bottiglie libere, avviso |
| Pagamento con la Visa di prova | 202, poi confermata con 112 e 113 (dopo le 111 dell'antefatto), avviso |
| Pagare due volte | 409 `PAGAMENTO_GIA_RICHIESTO` |
| Consegna da 10,8 kg | tiratura 540, SOCI resta 300, ONLINE 90; avviso con i numeri a Giulia |
| Un pod di notifiche cancellato | WebSocket chiuso con 1001 e riaperto sull'altro pod |
| Nessuna chiave di Stripe | «Pagamento in corso» senza esito; il comando finisce in `pagamenti.dlt` |
| Tempo scaduto | annullata (`SCADUTA`) entro 30 s, bottiglie libere, avviso |

## Ripetere la demo

Ogni giro ha bisogno di una formulazione nuova: Edizioni accetta una sola
edizione per versione di formulazione. Su un'edizione nuova Giulia può
prenotare di nuovo, e l'antefatto si rilancia con il nuovo id. Il limite di 2
bottiglie vale per persona e per edizione: sulla stessa edizione, con la 112 e
la 113 confermate, il modulo non ricompare.

Se la sessione scade, le pagine rispondono 401 e il WebSocket si chiude con
4401 senza riaprirsi: rifai il login.

## Con Docker Compose

La storia funziona uguale su `http://localhost` con `docker compose up` (e il
tunnel chiuso). La chiave di Stripe la legge Compose dalla stessa riga del
`.env`. Nei comandi cambia solo il modo di arrivare ai container:

| Su Minikube | Con Compose |
|---|---|
| `kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/...` | `docker compose exec kafka /opt/kafka/bin/...` |
| `... \| kubectl exec -i -n ginvault deploy/edizioni-db -- psql ...` | `... \| docker compose exec -T edizioni-db psql ...` |
| `kubectl logs -n ginvault deploy/pagamenti` | `docker compose logs pagamenti` |

In Compose notifiche ha una replica sola: la prova del passo 8 è solo su
Minikube.

## Come è stata verificata

- **La catena completa, l'11 novembre, in Docker Compose**, con il codice di
  questo branch. I servizi verificavano JWT di prova firmati da una chiave
  locale, al posto del gateway, e pagamenti chiamava uno Stripe finto (stesse
  risposte di Stripe in modalità test: 200 `succeeded`, 402 `card_declined`):
  - Marco: formulazione, scelta, edizione da 600 in prelazione;
  - 56 soci via API (55 × 2 e 1 × 1): 111 bottiglie confermate e numerate da 1
    a 111, senza buchi né doppioni, in 2,3 s;
  - Giulia:
    - 201 da pagare, 90 € e 15 minuti per pagare;
    - 409 `LIMITE_PER_PERSONA`, 400 per la carta fuori menu;
    - 202, poi 409 `PAGAMENTO_GIA_RICHIESTO`;
    - **confermata in 1,6 s con 112 e 113**, e l'avviso «Prenotazione
      confermata» sul WebSocket;
  - Anna con la carta rifiutata: annullata in 1,6 s, prenotate dei soci da 115
    a 113; Giulia non riceve l'avviso di Anna;
  - Elena: piano da `EdizioneCreata`, ordine, consegna da 10,8 kg →
    **tiratura 540 in 1,9 s**, SOCI 300, ONLINE 90, e l'avviso della tiratura
    a Giulia con «la 112 e la 113»;
  - `GET /notifiche` con 2 non lette, poi «tutte lette» 204 e 0 non lette;
  - un WebSocket senza JWT chiuso con 4401;
  - i dead letter topic vuoti.
- **Lo stesso giro di questa demo, con un account solo:**
  - l'antefatto SQL;
  - la carta rifiutata (annullata in 3,2 s, nessun numero, bottiglie libere);
  - la nuova prenotazione e la Visa: **112 e 113**;
  - la consegna ridotta, tiratura 540 in 1,9 s, con l'avviso.
- **Su Minikube**, senza login:
  - 17 pod Ready, i consumer group, le due connessioni LISTEN;
  - il test busybox delle NetworkPolicy, Stripe raggiungibile da pagamenti;
  - dall'Ingress: `/soci` dal front-end 0.4, e le API e il WebSocket di
    notifiche che rispondono 401 senza sessione.
- **Non verificato:** il giro dal browser con il login OAuth e Stripe vero in
  modalità test. Il primo lo fa l'utente; per il secondo serve la riga
  `STRIPE_SECRET_KEY=` nel `.env`.
