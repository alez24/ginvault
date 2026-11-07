# GinVault su Kubernetes (Minikube)

Deploy **parziale**, ammesso dal piano ([`docs/piano.md`](../docs/piano.md)):
front-end, gateway, edizioni, ricette e i database dei due servizi, esposti con
un Ingress. Delle NetworkPolicy isolano i database, il front-end e il gateway.
Gli altri servizi e Kafka arrivano nei passi successivi.

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
   │             Service ricette :8082  →  pod ricette  ── JDBC ──▶ Service ricette-db :5432
   ▼
Service edizioni-db :5432 → pod Postgres  →  PersistentVolumeClaim (1 GiB)

NetworkPolicy: frontend accetta solo l'Ingress Controller; gateway l'Ingress
Controller e i microservizi (JWKS); edizioni-db solo edizioni, ricette-db solo ricette.
```

Il gateway inoltra anche `/api/ricette/**` al Service `ricette`.

Pagine e API hanno la stessa origine, `http://localhost`: i cookie del gateway
valgono anche per le pagine e non serve CORS. Lo smistamento per path lo fa qui
l'Ingress, con Docker Compose nginx nel container del front-end e in sviluppo il
proxy di Vite.

## File

| File | Oggetti |
|---|---|
| `namespace.yaml` | Namespace `ginvault` |
| `edizioni-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `edizioni.yaml` | ConfigMap, Deployment, Service |
| `ricette-db.yaml` | PersistentVolumeClaim, Deployment, Service |
| `ricette.yaml` | ConfigMap, Deployment, Service |
| `networkpolicy-db.yaml` | Due NetworkPolicy: ogni database accetta solo il suo servizio |
| `networkpolicy-frontend-gateway.yaml` | Due NetworkPolicy: il front-end accetta solo l'Ingress Controller, il gateway l'Ingress Controller e i microservizi |
| `gateway.yaml` | ConfigMap, Deployment, Service |
| `frontend.yaml` | Deployment (2 repliche), Service |
| `ingress.yaml` | Ingress: `/api`, `/auth`, `/oauth2`, `/login`, `/logout` → gateway; tutto il resto → frontend |
| `kustomization.yaml` | Elenco dei file: `kubectl apply -k k8s/` li applica tutti |
| `crea-secret.ps1` | Secret `gateway-segreti` (dal `.env`), `edizioni-db` e `ricette-db` (password casuali) |

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
- **CNI kindnet**: applica le NetworkPolicy (verificato con una policy deny-all)
  e lascia passare le probe HTTP del kubelet anche verso i pod protetti
  (arrivano da 10.244.0.1, l'IP del nodo sulla rete dei pod).
- `kubectl get node` e `kubectl top node` mostrano CPU e RAM della VM di Docker
  Desktop (32 CPU, ~16 GB), non i limiti del container di Minikube (4 CPU, 8 GB).

## Avvio da cluster vuoto

Da PowerShell, nella cartella del progetto:

```powershell
# 1) Immagini, costruite DENTRO il nodo Minikube (nessun registry)
minikube image build -t ginvault/edizioni:0.3 .\edizioni
minikube image build -t ginvault/ricette:0.2  .\ricette
minikube image build -t ginvault/gateway:0.3  .\gateway
minikube image build -t ginvault/frontend:0.1 .\frontend   # npm ci, test e build di Vite dentro l'immagine

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

| | edizioni-db | edizioni | gateway | frontend |
|---|---|---|---|---|
| Immagine | `postgres:17-alpine` | `ginvault/edizioni:0.3` | `ginvault/gateway:0.3` | `ginvault/frontend:0.1` (nginx) |
| Repliche | 1 | 1 (scalabile: è stateless) | 1 (sessione e chiave in memoria) | 2 (solo file statici) |
| Strategia | `Recreate` (mai due Postgres sullo stesso volume) | `RollingUpdate` | `RollingUpdate` | `RollingUpdate`, `maxSurge 1`, `maxUnavailable 0` |
| startupProbe | `pg_isready`, fino a 60 s | `/health/liveness`, fino a 2 min | `/health/liveness`, fino a 2 min | nessuna: nginx parte in un attimo |
| readinessProbe | `pg_isready` | `/health/readiness` (include il DB) | `/health/readiness` (non dipende dai servizi a valle) | `GET /` ogni 10 s |
| livenessProbe | `pg_isready` | `/health/liveness` (non include il DB) | `/health/liveness` | `GET /` ogni 20 s |
| requests / limits memoria | 128 Mi / 512 Mi | 384 Mi / 768 Mi | 384 Mi / 768 Mi | 16 Mi / 64 Mi (ne usa ~3) |

ricette e ricette-db sono come edizioni ed edizioni-db (`ginvault/ricette:0.2`).

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

```powershell
kubectl get svc,endpointslice -n ginvault
kubectl port-forward -n ginvault svc/edizioni 18081:8081   # raggiungere un Service dal portatile
curl.exe http://localhost:18081/health/readiness
kubectl port-forward -n ginvault svc/frontend 18080:80     # il front-end senza Ingress
```

Dashboard: **Service → Services** (con l'elenco degli endpoint).

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
`frontend-net`); qui lo danno quattro **NetworkPolicy**:

| NetworkPolicy | Pod protetti | Sorgenti ammesse | Porta |
|---|---|---|---|
| `edizioni-db-solo-da-edizioni` | edizioni-db | i pod di edizioni | 5432 |
| `ricette-db-solo-da-ricette` | ricette-db | i pod di ricette | 5432 |
| `frontend-solo-da-ingress` | frontend | il controller ingress-nginx | 8080 |
| `gateway-da-ingress-e-servizi` | gateway | il controller ingress-nginx; i pod con `app.kubernetes.io/component: microservizio` (scaricano la JWKS) | 8080 |

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

# Un pod qualsiasi NON raggiunge database, front-end e gateway (timeout dopo 3 s)...
# (virgolette semplici: con le doppie PowerShell sostituirebbe $s prima di passarlo)
kubectl run prova-rete -n ginvault --rm -it --restart=Never --image=busybox:1.36 -- sh -c 'for s in edizioni-db:5432 ricette-db:5432 frontend:80 gateway:8080; do nc -z -w 3 ${s%:*} ${s#*:} && echo $s APERTO || echo $s BLOCCATO; done'
# ...ma raggiunge ricette, che è un servizio
kubectl run prova-rete -n ginvault --rm -it --restart=Never --image=busybox:1.36 -- sh -c "nc -z -w 3 ricette 8082 && echo APERTO || echo BLOCCATO"

# Da edizioni (label microservizio) la JWKS del gateway è raggiungibile, il front-end no
kubectl exec -n ginvault deploy/edizioni -- sh -c "wget -q -T 3 -O /dev/null http://gateway:8080/.well-known/jwks.json && echo JWKS APERTO || echo JWKS BLOCCATO; wget -q -T 3 -O /dev/null http://frontend/ && echo FRONTEND APERTO || echo FRONTEND BLOCCATO"

# I servizi raggiungono il proprio database: sono Ready, e la readiness include "db"
kubectl get pods -n ginvault
```

Il gateway si fida di una **label**: chi può creare pod nel namespace può darsi
`component: microservizio` e raggiungerlo. Lo stesso vale per qualsiasi
NetworkPolicy basata su label; con Istio l'identità diventerà un certificato
(mTLS) invece di una label.

Dashboard: **Service → Network Policies**.

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
minikube image ls | Select-String ginvault    # frontend:0.1, gateway:0.3, edizioni:0.3, ricette:0.2
```

Per un'immagine che manca, il suo `minikube image build` di
[Avvio da cluster vuoto](#avvio-da-cluster-vuoto). Su un cluster nuovo anche
`.\k8s\crea-secret.ps1`.

**3. Apply.**

```powershell
kubectl apply -k k8s/
kubectl wait -n ginvault --for=condition=Ready pod --all --timeout=180s
kubectl get pods -n ginvault
```

Sette pod `1/1 Running`: due `frontend`, `gateway`, `edizioni`, `edizioni-db`,
`ricette`, `ricette-db`.

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
3. **Crea l'edizione** → dopo circa 2 s (il timeout di Edizioni verso Ricette)
   compare l'errore "momentaneamente fermo" con **Riprova**. La pagina resta
   com'era, e in **Edizioni** c'è ancora una sola edizione.
4. Riaccendi Ricette:
   ```powershell
   kubectl scale deployment/ricette -n ginvault --replicas=1
   kubectl rollout status deployment/ricette -n ginvault    # aspetta che sia Ready
   ```
5. **Riprova**: l'edizione viene creata.

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
minikube image build -t ginvault/edizioni:0.4 .\edizioni
# cambiare il tag in k8s/edizioni.yaml (0.3 -> 0.4), poi:
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
| Pod `0/1 Running` a lungo | La readiness fallisce (edizioni: DB giù?) | Eventi in `kubectl describe pod` |
| `http://localhost` non risponde | Tunnel chiuso | `minikube tunnel` da amministratore |
| 404 di nginx | Aperto `127.0.0.1` o un host diverso da `localhost` | Usare `http://localhost` |
| `redirect_uri_mismatch` | URI non registrato sull'app OAuth | Vedi Prerequisiti |
| `authorization_request_not_found` | Sessione persa (gateway riavviato, cambio tra `localhost` e `127.0.0.1`) | Rifare il login |
| edizioni: `password authentication failed` | Secret `edizioni-db` ricreato, ma il volume ricorda la vecchia password | Cancellare insieme Secret e PVC (si perdono i dati), poi script e `apply` |
| Cambiato `.env` o una ConfigMap, nessun effetto | Le variabili si leggono solo all'avvio | `kubectl rollout restart deployment/<nome> -n ginvault` |
| `kubectl diff` → `executable file not found` | Su Windows manca il programma `diff` | `kubectl apply -k k8s/ --dry-run=server` |
| `POST /api/...` → 403 subito, senza arrivare al servizio | Manca l'header `X-XSRF-TOKEN` uguale al cookie `XSRF-TOKEN` (CSRF del gateway) | Vedi [`docs/demo-storia-marco.md`](../docs/demo-storia-marco.md) |
| `POST /api/edizioni/edizioni` → 503 "Ricette non disponibile" | Pod di ricette spento o non Ready | `kubectl get pods -n ginvault`; `kubectl scale deployment/ricette -n ginvault --replicas=1` |
| Un servizio resta `0/1` dopo aver aggiunto le NetworkPolicy | Label del pod diversa da quella ammessa dalla policy | `kubectl get pods -n ginvault --show-labels` |
| Un servizio nuovo non verifica i JWT (401, timeout sulla JWKS) | Al pod manca la label `app.kubernetes.io/component: microservizio`, la policy del gateway lo blocca | Aggiungere la label nel template del Deployment |
| `http://localhost` risponde 502 o 503 di nginx | Pod `frontend` non Ready o non ancora partiti | `kubectl get pods -n ginvault -l app.kubernetes.io/name=frontend` |
| Dopo il login si finisce su `http://localhost:5173` | È rimasto `FRONTEND_URL` impostato per lo sviluppo con Vite (vedi [`frontend/README.md`](../frontend/README.md)) | `kubectl set env deployment/gateway -n ginvault FRONTEND_URL-` |
| La porta 80 è occupata, il tunnel non parte | Stack Compose ancora acceso (il front-end usa la 80) | `docker compose down` |

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
  Le policy si fidano delle label; con Istio diventerà autorizzazione per
  identità (mTLS).
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
| `ports: "80:8080"` del front-end | Ingress + `minikube tunnel` |
| nginx del front-end smista `/api`, `/login`... verso il gateway | regole per path dell'Ingress |
| reti private `<servizio>-db-net`, `frontend-net` | NetworkPolicy (`networkpolicy-db.yaml`, `networkpolicy-frontend-gateway.yaml`) |
| `docker compose up --build` | `minikube image build` + `kubectl apply -k k8s/` |
