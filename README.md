# GinVault

Progetto d'esame per il corso TAASS: backend a microservizi con API Gateway,
Kafka e un database PostgreSQL per ogni servizio, avviato con Docker Compose.

> **Stato: passo 1, "Fondamenta".** Ogni servizio si avvia, ha un endpoint
> `GET /health` e il proprio database. Mancano ancora la logica di dominio, la
> sicurezza OAuth, gRPC e Kubernetes.

## Architettura

```
                    host (portatile)
                          │  :8080  (unica porta esposta)
                    ┌─────▼─────┐
                    │  gateway  │  Spring Cloud Gateway
                    └─────┬─────┘
   rete "backend"         │  /api/<servizio>/**  →  http://<servizio>:<porta>/**
 ┌──────────┬──────────┬──┴───────┬──────────┬───────────┬───────────┐
 edizioni   ricette    produzione magazzino  pagamenti   notifiche   kafka
  :8081      :8082      :8083      :8084      :8085       :8086      :9092
   │          │          │          │          │         (NestJS)   (KRaft)
 edizioni-db ricette-db produzione-db magazzino-db pagamenti-db
   (ognuno su una rete privata condivisa SOLO con il proprio servizio)
```

| Servizio    | Tecnologia                  | Porta | Database        | Via gateway                       |
|-------------|-----------------------------|-------|-----------------|-----------------------------------|
| gateway     | Spring Cloud Gateway        | 8080  | –               | `http://localhost:8080/health`    |
| edizioni    | Spring Boot 3.5 / Java 21   | 8081  | `edizioni-db`   | `/api/edizioni/health`            |
| ricette     | Spring Boot 3.5 / Java 21   | 8082  | `ricette-db`    | `/api/ricette/health`             |
| produzione  | Spring Boot 3.5 / Java 21   | 8083  | `produzione-db` | `/api/produzione/health`          |
| magazzino   | Spring Boot 3.5 / Java 21   | 8084  | `magazzino-db`  | `/api/magazzino/health`           |
| pagamenti   | Spring Boot 3.5 / Java 21   | 8085  | `pagamenti-db`  | `/api/pagamenti/health`           |
| notifiche   | NestJS 12 / Node 24         | 8086  | –               | `/api/notifiche/health`           |
| kafka       | Apache Kafka 4.3 (KRaft)    | 9092  | –               | non esposto                       |

## Struttura del repository

```
ginvault/
├── docker-compose.yml   # avvia tutto: servizi, database, Kafka
├── edizioni/            # un progetto Maven indipendente per servizio
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/{java,resources/application.yml}
├── ricette/  produzione/  magazzino/  pagamenti/   # stessa struttura
├── gateway/             # Spring Cloud Gateway (nessun DB)
├── notifiche/           # NestJS
├── frontend/            # (vuoto per ora)
├── k8s/                 # (vuoto per ora)
└── docs/
```

## Prerequisiti

- **Docker Desktop** avviato. Tutto lo stack occupa circa **2,1 GB di RAM**.
- Non servono né Java né Maven né Node installati: la compilazione avviene
  dentro i container (Dockerfile multi-stage).
- Solo per sviluppare in locale: JDK 21, e Node ≥ 24.15 per `notifiche`.

## Avvio

```bash
# Costruisce le immagini, avvia tutti i container e attende che siano "healthy"
docker compose up -d --build --wait
```

Il primo avvio richiede qualche minuto, perché scarica le immagini e le
dipendenze Maven/npm. I successivi sono molto più rapidi grazie alla cache.

## Verifica

```bash
docker compose ps                                  # tutti "Up ... (healthy)"

curl http://localhost:8080/health                  # gateway
curl http://localhost:8080/api/edizioni/health     # → {"status":"UP","components":{"db":{"status":"UP",...
curl http://localhost:8080/api/ricette/health
curl http://localhost:8080/api/produzione/health
curl http://localhost:8080/api/magazzino/health
curl http://localhost:8080/api/pagamenti/health
curl http://localhost:8080/api/notifiche/health    # → {"status":"UP"}

curl http://localhost:8080/gateway/routes          # route caricate dal gateway
```

> **Windows PowerShell:** usare `curl.exe` invece di `curl`, perché `curl` è
> un alias di `Invoke-WebRequest`. In alternativa si può aprire l'URL nel browser.

## Comandi utili

```bash
docker compose logs -f edizioni                    # log di un servizio (Ctrl+C per uscire)
docker compose up -d --build --wait edizioni       # ricostruisce e riavvia un solo servizio
docker compose restart edizioni                    # riavvia senza ricostruire
docker compose stop                                # ferma tutto (i container restano)
docker compose down                                # rimuove container e reti (i dati restano nei volumi)
docker compose down -v                             # rimuove anche i volumi: DB e Kafka ripartono vuoti
```

### Database

```bash
# Console psql sul DB di un servizio
docker compose exec edizioni-db psql -U edizioni -d edizioni
```

### Kafka

```bash
# Stato del quorum KRaft (il nodo 1 è leader e unico votante)
docker compose exec kafka /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status

# Elenco dei topic
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

> **Git Bash su Windows:** i percorsi che iniziano con `/` (es. `/opt/kafka/...`)
> vengono convertiti in percorsi Windows. Anteporre `MSYS_NO_PATHCONV=1` al
> comando, oppure usare PowerShell.

### Isolamento dei database

Ogni database è collegato soltanto alla rete privata del proprio servizio:

```bash
docker compose exec ricette sh -c "nc -z -w 2 edizioni-db 5432"    # → nc: bad address 'edizioni-db'
docker compose exec edizioni sh -c "nc -z -w 2 edizioni-db 5432 && echo ok"   # → ok
docker network ls --filter name=ginvault
```

## Sviluppo in IntelliJ

Ogni servizio è un progetto Maven a sé. Per aprirlo: tasto destro su
`<servizio>/pom.xml` → *Add as Maven Project*.

I valori di default in `application.yml` puntano a `localhost`, quindi un
servizio avviato da IntelliJ cerca Postgres su `localhost:5432`. Con lo stack
di questo passo, però, le porte dei DB non sono esposte sull'host: per ora
il modo di avviare il sistema è Docker Compose.
