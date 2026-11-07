/*
 * Le regole di Ricette ed Edizioni rifatte qui per le ANTEPRIME e per
 * segnalare gli errori mentre si scrive. Decidono sempre i servizi: se queste
 * regole e il dominio divergono, vince la risposta del servizio (e l'errore
 * che arriva viene mostrato com'è).
 *
 *   Dose, Formulazione.verificaDosi      -> controllaDosi
 *   Tiratura.da                          -> tiraturaPrevista
 *   Edizione.ricalcola / riduci / aumenta -> ricalcoloPrevisto
 *   Richieste.NuovaEdizione / Ricalcolo  -> controllaKg, controllaQuota
 */
import { CANALI, type Canale, type Edizione } from '../api/edizioni';
import type { Dose } from '../api/ricette';
import { cifre, leggiNumero } from './numeri';

/** Tiratura massima di un'edizione (Tiratura.MASSIMA). */
export const TIRATURA_MASSIMA = 600;

/** Come Dose.normalizzaBotanica: "Lavanda " e "lavanda" sono la stessa botanica. */
export function normalizzaBotanica(botanica: string): string {
  return botanica.trim().toLowerCase();
}

// ------------------------------------------------------------------ dosi

/** Una riga del modulo delle dosi, così come la scrive l'utente. */
export interface RigaDose {
  chiave: number;
  botanica: string;
  grammi: string;
}

export interface ErroriRiga {
  botanica?: string;
  grammi?: string;
}

export interface ControlloDosi {
  /** Le dosi da mandare a Ricette, oppure null se c'è qualcosa da correggere. */
  dosi: Dose[] | null;
  /** Errori delle singole righe, per chiave. */
  righe: Record<number, ErroriRiga>;
  /** Errori dell'elenco intero (nessuna dose, manca la botanica di stagione). */
  generali: string[];
}

/** Le righe lasciate del tutto vuote non contano: si possono aggiungere righe in più senza errori. */
export function rigaVuota(riga: RigaDose): boolean {
  return riga.botanica.trim() === '' && riga.grammi.trim() === '';
}

export function controllaDosi(righe: RigaDose[], botanicaDiStagione: string): ControlloDosi {
  const errori: Record<number, ErroriRiga> = {};
  const generali: string[] = [];
  const dosi: Dose[] = [];
  const viste = new Set<string>();

  for (const riga of righe.filter((r) => !rigaVuota(r))) {
    const erroriRiga: ErroriRiga = {};
    const botanica = riga.botanica.trim();
    const nome = normalizzaBotanica(botanica);

    if (botanica === '') {
      erroriRiga.botanica = 'Scrivi la botanica.';
    } else if (botanica.length > 50) {
      erroriRiga.botanica = 'Al massimo 50 caratteri.';
    } else if (viste.has(nome)) {
      erroriRiga.botanica = `${botanica} compare già sopra: ogni botanica una volta sola.`;
    }
    viste.add(nome);

    const grammi = controllaGrammi(riga.grammi);
    if (typeof grammi === 'string') {
      erroriRiga.grammi = grammi;
    }

    if (erroriRiga.botanica || erroriRiga.grammi) {
      errori[riga.chiave] = erroriRiga;
    } else if (typeof grammi === 'number') {
      dosi.push({ botanica, grammiPerBottiglia: grammi });
    }
  }

  if (viste.size === 0) {
    generali.push('Serve almeno una dose.');
  } else {
    const stagione = normalizzaBotanica(botanicaDiStagione);
    if (stagione !== '' && !viste.has(stagione)) {
      generali.push(`Manca la botanica di stagione (${stagione}): serve a calcolare la tiratura.`);
    }
  }

  const valide = Object.keys(errori).length === 0 && generali.length === 0;
  return { dosi: valide ? dosi : null, righe: errori, generali };
}

/** I grammi per bottiglia: maggiori di zero, al massimo 5 cifre intere e 2 decimali. */
function controllaGrammi(testo: string): number | string {
  if (testo.trim() === '') {
    return 'Scrivi i grammi per bottiglia.';
  }
  if (testo.trim().startsWith('-')) {
    return 'Devono essere più di zero.';
  }
  const valore = leggiNumero(testo);
  if (valore === null) {
    return 'Scrivi un numero, per esempio 12,5.';
  }
  if (valore <= 0) {
    return 'Devono essere più di zero.';
  }
  const { intere, decimali } = cifre(testo);
  if (decimali > 2) {
    return 'Al massimo due decimali.';
  }
  if (intere > 5) {
    return 'Al massimo 5 cifre prima della virgola.';
  }
  return valore;
}

// ------------------------------------------------------------------ edizione

