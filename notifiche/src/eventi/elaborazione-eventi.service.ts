import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DatabaseService } from '../database/database.service';
import { NotificheRepository, type NuovaNotifica } from '../notifiche/notifiche.repository';
import {
  avvisoAnnullamento,
  avvisoConferma,
  avvisoRimborso,
  avvisoTiratura,
} from '../notifiche/testi';
import { type Busta, type Evento, leggiBusta, leggiEvento } from './lettore-eventi';

/** Da dove arriva il messaggio: va in evento_elaborato e nei log. */
export interface Origine {
  topic: string;
  partizione: number;
  posizione: string;
}

export type Esito =
  | { esito: 'IGNORATO'; tipo: string }
  | { esito: 'GIA_ELABORATO'; tipo: string; eventId: string }
  | { esito: 'ELABORATO'; tipo: string; eventId: string; avvisi: number };

/**
 * Il consumer IDEMPOTENTE: ogni messaggio in UNA transazione.
 *
 *  1. legge la busta (illeggibile → EventoNonValido → dead letter);
 *  2. scrive l'eventId in evento_elaborato; se c'era già, si scarta. È il
 *     primo passo, non l'ultimo: lo stesso evento elaborato da due repliche
 *     nello stesso istante (dopo un ribilanciamento) si ferma sulla chiave
 *     primaria finché l'altra transazione non finisce, e poi si scarta;
 *  3. aggiorna la proiezione (edizioni, prenotazioni confermate) e salva le
 *     notifiche, ognuna con il suo NOTIFY;
 *  4. COMMIT: i NOTIFY partono adesso, l'offset di Kafka si salva dopo.
 */
@Injectable()
export class ElaborazioneEventi {
  constructor(
    private readonly db: DatabaseService,
    private readonly notifiche: NotificheRepository,
  ) {}

  async elabora(valore: Buffer | string | null, origine: Origine): Promise<Esito> {
    const busta = leggiBusta(valore);
    const evento = leggiEvento(busta);
    if (!evento) {
      // Su edizioni.eventi passano anche RicalcoloRifiutato, su pagamenti.esiti
      // PagamentoRiuscito e PagamentoFallito: non servono, e non si salvano
      return { esito: 'IGNORATO', tipo: busta.tipo };
    }
    return this.db.transazione(async (client) => {
      const { rowCount } = await client.query(
        `INSERT INTO evento_elaborato (event_id, tipo, topic, partizione, posizione)
         VALUES ($1, $2, $3, $4, $5) ON CONFLICT (event_id) DO NOTHING`,
        [busta.eventId, busta.tipo, origine.topic, origine.partizione, origine.posizione],
      );
      if (rowCount === 0) {
        return { esito: 'GIA_ELABORATO', tipo: busta.tipo, eventId: busta.eventId };
      }
      const nuove = await this.applica(client, busta, evento);
      let avvisi = 0;
      for (const nuova of nuove) {
        if (await this.notifiche.salvaNotifica(client, nuova)) {
          avvisi++;
        }
      }
      await client.query('UPDATE evento_elaborato SET avvisi = $2 WHERE event_id = $1', [
        busta.eventId,
        avvisi,
      ]);
      return { esito: 'ELABORATO', tipo: busta.tipo, eventId: busta.eventId, avvisi };
    });
  }

  /** Aggiorna la proiezione e restituisce le notifiche da salvare. */
  private async applica(client: PoolClient, busta: Busta, evento: Evento): Promise<NuovaNotifica[]> {
    const notifica = (destinatario: string, prenotazioneId: string | null) => ({
      destinatario,
      edizioneId: evento.edizioneId,
      prenotazioneId,
      eventoId: busta.eventId,
      creataIl: busta.occorsoIl,
    });
    switch (evento.tipo) {
      case 'EdizioneCreata':
        await this.notifiche.salvaEdizione(client, evento, busta.occorsoIl);
        return [];
      case 'PrenotazioneConfermata': {
        await this.notifiche.salvaConferma(client, evento, busta.occorsoIl);
        const botanica = await this.notifiche.botanicaDi(client, evento.edizioneId);
        return [
          {
            ...notifica(evento.acquirente, evento.prenotazioneId),
            avviso: avvisoConferma(evento, botanica),
          },
        ];
      }
      case 'PrenotazioneAnnullata': {
        const botanica = await this.notifiche.botanicaDi(client, evento.edizioneId);
        return [
          {
            ...notifica(evento.acquirente, evento.prenotazioneId),
            avviso: avvisoAnnullamento(evento, botanica),
          },
        ];
      }
      case 'TiraturaRidotta':
      case 'TiraturaAumentata': {
        // Stessa chiave (l'edizione), stessa partizione: le conferme arrivate
        // prima di questo evento sono già nella proiezione, quelle dopo no
        // (chi prenota dopo la riduzione non ha niente da sapere).
        const botanica = await this.notifiche.botanicaDi(client, evento.edizioneId);
        const acquirenti = await this.notifiche.numeriPerAcquirente(client, evento.edizioneId);
        return acquirenti.map(({ acquirente, numeri }) => ({
          ...notifica(acquirente, null),
          avviso: avvisoTiratura(evento, numeri, botanica),
        }));
      }
      case 'RimborsoEseguito': {
        // Arriva da pagamenti.esiti, un altro topic: l'edizione potrebbe non
        // essere ancora nella proiezione, e allora l'avviso dice solo "edizione"
        const botanica = await this.notifiche.botanicaDi(client, evento.edizioneId);
        return [
          {
            ...notifica(evento.acquirente, evento.prenotazioneId),
            avviso: avvisoRimborso(evento, botanica),
          },
        ];
      }
    }
  }
}
