/**
 * L'ambiente dei test di integrazione: PostgreSQL e Kafka veri con
 * Testcontainers, un finto gateway che pubblica la JWKS e firma i JWT, e le
 * istanze di Notifiche avviate nello stesso processo.
 */
import type { INestApplication } from '@nestjs/common';
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import { exportJWK, generateKeyPair, SignJWT } from 'jose';
import { Kafka, logLevel, Partitioners, type Producer } from 'kafkajs';
import { createServer, type Server } from 'node:http';
import { type AddressInfo, createServer as creaServerTcp } from 'node:net';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import {
  GenericContainer,
  getContainerRuntimeClient,
  type StartedTestContainer,
  Wait,
} from 'testcontainers';
import { WebSocket } from 'ws';
import { creaApp } from '../../src/app';
import type { Configurazione } from '../../src/configurazione';

export const TOPIC = ['edizioni.eventi', 'pagamenti.esiti', 'notifiche.dlt'];

export interface Ambiente {
  postgres: StartedPostgreSqlContainer;
  kafkaContainer: StartedTestContainer;
  bootstrap: string;
  kafka: Kafka;
  producer: Producer;
  db: Pool;
  gateway: GatewayFinto;
  chiudi(): Promise<void>;
}

export async function avviaAmbiente(): Promise<Ambiente> {
  const [postgres, { container: kafkaContainer, bootstrap }, gateway] = await Promise.all([
    new PostgreSqlContainer('postgres:17-alpine')
      .withDatabase('notifiche')
      .withUsername('notifiche')
      .withPassword('notifiche')
      .start(),
    avviaKafka(),
    GatewayFinto.avvia(),
  ]);
  const kafka = new Kafka({ clientId: 'test', brokers: [bootstrap], logLevel: logLevel.WARN });
  const admin = kafka.admin();
  await admin.connect();
  await admin.createTopics({
    waitForLeaders: true,
    topics: TOPIC.map((topic) => ({ topic, numPartitions: 3, replicationFactor: 1 })),
  });
  await admin.disconnect();
  const producer = kafka.producer({ createPartitioner: Partitioners.DefaultPartitioner });
  await producer.connect();
  const db = new Pool({
    host: postgres.getHost(),
    port: postgres.getPort(),
    database: 'notifiche',
    user: 'notifiche',
    password: 'notifiche',
  });
  return {
    postgres,
    kafkaContainer,
    bootstrap,
    kafka,
    producer,
    db,
    gateway,
    async chiudi() {
      await producer.disconnect();
      await db.end();
      await gateway.chiudi();
      await Promise.all([postgres.stop(), kafkaContainer.stop()]);
    },
  };
}

/**
 * apache/kafka:4.3.0 come in Compose. Il broker dice ai client a che indirizzo
 * collegarsi (advertised listener): deve essere la porta sul portatile, quindi
 * la si sceglie prima di avviare il container.
 */
async function avviaKafka(): Promise<{ container: StartedTestContainer; bootstrap: string }> {
  const porta = await portaLibera();
  const host = (await getContainerRuntimeClient()).info.containerRuntime.host;
  const container = await new GenericContainer('apache/kafka:4.3.0')
    .withExposedPorts({ container: 9092, host: porta })
    .withEnvironment({
      KAFKA_NODE_ID: '1',
      KAFKA_PROCESS_ROLES: 'broker,controller',
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@localhost:9093',
      KAFKA_LISTENERS: 'PLAINTEXT://:9092,CONTROLLER://:9093',
      KAFKA_ADVERTISED_LISTENERS: `PLAINTEXT://${host}:${porta}`,
      KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER',
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: '1',
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: '1',
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: '1',
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: '0',
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false',
      KAFKA_HEAP_OPTS: '-Xmx512m',
    })
    .withWaitStrategy(Wait.forLogMessage(/Kafka Server started/))
    .withStartupTimeout(120_000)
    .start();
  return { container, bootstrap: `${host}:${porta}` };
}

