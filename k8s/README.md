# GinVault su Kubernetes (Minikube)

Deploy **parziale**, ammesso dal piano ([`docs/piano.md`](../docs/piano.md)):
gateway, edizioni e il suo database, esposti con un Ingress. Gli altri servizi
e Kafka arrivano nei passi successivi, insieme alle NetworkPolicy che isolano
ogni database.

```
browser
   │  http://localhost   (cookie SESSION; il JWT non arriva mai al browser)
   ▼
minikube tunnel              porta 127.0.0.1:80 del portatile → Ingress del cluster
   ▼
ingress-nginx-controller     namespace ingress-nginx: applica l'Ingress "ginvault"
   ▼                         (host localhost, path / → Service gateway)
Service gateway :8080    →  pod gateway     login OAuth2, sessione, firma del JWT
   │  Authorization: Bearer <JWT>            ▲
   ▼                                         │ GET /.well-known/jwks.json
Service edizioni :8081   →  pod edizioni ────┘ (chiave pubblica per verificare il JWT)
   │  JDBC
   ▼
Service edizioni-db :5432 → pod Postgres  →  PersistentVolumeClaim (1 GiB)
```

## File

| File | Oggetti |
|---|---|
| `namespace.yaml` | Namespace `ginvault` |
| `edizioni-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `edizioni.yaml` | ConfigMap, Deployment, Service |
| `gateway.yaml` | ConfigMap, Deployment, Service |
| `ingress.yaml` | Ingress |
| `kustomization.yaml` | Elenco dei file: `kubectl apply -k k8s/` li applica tutti |
| `crea-secret.ps1` | Secret `gateway-segreti` (dal `.env`) ed `edizioni-db` (password casuale) |

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

Note sul cluster:

- **Container runtime containerd** (default di Minikube 1.39): le immagini si
  costruiscono con `minikube image build`. `minikube docker-env` con containerd
  su Windows non funziona.
- **CNI kindnet**: applica le NetworkPolicy (verificato con una policy deny-all).
- `kubectl get node` e `kubectl top node` mostrano CPU e RAM della VM di Docker
  Desktop (32 CPU, ~16 GB), non i limiti del container di Minikube (4 CPU, 8 GB).

## Avvio da cluster vuoto

Da PowerShell, nella cartella del progetto:

```powershell
# 1) Immagini, costruite DENTRO il nodo Minikube (nessun registry)
minikube image build -t ginvault/edizioni:0.1 .\edizioni
minikube image build -t ginvault/gateway:0.1  .\gateway

# 2) Namespace e Secret (legge il .env, non stampa i valori)
.\k8s\crea-secret.ps1

# 3) Tutti gli altri oggetti
kubectl apply -k k8s/

# 4) Attendere che i pod siano pronti
kubectl wait -n ginvault --for=condition=Ready pod --all --timeout=180s
kubectl get pods -n ginvault
```

Poi, in un **PowerShell come amministratore** (il tunnel apre la porta 80 del
portatile), da lasciare aperto:

```powershell
minikube tunnel
```

E nel browser, sempre con `localhost` e mai con `127.0.0.1`:

1. `http://localhost/login` → GitHub o Google
2. `http://localhost/auth/me` → identità e ruoli, letti dalla sessione del gateway
3. `http://localhost/api/edizioni/me` → la stessa identità, letta da edizioni nel JWT
4. `http://localhost/api/edizioni/prova` → consentito solo al ruolo SOCIO
5. `http://localhost/logout`

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

- `gateway-segreti` (8 chiavi: OAuth e `RUOLI_*`) → al gateway con `envFrom`.
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

| | edizioni-db | edizioni | gateway |
|---|---|---|---|
| Immagine | `postgres:17-alpine` | `ginvault/edizioni:0.1` | `ginvault/gateway:0.1` |
| Repliche | 1 | 1 (scalabile: è stateless) | 1 (sessione e chiave in memoria) |
| Strategia | `Recreate` (mai due Postgres sullo stesso volume) | `RollingUpdate` | `RollingUpdate` |
| startupProbe | `pg_isready`, fino a 60 s | `/health/liveness`, fino a 2 min | `/health/liveness`, fino a 2 min |
| readinessProbe | `pg_isready` | `/health/readiness` (include il DB) | `/health/readiness` (non dipende dai servizi a valle) |
| livenessProbe | `pg_isready` | `/health/liveness` (non include il DB) | `/health/liveness` |
| requests / limits memoria | 128 Mi / 512 Mi | 384 Mi / 768 Mi | 384 Mi / 768 Mi |

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
cambiano IP a ogni riavvio: `edizioni-db`, `edizioni`, `gateway` (nome completo
`<nome>.ginvault.svc.cluster.local`). Sceglie i pod con il `selector`
(`app.kubernetes.io/name`) e inoltra solo a quelli Ready, elencati
nell'**EndpointSlice**. Tutti i nostri Service sono ClusterIP: da fuori si entra
solo dall'Ingress.

