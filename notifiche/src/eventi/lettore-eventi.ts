/**
 * Il lettore dei messaggi di Kafka: la busta comune e i payload degli eventi
 * che interessano a Notifiche (contratti in docs/eventi.md).
 *
 * Lettore TOLLERANTE: i campi sconosciuti si ignorano, un `tipo` che non
 * interessa restituisce null. Un messaggio che non si può leggere (JSON non
 * valido, campo obbligatorio mancante, versione non supportata) lancia
 * EventoNonValido: riprovare non serve, va subito nel dead letter topic.
 */
export class EventoNonValido extends Error {
  override readonly name = 'EventoNonValido';
}

export interface Busta {
  eventId: string;
  tipo: string;
  versione: number;
  occorsoIl: Date;
  payload: Record<string, unknown>;
}

export interface EdizioneCreata {
  tipo: 'EdizioneCreata';
  edizioneId: string;
  botanicaDiStagione: string;
}

export interface PrenotazioneConfermata {
  tipo: 'PrenotazioneConfermata';
  prenotazioneId: string;
  edizioneId: string;
  acquirente: string;
  canale: string;
  bottiglie: number;
  numeriBottiglia: number[];
  /** In euro, come il prezzo della prenotazione. */
  totale: number;
}

export interface PrenotazioneAnnullata {
  tipo: 'PrenotazioneAnnullata';
  prenotazioneId: string;
  edizioneId: string;
  acquirente: string;
  canale: string;
  bottiglie: number;
  causa: string;
  spiegazione?: string;
}

export interface TiraturaCambiata {
  tipo: 'TiraturaRidotta' | 'TiraturaAumentata';
  edizioneId: string;
  tiraturaPrima: number;
  tiratura: number;
  causa?: string;
}

export interface RimborsoEseguito {
  tipo: 'RimborsoEseguito';
  prenotazioneId: string;
  edizioneId: string;
  acquirente: string;
  importoCentesimi: number;
  valuta: string;
  motivo?: string;
}

export type Evento =
  | EdizioneCreata
  | PrenotazioneConfermata
  | PrenotazioneAnnullata
  | TiraturaCambiata
  | RimborsoEseguito;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function leggiBusta(valore: Buffer | string | null): Busta {
  if (valore === null || valore.length === 0) {
    throw new EventoNonValido('Messaggio vuoto');
  }
  let json: unknown;
  try {
    json = JSON.parse(valore.toString());
  } catch {
    throw new EventoNonValido('Il messaggio non è un JSON valido');
  }
  if (!oggetto(json)) {
    throw new EventoNonValido('Il messaggio non è un oggetto JSON');
  }
  const occorsoIl = new Date(testo(json, 'occorsoIl'));
  if (Number.isNaN(occorsoIl.getTime())) {
    throw new EventoNonValido('occorsoIl non è una data ISO-8601');
  }
  const payload = json.payload;
  if (!oggetto(payload)) {
    throw new EventoNonValido('payload mancante');
  }
  return {
    eventId: uuid(json, 'eventId'),
    tipo: testo(json, 'tipo'),
    versione: intero(json, 'versione', 1),
    occorsoIl,
    payload,
  };
}