function portaLibera(): Promise<number> {
  return new Promise((risolvi, rifiuta) => {
    const server = creaServerTcp();
    server.once('error', rifiuta);
    server.listen(0, () => {
      const { port } = server.address() as AddressInfo;
      server.close(() => risolvi(port));
    });
  });
}

/** Il gateway per finta: una JWKS su HTTP e i JWT firmati con la sua chiave. */
export class GatewayFinto {
  private constructor(
    private readonly server: Server,
    private readonly chiavePrivata: CryptoKey,
    readonly jwksUri: string,
  ) {}

  static async avvia(): Promise<GatewayFinto> {
    const { publicKey, privateKey } = await generateKeyPair('RS256');
    const jwk = { ...(await exportJWK(publicKey)), kid: 'gateway-test', alg: 'RS256', use: 'sig' };
    const server = createServer((_, risposta) => {
      risposta.writeHead(200, { 'Content-Type': 'application/json' });
      risposta.end(JSON.stringify({ keys: [jwk] }));
    });
    await new Promise<void>((risolvi) => server.listen(0, '127.0.0.1', risolvi));
    const { port } = server.address() as AddressInfo;
    return new GatewayFinto(server, privateKey, `http://127.0.0.1:${port}/.well-known/jwks.json`);
  }

  /** Un JWT come quelli del gateway (5 minuti), o con la durata indicata. */
  token(sub: string, durataSecondi = 300, issuer = 'ginvault-gateway'): Promise<string> {
    return firma(this.chiavePrivata, sub, durataSecondi, issuer);
  }

  chiudi(): Promise<void> {
    return new Promise((risolvi) => this.server.close(() => risolvi()));
  }
}

/** Un JWT firmato con un'altra chiave: la firma non torna. */
export async function tokenFalso(sub: string): Promise<string> {
  const { privateKey } = await generateKeyPair('RS256');
  return firma(privateKey, sub, 300, 'ginvault-gateway');
}

function firma(chiave: CryptoKey, sub: string, durata: number, issuer: string): Promise<string> {
  const adesso = Math.floor(Date.now() / 1000);
  return new SignJWT({ roles: ['SOCIO'], name: sub })
    .setProtectedHeader({ alg: 'RS256', kid: 'gateway-test' })
    .setIssuer(issuer)
    .setSubject(sub)
    .setIssuedAt(adesso)
    .setExpirationTime(adesso + durata)
    .sign(chiave);
}

export interface Istanza {
  app: INestApplication;
  http: string;
  ws: string;
}

/** Un'istanza di Notifiche; con kafka false non legge eventi (solo REST, WebSocket e LISTEN). */
export async function avviaNotifiche(ambiente: Ambiente, kafka = true): Promise<Istanza> {
  const configurazione: Configurazione = {
    porta: 0,
    jwt: { jwksUri: ambiente.gateway.jwksUri, issuer: 'ginvault-gateway' },
    database: {
      host: ambiente.postgres.getHost(),
      porta: ambiente.postgres.getPort(),
      nome: 'notifiche',
      utente: 'notifiche',
      password: 'notifiche',
      maxConnessioni: 5,
    },
    kafka: {
      attivo: kafka,
      bootstrap: [ambiente.bootstrap],
      clientId: 'notifiche-test',
      gruppo: 'notifiche',
      tentativiMs: [100, 200],
    },
    avvisi: { keepaliveMs: 300 },
  };
  const app = await creaApp(configurazione, { logger: ['error', 'warn'] });
  await app.listen(0, '127.0.0.1');
  const { port } = app.getHttpServer().address() as AddressInfo;
  return { app, http: `http://127.0.0.1:${port}`, ws: `ws://127.0.0.1:${port}/avvisi` };
}

// ---- messaggi su Kafka -----------------------------------------------------------

export interface Busta {
  eventId: string;
  tipo: string;
  versione: number;
  occorsoIl: string;
  payload: Record<string, unknown>;
}

let secondi = 0;

