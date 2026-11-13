# Demo: la storia di Marco su Minikube

Marco è il mastro distillatore. In questa demo:

1. crea una formulazione con **3 prove di assaggio** (Ricette);
2. il panel **sceglie la seconda**;
3. crea l'edizione con **12 kg** di lavanda → **600 bottiglie** (Edizioni, che
   valida la formulazione chiamando Ricette via REST);
4. la pioggia durante la fioritura rovina parte del raccolto: **ricalcolo con
   10,8 kg** → **540 bottiglie**, tolte da ONLINE;
5. con **Ricette spento** la creazione di un'edizione risponde **503** e non
   lascia niente a metà; riacceso Ricette, la stessa richiesta va a buon fine.

Le chiamate passano tutte dal gateway (`http://localhost`), come quelle del
front-end: sessione di login, JWT verso i servizi, CSRF. La stessa storia fatta
dalle pagine del front-end è nella "Demo completa" di
[`k8s/README.md`](../k8s/README.md#demo-completa); qui si vedono le richieste
REST una per una.

## Prerequisiti

- Cluster avviato e applicato come in [`k8s/README.md`](../k8s/README.md),
  con le immagini `edizioni:0.3`, `ricette:0.2`, `gateway:0.3` e `frontend:0.1`.
- `minikube tunnel` aperto in una PowerShell **da amministratore** (lasciarla aperta).
- Un account con ruolo **DISTILLATORE**, cioè presente in `RUOLI_DISTILLATORE`
  nel `.env` da cui `k8s/crea-secret.ps1` ha creato il Secret `gateway-segreti`.

```powershell
kubectl get pods -n ginvault          # 18 pod Running: 2/2 i servizi e il front-end (2 frontend, 2 ricette, 2 notifiche), 1/1 i DB e kafka-0
curl.exe -i http://localhost/api/ricette/health
```

Il secondo comando risponde `200` con `{"status":"UP"}` e un header
`Set-Cookie: XSRF-TOKEN=...`: è il token CSRF (vedi sotto).

## 1. Login e console del browser

1. Apri `http://localhost` (Chrome o Edge) → **Accedi** → **Accedi con GitHub**:
   è l'account che ha il ruolo DISTILLATORE.
2. Dopo il login il gateway ti riporta sulla home del front-end. Apri
   `http://localhost/auth/me` e controlla che `ruoli` contenga `DISTILLATORE`.
3. Apri gli strumenti per sviluppatori con **F12**, poi la scheda **Console**. La
   prima volta che incolli del codice, Chrome ed Edge chiedono di scrivere
   `allow pasting`.

Incolla questa funzione. Fa quello che farà il front-end:

- legge il cookie `XSRF-TOKEN` e lo rimanda nell'header `X-XSRF-TOKEN`; senza,
  il gateway risponde 403 a ogni POST;
- il cookie di sessione `SESSION` lo manda il browser da solo;
- stampa lo stato HTTP, gli header `Location` e `Retry-After` e il corpo.

```js
const xsrf = () => document.cookie.split('; ')
  .find(c => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length);

async function api(metodo, percorso, corpo) {
  if (metodo !== 'GET' && !xsrf()) await fetch('/auth/me');   // fa arrivare il cookie
  const risposta = await fetch(percorso, {
    method: metodo,
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf() ?? '' },
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
  });
  const testo = await risposta.text();
  let dati = testo;
  try { dati = JSON.parse(testo); } catch { /* non è JSON */ }
  const header = ['Location', 'Retry-After']
    .filter(h => risposta.headers.has(h))
    .map(h => `${h}: ${risposta.headers.get(h)}`).join('  ');
  console.log(`${metodo} ${percorso} → ${risposta.status}  ${header}`, dati);
  return dati;
}

await api('GET', '/auth/me');
```

## 2. Ricette: tre prove, il panel sceglie la seconda

La botanica di stagione è la lavanda. Le tre prove cambiano soprattutto i suoi
grammi per bottiglia: 18, 20 e 22.

```js
const f = await api('POST', '/api/ricette/formulazioni', {
  nome: 'Gin alla lavanda 2026',
  botanicaDiStagione: 'lavanda',
  dosi: [
    { botanica: 'ginepro', grammiPerBottiglia: 25 },
    { botanica: 'lavanda', grammiPerBottiglia: 18 },
    { botanica: 'coriandolo', grammiPerBottiglia: 6 },
  ],
});

await api('POST', `/api/ricette/formulazioni/${f.id}/versioni`, { dosi: [
  { botanica: 'ginepro', grammiPerBottiglia: 25 },
  { botanica: 'lavanda', grammiPerBottiglia: 20 },
  { botanica: 'coriandolo', grammiPerBottiglia: 5 },
] });

await api('POST', `/api/ricette/formulazioni/${f.id}/versioni`, { dosi: [
  { botanica: 'ginepro', grammiPerBottiglia: 24 },
  { botanica: 'lavanda', grammiPerBottiglia: 22 },
  { botanica: 'coriandolo', grammiPerBottiglia: 5 },
] });

await api('POST', `/api/ricette/formulazioni/${f.id}/scelta`, { versione: 2 });
```

Atteso:

- la prima POST risponde **201**. La `Location` è
  `http://localhost/api/ricette/formulazioni/<id>`, cioè l'indirizzo pubblico
  con il prefisso del gateway, non `http://ricette:8082/...`. Lo stato è
  `IN_PROVA`, con una prova in `IN_ASSAGGIO`;
- le due `/versioni` rispondono **201**: le prove sono 3, le precedenti restano intatte;
- `/scelta` risponde **200** con stato `SCELTA`, `versioneScelta: 2`, la prova 2
  `SCELTA` e la 1 e la 3 `ARCHIVIATA`.

## 3. Edizioni: 12 kg di lavanda → 600 bottiglie

```js
const e = await api('POST', '/api/edizioni/edizioni', {
  formulazione: { id: f.id, versione: 2 },
  botanicaDisponibileKg: 12,
  quote: { SOCI: 300, BAR: 150, ONLINE: 150 },
});

await api('GET', `/api/ricette/formulazioni/${f.id}`);
```

Atteso:

- **201**, `Location: http://localhost/api/edizioni/edizioni/<id>`;
- `tiratura: 600`, perché 12 000 g / 20 g per bottiglia (versione 2) = 600, che
  è anche il massimo;
- `grammiPerBottiglia: 20` e `botanicaDiStagione: "lavanda"` copiati
  nell'edizione;
- in Ricette la formulazione ora è `USATA`, con `edizioneId` uguale a `e.id`.

Cosa è successo dietro (vedi il README principale, "Edizioni chiama Ricette"):
Edizioni ha fatto `GET /formulazioni/{id}` a Ricette con il JWT di Marco, ha
controllato le regole di dominio, poi `POST /formulazioni/{id}/utilizzo`, e solo
alla fine ha salvato.

Per vedere gli errori, rimanda la stessa richiesta (freccia su nella console):
risponde **409** "Formulazione già usata". Con `versione: 1` risponde **422**,
perché la versione 1 non è quella scelta dal panel. Con quote che non sommano a
600 risponde ancora **422**.

## 4. Ricalcolo: 10,8 kg → 540 bottiglie

```js
await api('POST', `/api/edizioni/edizioni/${e.id}/ricalcolo`, { botanicaDisponibileKg: 10.8 });
```

Atteso: **200** con `tiratura: 540` e `quote: { SOCI: 300, BAR: 150, ONLINE: 90 }`.
Le 60 bottiglie in meno escono da ONLINE; BAR si tocca solo dopo, SOCI mai.

Il ricalcolo non chiama Ricette: ha già tutto quello che serve. In PowerShell:

```powershell
kubectl logs deployment/edizioni -n ginvault | Select-String TiraturaRidotta
```

Mostra una riga `Evento TiraturaRidotta <eventId> pubblicato al tentativo 1`:
l'evento è passato dall'outbox al topic `edizioni.eventi` (lo leggerà
Notifiche, nella storia di Giulia). Il messaggio, con la tiratura da 600 a
540, le nuove quote e la causa `ricalcolo manuale del distillatore`:

```powershell
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c "select messaggio from outbox where tipo = 'TiraturaRidotta' order by creato_il desc limit 1"
```

Nella storia di Elena lo stesso ricalcolo arriva da solo, da Produzione
(`ResaRicalcolata`), senza chiamare l'endpoint.

## 5. Ricette spento → 503

Prepara prima una seconda formulazione già scelta. La lasciamo pronta per
dimostrare che il 503 non la "brucia":

```js
const f2 = await api('POST', '/api/ricette/formulazioni', {
  nome: "Gin d'inverno 2026",
  botanicaDiStagione: 'arancia amara',
  dosi: [
    { botanica: 'ginepro', grammiPerBottiglia: 25 },
    { botanica: 'arancia amara', grammiPerBottiglia: 15 },
  ],
});
await api('POST', `/api/ricette/formulazioni/${f2.id}/scelta`, { versione: 1 });
```

Spegni Ricette (PowerShell). Il Deployment resta, ma senza pod. Il Service
`ricette` esiste ancora ma non ha più indirizzi dietro:

```powershell
kubectl scale deployment/ricette -n ginvault --replicas=0
kubectl get pods -n ginvault -l app.kubernetes.io/name=ricette    # nessun pod (aspetta qualche secondo)
kubectl get endpointslices -n ginvault -l kubernetes.io/service-name=ricette   # ENDPOINTS <unset>
kubectl get deployment ricette -n ginvault                       # READY 0/0
```

Dashboard: **Workloads → Deployments → ricette** (0/0 pod).

Di nuovo nella console:

```js
const nuovaEdizione = {
  formulazione: { id: f2.id, versione: 1 },
  botanicaDisponibileKg: 9,
  quote: { SOCI: 300, BAR: 150, ONLINE: 150 },
};
await api('POST', '/api/edizioni/edizioni', nuovaEdizione);

await api('GET', `/api/edizioni/edizioni/${e.id}`);
```

Atteso:

- la POST risponde **503** con `Retry-After: 5`, titolo "Ricette non disponibile"
  e un messaggio chiaro ("Ricette ha risposto in modo inatteso alla lettura
  della formulazione ...: l'edizione non è stata creata. Riprova tra poco."),
  senza stack trace. Arriva **subito**: il sidecar Istio di Edizioni sa da
  istiod che il Service non ha pod e risponde da solo 503 `no healthy upstream`,
  che Edizioni traduce nel suo 503. Lo si vede anche senza login:
  `kubectl exec -n ginvault deploy/edizioni -c edizioni -- wget -qO- -T 5 http://ricette:8082/health`
  risponde in pochi millisecondi `server returned error: HTTP/1.1 503 Service Unavailable`.
  Senza Istio (in Compose) il Service senza pod non rifiuta la connessione: si
  aspettano i **2 secondi** del timeout di Edizioni, e il messaggio è "Ricette
  non risponde ... più lento di 2 secondi" (vedi
  [k8s/README.md](../k8s/README.md#istio-circuit-breaker-su-edizioni--ricette));
- la GET dell'edizione di prima risponde **200**: Edizioni legge solo dal suo
  database, quindi anche con Ricette spento mostra le sue edizioni.

Il database di Ricette è ancora acceso, perché abbiamo spento solo il servizio.
Si può quindi controllare che non sia rimasto niente a metà:

```powershell
# Edizioni: c'è ancora una sola edizione
kubectl exec -n ginvault deployment/edizioni-db -- psql -U edizioni -d edizioni -c "select id, formulazione_versione, botanica_disponibile_kg, tiratura from edizione"
# Ricette: "Gin d'inverno 2026" è ancora SCELTA, senza edizione
kubectl exec -n ginvault deployment/ricette-db -- psql -U ricette -d ricette -c "select nome, stato, versione_scelta, edizione_id from formulazione"
```

Riaccendi Ricette e riprova la stessa richiesta:

```powershell
kubectl scale deployment/ricette -n ginvault --replicas=2
kubectl rollout status deployment/ricette -n ginvault    # aspetta che i due pod siano Ready
```

```js
const e2 = await api('POST', '/api/edizioni/edizioni', nuovaEdizione);
```

Atteso: **201** con `tiratura: 600` (9000 g / 15 g = 600), e `f2` diventa `USATA`.

## 6. Lo stato finale nei database

```powershell
kubectl exec -n ginvault deployment/ricette-db -- psql -U ricette -d ricette -c "select nome, stato, versione_scelta, edizione_id from formulazione"
kubectl exec -n ginvault deployment/ricette-db -- psql -U ricette -d ricette -c "select f.nome, p.versione, d.grammi_per_bottiglia as grammi_botanica_di_stagione from formulazione f join prova_di_assaggio p on p.formulazione_id = f.id join dose d on d.prova_id = p.id where d.botanica = f.botanica_di_stagione order by f.nome, p.versione"
kubectl exec -n ginvault deployment/edizioni-db -- psql -U edizioni -d edizioni -c "select id, formulazione_versione, botanica_disponibile_kg, tiratura from edizione"
kubectl exec -n ginvault deployment/edizioni-db -- psql -U edizioni -d edizioni -c "select e.tiratura, q.canale, q.bottiglie, q.prenotate from edizione e join quota q on q.edizione_id = e.id order by e.creata_il, q.canale"
```

`kubectl exec` entra nel container di Postgres senza passare dalla rete dei pod,
quindi le NetworkPolicy non lo bloccano.

| Cosa | Atteso |
|---|---|
| Formulazioni | due, entrambe `USATA` (lavanda con versione scelta 2, arancia amara con 1), ognuna con il suo `edizione_id` |
| Prove della lavanda | versioni 1, 2, 3 con 18, 20, 22 g |
| Edizioni | due: lavanda versione 2, 10,800 kg, tiratura 540; arancia amara versione 1, 9,000 kg, tiratura 600 |
| Quote della lavanda | SOCI 300, BAR 150, ONLINE 90 |
| Quote dell'arancia amara | SOCI 300, BAR 150, ONLINE 150 |

## Riepilogo dei casi

| Richiesta | Risposta |
|---|---|
| Edizione con formulazione scelta e quote giuste | 201 + `Location` pubblica |
| Stessa formulazione una seconda volta | 409 Formulazione già usata |
| Versione diversa da quella scelta, quote che non sommano alla tiratura | 422 |
| Ricalcolo da 12 a 10,8 kg | 200, ONLINE 150 → 90, evento `TiraturaRidotta` su `edizioni.eventi` |
| Edizione con Ricette spento | 503 + `Retry-After`, niente scritto in nessuno dei due database |
| POST senza `X-XSRF-TOKEN` (ad esempio da `curl.exe` senza header) | 403 dal gateway |
| Account senza ruolo DISTILLATORE | 403 dal servizio |

## Ripetere la demo

Basta rifare i passi dalla console: ogni giro crea formulazioni ed edizioni
nuove, con id nuovi. Se la sessione scade, le chiamate rispondono 401: rifai il
login da `http://localhost/login` e reincolla la funzione `api`.
