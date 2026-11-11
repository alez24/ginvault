import {
  BeforeApplicationShutdown,
  Inject,
  Injectable,
  Logger,
  OnApplicationBootstrap,
} from '@nestjs/common';
import {
  type Consumer,
  type EachMessagePayload,
  Kafka,
  type KafkaMessage,
  type LogEntry,
  logLevel,
  Partitioners,
  type Producer,
} from 'kafkajs';
import { CONFIGURAZIONE, type Configurazione } from '../configurazione';
import { ElaborazioneEventi, type Origine } from './elaborazione-eventi.service';
import { EventoNonValido } from './lettore-eventi';
import { TOPIC_DLT, TOPIC_EDIZIONI, TOPIC_PAGAMENTI } from './topic';

export type StatoKafka = 'SPENTO' | 'NON_COLLEGATO' | 'IN_ASCOLTO';

/**
 * Legge edizioni.eventi e pagamenti.esiti nel consumer group "notifiche", con
 * le regole dei consumer Spring (docs/eventi.md):
 *
 * - 3 partizioni elaborate insieme, i messaggi di una partizione uno alla
 *   volta e in ordine; l'offset si salva dopo il COMMIT del database
 *   (autoCommitThreshold 1): se Notifiche cade nel mezzo, Kafka ripropone il
 *   messaggio ed evento_elaborato lo scarta;
 * - retry dopo 1, 2, 4 e 8 secondi (NOTIFICHE_TENTATIVI_MS), poi il dead
 *   letter topic notifiche.dlt, nella stessa partizione, con gli header di
 *   Spring (kafka_dlt-...). Un EventoNonValido ci va subito;
 * - Kafka spento all'avvio non ferma Notifiche: REST e WebSocket funzionano, e
 *   il consumer riprova a collegarsi ogni 5 secondi.
 */
@Injectable()
export class ConsumerEventi implements OnApplicationBootstrap, BeforeApplicationShutdown {
  private readonly log = new Logger(ConsumerEventi.name);
  private readonly kafka: Kafka;
  private consumer?: Consumer;
  private producer?: Producer;
  private stato: StatoKafka = 'SPENTO';
  private inArresto = false;

  constructor(
    @Inject(CONFIGURAZIONE) private readonly configurazione: Configurazione,
    private readonly elaborazione: ElaborazioneEventi,
  ) {
    const kafka = configurazione.kafka;
    this.kafka = new Kafka({
      clientId: kafka.clientId,
      brokers: kafka.bootstrap,
      logLevel: logLevel.WARN,
      logCreator: creaLog,
      retry: { initialRetryTime: 300, retries: 5 },
    });
  }

  statoKafka(): StatoKafka {
    return this.stato;
  }

  onApplicationBootstrap(): void {
    if (!this.configurazione.kafka.attivo) {
      this.log.log('Kafka spento (KAFKA_ATTIVO=false): questa istanza non legge eventi');
      return;
    }
    this.stato = 'NON_COLLEGATO';
    // Non si aspetta: l'avvio dell'app non dipende da Kafka
    void this.collegaFinoAlSuccesso();
  }

  async beforeApplicationShutdown(): Promise<void> {
    this.inArresto = true;
    await this.chiudi();
  }

  private async collegaFinoAlSuccesso(): Promise<void> {
    while (!this.inArresto) {
      try {
        await this.avvia();
        return;
      } catch (errore) {
        this.log.warn(`Kafka non raggiungibile (${messaggio(errore)}): riprovo tra 5 secondi`);
        await this.chiudi();
        await pausa(5_000);
      }
    }
  }

  private async avvia(): Promise<void> {
    const { gruppo } = this.configurazione.kafka;
    // Il producer serve solo per il dead letter topic. Non è idempotente:
    // kafkajs lo vorrebbe con tentativi infiniti, che bloccherebbero l'arresto.
    // Un doppione in notifiche.dlt (lo si legge a mano) non fa danni.
    const producer = this.kafka.producer({
      allowAutoTopicCreation: false,
      createPartitioner: Partitioners.DefaultPartitioner,
    });
    const consumer = this.kafka.consumer({
      groupId: gruppo,
      allowAutoTopicCreation: false,
      sessionTimeout: 30_000,
      heartbeatInterval: 3_000,
    });
    this.producer = producer;
    this.consumer = consumer;

    consumer.on(consumer.events.GROUP_JOIN, ({ payload }) => {
      this.stato = 'IN_ASCOLTO';
      const assegnate = Object.entries(payload.memberAssignment)
        .map(([topic, partizioni]) => `${topic} ${JSON.stringify(partizioni)}`)
        .join(', ');
      this.log.log(`Nel gruppo "${payload.groupId}": ${assegnate || 'nessuna partizione'}`);
    });
    consumer.on(consumer.events.CRASH, ({ payload }) => {
      this.stato = 'NON_COLLEGATO';
      if (!payload.restart && !this.inArresto) {
        // kafkajs non riparte da solo: si ricomincia da capo
        this.log.error(`Consumer fermo (${payload.error.message}): mi ricollego`);
        void this.chiudi().then(() => this.collegaFinoAlSuccesso());
      }
    });

    await producer.connect();
    await consumer.connect();
    await consumer.subscribe({ topics: [TOPIC_EDIZIONI, TOPIC_PAGAMENTI], fromBeginning: true });
    await consumer.run({
      partitionsConsumedConcurrently: 3,
      autoCommitThreshold: 1,
      eachMessage: (messaggio) => this.conTentativi(messaggio),
    });
  }