/** Una busta con un eventId nuovo; occorsoIl cresce di un secondo a ogni evento. */
export function busta(tipo: string, payload: Record<string, unknown>): Busta {
  secondi++;
  return {
    eventId: randomUUID(),
    tipo,
    versione: 1,
    occorsoIl: new Date(Date.UTC(2026, 7, 1, 9, 0, secondi)).toISOString(),
    payload,
  };
}

/** Pubblica e restituisce la partizione in cui è finito il messaggio. */
export async function pubblica(
  producer: Producer,
  topic: string,
  chiave: string,
  valore: Busta | string,
): Promise<number> {
  const [metadati] = await producer.send({
    topic,
    messages: [{ key: chiave, value: typeof valore === 'string' ? valore : JSON.stringify(valore) }],
  });
  return metadati.partition;
}

// ---- WebSocket ------------------------------------------------------------------

export interface Messaggio {
  tipo: string;
  nonLette?: number;
  notifica?: {
    id: string;
    tipo: string;
    titolo: string;
    testo: string;
    edizioneId: string | null;
    prenotazioneId: string | null;
    dati: Record<string, unknown>;
    creataIl: string;
    lettaIl: string | null;
  };
}

/** Un client degli avvisi: tiene tutti i messaggi e sa aspettare quello giusto. */
export class ClientAvvisi {
  readonly messaggi: Messaggio[] = [];
  readonly chiusura: Promise<{ codice: number; motivo: string }>;
  private attese: (() => void)[] = [];

  constructor(private readonly socket: WebSocket) {
    socket.on('message', (dati) => {
      this.messaggi.push(JSON.parse(dati.toString()) as Messaggio);
      [...this.attese].forEach((sveglia) => sveglia());
    });
    this.chiusura = new Promise((risolvi) =>
      socket.on('close', (codice, motivo) => risolvi({ codice, motivo: motivo.toString() })),
    );
  }

  static apri(url: string, token?: string): ClientAvvisi {
    const headers = token ? { Authorization: `Bearer ${token}` } : undefined;
    return new ClientAvvisi(new WebSocket(url, { headers }));
  }

  /** Le notifiche arrivate (senza "connesso" e "ping"). */
  notifiche(): NonNullable<Messaggio['notifica']>[] {
    return this.messaggi.filter((m) => m.tipo === 'notifica').map((m) => m.notifica!);
  }

  async aspetta(condizione: (m: Messaggio) => boolean, timeoutMs = 30_000): Promise<Messaggio> {
    const scadenza = Date.now() + timeoutMs;
    for (;;) {
      const trovato = this.messaggi.find(condizione);
      if (trovato) {
        return trovato;
      }
      const resta = scadenza - Date.now();
      if (resta <= 0) {
        throw new Error(`Messaggio non arrivato; arrivati: ${JSON.stringify(this.messaggi)}`);
      }
      await new Promise<void>((risolvi) => {
        const sveglia = () => {
          clearTimeout(timer);
          this.attese = this.attese.filter((altra) => altra !== sveglia);
          risolvi();
        };
        const timer = setTimeout(sveglia, resta);
        this.attese.push(sveglia);
      });
    }
  }

  aspettaNotifica(tipo: string, timeoutMs?: number) {
    return this.aspetta((m) => m.tipo === 'notifica' && m.notifica?.tipo === tipo, timeoutMs).then(
      (m) => m.notifica!,
    );
  }

  chiudi(): void {
    this.socket.close();
  }
}

/** Riprova finché la condizione vale (per le cose che arrivano da Kafka). */
export async function finche<T>(
  prova: () => Promise<T | undefined>,
  timeoutMs = 30_000,
): Promise<T> {
  const scadenza = Date.now() + timeoutMs;
  for (;;) {
    const valore = await prova();
    if (valore !== undefined) {
      return valore;
    }
    if (Date.now() > scadenza) {
      throw new Error('Condizione non verificata in tempo');
    }
    await new Promise((risolvi) => setTimeout(risolvi, 200));
  }
}
