# GinVault su Kubernetes (Minikube)

Tutto GinVault su Minikube: front-end, gateway, i sei microservizi (edizioni,
ricette, produzione, magazzino, pagamenti, notifiche), ciascuno con il suo
database, esposti con un Ingress, e Kafka per gli eventi tra i servizi. Delle
NetworkPolicy isolano i database, il front-end, il gateway e Kafka. Istio (solo
istiod) mette un sidecar Envoy accanto a ogni microservizio e al front-end, non
ai database, a Kafka e ai Job: vedi [Istio](#istio-istiod-e-i-sidecar). Tra i
sidecar il traffico è cifrato con mTLS: vedi [mTLS](#istio-mtls-tra-i-microservizi).
Sulle chiamate da Edizioni a Ricette (due repliche) il sidecar applica timeout,
un retry e il circuit breaker: vedi [circuit breaker](#istio-circuit-breaker-su-edizioni--ricette).

```
browser
   │  http://localhost   (cookie SESSION; il JWT non arriva mai al browser)
   ▼
minikube tunnel              porta 127.0.0.1:80 del portatile → Ingress del cluster
   ▼
ingress-nginx-controller     namespace ingress-nginx: applica l'Ingress "ginvault"
   │                         (host localhost, smistamento per path)
   ├── / /soci /distilleria/... /assets/...
   │      → Service frontend :80  →  2 pod nginx    i file dell'app React
   │
   └── /api /auth /oauth2 /login /logout
          ▼
Service gateway :8080    →  pod gateway     login OAuth2, sessione, firma del JWT
   │  Authorization: Bearer <JWT>            ▲
   ▼                                         │ GET /.well-known/jwks.json
Service edizioni :8081   →  pod edizioni ────┘ (chiave pubblica per verificare il JWT)
   │  JDBC          │ REST + stesso JWT (validazione della formulazione, timeout 2 s)
   │                ▼
   │             Service ricette :8082  →  2 pod ricette  ── JDBC ──▶ Service ricette-db :5432
   ▼
Service edizioni-db :5432 → pod Postgres  →  PersistentVolumeClaim (1 GiB)

Service kafka :9092 (headless) → pod kafka-0 (StatefulSet) → PVC dati-kafka-0 (1 GiB)
   ▲  topic edizioni.eventi, magazzino.eventi, produzione.eventi, pagamenti.comandi,
   │  pagamenti.esiti e i dead letter produzione.dlt, edizioni.dlt, pagamenti.dlt,
   │  notifiche.dlt (Job kafka-topic)
   └─ i servizi che pubblicano o consumano eventi (edizioni, magazzino, produzione,
      pagamenti, notifiche)

Service pagamenti :8085  → pod pagamenti ── HTTPS ──▶ api.stripe.com (modalità test)
                              └─ JDBC ──▶ Service pagamenti-db :5432
Service notifiche :8086  → 2 pod notifiche ◀── WebSocket /api/notifiche/avvisi (via gateway)
                              └─ JDBC, LISTEN/NOTIFY ──▶ Service notifiche-db :5432

NetworkPolicy: frontend accetta solo l'Ingress Controller; gateway l'Ingress
Controller e i microservizi (JWKS); ogni database solo il suo servizio
(edizioni-db solo edizioni, ..., notifiche-db solo notifiche); kafka solo i pod
con la label ginvault/usa-kafka.

Istio: istiod nel namespace istio-system; un sidecar Envoy (istio-proxy) nei pod
dei microservizi e del front-end (2/2), nessuno nei DB, in Kafka e nel Job.
Tra due sidecar solo mTLS (STRICT); verso DB e Kafka TCP in chiaro, come prima;
dall'Ingress in chiaro sulla 8080 di frontend e gateway.
edizioni → ricette: timeout 1,8 s, un retry sull'altro pod, circuit breaker
(un pod con 3 errori di fila resta fuori 30 s).
```

Il gateway inoltra anche `/api/ricette/**` al Service `ricette` e
`/api/magazzino/**` al Service `magazzino` (:8084). Magazzino ha il suo
database, `magazzino-db`, e pubblica su `magazzino.eventi` dalla tabella outbox
(vedi [Transactional outbox con Kafka spento](#transactional-outbox-con-kafka-spento)).
Allo stesso modo `/api/produzione/**` va al Service `produzione` (:8083), con
il database `produzione-db`: consuma `edizioni.eventi` e `magazzino.eventi` e
pubblica su `produzione.eventi` (vedi [Consumer di produzione](#consumer-di-produzione)).
Edizioni, a sua volta, consuma `produzione.eventi` e ricalcola la tiratura
(vedi [Consumer di edizioni](#consumer-di-edizioni)). Per la storia di Giulia
`/api/pagamenti/**` va a `pagamenti` (:8085), che addebita con Stripe in
modalità test, e `/api/notifiche/**` a `notifiche` (:8086), anche per il
WebSocket degli avvisi (vedi [Pagamenti e Notifiche](#pagamenti-e-notifiche-storia-di-giulia)).

Pagine e API hanno la stessa origine, `http://localhost`: i cookie del gateway
valgono anche per le pagine e non serve CORS. Lo smistamento per path lo fa qui
l'Ingress, con Docker Compose nginx nel container del front-end e in sviluppo il
proxy di Vite.

## File

| File | Oggetti |
|---|---|
| `namespace.yaml` | Namespace `ginvault`, con la label `istio-injection=enabled` |
| `edizioni-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `edizioni.yaml` | ConfigMap, Deployment, Service |
| `ricette-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `ricette.yaml` | ConfigMap, Deployment (2 repliche), Service |
| `magazzino-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `magazzino.yaml` | ConfigMap, Deployment, Service (porte `http` 8084 e `grpc` 9084) |
| `produzione-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `produzione.yaml` | ConfigMap, Deployment, Service |
| `pagamenti-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `pagamenti.yaml` | ConfigMap, Deployment, Service (la chiave di Stripe dal Secret `pagamenti-stripe`) |
| `notifiche-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `notifiche.yaml` | ConfigMap, Deployment (2 repliche), Service |
| `networkpolicy-db.yaml` | Sei NetworkPolicy: ogni database accetta solo il suo servizio |
| `networkpolicy-frontend-gateway.yaml` | Due NetworkPolicy: il front-end accetta solo l'Ingress Controller, il gateway l'Ingress Controller e i microservizi |
| `networkpolicy-kafka.yaml` | NetworkPolicy: Kafka accetta solo i pod con la label `ginvault/usa-kafka: "true"` |
| `networkpolicy-magazzino.yaml` | NetworkPolicy: la porta gRPC di magazzino (9084) accetta solo produzione; la 8084 resta aperta |
| `kafka.yaml` | Service headless, StatefulSet (con il suo PVC), Job che crea i topic |
| `gateway.yaml` | ConfigMap, Deployment, Service |
| `frontend.yaml` | Deployment (2 repliche), Service |
| `ingress.yaml` | Ingress: `/api`, `/auth`, `/oauth2`, `/login`, `/logout` → gateway; tutto il resto → frontend |
| `kustomization.yaml` | Elenco dei file: `kubectl apply -k k8s/` li applica tutti |
| `crea-secret.ps1` | Secret `gateway-segreti` e `pagamenti-stripe` (dal `.env`), `edizioni-db`, `ricette-db`, `magazzino-db`, `produzione-db`, `pagamenti-db` e `notifiche-db` (password casuali) |
| `istio/installazione.yaml` | La configurazione di Istio per `istioctl install`: profilo minimal, risorse ridotte. Non è in nessun kustomization |
| `istio/sidecar.yaml` | Sidecar `default`: ogni Envoy conosce solo i Service di ginvault e istiod |
| `istio/peer-authentication.yaml` | mTLS `STRICT` tra i sidecar; la porta 8080 di frontend e gateway accetta anche l'Ingress in chiaro |
| `istio/circuit-breaker-ricette.yaml` | DestinationRule (connectionPool, outlierDetection) e VirtualService (timeout, un retry) per le chiamate a Ricette |
| `istio/kustomization.yaml` | Le risorse di Istio: `kubectl apply -k k8s/istio/`, dopo l'installazione |
| `istio/demo/` | I guasti per la demo del circuit breaker ([`docs/demo-istio.md`](../docs/demo-istio.md)): EnvoyFilter sul sidecar di Ricette, la DestinationRule senza outlier detection, il `fault` del VirtualService. Non sono in nessun kustomization |

I Secret non stanno in nessun file del repository: li crea lo script.

## Prerequisiti (una volta sola)

1. Docker Desktop avviato, Minikube ≥ 1.39, kubectl (va bene quello di Docker Desktop).
2. Il cluster. Conviene prima fermare lo stack Compose (`docker compose down`)
   per liberare RAM:
   ```powershell
   minikube start --driver=docker --cpus=4 --memory=8g
   minikube addons enable ingress
   minikube addons enable metrics-server
   ```
   8 GB pensando già a Istio: la memoria non si cambia senza ricreare il cluster.
3. Il file `.env` compilato (vedi il [README principale](../README.md)).
4. Redirect URI registrati sulle app OAuth:
   - Google: `http://localhost/login/oauth2/code/google`
   - GitHub: `http://localhost/login/oauth2/code/github`
5. `istioctl` 1.31.4, fuori dal repository. Istio 1.31 supporta Kubernetes da
   1.32 a 1.37 (il cluster è 1.37):
   ```powershell
   $v = '1.31.4'; $d = "$HOME\istio"
   New-Item -ItemType Directory -Force "$d\download", "$d\$v" | Out-Null
   curl.exe -sSL -o "$d\download\istioctl-$v-win-amd64.zip" "https://github.com/istio/istio/releases/download/$v/istioctl-$v-win-amd64.zip"
   curl.exe -sSL "https://github.com/istio/istio/releases/download/$v/istioctl-$v-win-amd64.zip.sha256"   # lo sha256 atteso...
   (Get-FileHash "$d\download\istioctl-$v-win-amd64.zip" -Algorithm SHA256).Hash                         # ...uguale a questo
   Expand-Archive "$d\download\istioctl-$v-win-amd64.zip" -DestinationPath "$d\$v" -Force
   Set-Alias istioctl "$d\$v\istioctl.exe"    # per questa PowerShell; per sempre, la cartella nel PATH
   istioctl version --remote=false            # client version: 1.31.4
   istioctl x precheck                        # No issues found
   ```

Note sul cluster:

- **Container runtime containerd** (default di Minikube 1.39): le immagini si
  costruiscono con `minikube image build`. `minikube docker-env` con containerd
  su Windows non funziona.
- **CNI kindnet**: applica le NetworkPolicy (verificato con una policy deny-all)
  e lascia passare le probe HTTP del kubelet anche verso i pod protetti
  (arrivano da 10.244.0.1, l'IP del nodo sulla rete dei pod).
- `kubectl get node` e `kubectl top node` mostrano CPU e RAM della VM di Docker
  Desktop (32 CPU, ~16 GB), non i limiti del container di Minikube (4 CPU, 8 GB).

## Avvio da cluster vuoto

Da PowerShell, nella cartella del progetto:

```powershell
# 1) Immagini, costruite DENTRO il nodo Minikube (nessun registry)
minikube image build -t ginvault/edizioni:0.6 .\edizioni
minikube image build -t ginvault/ricette:0.2  .\ricette
minikube image build -t ginvault/pagamenti:0.1 .\pagamenti
minikube image build -t ginvault/notifiche:0.1 .\notifiche
#    magazzino e produzione hanno bisogno anche del modulo giacenze-api, che sta
#    fuori dalla loro cartella: un secondo build context con nome, che
#    minikube image build non sa passare. Si costruiscono con Docker Desktop
#    e si caricano nel nodo.
docker build --build-context giacenze-api=giacenze-api -t ginvault/magazzino:0.3 magazzino
docker build --build-context giacenze-api=giacenze-api -t ginvault/produzione:0.4 produzione
minikube image load ginvault/magazzino:0.3
minikube image load ginvault/produzione:0.4
minikube image build -t ginvault/gateway:0.3  .\gateway
minikube image build -t ginvault/frontend:0.4 .\frontend   # npm ci, test e build di Vite dentro l'immagine

# 2) Namespace e Secret (legge il .env, non stampa i valori)
.\k8s\crea-secret.ps1

# 3) Istio: istiod in istio-system, una volta per cluster (istioctl: vedi Prerequisiti).
#    Prima dei pod, così nascono già con il sidecar
istioctl install -f k8s\istio\installazione.yaml -y

# 4) Tutti gli altri oggetti (la prima volta il nodo scarica apache/kafka:4.3.0 da Docker Hub)
kubectl apply -k k8s/
kubectl apply -k k8s/istio/     # Sidecar, PeerAuthentication (mTLS STRICT), circuit breaker su ricette

# 5) Attendere che i pod siano pronti
kubectl wait -n ginvault --for=condition=Ready pod -l '!job-name' --timeout=180s   # tutti i pod, tranne quelli dei Job
kubectl wait -n ginvault --for=condition=Complete job --all --timeout=180s         # il Job dei topic Kafka è terminato
kubectl get pods -n ginvault
```

Poi, in un **PowerShell come amministratore** (il tunnel apre la porta 80 del
portatile), da lasciare aperto:

```powershell
minikube tunnel
```

E nel browser, sempre con `localhost` e mai con `127.0.0.1`:

1. `http://localhost` → la home del front-end. **Accedi** → GitHub o Google;
   dopo il login si torna sulla home, con il menu del proprio ruolo
2. `http://localhost/auth/me` → identità e ruoli in JSON, letti dalla sessione del gateway
3. `http://localhost/api/edizioni/me` → la stessa identità, letta da edizioni nel JWT
4. `http://localhost/api/edizioni/prova` → consentito solo al ruolo SOCIO
5. `http://localhost/api/ricette/me` → la stessa identità, letta da ricette
6. **Esci** nel menu del front-end

La storia di Marco dal front-end è nella [Demo completa](#demo-completa); con
le chiamate REST dalla console del browser è in
[`docs/demo-storia-marco.md`](../docs/demo-storia-marco.md).
La storia di Elena, con i messaggi nei topic e le prove di idempotenza e
outbox, è in [`docs/demo-storia-elena.md`](../docs/demo-storia-elena.md).

**Dopo un riavvio del PC:** Docker Desktop → `minikube start` → `minikube tunnel`
(da amministratore). Oggetti e dati del DB restano; il gateway riparte senza
sessioni e con una nuova chiave di firma, quindi va rifatto il login.

### macOS / Linux

`crea-secret.ps1` è uno script PowerShell. I comandi equivalenti:

```bash
kubectl apply -f k8s/namespace.yaml
kubectl create secret generic gateway-segreti -n ginvault --from-env-file=.env \
    --dry-run=client -o yaml | kubectl apply --server-side --field-manager crea-secret -f -
kubectl get secret edizioni-db -n ginvault >/dev/null 2>&1 || \
  kubectl create secret generic edizioni-db -n ginvault \
    --from-literal=POSTGRES_USER=edizioni \
    --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24 | tr -d '+/=')"
```

Tutto il resto è stato provato solo su **Windows con driver Docker**. Su macOS e
Linux il modo in cui `minikube tunnel` espone l'Ingress dipende dal driver:
va verificato che `http://localhost` arrivi all'Ingress, perché i redirect URI
OAuth sono registrati su `localhost`.

## Gli oggetti, uno per uno

Per vedere tutto il namespace (`get all` non include ConfigMap, Secret, PVC e Ingress):

```powershell
kubectl get all,configmap,secret,pvc,ingress -n ginvault
```

Nella dashboard (`minikube dashboard`) scegliere prima il namespace `ginvault`
dal menu in alto.

### Namespace

Il contenitore logico del progetto. I nomi sono unici solo al suo interno, e
cancellarlo cancella tutto ciò che contiene (Secret e dati del DB compresi).
Più avanti servirà ad applicare regole a tutto il progetto: quote, NetworkPolicy,
iniezione dei sidecar di Istio.

- `kubectl get namespace ginvault --show-labels`
- Dashboard: menu dei namespace in alto.

### Secret

Coppie chiave/valore riservate, passate ai container come variabili d'ambiente.
**Non sono cifrate**, i valori sono solo in base64: li protegge il controllo
degli accessi (RBAC).

- `gateway-segreti` (le chiavi del `.env`: OAuth e `RUOLI_*`) → al gateway con `envFrom`.
- `pagamenti-stripe` (`STRIPE_SECRET_KEY`) → a pagamenti con `secretKeyRef`
  (vedi [Pagamenti e Notifiche](#pagamenti-e-notifiche-storia-di-giulia)).
- `edizioni-db` (`POSTGRES_USER`, `POSTGRES_PASSWORD`) → a Postgres con `envFrom`
  e a edizioni con `secretKeyRef` (diventano `DB_USER`, `DB_PASSWORD`): una sola
  fonte di verità per la password.
- Lo script usa `kubectl apply --server-side`: l'apply classico salverebbe una
  copia dei valori nell'annotazione `last-applied-configuration`.
- I pod leggono i valori solo all'avvio: dopo una modifica serve
  `kubectl rollout restart deployment/gateway -n ginvault`.

Comandi: `kubectl get secrets -n ginvault`, `kubectl describe secret gateway-segreti -n ginvault`
(mostra le chiavi e i byte, non i valori).
Dashboard: **Config and Storage → Secrets**. Attenzione se condividete lo schermo:
l'icona a occhio mostra i valori in chiaro.

### ConfigMap

Come un Secret, ma per configurazione non riservata. Permette di usare la
**stessa immagine** in ambienti diversi: Compose, Minikube, un cluster vero.

- `edizioni-config`: `DB_HOST=edizioni-db`, `DB_PORT`, `DB_NAME`, `JWKS_URI` e le
  impostazioni delle probe.
- `gateway-config`: le `*_URI` delle route (anche dei servizi non ancora
  deployati) e `SERVER_FORWARD_HEADERS_STRATEGY=native`. Il gateway sta dietro
  l'Ingress e ricostruisce l'indirizzo visto dal browser dagli header
  `X-Forwarded-*`; serve per il `redirect_uri` OAuth (`http://localhost/login/oauth2/code/...`).

La ConfigMap `kube-root-ca.crt` non è nostra: Kubernetes la crea in ogni
namespace, e contiene il certificato della CA del cluster con cui i pod possono
verificare l'API server.

Comandi: `kubectl describe configmap edizioni-config -n ginvault`.
Dashboard: **Config and Storage → Config Maps**.

### PersistentVolumeClaim, PersistentVolume, StorageClass

Il **PVC** `edizioni-db-dati` è una *richiesta* di disco (1 GiB, `ReadWriteOnce`).
La **StorageClass** `standard` di Minikube crea da sola il **PV**, il disco vero
(provisioning dinamico), e lo lega alla richiesta: stato `Bound`. Il PVC vive
indipendentemente dal pod, quindi i dati sopravvivono al riavvio di Postgres.
La reclaim policy è `Delete`: cancellare il PVC cancella i dati.

Comandi: `kubectl get pvc,pv -n ginvault`, `kubectl get storageclass`.
Dashboard: **Config and Storage → Persistent Volume Claims**; i PV e le
StorageClass non appartengono a un namespace: **Cluster → Persistent Volumes**,
**Cluster → Storage Classes**.

### Deployment, ReplicaSet, Pod

Il **Deployment** descrive lo stato voluto ("1 pod con questo container"), crea
un **ReplicaSet** che a sua volta crea il **Pod** e lo ricrea se muore. I nomi lo
mostrano: `edizioni` → `edizioni-69d76847b5` (hash del template) →
`edizioni-69d76847b5-2jln8`.

| | edizioni-db | edizioni | gateway | frontend |
|---|---|---|---|---|
| Immagine | `postgres:17-alpine` | `ginvault/edizioni:0.6` | `ginvault/gateway:0.3` | `ginvault/frontend:0.4` (nginx) |
| Repliche | 1 | 1 (scalabile: è stateless) | 1 (sessione e chiave in memoria) | 2 (solo file statici) |
| Strategia | `Recreate` (mai due Postgres sullo stesso volume) | `RollingUpdate` | `RollingUpdate` | `RollingUpdate`, `maxSurge 1`, `maxUnavailable 0` |
| startupProbe | `pg_isready`, fino a 60 s | `/health/liveness`, fino a 2 min | `/health/liveness`, fino a 2 min | nessuna: nginx parte in un attimo |
| readinessProbe | `pg_isready` | `/health/readiness` (include il DB) | `/health/readiness` (non dipende dai servizi a valle) | `GET /` ogni 10 s |
| livenessProbe | `pg_isready` | `/health/liveness` (non include il DB) | `/health/liveness` | `GET /` ogni 20 s |
| requests / limits memoria | 128 Mi / 512 Mi | 384 Mi / 768 Mi | 384 Mi / 768 Mi | 16 Mi / 64 Mi (ne usa ~3) |

ricette e ricette-db sono come edizioni ed edizioni-db (`ginvault/ricette:0.2`),
e così magazzino e magazzino-db (`ginvault/magazzino:0.3`), produzione e
produzione-db (`ginvault/produzione:0.4`), pagamenti e pagamenti-db
(`ginvault/pagamenti:0.1`). La readiness di edizioni, magazzino, produzione e
pagamenti include il database ma **non Kafka**: con Kafka giù i servizi
lavorano lo stesso, gli eventi aspettano nella tabella outbox e i consumer si
ricollegano da soli. Notifiche (`ginvault/notifiche:0.1`, Node) ha due
repliche e probe sue: [Pagamenti e Notifiche](#pagamenti-e-notifiche-storia-di-giulia).

- **startupProbe**: tempo concesso per partire; finché non passa, le altre non contano.
- **readinessProbe**: se fallisce, il pod esce dal Service (niente traffico) ma
  non viene riavviato.
- **livenessProbe**: se fallisce più volte di fila, il kubelet riavvia il container.
- Le probe HTTP considerano successo anche un 3xx: per questo il gateway lascia
  `/health/**` pubblico, altrimenti risponderebbe con un redirect a `/login` e la
  probe passerebbe sempre (c'è un test apposta in `GatewaySicurezzaTest`).
- **requests** = quanto lo scheduler riserva; **limits** = tetto, oltre il limite
  di memoria il container viene ucciso (`OOMKilled`). Nessun limite di CPU.
- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50`: heap massimo pari a metà del
  limite del container (384 MiB).
- `imagePullPolicy: Never`: l'immagine deve essere nel nodo, mai cercata su un
  registry. Tag di versione (`0.1`) e non `latest`.
- `enableServiceLinks: false`: niente variabili automatiche come
  `EDIZIONI_DB_PORT=tcp://...`; i servizi si trovano con il DNS.

Il **front-end** ha due repliche perché non ha stato: ogni pod serve gli stessi
file e, se uno cade, l'altro continua a rispondere. Con `maxUnavailable: 0` un
aggiornamento non toglie un pod vecchio prima che il nuovo sia Ready. È anche il
Deployment più chiuso:

- `runAsNonRoot` e immagine `nginx-unprivileged` (uid 101, porta 8080);
- `readOnlyRootFilesystem`: nessuno può cambiare l'app o la configurazione di
  nginx, nemmeno nginx; scrive solo in `/tmp`, un volume `emptyDir` da 16 MiB;
- `allowPrivilegeEscalation: false`, `capabilities: drop ALL`, seccomp `RuntimeDefault`;
- `worker_processes 2` (sostituito nel Dockerfile): con `auto` nginx avviava un
  processo per ognuna delle 32 CPU che vede il nodo.

Comandi:

```powershell
kubectl get deploy,rs,pods -n ginvault -o wide
kubectl describe pod -n ginvault -l app.kubernetes.io/name=edizioni   # env, probe, eventi
kubectl logs -n ginvault deploy/gateway -f
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c "\dt"
kubectl rollout status deployment/edizioni -n ginvault
kubectl rollout history deployment/edizioni -n ginvault
kubectl top pod -n ginvault
```

Dashboard: **Workloads → Deployments / Replica Sets / Pods**. Dal pod si aprono
i log e una shell (icona *Exec*).

### Service ed EndpointSlice

Un **Service** dà un IP fisso (ClusterIP) e un nome DNS ai pod, che invece
cambiano IP a ogni riavvio: `edizioni-db`, `edizioni`, `gateway`, `frontend`
(nome completo `<nome>.ginvault.svc.cluster.local`). Sceglie i pod con il
`selector` (`app.kubernetes.io/name`) e inoltra solo a quelli Ready, elencati
nell'**EndpointSlice**. Tutti i nostri Service sono ClusterIP: da fuori si entra
solo dall'Ingress.

Il Service `frontend` espone la porta 80 e la porta ai pod sulla 8080
(`targetPort: http`, il nome della porta del container); divide le richieste tra
i due pod, e la sua EndpointSlice ha due IP.

Il Service `magazzino` ha **due porte con nome**: `http` (8084, per il gateway)
e `grpc` (9084, per produzione, con `appProtocol: kubernetes.io/h2c`, cioè
HTTP/2 in chiaro). Lo stesso nome DNS, due protocolli.

```powershell
kubectl get svc,endpointslice -n ginvault
kubectl port-forward -n ginvault svc/edizioni 18081:8081   # raggiungere un Service dal portatile
curl.exe http://localhost:18081/health/readiness
kubectl port-forward -n ginvault svc/frontend 18080:80     # il front-end senza Ingress
```

Dashboard: **Service → Services** (con l'elenco degli endpoint).

### StatefulSet, Service headless, Job: Kafka

Kafka 4.3 in modalità KRaft: un solo nodo che fa da broker e da controller,
senza ZooKeeper, con la stessa configurazione di Compose
([`kafka.yaml`](kafka.yaml)). Il contratto degli eventi (topic, chiavi, JSON)
è in [`docs/eventi.md`](../docs/eventi.md).

**StatefulSet** invece di un Deployment, perché un broker ha un'identità:

- il pod si chiama sempre `kafka-0` (non `kafka-69d7...-2jln8`), anche quando
  viene ricreato con un altro IP. Kafka si annuncia ai client con quel nome:
  `kafka-0.kafka.ginvault.svc.cluster.local:9092`;
- `volumeClaimTemplates` crea un PVC **per ogni pod** (`dati-kafka-0`): il pod
  ricreato ritrova lo stesso disco, con topic e messaggi. Cancellare lo
  StatefulSet non cancella il PVC;
- una replica: con più broker servirebbero un node id per pod e un quorum di 3
  controller.

Il **Service headless** (`clusterIP: None`) non ha un IP suo: il nome `kafka`
risponde direttamente con l'IP del pod, e ogni pod ha il suo nome DNS. Il
client si collega a `kafka:9092`, il broker gli dice "per questa partizione
parla con `kafka-0.kafka...`", e il client raggiunge proprio quel broker.
`publishNotReadyAddresses: true` mette il nome nel DNS anche prima che il pod sia
Ready: all'avvio Kafka si collega al proprio controller con quel nome.

Altre scelte:

- `KAFKA_HEAP_OPTS=-Xmx512m` (il default dell'immagine è 1 GB fisso), limite di
  memoria 1 GiB: ne usa circa 270 Mi;
- probe **TCP** sulla 9092. Il controllo di Compose (`kafka-broker-api-versions.sh`)
  avvierebbe una JVM a ogni probe, che conta nel limite di memoria;
- `enableServiceLinks: false` qui è necessario: l'immagine trasforma ogni
  variabile `KAFKA_*` in configurazione, e Kubernetes aggiungerebbe
  `KAFKA_PORT`, `KAFKA_SERVICE_HOST`... per il Service `kafka`;
- `runAsUser: 1000`: l'immagine dichiara l'utente per nome (`appuser`) e con
  `runAsNonRoot` il kubelet vuole il numero;
- `auto.create.topics.enable=false`: i topic li crea solo il Job.

Il **Job** `kafka-topic` esegue un compito e termina (`Completed`) invece di
restare acceso: aspetta che il broker risponda, crea `edizioni.eventi`,
`magazzino.eventi` e `produzione.eventi` con 3 partizioni (`--if-not-exists`:
ripetibile) e stampa la loro descrizione. Un'ora dopo la fine
(`ttlSecondsAfterFinished`) Kubernetes lo cancella, e il prossimo `apply` lo
ricrea. In Compose fa lo stesso il servizio `kafka-topic`, con `restart: "no"`.

```powershell
kubectl get statefulset,pod,pvc,job -n ginvault -l 'app.kubernetes.io/name in (kafka,kafka-topic)'
kubectl logs -n ginvault job/kafka-topic                  # topic creati, partizioni, leader
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status
kubectl top pod -n ginvault kafka-0
```

Chiave e partizione, su un topic di prova (il producer legge le righe
`chiave:valore` da tastiera; Ctrl+C per finire):

```powershell
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic prova.chiavi --partitions 3 --replication-factor 1
kubectl exec -it -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic prova.chiavi --reader-property parse.key=true --reader-property key.separator=:
#   ed-1:uno   ed-2:due   ed-1:tre   lavanda:quattro   ed-1:cinque   (una per riga)
kubectl exec -it -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic prova.chiavi --from-beginning --formatter-property print.key=true --formatter-property print.partition=true
#   ed-1 sempre nella stessa partizione, in ordine: uno, tre, cinque
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic prova.chiavi
```

Il topic di prova serve a non lasciare messaggi finti nei topic veri, dove i
consumer dei passi successivi li leggerebbero.

Dashboard: **Workloads → Stateful Sets** e **Workloads → Jobs**; il PVC in
**Config and Storage → Persistent Volume Claims**.

### Transactional outbox con Kafka spento

Magazzino non manda gli eventi dalla richiesta HTTP. Li scrive nella tabella
`outbox` del suo database, nella stessa transazione della consegna, e un
publisher li manda a Kafka ogni secondo ([`docs/eventi.md`](../docs/eventi.md#transactional-outbox)).
Su Kubernetes la prova consiste nello spegnere lo StatefulSet:

```powershell
# La tabella: cosa è partito (pubblicato_il) e cosa aspetta (tentativi, ultimo_errore)
kubectl exec -n ginvault deploy/magazzino-db -- psql -U magazzino -d magazzino -c "select numero, tipo, chiave, tentativi, pubblicato_il, ultimo_errore from outbox order by numero"

# 1) Kafka spento: il pod kafka-0 sparisce, il PVC dati-kafka-0 resta
kubectl scale statefulset kafka -n ginvault --replicas=0

# 2) Dal browser Elena registra una consegna: risposta 201, come sempre.
#    magazzino resta 2/2 Ready (la readiness non dipende da Kafka)
kubectl get pods -n ginvault -l app.kubernetes.io/name=magazzino
#    La riga è in attesa: pubblicato_il vuoto, tentativi che sale ogni pochi secondi
#    (stessa query di sopra). Nel log un WARN al primo tentativo, poi uno ogni 30
kubectl logs -n ginvault deploy/magazzino --tail=20

# 3) Kafka torna (stesso nome kafka-0, stesso disco, IP nuovo)
kubectl scale statefulset kafka -n ginvault --replicas=1
kubectl wait -n ginvault --for=condition=Ready pod/kafka-0 --timeout=180s
#    Entro una ventina di secondi la riga ha pubblicato_il, e nel log:
#    "Evento ConsegnaRegistrata ... pubblicato al tentativo N"
kubectl exec -it -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic magazzino.eventi --from-beginning --formatter-property print.key=true
```

Dopo il ritorno di Kafka passano alcuni secondi prima della pubblicazione:
- il broker finisce di partire anche dopo essere Ready, perché la probe
  controlla solo che la porta sia aperta;
- il producer prova prima il vecchio IP del pod, ma rinuncia dopo pochi
  secondi (`socket.connection.setup.timeout.*` in `application.yml`).

Verificato su Minikube con una riga di prova su un topic usa e getta:
- con Kafka spento, 5 tentativi falliti e magazzino sempre Ready;
- evento pubblicato 13 s dopo che kafka-0 era tornato Ready;
- una sola copia nel topic.

### Consumer di produzione

Produzione legge `edizioni.eventi` e `magazzino.eventi` nel consumer group
`produzione`: due listener (uno per topic) con 3 consumer ciascuno, uno per
partizione. I messaggi che non riesce a elaborare vanno in `produzione.dlt`
([`docs/eventi.md`](../docs/eventi.md#consumer-idempotenza-retry-e-dead-letter)).

```powershell
# Chi legge cosa: 6 righe, una per partizione, con offset e lag
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group produzione
# Il dead letter topic: 3 partizioni come gli altri
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic produzione.dlt
# I messaggi scartati, con gli header kafka_dlt-* (Ctrl+C per uscire)
kubectl exec -it -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic produzione.dlt --from-beginning --formatter-property print.headers=true
# Piani ed eventi già elaborati
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c "select edizione_id, botanica, kg_previsti, kg_disponibili from piano_di_produzione"
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c "select event_id, tipo, topic, partizione, posizione from evento_elaborato"
# Nel log: partizioni assegnate, eventi elaborati, tentativi (WARN) e dead letter (ERROR)
kubectl logs -n ginvault deploy/produzione --tail=30
```

Come magazzino, la readiness di produzione non dipende da Kafka: con Kafka
spento i piani si leggono lo stesso, e quando torna i consumer riprendono
dall'ultimo offset salvato.

Dashboard: **Workloads → Deployments → produzione** (i log con le partizioni
assegnate) e **Config and Storage → Config Maps → produzione-config**.

### Consumer di edizioni

Edizioni legge `produzione.eventi` nel consumer group `edizioni`: un listener
con 3 consumer, uno per partizione. Ogni `ResaRicalcolata` ricalcola la
tiratura e scrive nell'outbox, nello stesso commit, `TiraturaRidotta`,
`TiraturaAumentata` o `RicalcoloRifiutato` per `edizioni.eventi`. I messaggi
che non riesce a elaborare vanno in `edizioni.dlt`
([`docs/eventi.md`](../docs/eventi.md#il-consumer-di-edizioni)).

Il topic `edizioni.dlt` lo crea il Job `kafka-topic`. Un Job finito non
riparte, e Kubernetes lo cancella un'ora dopo la fine
(`ttlSecondsAfterFinished`): se c'è ancora, lo si cancella e lo si
riapplica. I topic che esistono già restano come sono (`--if-not-exists`).

```powershell
kubectl delete job kafka-topic -n ginvault --ignore-not-found
kubectl apply -k k8s/
kubectl wait -n ginvault --for=condition=Complete job/kafka-topic --timeout=120s

# 3 righe, una per partizione di produzione.eventi, con offset e lag
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group edizioni
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic edizioni.dlt
# Gli eventi in uscita e le rese già applicate, con l'esito
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c 'select tipo, chiave, pubblicato_il, tentativi from outbox order by creato_il'
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c 'select event_id, tipo, esito, partizione, posizione from evento_elaborato'
# Nel log: partizioni assegnate, rese applicate (INFO) e rifiutate (WARN)
kubectl logs -n ginvault deploy/edizioni --tail=30
```

Dashboard: **Workloads → Deployments → edizioni** (log) e **Workloads → Jobs
→ kafka-topic** (il log elenca i topic creati).

### Pagamenti e Notifiche (storia di Giulia)

La SAGA del pagamento passa da tre servizi e due topic
([`docs/eventi.md`](../docs/eventi.md)):

```
edizioni ── RichiestaPagamento ──▶ pagamenti.comandi ──▶ pagamenti ── HTTPS ──▶ api.stripe.com
   ▲                                                         │         (modalità test)
   └──── PagamentoRiuscito / PagamentoFallito ◀──── pagamenti.esiti ◀┘
            │
            └─▶ PrenotazioneConfermata (numeri 112 e 113) ──▶ edizioni.eventi ──▶ notifiche (2 pod)
                                                                                  │ WebSocket
                                                                                  ▼
                                                                         browser di Giulia
```

**La chiave di Stripe.** Sta nel Secret `pagamenti-stripe`, con la sola chiave
`STRIPE_SECRET_KEY`. Lo crea `crea-secret.ps1` dalla riga `STRIPE_SECRET_KEY=`
del `.env`, e dice se la chiave c'è ed è di test senza stamparla. Il pod la
riceve con `secretKeyRef`:

```powershell
.\k8s\crea-secret.ps1                                          # "chiave di test presente", oppure un AVVISO
kubectl describe secret pagamenti-stripe -n ginvault           # la chiave e quanti byte, non il valore
kubectl rollout restart deployment/pagamenti -n ginvault       # dopo aver cambiato il .env
# Senza chiave pagamenti parte lo stesso e lo scrive nel log (Count 0 = la chiave c'è)
kubectl logs -n ginvault deploy/pagamenti | Select-String -SimpleMatch 'STRIPE_SECRET_KEY non impostata' | Measure-Object
```

`gateway-segreti` invece si crea da tutto il `.env` (`--from-env-file`), quindi
contiene anche questa chiave. Il pod del gateway la vede come variabile, ma non
la usa.

**Pagamenti esce su Internet.** Le NetworkPolicy filtrano solo il traffico in
entrata, quindi pagamenti raggiunge `api.stripe.com:443`:

```powershell
kubectl exec -n ginvault deploy/pagamenti -- sh -c 'nc -z -w 5 api.stripe.com 443 && echo APERTO || echo BLOCCATO'
```

**I consumer group e i topic:**

```powershell
# pagamenti legge pagamenti.comandi; edizioni e notifiche leggono pagamenti.esiti
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group pagamenti
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notifiche
# Gli esiti, con le chiavi (l'id della prenotazione). Ctrl+C per uscire
kubectl exec -n ginvault kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic pagamenti.esiti --from-beginning --property print.key=true
# I pagamenti, con il riferimento di Stripe (pi_...)
kubectl exec -n ginvault deploy/pagamenti-db -- psql -U pagamenti -d pagamenti -c 'select * from pagamento'
# Gli avvisi salvati da notifiche
kubectl exec -n ginvault deploy/notifiche-db -- psql -U notifiche -d notifiche -c 'select tipo, destinatario, titolo, creata_il, letta_il from notifica order by creata_il'
```

**Notifiche con due repliche.** Il Deployment `notifiche` ha due pod. Le sei
partizioni dei suoi due topic (`edizioni.eventi` e `pagamenti.esiti`) si
dividono tra i pod del gruppo `notifiche`, e ogni evento lo legge un pod solo.
Il WebSocket di Giulia può però stare sull'altro. Per questo ogni avviso nuovo
fa un `NOTIFY` su `notifiche-db`: tutti e due i pod sono in `LISTEN`, e ognuno
consegna ai WebSocket che ha.

```powershell
# Nel log di ognuno: le sue partizioni ("Nel gruppo notifiche: ...") e "In ascolto sul canale notifiche_nuove"
kubectl logs -n ginvault -l app.kubernetes.io/name=notifiche --prefix --tail=20
# Le due connessioni in LISTEN, una per pod (dal database)
kubectl exec -n ginvault deploy/notifiche-db -- psql -U notifiche -d notifiche -c 'select client_addr, state, query from pg_stat_activity where query like $$LISTEN%$$'
```

Il WebSocket passa dall'Ingress e dal gateway: `ws://localhost/api/notifiche/avvisi`.
ingress-nginx inoltra l'upgrade senza annotazioni e chiude un WebSocket muto
dopo 60 s (`proxy-read-timeout`); notifiche manda un `ping` ogni 25 s. Con
`kubectl rollout restart deployment/notifiche -n ginvault` il pod che si spegne
chiude i suoi WebSocket con 1001, e il front-end si ricollega da solo all'altro.

**Le probe di notifiche:**

- startup e readiness su `GET /health`, che risponde 503 se il database non
  risponde;
- liveness `tcpSocket`: con il DB giù riavviare Node non servirebbe.

Al primo avvio un pod può riavviarsi una volta, se parte prima di
`notifiche-db` (nel log precedente c'è `ECONNREFUSED`). Il caso degli
avvisi senza Kafka è in [`docs/eventi.md`](../docs/eventi.md#il-consumer-di-notifiche).

```powershell
kubectl logs -n ginvault <pod notifiche> --previous --tail=5
```

Dashboard: **Workloads → Deployments → pagamenti / notifiche** (log e pod),
**Config and Storage → Secrets → pagamenti-stripe** (attenzione all'icona a
occhio), **Service → Services → notifiche** (i due endpoint).

### gRPC tra produzione e magazzino

Quando Marco integra un lotto nel piano, produzione chiama il servizio gRPC
`Giacenze` di magazzino su `magazzino:9084` (`MAGAZZINO_GRPC` nella ConfigMap),
con una deadline di 2 secondi e il JWT di Marco nel metadata
([docs/grpc.md](../docs/grpc.md)). Il server gRPC gira nello stesso pod di
magazzino, accanto a Tomcat, e parte e si ferma con Spring.

La NetworkPolicy `magazzino-grpc-solo-da-produzione` apre la 9084 solo ai pod
di produzione. Una policy che seleziona un pod chiude tutto ciò che non elenca,
quindi ha una seconda regola che lascia la 8084 aperta come prima, per il
gateway e le probe.

```powershell
# Le due porte del Service e dell'EndpointSlice
kubectl get svc magazzino -n ginvault -o custom-columns='PORTE:.spec.ports[*].name,NUMERI:.spec.ports[*].port'
kubectl get endpointslices -n ginvault -l kubernetes.io/service-name=magazzino
# Il server gRPC partito: "Server gRPC Giacenze in ascolto sulla porta 9084"
kubectl logs -n ginvault deploy/magazzino | Select-String 'gRPC Giacenze'
# La policy
kubectl describe networkpolicy magazzino-grpc-solo-da-produzione -n ginvault

# Da un pod qualsiasi: 9084 BLOCCATO, 8084 APERTO
kubectl run prova-rete -n ginvault --rm -it --restart=Never --image=busybox:1.36 -- sh -c 'for s in magazzino:9084 magazzino:8084; do nc -z -w 3 ${s%:*} ${s#*:} && echo $s APERTO || echo $s BLOCCATO; done'
# Da produzione: 9084 APERTO
kubectl exec -n ginvault deploy/produzione -- sh -c 'nc -z -w 3 magazzino 9084 && echo magazzino:9084 APERTO || echo magazzino:9084 BLOCCATO'
# Da edizioni e dal gateway: 9084 BLOCCATO
kubectl exec -n ginvault deploy/edizioni -- sh -c 'nc -z -w 3 magazzino 9084 && echo APERTO || echo BLOCCATO'
kubectl exec -n ginvault deploy/gateway -- sh -c 'nc -z -w 3 magazzino 9084 && echo APERTO || echo BLOCCATO'

# Il server risponde in gRPC (il port-forward passa dal kubelet: la policy non si applica).
# Senza JWT: "Code: Unauthenticated", "Serve il JWT dell'utente nel metadata authorization"
kubectl port-forward -n ginvault svc/magazzino 19084:9084      # in un'altra finestra
docker run --rm -v "${PWD}\giacenze-api\src\main\proto:/proto" fullstorydev/grpcurl:v1.9.3 -plaintext -import-path /proto -proto giacenze.proto -d '{\"botanica\": \"lavanda\"}' host.docker.internal:19084 ginvault.giacenze.v1.Giacenze/Disponibilita

# Le riserve in magazzino e le integrazioni nei piani. Una riserva annullata
# (compensazione) sparisce da lotto_riserva e lascia un WARN nel log di magazzino:
#   kubectl logs -n ginvault deploy/magazzino | Select-String 'Riserva annullata'
kubectl exec -n ginvault deploy/magazzino-db -- psql -U magazzino -d magazzino -c 'select lotto_id, piano_id, kg, riservata_da from lotto_riserva'
kubectl exec -n ginvault deploy/produzione-db -- psql -U produzione -d produzione -c 'select piano_id, lotto_id, origine, kg, motivo from piano_integrazione'
```

Dashboard: **Service → Services → magazzino** (le due porte) e **Service →
Network Policies → magazzino-grpc-solo-da-produzione**.

### Ingress, IngressClass, Ingress Controller

L'**Ingress** `ginvault` è solo un insieme di *regole* per l'host `localhost`.
A eseguirle è l'**Ingress Controller** ingress-nginx (addon di Minikube), un
nginx nel namespace `ingress-nginx` che legge gli Ingress del cluster e si
riconfigura da solo; l'**IngressClass** `nginx` collega le regole al controller.

| Path (tutti `Prefix`) | Service | Cosa c'è |
|---|---|---|
| `/api` | `gateway` | le API dei servizi, con il JWT aggiunto dal gateway |
| `/auth` | `gateway` | `/auth/me`: chi è l'utente, i suoi ruoli |
| `/oauth2`, `/login` | `gateway` | l'inizio e il ritorno del login OAuth2 |
| `/logout` | `gateway` | uscita (POST con il token CSRF) |
| `/` | `frontend` | tutto il resto: `/`, `/soci`, `/distilleria/...`, `/assets/...` |

- `Prefix` confronta il path a pezzi interi separati da `/`: `/login` vale per
  `/login/oauth2/code/github` ma non per `/loginx`, che va al front-end.
- Se più regole vanno bene **vince la più lunga**: `/api/edizioni/me` va al
  gateway, `/soci` (nessun'altra regola) al front-end.
- Le pagine dell'app (`/soci`, `/distilleria/edizioni/...`) non sono file: nginx
  del front-end risponde con `index.html` e il percorso lo legge React Router.
- Con il driver Docker su Windows l'IP del nodo (colonna ADDRESS, 192.168.49.2)
  non è raggiungibile: `minikube tunnel` porta l'Ingress su `127.0.0.1:80`.
- `http://127.0.0.1` risponde 404 di proposito: le regole accettano solo l'host
  `localhost`, quello registrato come redirect URI.

```powershell
kubectl get ingress -n ginvault
kubectl describe ingress ginvault -n ginvault          # ogni path → IP dei pod
kubectl get ingressclass
kubectl logs -n ingress-nginx deploy/ingress-nginx-controller -f   # ogni richiesta del browser
```

Nei log del controller, verso la fine di ogni riga, c'è il backend scelto:
`ginvault-frontend-http` per le pagine, `ginvault-gateway-http` per API e login.

Dashboard: **Service → Ingresses**; namespace `ingress-nginx` → **Workloads →
Pods** per il controller e i suoi log.

### NetworkPolicy

Di default ogni pod del cluster può collegarsi a qualsiasi altro. In Compose
l'isolamento lo danno le reti private (`edizioni-db-net`, `ricette-db-net`,
`magazzino-db-net`, `produzione-db-net`, `pagamenti-db-net`, `notifiche-db-net`,
`frontend-net`, `kafka-net`); qui lo danno dieci **NetworkPolicy**:

| NetworkPolicy | Pod protetti | Sorgenti ammesse | Porta |
|---|---|---|---|
| `edizioni-db-solo-da-edizioni` | edizioni-db | i pod di edizioni | 5432 |
| `ricette-db-solo-da-ricette` | ricette-db | i pod di ricette | 5432 |
| `magazzino-db-solo-da-magazzino` | magazzino-db | i pod di magazzino | 5432 |
| `produzione-db-solo-da-produzione` | produzione-db | i pod di produzione | 5432 |
| `pagamenti-db-solo-da-pagamenti` | pagamenti-db | i pod di pagamenti | 5432 |
| `notifiche-db-solo-da-notifiche` | notifiche-db | i due pod di notifiche (anche il LISTEN/NOTIFY tra loro) | 5432 |
| `frontend-solo-da-ingress` | frontend | il controller ingress-nginx | 8080 |
| `gateway-da-ingress-e-servizi` | gateway | il controller ingress-nginx; i pod con `app.kubernetes.io/component: microservizio` (scaricano la JWKS) | 8080 |
| `kafka-solo-da-chi-usa-kafka` | kafka | i pod con `ginvault/usa-kafka: "true"` (il Job dei topic, edizioni, magazzino, produzione, pagamenti, notifiche); Kafka stesso sulla 9093 del controller | 9092, 9093 |
| `magazzino-grpc-solo-da-produzione` | magazzino | i pod di produzione sulla 9084 (gRPC); tutti sulla 8084 (HTTP, come prima) | 9084, 8084 |

Come sono fatte:

- `podSelector`: i pod protetti;
- `policyTypes: [Ingress]`: da quel momento rifiutano tutto il traffico in
  entrata, tranne quello elencato. Quello in uscita resta libero (DNS, chiamate
  tra servizi);
- `ingress.from`: le sorgenti ammesse. Per il controller servono insieme
  `namespaceSelector` (il namespace `ingress-nginx`) e `podSelector` (il pod del
  controller) **nella stessa voce**, quindi in AND. Scritti come due voci
  separate varrebbero in OR: tutto il namespace `ingress-nginx` più i pod con
  quelle label in `ginvault`;
- `ports`: la porta del **pod** (containerPort), non quella del Service: il
  Service `frontend` espone la 80, ma la connessione arriva al pod sulla 8080.

Le applica il plugin di rete (kindnet). Non toccano le probe `exec` di Postgres,
`kubectl exec` e `kubectl port-forward`, che non passano dalla rete dei pod.
Le probe HTTP del front-end e del gateway invece passano dalla rete: kindnet le
lascia arrivare dal nodo (10.244.0.1) anche con la policy, quindi non servono
eccezioni `ipBlock` né probe `exec` (verificato: nessun riavvio e pod sempre Ready).

I servizi tra loro restano raggiungibili, come sulla rete `backend` di Compose:
edizioni deve poter chiamare ricette. Il front-end non raggiunge il gateway, e
non gli serve: le chiamate del browser arrivano al gateway dall'Ingress.

```powershell
kubectl get networkpolicy -n ginvault
kubectl describe networkpolicy gateway-da-ingress-e-servizi -n ginvault

# Con Istio i pod di prova nascono SENZA sidecar (label sidecar.istio.io/inject=false):
# con il sidecar nc -z direbbe sempre APERTO, perché la connessione la accetta Envoy.
# Un pod qualsiasi NON raggiunge database, front-end, gateway e Kafka (timeout dopo 3 s)...
# (virgolette semplici: con le doppie PowerShell sostituirebbe $s prima di passarlo)
# (magazzino:9084 BLOCCATO anche lui; magazzino:8084, pagamenti:8085, notifiche:8086 e ricette:8082 APERTI: sono servizi.
#  APERTO vuol dire che la NetworkPolicy lascia passare: la connessione TCP la accetta Envoy,
#  ma con l'mTLS STRICT una richiesta HTTP in chiaro viene rifiutata, vedi la sezione mTLS)
kubectl run prova-rete -n ginvault --rm -it --restart=Never --image=busybox:1.36 --labels=sidecar.istio.io/inject=false -- sh -c 'for s in edizioni-db:5432 ricette-db:5432 magazzino-db:5432 produzione-db:5432 pagamenti-db:5432 notifiche-db:5432 frontend:80 gateway:8080 kafka:9092 magazzino:9084 magazzino:8084 pagamenti:8085 notifiche:8086 ricette:8082; do nc -z -w 3 ${s%:*} ${s#*:} && echo $s APERTO || echo $s BLOCCATO; done'
# ...con la label di chi usa Kafka, Kafka risponde
kubectl run prova-rete -n ginvault --rm -it --restart=Never --image=busybox:1.36 --labels=sidecar.istio.io/inject=false,ginvault/usa-kafka=true -- sh -c 'nc -z -w 3 kafka 9092 && echo kafka APERTO || echo kafka BLOCCATO'

# Dai microservizi si prova dal container istio-proxy: è lo stesso pod (stesso IP, stesse
# label per le NetworkPolicy), ma il suo traffico (utente 1337) non passa da Envoy.
# Da edizioni (ha la label) Kafka sì, da ricette (non usa Kafka) no
kubectl exec -n ginvault deploy/edizioni -c istio-proxy -- sh -c 'nc -z -w 3 kafka 9092 2>/dev/null && echo APERTO || echo BLOCCATO'
kubectl exec -n ginvault deploy/ricette -c istio-proxy -- sh -c 'nc -z -w 3 kafka 9092 2>/dev/null && echo APERTO || echo BLOCCATO'
# Ogni servizio: Kafka e il proprio database sì, quello di un altro no (produzione anche magazzino:9084)
kubectl exec -n ginvault deploy/magazzino -c istio-proxy -- sh -c 'for s in kafka:9092 magazzino-db:5432 edizioni-db:5432; do nc -z -w 3 ${s%:*} ${s#*:} 2>/dev/null && echo $s APERTO || echo $s BLOCCATO; done'
kubectl exec -n ginvault deploy/produzione -c istio-proxy -- sh -c 'for s in kafka:9092 produzione-db:5432 magazzino-db:5432 magazzino:9084; do nc -z -w 3 ${s%:*} ${s#*:} 2>/dev/null && echo $s APERTO || echo $s BLOCCATO; done'
# Pagamenti e notifiche: anche la JWKS del gateway
kubectl exec -n ginvault deploy/pagamenti -c istio-proxy -- sh -c 'for s in kafka:9092 pagamenti-db:5432 notifiche-db:5432 edizioni-db:5432; do nc -z -w 3 ${s%:*} ${s#*:} 2>/dev/null && echo $s APERTO || echo $s BLOCCATO; done; curl -sf -o /dev/null -m 3 http://gateway:8080/.well-known/jwks.json && echo JWKS APERTO || echo JWKS BLOCCATO'
kubectl exec -n ginvault deploy/notifiche -c istio-proxy -- sh -c 'for s in kafka:9092 notifiche-db:5432 pagamenti-db:5432 edizioni-db:5432; do nc -z -w 3 ${s%:*} ${s#*:} 2>/dev/null && echo $s APERTO || echo $s BLOCCATO; done; curl -sf -o /dev/null -m 3 http://gateway:8080/.well-known/jwks.json && echo JWKS APERTO || echo JWKS BLOCCATO'

# Da edizioni (label microservizio) la JWKS del gateway è raggiungibile, il front-end no
kubectl exec -n ginvault deploy/edizioni -c istio-proxy -- sh -c 'curl -sf -o /dev/null -m 3 http://gateway:8080/.well-known/jwks.json && echo JWKS APERTO || echo JWKS BLOCCATO; curl -sf -o /dev/null -m 3 http://frontend/ && echo FRONTEND APERTO || echo FRONTEND BLOCCATO'

# Il falso positivo, per vederlo: dal container dell'applicazione la connessione la accetta
# Envoy, e nc dice APERTO anche verso Kafka, che per ricette è bloccato
kubectl exec -n ginvault deploy/ricette -c ricette -- sh -c 'nc -z -w 3 kafka 9092 && echo APERTO || echo BLOCCATO'

# I servizi raggiungono il proprio database: sono Ready, e la readiness include "db"
kubectl get pods -n ginvault
```

Il gateway si fida di una **label**: chi può creare pod nel namespace può darsi
`component: microservizio` e raggiungerlo. Lo stesso vale per qualsiasi
NetworkPolicy basata su label. Con l'mTLS di Istio ogni sidecar ha anche un
certificato, ma per ora tutti i pod usano la ServiceAccount `default` e quindi
la stessa identità (vedi [mTLS](#istio-mtls-tra-i-microservizi)): per
autorizzare per identità servirebbe una ServiceAccount per servizio.

Dashboard: **Service → Network Policies**.

### Istio: istiod e i sidecar

Istio 1.31.4, profilo **minimal**: solo il control plane, **istiod**, nel
namespace `istio-system`. L'Ingress resta quello di nginx, senza ingress
gateway di Istio. La configurazione è in
[`istio/installazione.yaml`](istio/installazione.yaml).

```
kube-apiserver ── crea un pod in ginvault ──▶ webhook istio-sidecar-injector (istiod :15017)
                                              che aggiunge istio-init e istio-proxy al pod
pod di edizioni
  istio-init   init container: regole iptables, il traffico del pod passa da Envoy
  istio-proxy  sidecar nativo: Envoy, :15001 in uscita, :15006 in entrata, :15020 probe
               └─ collegato a istiod :15012 (xDS): Service, endpoint, certificati
  edizioni     l'applicazione, che non sa niente di Istio
```

Gli oggetti:

- **istiod** (`Deployment` in `istio-system`): calcola la configurazione di
  ogni Envoy (xDS) e firma i certificati dei sidecar. Una replica, senza
  autoscaling, con 100m / 256Mi richiesti; ne usa circa 60 MiB.
- **`MutatingWebhookConfiguration` `istio-sidecar-injector`**: per ogni pod
  nuovo l'API server chiede a istiod di completarlo.
  - La label `istio-injection=enabled` del namespace (in `namespace.yaml`) lo
    accende per ginvault.
  - La label `sidecar.istio.io/inject: "false"` nel template del pod lo spegne
    per quel pod: i sei database, `kafka-0` e il Job `kafka-topic`.
- **Il sidecar `istio-proxy`** (Envoy). Con Kubernetes 1.37 è un **sidecar
  nativo**: sta tra gli `initContainers` con `restartPolicy: Always`, parte
  prima dell'applicazione e si ferma dopo, e un Job con il sidecar
  finirebbe lo stesso.
  - `istio-init` scrive le regole iptables che mandano a Envoy il traffico del
    pod; quello dell'utente 1337 (Envoy stesso) resta fuori.
  - Risorse: 10m / 48Mi richiesti, limite 500m / 256Mi, un thread. Ognuno usa
    27-30 MiB.
  - `holdApplicationUntilProxyStarts`: l'applicazione parte quando Envoy è
    pronto, perché i servizi Java si collegano subito a DB, Kafka e JWKS.
- **Le probe**: il webhook le riscrive verso l'agente del sidecar,
  `:15020/app-health/<container>/readyz` (e `livez`, `startupz`), che le passa
  all'applicazione. Arrivano dal kubelet come prima: kindnet le lascia passare
  anche verso frontend, gateway e magazzino, che hanno una NetworkPolicy.
- **`Sidecar` `default`** ([`istio/sidecar.yaml`](istio/sidecar.yaml)): ogni
  Envoy conosce solo i Service di ginvault e istiod. Le destinazioni fuori,
  come api.stripe.com, passano lo stesso (`outboundTrafficPolicy` `ALLOW_ANY`).
- **`appProtocol: tcp`** sulle porte dei database e di Kafka: Envoy passa i
  byte così come sono, senza provare a riconoscere il protocollo.

Per le applicazioni non cambia niente: `edizioni → ricette:8082` diventa
edizioni → Envoy di edizioni → Envoy di ricette → ricette. Tra i due Envoy il
traffico è in mTLS, e dalla [PeerAuthentication](#istio-mtls-tra-i-microservizi)
in poi solo in mTLS.

```powershell
istioctl version                                  # client, control plane e data plane 1.31.4
kubectl get pods -n istio-system                  # istiod 1/1 Running
istioctl proxy-status                             # gli 11 sidecar, sincronizzati con istiod
kubectl get pods -n ginvault                      # 2/2 i microservizi e il front-end, 1/1 DB e Kafka
kubectl get pod -n ginvault -l app.kubernetes.io/name=edizioni -o jsonpath='{.items[0].spec.initContainers[*].name}'          # istio-init istio-proxy
kubectl get pod -n ginvault -l app.kubernetes.io/name=gateway -o jsonpath='{.items[0].spec.containers[0].readinessProbe.httpGet}'  # porta 15020
kubectl top pods -n ginvault --containers | Select-String istio-proxy
istioctl proxy-config cluster deploy/edizioni -n ginvault     # i Service che il sidecar di edizioni conosce
kubectl logs -n ginvault deploy/edizioni -c istio-proxy --tail=20
# Le richieste di edizioni a ricette, contate dal sidecar (metriche di Istio)
kubectl exec -n ginvault deploy/edizioni -c istio-proxy -- pilot-agent request GET stats/prometheus | Select-String 'istio_requests_total.*destination_service_name="ricette"'
```

Un pod creato prima dell'installazione non ha il sidecar: `kubectl rollout
restart deployment/<nome> -n ginvault`, due alla volta, perché ogni pod nuovo
parte prima che il vecchio si fermi. Di tutte le statistiche di Envoy Istio
ne espone poche, per risparmiare memoria: quelle del circuit breaker si
accendono solo sul sidecar di edizioni.

**Verificato l'11 novembre**, dopo l'installazione:

- memoria del container minikube da 3,66 a 4,1 GiB su 8: istiod 57 MiB, i 10
  sidecar 281 MiB in tutto;
- NetworkPolicy: il test qui sopra dà gli stessi risultati di prima di Istio;
- dall'Ingress: le pagine `200`, `/api/<servizio>/health` `200` per i sei
  servizi, le API `401` senza sessione;
- edizioni → ricette: `server: envoy` nella risposta, e la richiesta in
  `istio_requests_total`;
- produzione → magazzino:9084 in gRPC: `grpcurl` dal pod di produzione (un
  container effimero, `kubectl debug`) riceve `UNAUTHENTICATED` da magazzino,
  e il sidecar di magazzino conta la chiamata (`source_workload="produzione"`,
  `grpc_response_status="16"`);
- WebSocket: dal pod di notifiche l'upgrade di `/avvisi` riceve `101` da Envoy
  e poi la chiusura 4401 dell'applicazione;
- Kafka: i consumer group di edizioni, produzione, pagamenti e notifiche con i
  loro membri; pagamenti → api.stripe.com attraverso Envoy.

Dashboard:
- namespace `istio-system` → **Workloads → Deployments → istiod**;
- namespace `ginvault` → **Workloads → Pods →** un pod: tra i container ci
  sono `istio-init` e `istio-proxy`;
- **Custom Resource Definitions → sidecars.networking.istio.io**: il `Sidecar`
  `default`.

### Istio: mTLS tra i microservizi

Con l'**mTLS** (TLS reciproco) client e server si mostrano a vicenda un
certificato: il traffico è cifrato e ognuno sa che dall'altra parte c'è un
sidecar del mesh. Lo fanno i due Envoy. Le applicazioni continuano a parlare
HTTP e gRPC in chiaro, ma solo dentro il proprio pod.

```
pod di edizioni                                         pod di ricette
  edizioni ─ HTTP in chiaro ─▶ Envoy ══ mTLS, eth0 ══▶ Envoy ─ HTTP in chiaro, lo ─▶ ricette

pod senza sidecar (busybox) ─ HTTP in chiaro ─▶ Envoy di ricette: connessione chiusa
edizioni ─▶ Envoy ─ TCP in chiaro ─▶ edizioni-db, kafka       (dall'altra parte nessun sidecar)
ingress-nginx ─ HTTP in chiaro ─▶ Envoy di frontend e gateway, porta 8080 (PERMISSIVE)
```

Gli oggetti, in [`istio/peer-authentication.yaml`](istio/peer-authentication.yaml):

- **PeerAuthentication `default`**, senza selector: `STRICT` per tutto il
  namespace. Il sidecar di ogni microservizio accetta solo connessioni mTLS,
  cioè da un altro sidecar.
- **`frontend-da-ingress` e `gateway-da-ingress`**: `STRICT` anche loro, tranne
  la porta 8080 (quella del container, non del Service), che è `PERMISSIVE`.
  - Il controller ingress-nginx non ha il sidecar: con `STRICT` le sue
    richieste sarebbero rifiutate e `http://localhost` risponderebbe 502.
  - Sulla stessa porta i microservizi scaricano la JWKS del gateway, e arrivano
    comunque in mTLS.
  - Chi altro può arrivarci in chiaro lo decidono le NetworkPolicy.

Una PeerAuthentication decide solo che cosa un sidecar accetta **in entrata**.
In uscita ci pensa l'**auto mTLS**: il sidecar del client usa mTLS verso i pod
con la label `security.istio.io/tlsMode: istio` (la mette il webhook insieme al
sidecar), TCP in chiaro verso gli altri. Per questo:
- database e Kafka, senza sidecar, si raggiungono come prima: dall'altra parte
  non c'è un Envoy che possa rifiutare la connessione;
- le probe non cambiano: il kubelet chiama la 15020 dell'agente, che la
  PeerAuthentication non tocca;
- `kubectl port-forward` e `kubectl exec` funzionano come prima: entrano nel pod
  da localhost, senza passare da Envoy.

**I certificati.** Li firma istiod, che fa da CA del mesh (`O=cluster.local`;
la sua radice dura 10 anni), con l'identità SPIFFE della ServiceAccount del pod.
Durano 24 ore e il sidecar li rinnova da solo, senza riavvii. Tutti i pod di
ginvault usano la ServiceAccount `default`, quindi hanno tutti la stessa
identità, `spiffe://cluster.local/ns/ginvault/sa/default`. L'mTLS cifra e
garantisce che il client sia nel mesh, ma non distingue edizioni dal gateway.
Con una ServiceAccount per servizio, una `AuthorizationPolicy` potrebbe dire
"ricette accetta solo edizioni e il gateway".

**Come verificare che edizioni → ricette è cifrato.** Tre prove: la
configurazione, le metriche del sidecar di ricette, i pacchetti sul filo.

```powershell
# La configurazione
kubectl get peerauthentication -n ginvault          # default, frontend-da-ingress, gateway-da-ingress: STRICT
$r = kubectl get pod -n ginvault -l app.kubernetes.io/name=ricette -o jsonpath='{.items[0].metadata.name}'
istioctl x describe pod $r -n ginvault              # "Workload mTLS mode: STRICT"
$g = kubectl get pod -n ginvault -l app.kubernetes.io/name=gateway -o jsonpath='{.items[0].metadata.name}'
istioctl x describe pod $g -n ginvault              # STRICT, "8080: PERMISSIVE"
istioctl proxy-config secret deploy/ricette -n ginvault   # "default" (il certificato, 24 ore) e "ROOTCA"

# Le metriche: le richieste ricevute dal sidecar di ricette, una riga per chiamante.
# Da edizioni e dal gateway: mutual_tls, con l'identità del chiamante.
# I contatori ripartono da zero quando il pod si riavvia. Una riga "unknown none" è
# di prima della PeerAuthentication: con STRICT il chiaro non arriva a una richiesta.
kubectl exec -n ginvault deploy/ricette -c istio-proxy -- pilot-agent request GET stats/prometheus 2>$null | Select-String 'istio_requests_total\{reporter="destination"' | ForEach-Object { if ($_.Line -match 'source_workload="([^"]*)".*source_principal="([^"]*)".*response_code="([^"]*)".*connection_security_policy="([^"]*)"\} (\d+)') { '{0,-10} {1,-11} {2} {3} {4}' -f $Matches[1], $Matches[4], $Matches[3], $Matches[5], $Matches[2] } }
#   edizioni   mutual_tls  200 6 spiffe://cluster.local/ns/ginvault/sa/default
#   gateway    mutual_tls  200 2 spiffe://cluster.local/ns/ginvault/sa/default

# I pacchetti: tcpdump in un container effimero nel pod di ricette. Con --profile=netadmin
# può catturare; parte da solo e cattura per 60 s. Ogni container effimero vuole un nome
# nuovo e resta nella spec del pod (terminato) finché il pod non si ricrea.
$c = "cattura-$(Get-Random -Maximum 100000)"
kubectl debug -n ginvault $r --image=alpine:3.22 --profile=netadmin --container=$c --quiet -- sh -c 'apk add -q tcpdump && timeout 60 tcpdump -i any -nn -A -l tcp port 8082 and greater 100' | Out-Null
do { Start-Sleep -Seconds 1 } until ((kubectl logs -n ginvault $r -c $c 2>$null) -match 'listening on')
# Entro i 60 s, due richieste. Da un pod senza sidecar, in chiaro: la NetworkPolicy lascia
# passare (ricette non ne ha), ma Envoy chiude la connessione: "Connection reset by peer", RIFIUTATA
kubectl run prova-chiaro -n ginvault --rm -i --restart=Never --image=busybox:1.36 --labels=sidecar.istio.io/inject=false -- sh -c 'wget -q -T 3 -O /dev/null http://ricette:8082/health && echo RISPOSTA || echo RIFIUTATA'
# Da edizioni, attraverso il suo sidecar: RISPOSTA
kubectl exec -n ginvault deploy/edizioni -c edizioni -- sh -c 'wget -q -O /dev/null http://ricette:8082/health && echo RISPOSTA || echo RIFIUTATA'
# Che cosa ha visto la scheda di rete
kubectl logs -n ginvault $r -c $c | Select-String -Pattern 'Flags', 'GET /health'
```

Nella cattura (gli IP cambiano; `kubectl get pods -n ginvault -o wide`):

| Riga | Che cos'è |
|---|---|
| `eth0 In IP 10.244.0.55.… > 10.244.0.35.8082`, poi `GET /health HTTP/1.1` | busybox: la richiesta passa sul filo in chiaro, e viene rifiutata |
| `eth0 In IP 10.244.0.50.… > 10.244.0.35.8082`, più pacchetti, nessun `GET` | edizioni: handshake TLS e dati cifrati, illeggibili |
| `lo In IP 127.0.0.6.… > 10.244.0.35.8082`, poi `GET /health HTTP/1.1` | l'Envoy di ricette passa all'applicazione la richiesta di edizioni, decifrata, dentro il pod |
| `lo In IP 127.0.0.6.…`, poi `GET /health/readiness` o `/health/liveness` | le probe, che l'agente del sidecar passa all'applicazione |

**Verificato il 12 novembre**, con la PeerAuthentication applicata:
- `istioctl x describe`: STRICT su ricette; sul gateway STRICT e la 8080 PERMISSIVE;
- da un pod senza sidecar, HTTP in chiaro verso ricette, magazzino, pagamenti e
  notifiche: `Connection reset by peer`;
- dall'Ingress: `/` e `/soci` `200`, `/api/<servizio>/health` `200` per i sei
  servizi, le API `401` senza sessione;
- edizioni riavviato sotto STRICT: database, Kafka (6 partizioni) e JWKS a
  posto, nessun errore;
- le metriche: edizioni e il gateway verso ricette in `mutual_tls`; gRPC
  produzione → magazzino (`grpc_response_status="16"`) e WebSocket verso
  notifiche (`101`) in `mutual_tls`;
- la cattura qui sopra: edizioni cifrato su `eth0`, busybox in chiaro e
  rifiutato;
- `kubectl port-forward` verso ricette: `200`;
- memoria del container minikube: 4,33 GiB su 8.

Dashboard: **Custom Resource Definitions → peerauthentications.security.istio.io**,
namespace `ginvault`: le tre PeerAuthentication.

### Istio: circuit breaker su Edizioni → Ricette

Quando Marco crea un'edizione, edizioni chiama ricette due volte e aspetta al
massimo 2 secondi:
- `GET /formulazioni/{id}`, la lettura della formulazione;
- `POST /formulazioni/{id}/utilizzo`, che la segna usata. Per la stessa
  edizione si può ripetere senza danni.

Su Kubernetes ricette ha **due repliche**, e il sidecar di edizioni decide per
ogni chiamata:
- quanto aspettare;
- se riprovare;
- se vale ancora la pena chiamare un pod.

Il codice di edizioni non cambia.

```
edizioni (applicazione)   aspetta 2 s, poi 503 "Ricette non disponibile" a Marco
  └─ Envoy di edizioni    VirtualService:   1,8 s in tutto, 0,8 s a tentativo, 1 retry su un ALTRO pod
       │                  DestinationRule:  al massimo 10 connessioni e 5 richieste in coda;
       │                                    3 errori 5xx di fila → pod escluso per 30 s
       ├──▶ ricette, pod A
       └──▶ ricette, pod B
```

Gli oggetti, in [`istio/circuit-breaker-ricette.yaml`](istio/circuit-breaker-ricette.yaml):

- **DestinationRule `ricette`**: *come* si parla con i pod di ricette. La
  applica ogni sidecar che chiama ricette, quindi anche quello del gateway.
  - `connectionPool`:
    - al massimo 10 connessioni e 5 richieste in attesa; oltre, Envoy risponde
      subito 503 invece di accodare (`upstream_rq_pending_overflow`). È una
      paratia (*bulkhead*): un picco di edizioni non affoga ricette;
    - `connectTimeout` 0,5 s, invece dei 10 s di default, che sarebbero più
      dei 2 s di edizioni;
    - al massimo 2 retry in corso. I default di Istio sono praticamente
      infiniti.
  - `outlierDetection`, il **circuit breaker**:
    - un pod con 3 errori 5xx di fila è escluso per 30 s. Contano anche i
      tentativi scaduti e le connessioni fallite, quindi viene escluso anche
      un pod lento, non solo uno che sbaglia;
    - con `maxEjectionPercent: 100` si possono escludere tutti e due i pod. Il
      circuito è aperto: Envoy risponde 503 `no healthy upstream` senza
      chiamare ricette;
    - `interval: 1s`: Envoy ricontrolla ogni secondo, quindi un pod escluso
      torna dopo 30 s, non dopo 30-40;
    - un pod escluso più volte di fila resta fuori 30, poi 60, poi 90 s;
    - `minHealthPercent: 0`: niente *panic mode*, cioè Envoy non torna a
      chiamare i pod esclusi quando ne restano pochi.
- **VirtualService `ricette`**: *quanto* aspettare e *se* riprovare.
  - La route `da-edizioni` vale solo per le chiamate del sidecar di edizioni
    (`sourceLabels`):
    - `timeout: 1.8s` in tutto;
    - `perTryTimeout: 0.8s` a tentativo;
    - `attempts: 1`, un solo retry, su un pod diverso: Istio esclude quello
      appena provato (`previous_hosts`);
    - `retryOn`: solo 502, 503, 504, tentativo scaduto, connessione fallita o
      rifiutata. Un 500 è un errore dell'applicazione e sarebbe uguale anche
      sull'altro pod.
  - La route `altri` è quella del gateway (`/api/ricette/**` dal browser):
    restano i default di Istio, cioè nessun timeout e fino a 2 retry su
    connessione fallita e 503.
- In [`ricette.yaml`](ricette.yaml) `replicas: 2`: ricette non ha stato in
  memoria né lavori pianificati, tutto è nel database.
- In [`edizioni.yaml`](edizioni.yaml) l'annotazione `proxy.istio.io/config`
  accende, solo sul sidecar di edizioni, le statistiche di Envoy del cluster
  verso ricette (`outlier_detection.*`, `upstream_rq_*`). Di default Istio
  non le espone.

**Timeout dell'applicazione e timeout del mesh: chi decide cosa.**

| | Timeout dell'applicazione | Timeout del mesh |
|---|---|---|
| Dove | `ricette.timeout: 2s` in [`edizioni/.../application.yml`](../edizioni/src/main/resources/application.yml), usato da `CatalogoRicetteRest` | `timeout` e `retries` nel VirtualService, `connectTimeout` nella DestinationRule |
| Chi lo decide | Chi scrive edizioni: quanto può aspettare Marco e che cosa rispondergli se Ricette non c'è (503 con `Retry-After`, nessuna edizione creata) | Chi gestisce la piattaforma: quanto aspettare un pod, se riprovare su un altro, quando smettere di chiamare un pod che sbaglia |
| Che cosa sa | Il significato della chiamata: 404 vuol dire formulazione inesistente (422 a Marco), 409 già usata | Solo il trasporto: codici 5xx, timeout, connessioni. Non sa che cos'è una formulazione |
| Come si cambia | Nuova immagine o variabile d'ambiente, e riavvio del pod | `kubectl apply`, attivo in pochi secondi, senza riavvii |
| Dove vale | Ovunque: Compose, test, Kubernetes | Solo su Kubernetes con Istio |

La regola è che **il timeout del mesh, retry compresi, sta dentro quello
dell'applicazione**: 0,8 + 0,8 s, più circa 25 ms tra i due tentativi, fa meno
di 1,8 s, che è meno di 2 s. Così:
- edizioni riceve sempre una risposta da Envoy prima di arrendersi da sola: al
  peggio un 504 `upstream request timeout`, che traduce nel suo 503;
- Envoy non riprova mai per una richiesta che edizioni ha già abbandonato. Al
  contrario, con il mesh a 5 s e l'applicazione a 2 s, Marco avrebbe già il suo
  503 e ricette, già in difficoltà, riceverebbe lo stesso i retry;
- i retry non si moltiplicano, perché edizioni non riprova per conto suo. Con
  un retry nel codice e uno nel mesh, una chiamata diventerebbe fino a 4
  tentativi.

Che cosa succede a una chiamata di edizioni:

| Situazione | Envoy di edizioni | edizioni riceve |
|---|---|---|
| Tutto bene | Sceglie uno dei due pod (*least request*) | La risposta di ricette |
| Un pod risponde 503 o non risponde entro 0,8 s | Riprova sull'altro pod | La risposta dell'altro pod, nessun errore |
| Un pod sbaglia 3 volte di fila | Lo esclude per 30 s e manda tutto all'altro | Nessun errore |
| Sbagliano tutti e due | Dopo 3 errori a testa li esclude tutti e due: circuito aperto | Per circa 1 s ancora errori, poi 503 `no healthy upstream` in 1-2 ms, senza chiamare ricette |
| Dopo 30 s | Riammette i pod. Se sbagliano ancora li riesclude, per 60 s | Di nuovo le risposte di ricette |
| Nessun pod (`--replicas=0`) | Lo sa da istiod: la lista degli endpoint è vuota | 503 `no healthy upstream` subito |
| Più di 10 richieste insieme, più 5 in coda | Rifiuta subito le altre (`upstream_rq_pending_overflow`) | 503 |

In tutti i casi di errore Marco riceve lo stesso 503 "Ricette non disponibile",
con `Retry-After: 5`, e nessuna edizione viene creata. Il testo cambia:
- per un 503 o 504 di Envoy il codice dice "Ricette ha risposto in modo
  inatteso…", perché tratta ogni 5xx come una risposta inattesa;
- "Ricette non risponde… più lento di 2 secondi" resta per il suo timeout, per
  esempio in Compose, dove Istio non c'è.

```powershell
kubectl get destinationrule,virtualservice -n ginvault
kubectl get pods -n ginvault -l app.kubernetes.io/name=ricette       # due pod 2/2
istioctl analyze -n ginvault                                        # No validation issues found
# Che cosa è arrivato al sidecar di edizioni
istioctl proxy-config cluster deploy/edizioni -n ginvault --fqdn ricette.ginvault.svc.cluster.local -o json   # circuitBreakers, outlierDetection, "connectTimeout": "0.500s"
istioctl proxy-config route deploy/edizioni -n ginvault --name 8082 -o json   # route "da-edizioni": "timeout": "1.800s", retryPolicy
istioctl proxy-config route deploy/gateway -n ginvault --name 8082 -o json    # il gateway ha solo la route "altri"
# I due pod visti da edizioni: OUTLIER CHECK OK, oppure FAILED se escluso
istioctl proxy-config endpoint deploy/edizioni -n ginvault --cluster "outbound|8082||ricette.ginvault.svc.cluster.local"

# Venti chiamate da edizioni, poi le statistiche del suo sidecar e come si sono divise tra i pod
kubectl exec -n ginvault deploy/edizioni -c edizioni -- sh -c 'for i in $(seq 1 20); do wget -q -O /dev/null http://ricette:8082/health; done'
kubectl exec -n ginvault deploy/edizioni -c istio-proxy -- pilot-agent request GET stats 2>$null | Select-String 'ricette.*\.(outlier_detection\.ejections_active|upstream_rq_total|upstream_rq_retry|upstream_rq_per_try_timeout|upstream_rq_pending_overflow|upstream_cx_none_healthy|membership_healthy):'
kubectl get pod -n ginvault -l app.kubernetes.io/name=ricette -o name | ForEach-Object { $_; kubectl exec -n ginvault $_ -c istio-proxy -- pilot-agent request GET stats/prometheus 2>$null | Select-String 'istio_requests_total\{reporter="destination".*source_workload="edizioni"' | ForEach-Object { if ($_.Line -match 'response_code="(\d+)".*connection_security_policy="([^"]*)"\} (\d+)') { "  $($Matches[1]) $($Matches[2]): $($Matches[3])" } } }
```

Le statistiche sono contatori dall'avvio del sidecar: si guarda come
cambiano. `ejections_active` dice quanti pod sono esclusi in questo momento,
`upstream_cx_none_healthy` quante volte Envoy ha risposto `no healthy
upstream`.

**Verificato il 12 novembre:**
- configurazione:
  - `istioctl analyze` senza problemi;
  - nel sidecar di edizioni i valori della DestinationRule, la route
    `da-edizioni` con 1,8 s, un retry da 0,8 s e `previous_hosts`;
  - nel gateway la route `altri`, con i default;
  - l'mTLS resta: la DestinationRule non tocca `tls`;
- traffico normale: 20 chiamate da edizioni, tutte 200, divise 12 e 8 tra i
  due pod, in `mutual_tls`, nessun retry;
- ricette a zero repliche: 503 `no healthy upstream` in 3 ms. Prima di Istio
  si aspettavano i 2 s del timeout di edizioni;
- i guasti della [demo](../docs/demo-istio.md):
  - un pod guasto nascosto dal retry e poi escluso;
  - senza circuit breaker 504 dopo 1,6 s a ogni chiamata;
  - con il circuit breaker `no healthy upstream` in 1 ms e Ricette non più
    chiamato;
  - il circuito di nuovo chiuso dopo 30 s;
  - la coda piena;
- memoria del container minikube: 4,82 GiB su 8.

**Provare i guasti.** La demo completa, con i comandi, è in
[`docs/demo-istio.md`](../docs/demo-istio.md). I guasti stanno in
[`istio/demo/`](istio/demo/) e si mettono dalla **parte di Ricette**: un
EnvoyFilter aggiunge al suo sidecar, in entrata, il filtro `fault` di Envoy
(503 o un ritardo). Il `fault.abort` e il `fault.delay` di un VirtualService
non servono a questo, perché agiscono nel sidecar di chi chiama, prima che
scelga un pod:
- con `fault.abort` arriva subito un 503, ma Ricette non viene chiamato e
  l'outlier detection non conta niente;
- con `fault.delay` il ritardo passa intero (3 s), perché il timeout della
  route parte dopo. Lo ferma solo il timeout di edizioni.

Verificato tutte e due le cose.

Dashboard:
- **Custom Resource Definitions** → `destinationrules.networking.istio.io` e
  `virtualservices.networking.istio.io`, namespace `ginvault`;
- **Workloads → Deployments → ricette**: 2/2 pod.

## Demo completa

Dal cluster spento alla storia di Marco, tutta dal front-end. I comandi sono
per PowerShell, nella cartella del progetto.

**0. Prima di cominciare.** Docker Desktop avviato e stack Compose spento
(`docker compose down`): il front-end di Compose e `minikube tunnel` vogliono
entrambi la porta 80.

**1. Il cluster.**

```powershell
minikube start
kubectl get node              # STATUS Ready
```

Senza opzioni `minikube start` riprende il cluster esistente con le sue
impostazioni. Se il cluster non c'è ancora: [Prerequisiti](#prerequisiti-una-volta-sola).

**2. Immagini e Secret**, solo se mancano o se il codice è cambiato:

```powershell
minikube image ls | Select-String ginvault    # frontend:0.4, gateway:0.3, edizioni:0.6, ricette:0.2, magazzino:0.3, produzione:0.4, pagamenti:0.1, notifiche:0.1
```

Per un'immagine che manca, il suo `minikube image build` (per magazzino e
produzione `docker build` + `minikube image load`) di
[Avvio da cluster vuoto](#avvio-da-cluster-vuoto). Su un cluster nuovo anche
`.\k8s\crea-secret.ps1` e `istioctl install -f k8s\istio\installazione.yaml -y`.

**3. Apply.**

```powershell
kubectl apply -k k8s/
kubectl apply -k k8s/istio/
kubectl wait -n ginvault --for=condition=Ready pod -l '!job-name' --timeout=180s   # tutti i pod, tranne quelli dei Job
kubectl wait -n ginvault --for=condition=Complete job --all --timeout=180s         # il Job dei topic Kafka è terminato
kubectl get pods -n ginvault
```

Diciotto pod `Running`:
- `2/2`, cioè l'applicazione e il sidecar di Istio: due `frontend`, `gateway`,
  `edizioni`, due `ricette`, `magazzino`, `produzione`, `pagamenti`, due `notifiche`;
- `1/1`, senza sidecar: `edizioni-db`, `ricette-db`, `magazzino-db`,
  `produzione-db`, `pagamenti-db`, `notifiche-db`, `kafka-0`.

In più il pod del Job `kafka-topic`, `0/1 Completed`: ha creato i topic ed è
terminato. Il Job sparisce da solo un'ora dopo, e il prossimo `apply` lo ricrea.

**4. Tunnel**, in un **PowerShell come amministratore** da lasciare aperto:

```powershell
minikube tunnel
```

Poi, in un PowerShell normale:

```powershell
curl.exe -I http://localhost/          # 200 con Content-Security-Policy: il front-end
curl.exe -i http://localhost/auth/me   # 401: il gateway, ancora senza sessione
```

**5. Login.** Per vedere le richieste passare, in un altro terminale:
`kubectl logs -n ingress-nginx deploy/ingress-nginx-controller -f`.

1. Apri `http://localhost` (non `127.0.0.1`) → **Accedi** → **Accedi con GitHub**,
   l'account che è in `RUOLI_DISTILLATORE`.
2. Si torna sulla home, e nel menu compaiono **Formulazioni** ed **Edizioni**.
   **Il mio profilo** mostra il ruolo DISTILLATORE.

**6. La storia di Marco.** Una formulazione alla lavanda con tre prove, il panel
sceglie la seconda, l'edizione da 12 kg e il ricalcolo dopo la pioggia.

1. **Formulazioni** → **Nuova formulazione**. Nome `Gin alla lavanda 2026`,
   botanica di stagione `lavanda`, dosi (grammi per bottiglia): ginepro 25,
   lavanda 18, coriandolo 6. **Registra la formulazione**: si apre la sua pagina,
   con la versione 1 in assaggio.
2. **Aggiungi una prova**: il modulo parte dalle dosi dell'ultima versione.
   Lavanda 20, coriandolo 5 → **Registra la prova** (versione 2). Di nuovo:
   ginepro 24, lavanda 22, coriandolo 5 (versione 3).
3. Sulla versione 2: **Scegli questa versione** → **Sì, scegli questa**. La 2 è
   scelta, la 1 e la 3 archiviate; sotto compare il modulo **Crea l'edizione**.
4. **Kg di lavanda disponibili** `12`: l'anteprima dice 600 bottiglie
   (12 000 g / 20 g, anche il massimo). Quote: **Metà ai soci, un quarto ai bar,
   il resto online** (300 / 150 / 150) → **Crea l'edizione**. Prima di salvare,
   Edizioni ha chiesto la formulazione a Ricette via REST, con il JWT di Marco,
   e l'ha segnata come usata: la sua pagina ora ha **Vai all'edizione**.
5. Si apre la pagina dell'edizione: le quote, ognuna con le sue bottiglie. In
   **Ricalcola con nuova disponibilità** scrivi `10,8`: l'anteprima mostra
   600 → 540 e il canale che perde le bottiglie. **Ricalcola la tiratura**: le
   bottiglie di ONLINE si svuotano (150 → 90), soci e bar restano uguali.
6. **Edizioni**: l'edizione nell'elenco, con la tiratura nuova.

Facoltativo, **Ricette spento**: la creazione risponde 503 e non lascia niente
a metà.

1. Prepara una seconda formulazione, `Gin d'inverno 2026`, botanica di stagione
   `arancia amara`, dosi ginepro 25 e arancia amara 15. Sceglila subito
   (versione 1), poi nel modulo **Crea l'edizione** scrivi `9` kg (600
   bottiglie) e dividi le quote, ma non crearla ancora.
2. Spegni Ricette: `kubectl scale deployment/ricette -n ginvault --replicas=0`.
3. **Crea l'edizione** → compare subito l'errore "momentaneamente fermo" con
   **Riprova**: il sidecar di Edizioni sa che Ricette non ha pod e risponde 503
   da solo (senza Istio arriverebbe dopo i 2 s del timeout di Edizioni). La
   pagina resta com'era, e in **Edizioni** c'è ancora una sola edizione.
4. Riaccendi Ricette:
   ```powershell
   kubectl scale deployment/ricette -n ginvault --replicas=2
   kubectl rollout status deployment/ricette -n ginvault    # aspetta che sia Ready
   ```
5. **Riprova**: l'edizione viene creata.

**6+. La storia di Elena**, con lo stesso cluster: Marco crea l'edizione,
Elena ordina 12 kg e ne registra 10,8, e l'edizione scende a 540 con gli
eventi Kafka. Servono i ruoli DISTILLATORE e RESP_ACQUISTI (anche sullo stesso
account). I passi sono in [`docs/demo-storia-elena.md`](../docs/demo-storia-elena.md).

**6+. La storia di Giulia**, con lo stesso cluster: Giulia prenota 2 bottiglie
in prelazione, prova la carta rifiutata, paga con la Visa di prova e riceve le
bottiglie 112 e 113; poi la consegna ridotta di Elena le manda l'avviso in
diretta. Servono la chiave di test di Stripe nel `.env` (Secret
`pagamenti-stripe`) e i ruoli SOCIO, DISTILLATORE e RESP_ACQUISTI. I passi
sono in [`docs/demo-storia-giulia.md`](../docs/demo-storia-giulia.md).

**7. Fine.** **Esci** nel menu; Ctrl+C nel PowerShell del tunnel;
`minikube stop` (oggetti e dati restano per la prossima volta).

## Demo per l'esame

**Self-healing**: il pod cancellato viene ricreato dal ReplicaSet, con un altro nome.

```powershell
kubectl delete pod -n ginvault -l app.kubernetes.io/name=edizioni
kubectl get pods -n ginvault -w          # Ctrl+C per uscire
```

**Persistenza** dei dati del DB:

```powershell
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c "create table demo(t text); insert into demo values ('prima del riavvio')"
kubectl delete pod -n ginvault -l app.kubernetes.io/name=edizioni-db
kubectl get pods -n ginvault -w
kubectl exec -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -c "select * from demo; drop table demo"
```

**Readiness contro liveness**: senza DB edizioni esce dal Service ma non viene riavviato.

```powershell
kubectl scale deployment/edizioni-db -n ginvault --replicas=0
kubectl get pods -n ginvault -w          # edizioni passa a 1/2 (il sidecar è pronto, l'applicazione no), RESTARTS resta 0
kubectl get endpointslice -n ginvault -l kubernetes.io/service-name=edizioni
kubectl scale deployment/edizioni-db -n ginvault --replicas=1
```

**Scalabilità**: edizioni è stateless, il Service distribuisce tra due pod.
Il gateway no, finché sessione e chiave stanno in memoria.

```powershell
kubectl scale deployment/edizioni -n ginvault --replicas=2
kubectl get endpointslice -n ginvault -l kubernetes.io/service-name=edizioni   # due IP
kubectl scale deployment/edizioni -n ginvault --replicas=1
```

**Istio**: sidecar, mTLS e circuit breaker con i guasti su Ricette, in
[`docs/demo-istio.md`](../docs/demo-istio.md).

**Rolling update** con una nuova versione dell'immagine:

```powershell
minikube image build -t ginvault/edizioni:0.7 .\edizioni
# cambiare il tag in k8s/edizioni.yaml (0.6 -> 0.7), poi:
kubectl apply -k k8s/
kubectl rollout status deployment/edizioni -n ginvault   # il nuovo pod parte prima che il vecchio si spenga
kubectl rollout history deployment/edizioni -n ginvault
kubectl rollout undo deployment/edizioni -n ginvault     # torna alla versione precedente
```

**Il front-end non si ferma mai**: due repliche e `maxUnavailable: 0`. Con il
tunnel aperto, un PowerShell interroga la home ogni mezzo secondo mentre un
altro riavvia i pod uno alla volta:

```powershell
1..40 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code} " http://localhost/; Start-Sleep -Milliseconds 500 }
```

```powershell
kubectl rollout restart deployment/frontend -n ginvault
kubectl rollout status deployment/frontend -n ginvault    # i pod nuovi Ready prima che i vecchi si spengano
```

Il primo terminale stampa solo `200`. Lo stesso con un pod cancellato a mano
(`kubectl delete pod -n ginvault <nome di un pod frontend>`): risponde l'altro.

**Un Secret è solo base64** (con una chiave non riservata):

```powershell
$b64 = kubectl get secret edizioni-db -n ginvault -o jsonpath='{.data.POSTGRES_USER}'
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))   # -> edizioni
```

**L'Ingress dal vivo**: durante il login, in un altro terminale,
`kubectl logs -n ingress-nginx deploy/ingress-nginx-controller -f` mostra
`/oauth2/authorization/github` (302) → `/login/oauth2/code/github` (302) →
`/` (200, backend `ginvault-frontend-http`) → `/auth/me` (200,
`ginvault-gateway-http`: la chiede il front-end per sapere chi è entrato).

## Problemi frequenti

| Sintomo | Causa | Rimedio |
|---|---|---|
| Pod in `ErrImageNeverPull` | Immagine non costruita nel nodo, o tag diverso | `minikube image build ...`, controllare con `minikube image ls` |
| Pod in `CreateContainerConfigError` | Manca un Secret o una ConfigMap | `.\k8s\crea-secret.ps1`; dettagli in `kubectl describe pod` |
| Pod in `CrashLoopBackOff` | L'applicazione termina all'avvio | `kubectl logs -n ginvault <pod> --previous` |
| Pod `0/1` o `1/2 Running` a lungo | La readiness fallisce (edizioni: DB giù?) | Eventi in `kubectl describe pod` |
| `http://localhost` non risponde | Tunnel chiuso | `minikube tunnel` da amministratore |
| 404 di nginx | Aperto `127.0.0.1` o un host diverso da `localhost` | Usare `http://localhost` |
| `redirect_uri_mismatch` | URI non registrato sull'app OAuth | Vedi Prerequisiti |
| `authorization_request_not_found` | Sessione persa (gateway riavviato, cambio tra `localhost` e `127.0.0.1`) | Rifare il login |
| edizioni: `password authentication failed` | Secret `edizioni-db` ricreato, ma il volume ricorda la vecchia password | Cancellare insieme Secret e PVC (si perdono i dati), poi script e `apply` |
| Cambiato `.env` o una ConfigMap, nessun effetto | Le variabili si leggono solo all'avvio | `kubectl rollout restart deployment/<nome> -n ginvault` |
| `kubectl diff` → `executable file not found` | Su Windows manca il programma `diff` | `kubectl apply -k k8s/ --dry-run=server` |
| `kubectl wait ... pod --all` non finisce mai | Il pod del Job `kafka-topic` è `Completed`, non diventerà mai Ready | `kubectl wait ... pod -l '!job-name'` e `kubectl wait --for=condition=Complete job --all` (vedi Avvio) |
| `apply` → `Job.batch "kafka-topic" is invalid ... field is immutable` | Cambiato il Job mentre quello vecchio esiste ancora (resta un'ora) | `kubectl delete job kafka-topic -n ginvault`, poi di nuovo `apply` |
| Job `kafka-topic` in errore, pod `Error` | Kafka non è partito in 3 minuti | `kubectl logs -n ginvault kafka-0`; il Job riprova fino a 4 volte |
| Un servizio non raggiunge Kafka (timeout su `kafka:9092`) | Al pod manca la label `ginvault/usa-kafka: "true"` | Aggiungerla nel template del Deployment |
| Un produttore fallisce con `UnknownTopicOrPartition` | Nome del topic sbagliato: la creazione automatica è spenta | Nomi in [`docs/eventi.md`](../docs/eventi.md); `kafka-topics.sh --list` |
| `POST /api/...` → 403 subito, senza arrivare al servizio | Manca l'header `X-XSRF-TOKEN` uguale al cookie `XSRF-TOKEN` (CSRF del gateway) | Vedi [`docs/demo-storia-marco.md`](../docs/demo-storia-marco.md) |
| `POST /api/edizioni/edizioni` → 503 "Ricette non disponibile" | Pod di ricette spento o non Ready | `kubectl get pods -n ginvault`; `kubectl scale deployment/ricette -n ginvault --replicas=2` |
| Edizioni risponde 503 subito anche con ricette acceso (`no healthy upstream`) | Circuito aperto: il sidecar di edizioni ha escluso i pod dopo 3 errori di fila | `istioctl proxy-config endpoint` di edizioni (vedi [circuit breaker](#istio-circuit-breaker-su-edizioni--ricette)): `OUTLIER CHECK FAILED`. Si richiude da solo dopo 30 s, se ricette risponde |
| Nessuna statistica `outlier_detection` nel sidecar di edizioni | Manca l'annotazione `proxy.istio.io/config` nel template, o il pod è nato prima | `kubectl rollout restart deployment/edizioni -n ginvault` dopo l'`apply` |
| Il `fault` del VirtualService dà 503, ma il circuito non si apre | Il fault agisce nel sidecar di chi chiama: Ricette non viene chiamato, nessun pod sbaglia | I guasti di `istio/demo/guasto-*.yaml`, sul sidecar di Ricette ([`docs/demo-istio.md`](../docs/demo-istio.md)) |
| Ricette resta escluso 60 s invece di 30 | Escluso di nuovo appena riammesso: il tempo cresce a ogni esclusione di fila | Togliere il guasto e aspettare; prima di ogni prova, i due pod `OK` in `proxy-config endpoint` |
| Dopo `kubectl apply -f k8s/istio/demo/guasto-*.yaml` le prime richieste passano ancora | Il filtro arriva ai sidecar in qualche secondo, e le connessioni già aperte finiscono sulla configurazione di prima | Ripetere le chiamate; controllare la label `ginvault/guasto=si` sui pod |
| Un servizio resta `0/1` (o `1/2`) dopo aver aggiunto le NetworkPolicy | Label del pod diversa da quella ammessa dalla policy | `kubectl get pods -n ginvault --show-labels` |
| Un servizio nuovo non verifica i JWT (401, timeout sulla JWKS) | Al pod manca la label `app.kubernetes.io/component: microservizio`, la policy del gateway lo blocca | Aggiungere la label nel template del Deployment |
| `http://localhost` risponde 502 o 503 di nginx | Pod `frontend` non Ready o non ancora partiti | `kubectl get pods -n ginvault -l app.kubernetes.io/name=frontend` |
| Dopo il login si finisce su `http://localhost:5173` | È rimasto `FRONTEND_URL` impostato per lo sviluppo con Vite (vedi [`frontend/README.md`](../frontend/README.md)) | `kubectl set env deployment/gateway -n ginvault FRONTEND_URL-` |
| La porta 80 è occupata, il tunnel non parte | Stack Compose ancora acceso (il front-end usa la 80) | `docker compose down` |
| Un microservizio `1/1` invece di `2/2` | Il pod è nato prima di Istio, o il namespace non ha la label | `kubectl get ns ginvault --show-labels`; `kubectl rollout restart deployment/<nome> -n ginvault` |
| Un database o `kafka-0` `2/2` | Manca `sidecar.istio.io/inject: "false"` nel template del pod | Aggiungerla nel template: il pod si ricrea senza sidecar |
| Un pod resta `1/2` | Il sidecar non è pronto (istiod spento?) | `kubectl get pods -n istio-system`; `kubectl logs -n ginvault <pod> -c istio-proxy` |
| Nel test delle NetworkPolicy è tutto `APERTO` | La prova passa da Envoy, che accetta la connessione | `--labels=sidecar.istio.io/inject=false` su `kubectl run`, `-c istio-proxy` su `kubectl exec` |
| Da un pod di prova `Connection reset by peer` verso un microservizio | Il pod non ha il sidecar e la PeerAuthentication è `STRICT`: il chiaro è rifiutato. È voluto | Per provare un servizio: `kubectl exec` nel container di un altro microservizio, o `kubectl port-forward` |
| `http://localhost` risponde 502 dopo `apply -k k8s/istio/` | Manca la porta 8080 `PERMISSIVE` per frontend o gateway: l'Ingress non ha il sidecar | `kubectl get peerauthentication -n ginvault` (servono `frontend-da-ingress` e `gateway-da-ingress`) |
In PowerShell 5.1: `curl` è un alias di `Invoke-WebRequest`, usare `curl.exe`;
negli argomenti di `kubectl exec` evitare le virgolette doppie annidate, che
vanno perse.

## Pulizia

- `kubectl delete -k k8s/` cancella **tutto**, compresi i Secret e i dati del DB
  (cancella il namespace).
- `minikube stop` ferma il cluster mantenendo oggetti e dati; `minikube delete`
  lo distrugge.

## Scelte e limiti (per l'orale)

- **Gateway a una replica**: sessione e chiave RSA in memoria. Per scalare:
  sessione su Redis e chiave in un Secret condiviso.
- **Database con Deployment + PVC + `Recreate`**: con una replica funziona. Il
  caso "da manuale" per i database è lo StatefulSet (nome stabile, un volume
  per replica).
- **Secret non cifrati**: in un cluster vero si aggiungono la cifratura di etcd
  e/o strumenti come Sealed Secrets o External Secrets.
- **Niente HTTPS**: Google accetta redirect `http://` solo verso `localhost`. Con
  un dominio vero servirebbe TLS sull'Ingress (es. cert-manager).
- **NetworkPolicy sui database e sui punti d'ingresso** (front-end e gateway),
  come le reti `<servizio>-db-net` e `frontend-net` di Compose. I servizi tra
  loro non sono isolati. Un passo successivo è il "default deny" su tutto il
  namespace, con regole esplicite (gateway → servizi, edizioni → ricette).
  Le policy si fidano delle label.
- **mTLS STRICT, ma un'identità sola**: tutti i pod usano la ServiceAccount
  `default`, quindi i certificati dicono solo "questo pod è nel mesh di
  ginvault". Il passo successivo è una ServiceAccount per servizio e delle
  `AuthorizationPolicy` (ricette solo da edizioni e dal gateway, magazzino:9084
  solo da produzione): autorizzazione per identità, non per label.
- **La porta 8080 di frontend e gateway accetta anche il chiaro**, perché
  l'Ingress è nginx senza sidecar. Con l'ingress gateway di Istio, o con il
  sidecar nel controller, anche quel tratto sarebbe mTLS.
- **Circuit breaker solo verso Ricette, e deciso da ogni sidecar per sé.** La
  DestinationRule vale anche per il gateway, ma ogni Envoy conta i suoi errori:
  un pod escluso da edizioni può ricevere ancora le chiamate del gateway. Le
  altre chiamate tra servizi (produzione → magazzino in gRPC, Stripe) restano
  con i default di Istio.
- **Il gateway verso Ricette ha i default di Istio**: nessun timeout e fino a 2
  retry su connessione fallita e 503, anche per le POST dal browser.
- **Il messaggio di edizioni per un 503 o 504 di Envoy** è "Ricette ha risposto
  in modo inatteso": il risultato è giusto (503, nessuna edizione), il testo
  meno preciso. Si correggerebbe in `CatalogoRicetteRest`, trattando 502, 503 e
  504 come "non disponibile".
- **Limiti del `connectionPool` bassi** (10 connessioni, 5 in coda): bastano
  per il traffico di GinVault e rendono visibile la paratia nella demo, ma 25
  richieste nello stesso istante ne fanno già traboccare qualcuna. In un
  sistema vero si dimensionano sul traffico misurato.
- **I guasti della demo sono EnvoyFilter**, cioè configurazione di Envoy così
  com'è: un'API di basso livello, che può cambiare tra le versioni di Istio.
  Vanno bene per una demo, non per la configurazione di tutti i giorni.
- **Stessa immagine del front-end per Compose e Kubernetes**: nginx contiene
  anche il proxy verso `gateway:8080`, che serve a Compose. Su Kubernetes quel
  proxy non riceve traffico, perché l'Ingress manda `/api`, `/auth`, `/oauth2`,
  `/login` e `/logout` direttamente al gateway; e la policy del gateway non
  ammette il front-end. nginx però risolve il nome `gateway` all'avvio, quindi
  il Service `gateway` deve esistere (con `apply -k` c'è sempre).
- **Il front-end non ha configurazione per ambiente**: le API sono relative
  (`/api/...`) e la stessa build va bene ovunque, purché pagine e API stiano
  sulla stessa origine.
- **Solo requests di CPU, nessun limite**: oltre la richiesta un container
  rallenta invece di fallire. Fanno eccezione i sidecar di Istio (vedi sotto).
- **Istio con il profilo minimal**: solo istiod. L'Ingress resta nginx, senza
  ingress gateway di Istio, e non ci sono Kiali, Prometheus, Grafana o
  Jaeger: costerebbero 0,5-1 GiB su 8. Lo stato si legge con `istioctl` e le
  statistiche di Envoy.
- **Sidecar con un limite di memoria basso** (256Mi, un thread): il nodo
  dichiara la memoria della VM di Docker Desktop, non gli 8 GiB del container
  minikube, quindi lo scheduler non ci protegge.
- **Kafka a un nodo**: niente repliche dei messaggi (replication factor 1) e un
  quorum KRaft di un solo controller. Se `kafka-0` si ferma, gli eventi aspettano
  nell'outbox dei servizi (dal passo 2) finché non torna. In un cluster vero: 3
  broker, replication factor 3, `min.insync.replicas=2`.
- **Kafka in chiaro**, senza TLS né autenticazione: lo protegge solo la
  NetworkPolicy. Kafka è fuori dal mesh (senza sidecar), quindi nemmeno l'mTLS
  di Istio lo copre. In un cluster vero si aggiungono TLS e SASL.

## Da Docker Compose a Kubernetes

| Docker Compose | Kubernetes |
|---|---|
| un servizio (container) | Deployment → ReplicaSet → Pod |
| nome del servizio come hostname | Service (ClusterIP) + DNS del cluster |
| `environment:` | ConfigMap |
| `.env` con i segreti | Secret |
| volume con nome | PersistentVolumeClaim → PersistentVolume |
| `healthcheck:` | startup / readiness / liveness probe |
| `restart: unless-stopped` | il ReplicaSet ricrea il pod |
| servizio che parte, fa un lavoro e termina (`kafka-topic`, `restart: "no"`) | Job |
| volume con nome di Kafka, nome `kafka` fisso | StatefulSet con `volumeClaimTemplates` + Service headless |
| `depends_on: condition: service_healthy` | nessun equivalente: si riavvia finché le dipendenze non ci sono, e la readiness tiene il pod fuori dal Service |
| `ports: "80:8080"` del front-end | Ingress + `minikube tunnel` |
| nginx del front-end smista `/api`, `/login`... verso il gateway | regole per path dell'Ingress |
| reti private `<servizio>-db-net` (sei), `frontend-net`, `kafka-net` | NetworkPolicy (`networkpolicy-db.yaml`, `networkpolicy-frontend-gateway.yaml`, `networkpolicy-kafka.yaml`) |
| `docker compose up --build` | `minikube image build` + `kubectl apply -k k8s/` |