/** I kg di botanica disponibili: maggiori di zero, al grammo (3 decimali), al massimo 5 cifre intere. */
export function controllaKg(testo: string): number | string {
  if (testo.trim() === '') {
    return 'Scrivi i kg disponibili.';
  }
  if (testo.trim().startsWith('-')) {
    return 'Devono essere più di zero.';
  }
  const valore = leggiNumero(testo);
  if (valore === null) {
    return 'Scrivi un numero, per esempio 10,8.';
  }
  if (valore <= 0) {
    return 'Devono essere più di zero.';
  }
  const { intere, decimali } = cifre(testo);
  if (decimali > 3) {
    return 'Al massimo tre decimali: i kg si indicano al grammo.';
  }
  if (intere > 5) {
    return 'Al massimo 5 cifre prima della virgola.';
  }
  return valore;
}

/** Una quota: un numero intero di bottiglie, anche zero. */
export function controllaQuota(testo: string): number | string {
  const pulito = testo.trim();
  if (pulito === '') {
    return 'Scrivi quante bottiglie (anche 0).';
  }
  if (!/^\d+$/.test(pulito)) {
    return 'Un numero intero di bottiglie, zero o più.';
  }
  return Number(pulito);
}

/**
 * Quante bottiglie si riempiono: kg × 1000 / grammi per bottiglia, per difetto.
 * I conti si fanno in centigrammi interi per non sbagliare con la virgola
 * mobile (1,005 × 1000 = 1004,999… in JavaScript).
 */
export function bottigliePossibili(kg: number, grammiPerBottiglia: number): number {
  if (!(kg > 0) || !(grammiPerBottiglia > 0)) {
    return 0;
  }
  const centigrammiDisponibili = Math.round(kg * 100_000);
  const centigrammiPerBottiglia = Math.round(grammiPerBottiglia * 100);
  return Math.floor(centigrammiDisponibili / centigrammiPerBottiglia);
}

/** Tiratura.da: le bottiglie possibili, al massimo 600. 0 = non si riempie nemmeno una bottiglia. */
export function tiraturaPrevista(kg: number, grammiPerBottiglia: number): number {
  return Math.min(TIRATURA_MASSIMA, bottigliePossibili(kg, grammiPerBottiglia));
}

/** Una divisione di partenza: metà ai soci, un quarto ai bar, il resto online (600 -> 300/150/150). */
export function quoteSuggerite(tiratura: number): Record<Canale, number> {
  const soci = Math.floor(tiratura / 2);
  const bar = Math.floor(tiratura / 4);
  return { SOCI: soci, BAR: bar, ONLINE: tiratura - soci - bar };
}

export function somma(quote: Record<Canale, number>): number {
  return CANALI.reduce((totale, canale) => totale + quote[canale], 0);
}

// ------------------------------------------------------------------ ricalcolo

export type Ricalcolo =
  /** Si può fare: la nuova tiratura e le nuove quote. */
  | { esito: 'possibile'; tiratura: number; quote: Record<Canale, number> }
  /** Con quei kg non si riempie nemmeno una bottiglia (Edizioni risponde 422). */
  | { esito: 'nessuna-bottiglia' }
  /** Bisognerebbe toccare SOCI o scendere sotto le prenotate (Edizioni risponde 409). */
  | { esito: 'non-riducibile'; tiratura: number; daTogliere: number; libere: number };

/** Prima ONLINE, poi BAR; SOCI non si riduce mai. */
const ORDINE_DI_RIDUZIONE: Canale[] = ['ONLINE', 'BAR'];

/**
 * Edizione.ricalcola: se la tiratura scende si toglie da ONLINE e poi da BAR,
 * mai sotto le bottiglie già prenotate; se sale, le bottiglie in più vanno a ONLINE.
 */
export function ricalcoloPrevisto(edizione: Edizione, kg: number): Ricalcolo {
  const nuova = tiraturaPrevista(kg, edizione.grammiPerBottiglia);
  if (nuova === 0) {
    return { esito: 'nessuna-bottiglia' };
  }
  const quote = { ...edizione.quote };

  if (nuova < edizione.tiratura) {
    const daTogliere = edizione.tiratura - nuova;
    let restano = daTogliere;
    for (const canale of ORDINE_DI_RIDUZIONE) {
      const tolte = Math.min(restano, quote[canale] - edizione.prenotate[canale]);
      quote[canale] -= tolte;
      restano -= tolte;
    }
    if (restano > 0) {
      return { esito: 'non-riducibile', tiratura: nuova, daTogliere, libere: daTogliere - restano };
    }
  } else if (nuova > edizione.tiratura) {
    quote.ONLINE += nuova - edizione.tiratura;
  }
  return { esito: 'possibile', tiratura: nuova, quote };
}