/** L'evento della busta, o null se il `tipo` non interessa a Notifiche. */
export function leggiEvento(busta: Busta): Evento | null {
  const p = busta.payload;
  switch (busta.tipo) {
    case 'EdizioneCreata':
      versione1(busta);
      return {
        tipo: busta.tipo,
        edizioneId: uuid(p, 'edizioneId'),
        botanicaDiStagione: testo(p, 'botanicaDiStagione'),
      };
    case 'PrenotazioneConfermata': {
      versione1(busta);
      const bottiglie = intero(p, 'bottiglie', 1);
      const numeriBottiglia = interi(p, 'numeriBottiglia');
      if (numeriBottiglia.length !== bottiglie) {
        throw new EventoNonValido(
          `${bottiglie} bottiglie ma ${numeriBottiglia.length} numeri di bottiglia`,
        );
      }
      return {
        tipo: busta.tipo,
        prenotazioneId: uuid(p, 'prenotazioneId'),
        edizioneId: uuid(p, 'edizioneId'),
        acquirente: testo(p, 'acquirente'),
        canale: testo(p, 'canale'),
        bottiglie,
        numeriBottiglia,
        totale: numero(p, 'totale'),
      };
    }
    case 'PrenotazioneAnnullata':
      versione1(busta);
      return {
        tipo: busta.tipo,
        prenotazioneId: uuid(p, 'prenotazioneId'),
        edizioneId: uuid(p, 'edizioneId'),
        acquirente: testo(p, 'acquirente'),
        canale: testo(p, 'canale'),
        bottiglie: intero(p, 'bottiglie', 1),
        causa: testo(p, 'causa'),
        spiegazione: testoFacoltativo(p, 'spiegazione'),
      };
    case 'TiraturaRidotta':
    case 'TiraturaAumentata':
      versione1(busta);
      return {
        tipo: busta.tipo,
        edizioneId: uuid(p, 'edizioneId'),
        tiraturaPrima: intero(p, 'tiraturaPrima', 0),
        tiratura: intero(p, 'tiratura', 0),
        causa: testoFacoltativo(p, 'causa'),
      };
    case 'RimborsoEseguito':
      versione1(busta);
      return {
        tipo: busta.tipo,
        prenotazioneId: uuid(p, 'prenotazioneId'),
        edizioneId: uuid(p, 'edizioneId'),
        acquirente: testo(p, 'acquirente'),
        importoCentesimi: intero(p, 'importoCentesimi', 1),
        valuta: testo(p, 'valuta'),
        motivo: testoFacoltativo(p, 'motivo'),
      };
    default:
      return null;
  }
}

function versione1(busta: Busta): void {
  if (busta.versione !== 1) {
    throw new EventoNonValido(`${busta.tipo} versione ${busta.versione} non supportata`);
  }
}

function oggetto(valore: unknown): valore is Record<string, unknown> {
  return typeof valore === 'object' && valore !== null && !Array.isArray(valore);
}

function testo(dati: Record<string, unknown>, campo: string): string {
  const valore = dati[campo];
  if (typeof valore !== 'string' || valore.trim() === '') {
    throw new EventoNonValido(`${campo} mancante o vuoto`);
  }
  return valore;
}

function testoFacoltativo(dati: Record<string, unknown>, campo: string): string | undefined {
  const valore = dati[campo];
  if (valore === undefined || valore === null || valore === '') {
    return undefined;
  }
  if (typeof valore !== 'string') {
    throw new EventoNonValido(`${campo} non è un testo`);
  }
  return valore;
}

function uuid(dati: Record<string, unknown>, campo: string): string {
  const valore = testo(dati, campo);
  if (!UUID.test(valore)) {
    throw new EventoNonValido(`${campo} non è un UUID: ${valore}`);
  }
  return valore.toLowerCase();
}

function numero(dati: Record<string, unknown>, campo: string): number {
  const valore = dati[campo];
  if (typeof valore !== 'number' || !Number.isFinite(valore) || valore < 0) {
    throw new EventoNonValido(`${campo} non è un numero valido`);
  }
  return valore;
}

function intero(dati: Record<string, unknown>, campo: string, minimo: number): number {
  const valore = dati[campo];
  if (!Number.isInteger(valore) || (valore as number) < minimo) {
    throw new EventoNonValido(`${campo} deve essere un intero da ${minimo} in su`);
  }
  return valore as number;
}

function interi(dati: Record<string, unknown>, campo: string): number[] {
  const valore = dati[campo];
  if (!Array.isArray(valore) || valore.some((n) => !Number.isInteger(n) || n < 1)) {
    throw new EventoNonValido(`${campo} deve essere una lista di interi positivi`);
  }
  return valore as number[];
}
