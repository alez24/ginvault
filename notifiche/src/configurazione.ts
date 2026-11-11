/**
 * La configurazione di Notifiche, letta UNA volta dalle variabili d'ambiente
 * (Docker Compose, Kubernetes). I default valgono per l'avvio dal portatile:
 * il database su 127.0.0.1:5446 (docker-compose.override.yml), Kafka e il
 * gateway su localhost.
 *
 * È un valore iniettato (token CONFIGURAZIONE) e non process.env letto qua e
 * là: i test avviano due istanze di Notifiche nello stesso processo, ognuna
 * con la sua configurazione.
 */
export interface Configurazione {
  porta: number;
  jwt: {
    jwksUri: string;
    issuer: string;
  };
  database: {
    host: string;
    porta: number;
    nome: string;
    utente: string;
    password: string;
    maxConnessioni: number;
  };
  kafka: {
    /** false: l'istanza non legge Kafka (serve ai test del fan-out tra repliche). */
    attivo: boolean;
    bootstrap: string[];
    clientId: string;
    gruppo: string;
    /** Le attese tra un tentativo e il successivo, poi il dead letter topic. */
    tentativiMs: number[];
  };
  avvisi: {
    /** Ogni quanto si manda {"tipo":"ping"} sui WebSocket aperti. */
    keepaliveMs: number;
  };
}

export const CONFIGURAZIONE = Symbol('CONFIGURAZIONE');

export function leggiConfigurazione(env: NodeJS.ProcessEnv = process.env): Configurazione {
  return {
    porta: intero(env, 'PORT', 8086),
    jwt: {
      jwksUri: env.JWKS_URI ?? 'http://localhost:8080/.well-known/jwks.json',
      issuer: env.JWT_ISSUER ?? 'ginvault-gateway',
    },
    database: {
      host: env.DB_HOST ?? '127.0.0.1',
      porta: intero(env, 'DB_PORT', 5446),
      nome: env.DB_NAME ?? 'notifiche',
      utente: env.DB_USER ?? 'notifiche',
      password: env.DB_PASSWORD ?? 'notifiche',
      maxConnessioni: intero(env, 'DB_MAX_CONNESSIONI', 10),
    },
    kafka: {
      attivo: (env.KAFKA_ATTIVO ?? 'true') !== 'false',
      bootstrap: (env.KAFKA_BOOTSTRAP ?? 'localhost:9092').split(',').map((b) => b.trim()),
      clientId: env.KAFKA_CLIENT_ID ?? 'notifiche',
      gruppo: env.KAFKA_GRUPPO ?? 'notifiche',
      tentativiMs: (env.NOTIFICHE_TENTATIVI_MS ?? '1000,2000,4000,8000')
        .split(',')
        .map((ms) => Number(ms.trim()))
        .filter((ms) => Number.isInteger(ms) && ms >= 0),
    },
    avvisi: {
      keepaliveMs: intero(env, 'NOTIFICHE_KEEPALIVE_MS', 25_000),
    },
  };
}

function intero(env: NodeJS.ProcessEnv, nome: string, predefinito: number): number {
  const valore = env[nome];
  if (valore === undefined || valore === '') {
    return predefinito;
  }
  const numero = Number(valore);
  if (!Number.isInteger(numero) || numero < 0) {
    throw new Error(`${nome} deve essere un intero, non "${valore}"`);
  }
  return numero;
}
