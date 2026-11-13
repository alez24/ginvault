# Demo: Istio, mTLS e circuit breaker

Che cosa mostra, su Minikube e senza toccare il codice:

- i sidecar Envoy accanto ai microservizi;
- l'mTLS tra i sidecar;
- il circuit breaker sulle chiamate da Edizioni a Ricette.

Per il circuit breaker:

- **un pod guasto:** il retry lo nasconde, l'outlier detection lo esclude;
- **senza circuit breaker:** ogni chiamata aspetta il timeout, poi 503;
- **con il circuito aperto:** 503 subito, e Ricette non riceve più chiamate;
- **dopo 30 s** il circuito si richiude da solo;
- **la coda piena** (`upstream_rq_pending_overflow`);
- **perché il `fault` di un VirtualService non basta** per aprire il circuito.

La configurazione è spiegata in [`k8s/README.md`](../k8s/README.md), nelle
sezioni [Istio: istiod e i sidecar](../k8s/README.md#istio-istiod-e-i-sidecar),
[mTLS](../k8s/README.md#istio-mtls-tra-i-microservizi) e
[circuit breaker](../k8s/README.md#istio-circuit-breaker-su-edizioni--ricette).
I comandi sono per PowerShell, nella cartella `ginvault`. Non serve il login:
le chiamate partono dal pod di edizioni verso `/health` di Ricette, che è
pubblico. Ci vogliono circa 15 minuti.

## 0. Prima di cominciare

Il cluster con GinVault e Istio, come in
[Avvio da cluster vuoto](../k8s/README.md#avvio-da-cluster-vuoto), compreso
`kubectl apply -k k8s/istio/`.

```powershell
Set-Alias istioctl "$HOME\istio\1.31.4\istioctl.exe"   # per questa finestra
kubectl get pods -n istio-system                        # istiod 1/1 Running
kubectl get pods -n ginvault                            # 18 pod: 2/2 i servizi e il front-end, 1/1 i DB e kafka-0
kubectl get destinationrule,virtualservice,envoyfilter -n ginvault   # ricette e ricette; nessun EnvoyFilter
```

**Il client della demo**: un container effimero con `curl` nel pod di
edizioni. È nello stesso pod dell'applicazione, quindi le sue richieste
passano dal sidecar di edizioni, con le stesse regole delle chiamate di
edizioni a Ricette (la route `da-edizioni` del VirtualService). Il container
resta acceso un'ora.

```powershell
$e = kubectl get pod -n ginvault -l app.kubernetes.io/name=edizioni -o jsonpath='{.items[0].metadata.name}'
$c = "curl-$(Get-Random -Maximum 100000)"
kubectl debug -n ginvault $e --image=curlimages/curl:8.11.1 --container=$c --quiet -- sleep 3600 | Out-Null
do { Start-Sleep -Seconds 1; kubectl exec -n ginvault $e -c $c -- true 2>$null } until ($LASTEXITCODE -eq 0)
```

Quattro funzioni per questa finestra di PowerShell:

```powershell
# Dieci chiamate a Ricette, una ogni mezzo secondo: ora, codice, durata, inizio della risposta
function Chiamate { kubectl exec -n ginvault $e -c $c -- sh -c 'for i in 1 2 3 4 5 6 7 8 9 10; do r=$(curl -s -m 5 -o /tmp/r -w %{http_code}:%{time_total} http://ricette:8082/health); echo $(date +%T) ${r%:*} ${r#*:}s $(head -c 30 /tmp/r); sleep 0.5; done' }
# Le statistiche del sidecar di edizioni verso Ricette (contatori dall'avvio: si guarda come cambiano)
function Stato { kubectl exec -n ginvault $e -c istio-proxy -- pilot-agent request GET stats 2>$null | Select-String 'ricette.*\.(outlier_detection\.ejections_active|upstream_rq_total|upstream_rq_retry|upstream_rq_per_try_timeout|upstream_rq_pending_overflow|upstream_cx_none_healthy):' | ForEach-Object { $_.Line -replace '^.*local;\.', '' } }
# Quante richieste di edizioni ha ricevuto ogni pod di Ricette (le conta il SUO sidecar)
function Ricevute { foreach ($p in (kubectl get pod -n ginvault -l app.kubernetes.io/name=ricette -o name)) { $n = 0; kubectl exec -n ginvault $p -c istio-proxy -- pilot-agent request GET stats/prometheus 2>$null | Select-String 'istio_requests_total\{reporter="destination".*source_workload="edizioni"' | ForEach-Object { if ($_.Line -match '\} (\d+)$') { $n += [int]$Matches[1] } }; "$p ha ricevuto da edizioni $n richieste" } }
# I due pod di Ricette visti dal sidecar di edizioni: OUTLIER CHECK OK, o FAILED se escluso
function Endpoint { istioctl proxy-config endpoint deploy/edizioni -n ginvault --cluster "outbound|8082||ricette.ginvault.svc.cluster.local" }
```

`Chiamate` deve dare dieci `200`, `Endpoint` due pod `OK`.

## 1. I sidecar e l'mTLS

```powershell
istioctl proxy-status                                   # 11 sidecar sincronizzati con istiod
kubectl get pod -n ginvault $e -o jsonpath='{.spec.initContainers[*].name}'   # istio-init istio-proxy
$r = kubectl get pod -n ginvault -l app.kubernetes.io/name=ricette -o jsonpath='{.items[0].metadata.name}'
istioctl x describe pod $r -n ginvault                  # "Workload mTLS mode: STRICT"
# In chiaro, da un pod senza sidecar: rifiutato ("Connection reset by peer", RIFIUTATA)
kubectl run prova-chiaro -n ginvault --rm -i --restart=Never --image=busybox:1.36 --labels=sidecar.istio.io/inject=false -- sh -c 'wget -q -T 3 -O /dev/null http://ricette:8082/health && echo RISPOSTA || echo RIFIUTATA'
# Le richieste di edizioni arrivano a Ricette in mutual_tls, con il certificato del mesh
# (spiffe://.../sa/default: tutti i pod usano la ServiceAccount default)
kubectl exec -n ginvault deploy/ricette -c istio-proxy -- pilot-agent request GET stats/prometheus 2>$null | Select-String 'istio_requests_total\{reporter="destination".*source_workload="edizioni"' | Select-Object -First 1 | ForEach-Object { if ($_.Line -match 'source_principal="([^"]*)".*connection_security_policy="([^"]*)"') { "$($Matches[2]) da $($Matches[1])" } }
```

La prova con i pacchetti (tcpdump nel pod di Ricette: da edizioni solo byte
cifrati) è in [k8s/README.md](../k8s/README.md#istio-mtls-tra-i-microservizi).

## 2. La configurazione del circuit breaker

In [`k8s/istio/circuit-breaker-ricette.yaml`](../k8s/istio/circuit-breaker-ricette.yaml):

- **DestinationRule:**
  - al massimo 10 connessioni e 5 richieste in coda;
  - 3 errori 5xx di fila e il pod è escluso per 30 s, anche tutti e due.
- **VirtualService**, solo per le chiamate di edizioni:
  - 0,8 s a tentativo;
  - un retry su un altro pod;
  - 1,8 s in tutto, dentro i 2 s di edizioni.

```powershell
kubectl get destinationrule ricette -n ginvault -o yaml
istioctl proxy-config cluster deploy/edizioni -n ginvault --fqdn ricette.ginvault.svc.cluster.local -o json | Select-String 'consecutive5xx|baseEjectionTime|maxEjectionPercent|maxConnections|maxPendingRequests|connectTimeout'
istioctl proxy-config route deploy/edizioni -n ginvault --name 8082 -o json | Select-String '"name": "da-edizioni"|"timeout"|numRetries|perTryTimeout|retryOn'
Endpoint
```

Chi decide che cosa (timeout dell'applicazione e timeout del mesh) è nella
tabella di [k8s/README.md](../k8s/README.md#istio-circuit-breaker-su-edizioni--ricette).

## Come si rompe Ricette senza toccare il codice

I guasti stanno in [`k8s/istio/demo/`](../k8s/istio/demo/). Non sono in nessun
kustomization: si applicano a mano e si tolgono.

| File | Che cosa fa |
|---|---|
| `guasto-503.yaml` | Il sidecar di Ricette risponde 503 a ogni richiesta |
| `guasto-lento.yaml` | Il sidecar di Ricette trattiene ogni richiesta 1,5 s |
| `guasto-un-po-lento.yaml` | 0,5 s: nessun timeout, serve a riempire la coda |
| `senza-outlier-detection.yaml` | La DestinationRule senza circuit breaker |
| `vs-fault-abort.yaml`, `vs-fault-delay.yaml` | Il `fault` di Istio nel VirtualService, dalla parte di edizioni (sezione 8) |

I tre `guasto-*` sono un **EnvoyFilter**: mettono nel sidecar di Ricette, in
entrata, il filtro `fault` di Envoy, lo stesso che Istio usa per `fault.abort`
e `fault.delay`.
- Valgono solo per i pod di Ricette con la label `ginvault/guasto=si`, che si
  mette e si toglie al volo con `kubectl label`.
- Hanno tutti lo stesso nome, `guasto-ricette`: applicarne uno sostituisce
  l'altro.
- Le probe non passano da Envoy: i pod restano `2/2 Ready` anche con il
  guasto.
- `kubectl apply` stampa un avviso: EnvoyFilter è un'API di basso livello.
  Qui va bene, perché è una demo.

Il guasto arriva ai sidecar in pochi secondi. Le prime una o due richieste
possono passare ancora, sulle connessioni già aperte.

## 3. Un pod guasto: il retry lo nasconde, l'outlier detection lo esclude

```powershell
$a = kubectl get pod -n ginvault -l app.kubernetes.io/name=ricette -o jsonpath='{.items[0].metadata.name}'
kubectl label pod -n ginvault $a ginvault/guasto=si
kubectl apply -f k8s/istio/demo/guasto-503.yaml
Chiamate      # tutte 200
Stato         # upstream_rq_retry cresciuto di 3, ejections_active: 1
Endpoint      # il pod $a FAILED, l'altro OK
```

Che cosa è successo:
- le prime 3 richieste che toccano il pod guasto ricevono 503;
- il sidecar di edizioni le riprova sull'altro pod (`previous_hosts`), e chi
  chiama non vede errori;
- al terzo errore di fila il pod è escluso, e per 30 s il sidecar non lo
  sceglie più.

Si toglie il guasto e si aspetta che il pod torni (al massimo 30 s):

```powershell
kubectl delete envoyfilter guasto-ricette -n ginvault
kubectl label pod -n ginvault -l app.kubernetes.io/name=ricette ginvault/guasto-
do { Start-Sleep -Seconds 2 } until (((Endpoint) -match ' OK ').Count -eq 2)
```

Prima di ogni scenario conviene aspettare i due `OK`: un pod escluso più volte
di fila resta fuori di più (30 s, poi 60, poi 90).

## 4. Senza circuit breaker: attesa e poi 503

La DestinationRule senza `outlierDetection` (timeout e retry restano), e
Ricette lento su tutti e due i pod:

```powershell
kubectl apply -f k8s/istio/demo/senza-outlier-detection.yaml
kubectl label pod -n ginvault -l app.kubernetes.io/name=ricette ginvault/guasto=si
kubectl apply -f k8s/istio/demo/guasto-lento.yaml
Ricevute
Chiamate      # tutte 504 "upstream request timeout" dopo circa 1,6 s
Ricevute      # 20 richieste in più: 2 tentativi per chiamata, nessuna risparmiata
```

Ogni chiamata prova un pod per 0,8 s, poi l'altro per 0,8 s, poi il sidecar di
edizioni risponde 504. Marco, dal back-office, avrebbe dopo 1,6 s il 503
"Ricette non disponibile" di edizioni, e così a ogni tentativo. Ricette, già
lento, riceve tutte le chiamate e i loro retry.

## 5. Con il circuit breaker: 503 subito, Ricette non riceve chiamate

Il guasto resta. Si rimette la DestinationRule vera:

```powershell
kubectl apply -k k8s/istio/
Ricevute
Chiamate      # 3-4 volte 504 dopo 1,6 s, poi 503 "no healthy upstream" in 1-2 ms
Ricevute      # cresciuti solo per le prime chiamate, poi fermi
Chiamate      # di nuovo dieci 503 immediati
Ricevute      # fermi: Ricette non riceve niente
Stato         # ejections_active: 2, upstream_cx_none_healthy +10, upstream_rq_total fermo
Endpoint      # tutti e due FAILED
```

Che cosa è successo:
- **apertura:** ogni tentativo scaduto conta come un errore 5xx del suo pod.
  Al terzo di fila il pod è escluso, ed esclusi tutti e due il circuito è
  aperto;
- **un secondo di ritardo:** Envoy raccoglie gli aggiornamenti dello stato dei
  pod in finestre di 1 s, per questo a volte passa una chiamata lenta in più;
- **circuito aperto:** il sidecar risponde da solo `no healthy upstream`, senza
  aspettare e senza chiamare Ricette. Marco avrebbe subito il 503 di edizioni.

Stessa cosa con gli errori invece della lentezza:
`kubectl apply -f k8s/istio/demo/guasto-503.yaml` (sostituisce il guasto
lento). Le prime risposte sono `fault filter abort`, che arriva davvero da un
pod di Ricette, poi `no healthy upstream`.

## 6. Dopo 30 secondi il circuito si richiude da solo

Si toglie il guasto e si guarda, una volta al secondo, finché torna il 200
(si stampa solo quando la risposta cambia):

```powershell
kubectl delete envoyfilter guasto-ricette -n ginvault
kubectl label pod -n ginvault -l app.kubernetes.io/name=ricette ginvault/guasto-
kubectl exec -n ginvault $e -c $c -- sh -c 'p=; while true; do r=$(curl -s -m 3 -o /tmp/r -w %{http_code} http://ricette:8082/health); [ x$r != x$p ] && echo $(date +%T) $r $(head -c 30 /tmp/r); p=$r; [ $r = 200 ] && break; sleep 1; done'
Endpoint      # tutti e due OK
```

Il 200 torna 30 s dopo l'esclusione, non quando si toglie il guasto:
- fino ad allora il sidecar non sa che Ricette è guarito, perché non lo
  chiama;
- allo scadere dei 30 s riammette i pod, e la prima chiamata va a buon fine.

Se il guasto è ancora lì quando i pod tornano, bastano 3 errori per
riescluderli, questa volta per 60 s. Per vederlo, si lascia il guasto e si
lancia lo stesso ciclo: dopo 30 s compare per un attimo la risposta di Ricette
(`fault filter abort` o `upstream request timeout`), poi di nuovo `no healthy
upstream`.

## 7. La coda piena: `upstream_rq_pending_overflow`

Ricette risponde in 0,5 s, meno degli 0,8 s di un tentativo: nessun errore e
nessuna esclusione. Arrivano però 25 richieste insieme:

```powershell
do { Start-Sleep -Seconds 2 } until (((Endpoint) -match ' OK ').Count -eq 2)
kubectl label pod -n ginvault -l app.kubernetes.io/name=ricette ginvault/guasto=si
kubectl apply -f k8s/istio/demo/guasto-un-po-lento.yaml
Stato
kubectl exec -n ginvault $e -c $c -- sh -c 'rm -f /tmp/c*; for i in $(seq 1 25); do curl -s -o /dev/null -w %{http_code} http://ricette:8082/health > /tmp/c$i & done; wait; for f in /tmp/c*; do cat $f; echo; done | sort | uniq -c'
#      15 200
#      10 503
Stato         # upstream_rq_pending_overflow +10, ejections_active: 0
kubectl delete envoyfilter guasto-ricette -n ginvault
kubectl label pod -n ginvault -l app.kubernetes.io/name=ricette ginvault/guasto-
```

Il sidecar di edizioni smista così le 25 richieste:
- 10 sulle 10 connessioni del `connectionPool`;
- 5 in coda, servite appena una connessione si libera;
- le altre 10 ricevono subito 503 (`upstream_rq_pending_overflow`), senza
  arrivare a Ricette.

È una paratia: un picco non si scarica su un servizio lento. I limiti sono
bassi apposta per la demo: anche con Ricette veloce, 25 richieste nello stesso
istante ne fanno traboccare qualcuna. Non vengono riprovate, e non contano per
l'outlier detection.

## 8. Perché non basta il `fault` del VirtualService

Istio ha `fault.abort` e `fault.delay` nel VirtualService. Agiscono nel
sidecar di **chi chiama**, prima che scelga un pod.

```powershell
kubectl apply -f k8s/istio/demo/vs-fault-abort.yaml
Ricevute
Chiamate      # dieci 503 "fault filter abort" in 1-2 ms
Ricevute      # fermi: Ricette non è mai stato chiamato
Stato         # upstream_rq_total fermo, ejections_active: 0
Endpoint      # tutti e due OK: nessuno escluso
```

Il 503 è immediato, ma non è il circuit breaker. L'errore lo inventa il
sidecar di edizioni, nessun pod ha sbagliato, e l'outlier detection non ha
niente da contare. Per questo i guasti della demo stanno dalla parte di
Ricette.

```powershell
kubectl apply -f k8s/istio/demo/vs-fault-delay.yaml
kubectl exec -n ginvault $e -c $c -- sh -c 'r=$(curl -s -o /dev/null -w %{http_code}:%{time_total} http://ricette:8082/health); echo senza limite: ${r%:*} dopo ${r#*:}s; r=$(curl -s -m 2 -o /dev/null -w %{http_code}:%{time_total} http://ricette:8082/health); echo con 2 s come edizioni: ${r%:*} dopo ${r#*:}s'
# senza limite: 200 dopo 3.0s
# con 2 s come edizioni: 000 dopo 2.0s
kubectl apply -k k8s/istio/      # il VirtualService vero, senza fault
```

Il ritardo di 3 s passa intero, anche se la route ha un timeout di 1,8 s: il
sidecar trattiene la richiesta *prima* di inoltrarla, e il timeout parte solo
dopo. A fermare l'attesa è il timeout dell'applicazione:
- edizioni smette di aspettare dopo 2 s (il `curl -m 2` fa lo stesso);
- risponde a Marco 503 "Ricette non risponde… più lento di 2 secondi".

È l'esempio di chi decide che cosa: il mesh protegge le chiamate verso i pod,
ma quanto può aspettare l'utente lo decide l'applicazione.

## 9. Dal browser, con il login (facoltativo)

Con un guasto attivo, Marco nel back-office prova a creare un'edizione
([demo di Marco](demo-storia-marco.md)):
- **senza circuit breaker** (sezione 4): l'errore "momentaneamente fermo" dopo
  circa 1,6 s, a ogni tentativo;
- **con il circuito aperto** (sezione 5): l'errore subito;
- **tolto il guasto**, dopo 30 s l'edizione si crea.

Nel log di edizioni c'è la causa:

```powershell
kubectl logs -n ginvault deploy/edizioni -c edizioni --tail=20 | Select-String 'Ricette'
# ...Causa: ...HttpServerErrorException$GatewayTimeout: 504 Gateway Timeout: "upstream request timeout"
# ...Causa: ...HttpServerErrorException$ServiceUnavailable: 503 Service Unavailable: "no healthy upstream"
```

Anche le pagine delle formulazioni non rispondono: il gateway chiama Ricette, e
il guasto è sul sidecar di Ricette, quindi vale per tutti. Questa parte non è
provata senza login: i messaggi sono quelli previsti dal codice
(`CatalogoRicetteRest`).

## 10. Pulizia

```powershell
kubectl delete envoyfilter guasto-ricette -n ginvault --ignore-not-found
kubectl label pod -n ginvault -l app.kubernetes.io/name=ricette ginvault/guasto-
kubectl apply -k k8s/istio/                  # DestinationRule e VirtualService veri
kubectl get destinationrule,virtualservice,envoyfilter -n ginvault
```

Il container effimero si ferma da solo dopo un'ora. Resta, terminato, nella
spec del pod di edizioni finché il pod non si ricrea
(`kubectl rollout restart deployment/edizioni -n ginvault`).

## Riepilogo

| Scenario | Configurazione | Il sidecar di edizioni risponde | Ricette chiamato? |
|---|---|---|---|
| Normale | Vera | 200 da uno dei due pod | Sì |
| Un pod che sbaglia | Vera, `guasto-503` su un pod | 200: retry sull'altro, poi il pod guasto escluso | Solo il pod sano |
| Ricette lento, senza circuit breaker | `senza-outlier-detection`, `guasto-lento` | 504 dopo 1,6 s, sempre | Sì, 2 volte a chiamata |
| Ricette lento, con il circuit breaker | Vera, `guasto-lento` | 504 per 3-4 chiamate, poi 503 `no healthy upstream` in 1 ms | Solo all'inizio |
| Dopo 30 s, guasto tolto | Vera | 200 | Sì |
| 25 richieste insieme | Vera, `guasto-un-po-lento` | 15 volte 200, 10 volte 503 subito | Solo per le 15 |
| `fault.abort` nel VirtualService | `vs-fault-abort` | 503 subito | No, ma nessun pod escluso |
| `fault.delay` nel VirtualService | `vs-fault-delay` | 200 dopo 3 s: lo ferma solo edizioni (2 s) | Sì, in ritardo |

Le statistiche di Envoy usate, accese solo sul sidecar di edizioni
(l'annotazione `proxy.istio.io/config` in `k8s/edizioni.yaml`):

| Statistica | Che cosa conta |
|---|---|
| `outlier_detection.ejections_active` | Pod esclusi adesso |
| `outlier_detection.ejections_enforced_consecutive_5xx` | Esclusioni per errori 5xx di fila, da sempre |
| `upstream_cx_none_healthy` | Risposte `no healthy upstream`: circuito aperto, o nessun pod |
| `upstream_rq_total` | Richieste partite davvero verso Ricette, retry compresi |
| `upstream_rq_retry` | Retry |
| `upstream_rq_per_try_timeout` | Tentativi scaduti (0,8 s) |
| `upstream_rq_pending_overflow` | Richieste rifiutate perché la coda era piena |
| `membership_healthy` | Pod non esclusi |

**Verificato il 12 novembre**, con Ricette a due repliche. I risultati sono
quelli scritti sopra:
- **un pod guasto:** 12 risposte 200 su 12, 3 retry, il pod guasto escluso;
- **senza circuit breaker:** 8 risposte 504 su 8, tutte dopo circa 1,62 s;
  Ricette riceve 16 chiamate;
- **con il circuit breaker:** 4 risposte 504, poi `no healthy upstream` in
  1 ms; con altre 20 chiamate i contatori di Ricette restano fermi;
- **richiusura:** di nuovo 200 30 s dopo l'esclusione;
- **coda:** 15 risposte 200 e 10 risposte 503, `upstream_rq_pending_overflow`
  a +10;
- **`fault` del VirtualService:** 10 risposte 503 con `upstream_rq_total`
  fermo e nessuna esclusione; 3 s di ritardo e poi 200;
- **pod di Ricette:** sempre `2/2 Ready`, 0 riavvii.