  private async chiudi(): Promise<void> {
    const { consumer, producer } = this;
    this.consumer = undefined;
    this.producer = undefined;
    if (this.stato !== 'SPENTO') {
      this.stato = 'NON_COLLEGATO';
    }
    await consumer?.disconnect().catch(() => undefined);
    await producer?.disconnect().catch(() => undefined);
  }

  private async conTentativi({ topic, partition, message, heartbeat }: EachMessagePayload) {
    const origine: Origine = { topic, partizione: partition, posizione: message.offset };
    const dove = `${topic}[${partition}]@${message.offset}`;
    const attese = this.configurazione.kafka.tentativiMs;
    for (let tentativo = 1; ; tentativo++) {
      try {
        const esito = await this.elaborazione.elabora(message.value, origine);
        if (esito.esito === 'ELABORATO') {
          this.log.log(`${dove} ${esito.tipo} ${esito.eventId}: ${esito.avvisi} avvisi`);
        } else if (esito.esito === 'GIA_ELABORATO') {
          this.log.log(`${dove} ${esito.tipo} ${esito.eventId}: già elaborato, scartato`);
        }
        return;
      } catch (e) {
        const errore = e instanceof Error ? e : new Error(String(e));
        if (errore instanceof EventoNonValido || tentativo > attese.length) {
          // Se anche il dead letter fallisce l'errore sale a kafkajs, che non
          // salva l'offset e ripropone il messaggio
          await this.deadLetter(topic, partition, message, errore);
          this.log.error(`${dove} → ${TOPIC_DLT}: ${nomeErrore(errore)}: ${errore.message}`);
          return;
        }
        const attesa = attese[tentativo - 1];
        this.log.warn(
          `${dove}: tentativo ${tentativo} fallito (${errore.message}), riprovo tra ${attesa} ms`,
        );
        await this.aspetta(attesa, heartbeat);
      }
    }
  }

  /** Aspetta tenendo vivo il consumer nel gruppo (heartbeat ogni secondo). */
  private async aspetta(ms: number, heartbeat: () => Promise<void>): Promise<void> {
    for (let passato = 0; passato < ms; passato += 1_000) {
      if (this.inArresto) {
        throw new Error('Notifiche in arresto: il messaggio resta da elaborare');
      }
      await pausa(Math.min(1_000, ms - passato));
      await heartbeat();
    }
  }

  private async deadLetter(
    topic: string,
    partizione: number,
    message: KafkaMessage,
    errore: Error,
  ): Promise<void> {
    if (!this.producer) {
      throw new Error('Producer non collegato');
    }
    const causa = errore.cause instanceof Error ? errore.cause : undefined;
    await this.producer.send({
      topic: TOPIC_DLT,
      acks: -1,
      messages: [
        {
          key: message.key,
          value: message.value,
          partition: partizione,
          headers: {
            ...message.headers,
            'kafka_dlt-original-topic': topic,
            'kafka_dlt-original-partition': String(partizione),
            'kafka_dlt-original-offset': message.offset,
            'kafka_dlt-exception-fqcn': nomeErrore(errore),
            ...(causa ? { 'kafka_dlt-exception-cause-fqcn': nomeErrore(causa) } : {}),
            'kafka_dlt-exception-message': errore.message,
          },
        },
      ],
    });
  }
}

/** Il nome della classe dell'errore (EventoNonValido, DatabaseError...). */
function nomeErrore(errore: Error): string {
  return errore.constructor?.name && errore.constructor.name !== 'Error'
    ? errore.constructor.name
    : errore.name;
}

function messaggio(errore: unknown): string {
  return errore instanceof Error ? errore.message : String(errore);
}

function pausa(ms: number): Promise<void> {
  return new Promise((risolvi) => setTimeout(risolvi, ms));
}

/** I log di kafkajs nel logger di Nest, con il contesto "Kafka". */
function creaLog() {
  const log = new Logger('Kafka');
  return ({ namespace, level, log: voce }: LogEntry) => {
    const testo = `${namespace}: ${voce.message}`;
    if (level === logLevel.ERROR) {
      log.error(testo);
    } else if (level === logLevel.WARN) {
      log.warn(testo);
    } else {
      log.debug(testo);
    }
  };
}
