import {
  Inject,
  Injectable,
  Logger,
  OnApplicationBootstrap,
  OnModuleDestroy,
} from '@nestjs/common';
import { Client } from 'pg';
import { CONFIGURAZIONE, type Configurazione } from '../configurazione';
import { CANALE_NOTIFICHE } from '../database/schema';
import { NotificheRepository, type NotificaSalvata } from '../notifiche/notifiche.repository';
import { RegistroConnessioni } from './registro-connessioni';

/**
 * Il FAN-OUT tra repliche. Il messaggio di Kafka lo elabora UNA replica (quella
 * che ha la partizione), ma il WebSocket di Giulia può essere aperto su
 * un'altra. Ogni notifica salvata fa un NOTIFY sul database (nello stesso
 * commit): ogni replica è in LISTEN su una connessione sua, e consegna la
 * notifica ai WebSocket che ha lei.
 *
 * Se la connessione di LISTEN cade, si ricollega dopo 2 secondi. Le notifiche
 * salvate nel frattempo non arrivano in diretta, ma sono nel database: il
 * front-end le rilegge con GET /notifiche quando riceve "connesso".
 */
@Injectable()
export class AscoltoNotifiche implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly log = new Logger(AscoltoNotifiche.name);
  private client?: Client;
  private ricollegamento?: NodeJS.Timeout;
  private inArresto = false;
  // Le consegne una alla volta, nell'ordine dei NOTIFY
  private coda: Promise<void> = Promise.resolve();

  constructor(
    @Inject(CONFIGURAZIONE) private readonly configurazione: Configurazione,
    private readonly registro: RegistroConnessioni,
    private readonly notifiche: NotificheRepository,
  ) {}

  async onApplicationBootstrap(): Promise<void> {
    await this.collega();
  }

  async onModuleDestroy(): Promise<void> {
    this.inArresto = true;
    clearTimeout(this.ricollegamento);
    await this.client?.end().catch(() => undefined);
    await this.coda;
  }

  private async collega(): Promise<void> {
    if (this.inArresto) {
      return;
    }
    const db = this.configurazione.database;
    const client = new Client({
      host: db.host,
      port: db.porta,
      database: db.nome,
      user: db.utente,
      password: db.password,
      connectionTimeoutMillis: 5_000,
      application_name: 'notifiche-listen',
    });
    let perso = false;
    const quandoPerso = (motivo: string) => {
      if (perso || this.inArresto) {
        return;
      }
      perso = true;
      this.client = undefined;
      this.log.warn(`LISTEN interrotto (${motivo}): mi ricollego tra 2 secondi`);
      client.end().catch(() => undefined);
      this.ricollegamento = setTimeout(() => void this.collega(), 2_000);
    };
    client.on('error', (errore) => quandoPerso(errore.message));
    client.on('end', () => quandoPerso('connessione chiusa'));
    client.on('notification', ({ payload }) => {
      this.coda = this.coda.then(() => this.consegna(payload));
    });
    try {
      await client.connect();
      await client.query(`LISTEN ${CANALE_NOTIFICHE}`);
      if (!perso) {
        this.client = client;
        this.log.log(`In ascolto sul canale ${CANALE_NOTIFICHE}`);
      }
    } catch (errore) {
      quandoPerso(errore instanceof Error ? errore.message : String(errore));
    }
  }

  private async consegna(payload: string | undefined): Promise<void> {
    try {
      const salvata = JSON.parse(payload ?? '') as NotificaSalvata;
      // Solo la replica con un WebSocket dell'utente rilegge la notifica
      if (!this.registro.collegato(salvata.destinatario)) {
        return;
      }
      const notifica = await this.notifiche.perId(salvata.id);
      if (notifica) {
        this.registro.invia(salvata.destinatario, { tipo: 'notifica', notifica });
      }
    } catch (errore) {
      this.log.warn(`Notifica non consegnata in diretta: ${String(errore)}`);
    }
  }
}
