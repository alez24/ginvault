/*
 * Area soci (storia di Giulia): prenotazioni, avvisi e tracciabilità.
 *
 * Le prenotazioni non esistono ancora nei servizi. Per ora i dati arrivano dal
 * modulo src/mock/soci.ts, già nella forma delle risposte vere. Quando Edizioni
 * esporrà le prenotazioni, basterà cambiare l'oggetto `soci` qui sotto con le
 * chiamate api('GET', ...): tipi e pagine restano uguali. Gli avvisi arriveranno
 * da Notifiche, via WebSocket.
 */
import { datiFinti } from '../mock/soci';
import type { Dose } from './ricette';
import type { Canale } from './edizioni';

/** Gli stati di una prenotazione (docs/piano.md, state machine). */
export type StatoPrenotazione = 'IN_ATTESA_PAGAMENTO' | 'CONFERMATA' | 'NUMERATA' | 'RITIRATA' | 'ANNULLATA';

export const NOMI_STATI_PRENOTAZIONE: Record<StatoPrenotazione, string> = {
  IN_ATTESA_PAGAMENTO: 'In attesa di pagamento',
  CONFERMATA: 'Confermata',
  NUMERATA: 'Numerata',
  RITIRATA: 'Ritirata',
  ANNULLATA: 'Annullata',
};

/** Un avviso per il socio, nato da un evento di Edizioni. */
export interface Notifica {
  id: string;
  evento: 'TiraturaRidotta' | 'PrenotazioneConfermata' | 'NumeriAssegnati';
  titolo: string;
  testo: string;
  arrivataIl: string;
  /** L'edizione di cui parla. */
  idEdizione: string;
}

/** Dove e quando si ritirano le bottiglie: un'esperienza del club. */
export interface Ritiro {
  /** In minuscolo, si legge dopo «Ritiro alla»: "masterclass d’agosto". */
  occasione: string;
  tema: string;
  descrizione: string;
  /** Inizio, ISO 8601 con il fuso. */
  inizio: string;
  /** Fine, ISO 8601 con il fuso. */
  fine: string;
  luogo: string;
  daPortare: string;
}

export interface Prenotazione {
  id: string;
  edizione: { id: string; nome: string; botanica: string; tiratura: number };
  stato: StatoPrenotazione;
  prenotataIl: string;
  /** I numeri delle bottiglie, assegnati all'imbottigliamento (vuoto prima). */
  numeri: number[];
  ritiro: Ritiro;
}

/** Un passaggio della tracciabilità: dalla ricetta al controllo qualità. */
export interface Tappa {
  tipo: 'FORMULAZIONE' | 'BOTANICA' | 'CONTROLLO_QUALITA';
  titolo: string;
  quando: string;
  testo: string;
  dati: { etichetta: string; valore: string }[];
  /** Solo per la formulazione: le dosi per bottiglia. */
  dosi?: Dose[];
}

/** Un'edizione vista da un socio: tiratura, la sua prenotazione e la tracciabilità. */
export interface EdizioneSoci {
  id: string;
  nome: string;
  botanica: string;
  descrizione: string;
  grammiPerBottiglia: number;
  /** I kg di botanica annunciati e quelli arrivati davvero. */
  botanicaAnnunciataKg: number;
  botanicaDisponibileKg: number;
  tiraturaAnnunciata: number;
  tiratura: number;
  quoteAnnunciate: Record<Canale, number>;
  quote: Record<Canale, number>;
  /** La prenotazione del socio che guarda, se c'è. */
  prenotazione: Prenotazione | null;
  tracciabilita: Tappa[];
}

export interface AreaSoci {
  notifiche: Notifica[];
  prenotazioni: Prenotazione[];
  club: { giorniDiPrelazione: number; bottigliePerSocio: number };
}

export const soci: {
  area: () => Promise<AreaSoci>;
  edizione: (id: string) => Promise<EdizioneSoci>;
} = datiFinti;

/** Il percorso della pagina di un'edizione nell'area soci. */
export const percorsoEdizioneSoci = (id: string) => `/soci/edizioni/${encodeURIComponent(id)}`;

/** "n. 112 e 113", "n. 112", "n. 110, 111 e 112". */
export function numeriBottiglie(numeri: number[]): string {
  if (numeri.length === 0) {
    return '';
  }
  const ultimi = numeri.slice(-1);
  const primi = numeri.slice(0, -1);
  return `n. ${primi.length ? `${primi.join(', ')} e ` : ''}${ultimi[0]}`;
}
