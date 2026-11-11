import { Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { DatabaseService } from '../database/database.service';
import { CANALE_NOTIFICHE } from '../database/schema';
import type { EdizioneCreata, PrenotazioneConfermata } from '../eventi/lettore-eventi';
import type { Avviso, TipoNotifica } from './testi';

/** Una notifica come la vede il suo destinatario (REST e WebSocket). */
export interface NotificaVista {
  id: string;
  tipo: TipoNotifica;
  titolo: string;
  testo: string;
  edizioneId: string | null;
  prenotazioneId: string | null;
  dati: Record<string, unknown>;
  creataIl: string;
  lettaIl: string | null;
}

export interface NuovaNotifica {
  destinatario: string;
  avviso: Avviso;
  edizioneId: string | null;
  prenotazioneId: string | null;
  eventoId: string;
  creataIl: Date;
}

/** Il messaggio di NOTIFY: chi deve ricevere quale notifica (la si rilegge per id). */
export interface NotificaSalvata {
  id: string;
  destinatario: string;
}

interface RigaNotifica {
  id: string;
  tipo: TipoNotifica;
  titolo: string;
  testo: string;
  edizione_id: string | null;
  prenotazione_id: string | null;
  dati: Record<string, unknown>;
  creata_il: Date;
  letta_il: Date | null;
}

const COLONNE =
  'id, tipo, titolo, testo, edizione_id, prenotazione_id, dati, creata_il, letta_il';

/**
 * Le query di Notifiche. Quelle che scrivono ricevono il client della
 * transazione di ElaborazioneEventi: proiezione, notifiche e riga di
 * evento_elaborato vanno nello stesso commit.
 */
@Injectable()
export class NotificheRepository {
  constructor(private readonly db: DatabaseService) {}

  // ---- proiezione degli eventi di Edizioni ---------------------------------

  async salvaEdizione(client: PoolClient, evento: EdizioneCreata, creataIl: Date): Promise<void> {
    await client.query(
      `INSERT INTO edizione (edizione_id, botanica_di_stagione, creata_il)
       VALUES ($1, $2, $3) ON CONFLICT (edizione_id) DO NOTHING`,
      [evento.edizioneId, evento.botanicaDiStagione, creataIl],
    );
  }

  async botanicaDi(client: PoolClient, edizioneId: string): Promise<string | undefined> {
    const { rows } = await client.query<{ botanica_di_stagione: string }>(
      'SELECT botanica_di_stagione FROM edizione WHERE edizione_id = $1',
      [edizioneId],
    );
    return rows[0]?.botanica_di_stagione;
  }

  async salvaConferma(
    client: PoolClient,
    evento: PrenotazioneConfermata,
    confermataIl: Date,
  ): Promise<void> {
    await client.query(
      `INSERT INTO prenotazione_confermata
         (prenotazione_id, edizione_id, acquirente, canale, bottiglie, numeri_bottiglia, confermata_il)
       VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (prenotazione_id) DO NOTHING`,
      [
        evento.prenotazioneId,
        evento.edizioneId,
        evento.acquirente,
        evento.canale,
        evento.bottiglie,
        evento.numeriBottiglia,
        confermataIl,
      ],
    );
  }

  /** Chi ha bottiglie confermate dell'edizione, con tutti i suoi numeri in ordine. */
  async numeriPerAcquirente(
    client: PoolClient,
    edizioneId: string,
  ): Promise<{ acquirente: string; numeri: number[] }[]> {
    const { rows } = await client.query<{ acquirente: string; numeri: number[] }>(
      `SELECT p.acquirente, array_agg(n ORDER BY n) AS numeri
         FROM prenotazione_confermata p, unnest(p.numeri_bottiglia) AS n
        WHERE p.edizione_id = $1
        GROUP BY p.acquirente
        ORDER BY p.acquirente`,
      [edizioneId],
    );
    return rows;
  }

  // ---- notifiche --------------------------------------------------------------

  /**
   * Salva la notifica e, nella stessa transazione, la annuncia con NOTIFY: il
   * messaggio parte solo al COMMIT (con un ROLLBACK non parte), e lo ricevono
   * tutte le repliche in ascolto (AscoltoNotifiche).
   */
  async salvaNotifica(client: PoolClient, nuova: NuovaNotifica): Promise<NotificaSalvata | null> {
    const id = randomUUID();
    const { rowCount } = await client.query(
      `INSERT INTO notifica
         (id, destinatario, tipo, titolo, testo, edizione_id, prenotazione_id, dati, evento_id, creata_il)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
       ON CONFLICT (evento_id, destinatario) DO NOTHING`,
      [
        id,
        nuova.destinatario,
        nuova.avviso.tipo,
        nuova.avviso.titolo,
        nuova.avviso.testo,
        nuova.edizioneId,
        nuova.prenotazioneId,
        JSON.stringify(nuova.avviso.dati),
        nuova.eventoId,
        nuova.creataIl,
      ],
    );
    if (rowCount === 0) {
      return null;
    }
    const salvata: NotificaSalvata = { id, destinatario: nuova.destinatario };
    await client.query('SELECT pg_notify($1, $2)', [CANALE_NOTIFICHE, JSON.stringify(salvata)]);
    return salvata;
  }

  async elenco(destinatario: string, limite: number): Promise<NotificaVista[]> {
    const righe = await this.db.query<RigaNotifica>(
      `SELECT ${COLONNE} FROM notifica
        WHERE destinatario = $1
        ORDER BY creata_il DESC, id
        LIMIT $2`,
      [destinatario, limite],
    );
    return righe.map(vista);
  }

  async perId(id: string): Promise<NotificaVista | undefined> {
    const righe = await this.db.query<RigaNotifica>(`SELECT ${COLONNE} FROM notifica WHERE id = $1`, [
      id,
    ]);
    return righe.map(vista)[0];
  }

  async nonLette(destinatario: string): Promise<number> {
    const righe = await this.db.query<{ quante: number }>(
      'SELECT count(*)::int AS quante FROM notifica WHERE destinatario = $1 AND letta_il IS NULL',
      [destinatario],
    );
    return righe[0].quante;
  }

  /** false: la notifica non c'è, o è di un altro (per chi chiede è lo stesso: 404). */
  async segnaLetta(id: string, destinatario: string, adesso: Date): Promise<boolean> {
    const righe = await this.db.query(
      `UPDATE notifica SET letta_il = COALESCE(letta_il, $3)
        WHERE id = $1 AND destinatario = $2
        RETURNING id`,
      [id, destinatario, adesso],
    );
    return righe.length === 1;
  }

  async segnaTutteLette(destinatario: string, adesso: Date): Promise<number> {
    const righe = await this.db.query(
      `UPDATE notifica SET letta_il = $2
        WHERE destinatario = $1 AND letta_il IS NULL
        RETURNING id`,
      [destinatario, adesso],
    );
    return righe.length;
  }
}

function vista(riga: RigaNotifica): NotificaVista {
  return {
    id: riga.id,
    tipo: riga.tipo,
    titolo: riga.titolo,
    testo: riga.testo,
    edizioneId: riga.edizione_id,
    prenotazioneId: riga.prenotazione_id,
    dati: riga.dati,
    creataIl: riga.creata_il.toISOString(),
    lettaIl: riga.letta_il?.toISOString() ?? null,
  };
}