```powershell
kubectl get svc,endpointslice -n ginvault
kubectl port-forward -n ginvault svc/edizioni 18081:8081   # raggiungere un Service dal portatile
curl.exe http://localhost:18081/health/readiness
```

Dashboard: **Service → Services** (con l'elenco degli endpoint).

### Ingress, IngressClass, Ingress Controller

L'**Ingress** `ginvault` è solo una *regola*: host `localhost`, path `/`
(Prefix) → Service `gateway`, porta `http`. A eseguirla è l'**Ingress
Controller** ingress-nginx (addon di Minikube), un nginx nel namespace
`ingress-nginx` che legge gli Ingress del cluster e si riconfigura da solo;
l'**IngressClass** `nginx` collega le regole al controller.

- Con il driver Docker su Windows l'IP del nodo (colonna ADDRESS, 192.168.49.2)
  non è raggiungibile: `minikube tunnel` porta l'Ingress su `127.0.0.1:80`.
- `http://127.0.0.1` risponde 404 di proposito: la regola accetta solo l'host
  `localhost`, quello registrato come redirect URI.

```powershell
kubectl get ingress -n ginvault
kubectl describe ingress ginvault -n ginvault          # regola → IP del pod gateway
kubectl get ingressclass
kubectl logs -n ingress-nginx deploy/ingress-nginx-controller -f   # ogni richiesta del browser
```

Dashboard: **Service → Ingresses**; namespace `ingress-nginx` → **Workloads →
Pods** per il controller e i suoi log.

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
kubectl get pods -n ginvault -w          # edizioni passa a 0/1, RESTARTS resta 0
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

**Rolling update** con una nuova versione dell'immagine:

```powershell
minikube image build -t ginvault/edizioni:0.2 .\edizioni
# cambiare il tag in k8s/edizioni.yaml (0.1 -> 0.2), poi:
kubectl apply -k k8s/
kubectl rollout status deployment/edizioni -n ginvault   # il nuovo pod parte prima che il vecchio si spenga
kubectl rollout history deployment/edizioni -n ginvault
kubectl rollout undo deployment/edizioni -n ginvault     # torna alla versione precedente
```

**Un Secret è solo base64** (con una chiave non riservata):

```powershell
$b64 = kubectl get secret edizioni-db -n ginvault -o jsonpath='{.data.POSTGRES_USER}'
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))   # -> edizioni
```

**L'Ingress dal vivo**: durante il login, in un altro terminale,
`kubectl logs -n ingress-nginx deploy/ingress-nginx-controller -f` mostra
`/oauth2/authorization/github` (302) → `/login/oauth2/code/github` (302) →
`/auth/me` (200).

## Problemi frequenti

| Sintomo | Causa | Rimedio |
|---|---|---|
| Pod in `ErrImageNeverPull` | Immagine non costruita nel nodo, o tag diverso | `minikube image build ...`, controllare con `minikube image ls` |
| Pod in `CreateContainerConfigError` | Manca un Secret o una ConfigMap | `.\k8s\crea-secret.ps1`; dettagli in `kubectl describe pod` |
| Pod in `CrashLoopBackOff` | L'applicazione termina all'avvio | `kubectl logs -n ginvault <pod> --previous` |
| Pod `0/1 Running` a lungo | La readiness fallisce (edizioni: DB giù?) | Eventi in `kubectl describe pod` |
| `http://localhost` non risponde | Tunnel chiuso | `minikube tunnel` da amministratore |
| 404 di nginx | Aperto `127.0.0.1` o un host diverso da `localhost` | Usare `http://localhost` |
| `redirect_uri_mismatch` | URI non registrato sull'app OAuth | Vedi Prerequisiti |
| `authorization_request_not_found` | Sessione persa (gateway riavviato, cambio tra `localhost` e `127.0.0.1`) | Rifare il login |
| edizioni: `password authentication failed` | Secret `edizioni-db` ricreato, ma il volume ricorda la vecchia password | Cancellare insieme Secret e PVC (si perdono i dati), poi script e `apply` |
| Cambiato `.env` o una ConfigMap, nessun effetto | Le variabili si leggono solo all'avvio | `kubectl rollout restart deployment/<nome> -n ginvault` |
| `kubectl diff` → `executable file not found` | Su Windows manca il programma `diff` | `kubectl apply -k k8s/ --dry-run=server` |

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
- **NetworkPolicy**: kindnet le applica; arrivano con gli altri servizi, per
  isolare ogni database come le reti `<servizio>-db-net` di Compose.
- **Solo requests di CPU, nessun limite**: oltre la richiesta un container
  rallenta invece di fallire.

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
| `depends_on: condition: service_healthy` | nessun equivalente: si riavvia finché le dipendenze non ci sono, e la readiness tiene il pod fuori dal Service |
| `ports: "8080:8080"` | Ingress + `minikube tunnel` |
| reti private `<servizio>-db-net` | NetworkPolicy (da aggiungere) |
| `docker compose up --build` | `minikube image build` + `kubectl apply -k k8s/` |
