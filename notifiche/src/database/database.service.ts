import { Inject, Injectable, Logger, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { Pool, type PoolClient, type QueryResultRow } from 'pg';
import { CONFIGURAZIONE, type Configurazione } from '../configurazione';
import { BLOCCO_SCHEMA, SCHEMA } from './schema';

/**
 * Il database di Notifiche (PostgreSQL, uno per servizio come gli altri).
 *
 * Un pool di connessioni "pg" per le query e le transazioni. All'avvio crea lo
 * schema: se il database non risponde l'avvio fallisce, come un servizio
 * Spring Boot (Docker e Kubernetes riavviano il container).
 */
@Injectable()
export class DatabaseService implements OnModuleInit, OnApplicationShutdown {
  private readonly log = new Logger(DatabaseService.name);
  private readonly pool: Pool;

  constructor(@Inject(CONFIGURAZIONE) private readonly configurazione: Configurazione) {
    const db = configurazione.database;
    this.pool = new Pool({
      host: db.host,
      port: db.porta,
      database: db.nome,
      user: db.utente,
      password: db.password,
      max: db.maxConnessioni,
      connectionTimeoutMillis: 5_000,
      application_name: 'notifiche',
    });
    // Una connessione libera del pool che cade (database riavviato) non deve
    // far cadere il processo: il pool la scarta e ne apre un'altra.
    this.pool.on('error', (errore) => this.log.warn(`Connessione persa: ${errore.message}`));
  }

  async onModuleInit(): Promise<void> {
    await this.transazione(async (client) => {
      // pg_advisory_xact_lock: un lucchetto con un numero, rilasciato al
      // commit. Due repliche avviate insieme creano lo schema una dopo l'altra.
      await client.query('SELECT pg_advisory_xact_lock($1)', [BLOCCO_SCHEMA]);
      await client.query(SCHEMA);
    });
    const db = this.configurazione.database;
    this.log.log(`Schema pronto su ${db.host}:${db.porta}/${db.nome}`);
  }

  async onApplicationShutdown(): Promise<void> {
    await this.pool.end();
  }

  async query<R extends QueryResultRow>(sql: string, parametri: unknown[] = []): Promise<R[]> {
    const risultato = await this.pool.query<R>(sql, parametri);
    return risultato.rows;
  }

  /** Esegue il lavoro in UNA transazione: COMMIT se va bene, ROLLBACK se lancia. */
  async transazione<T>(lavoro: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const risultato = await lavoro(client);
      await client.query('COMMIT');
      return risultato;
    } catch (errore) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw errore;
    } finally {
      client.release();
    }
  }
}
