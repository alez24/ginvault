/* Edizioni: tiratura, quote e ricalcolo (dal gateway: /api/edizioni/edizioni). */
import { api } from './http';

export type Canale = 'SOCI' | 'BAR' | 'ONLINE';

/** Ordine in cui mostrare i canali (lo stesso della regola: ONLINE cala per primo, SOCI mai). */
export const CANALI: Canale[] = ['SOCI', 'BAR', 'ONLINE'];

export const NOMI_CANALI: Record<Canale, string> = {
  SOCI: 'Soci del club',
  BAR: 'Bar',
  ONLINE: 'Vendita online',
};

/**
 * In che fase di vendita è l'edizione: in prelazione prenotano solo i soci
 * (quota SOCI), poi tutti dalla quota ONLINE, finché non è esaurita.
 */
export type StatoEdizione = 'IN_PRELAZIONE' | 'IN_VENDITA' | 'ESAURITA';

/** EdizioneVista di Edizioni. */
export interface Edizione {
  id: string;
  formulazione: { id: string; versione: number };
  botanicaDiStagione: string;
  grammiPerBottiglia: number;
  botanicaDisponibileKg: number;
  /** Da dove deve venire la botanica di stagione; null per le edizioni create prima del campo. */
  origineBotanica: string | null;
  tiratura: number;
  quote: Record<Canale, number>;
  prenotate: Record<Canale, number>;
  creataIl: string;
  /** Calcolato da Edizioni con l'ora di adesso. */
  stato: StatoEdizione;
  /** Quando finisce la prelazione; null (come i campi dell'annuncio) per le edizioni create prima. */
  prelazioneFinoAl: string | null;
  /** Com'era l'edizione all'annuncio, prima di ogni ricalcolo. */
  tiraturaAnnunciata: number | null;
  botanicaAnnunciataKg: number | null;
  quoteAnnunciate: Record<Canale, number> | null;
}

export interface NuovaEdizione {
  formulazione: { id: string; versione: number };
  botanicaDisponibileKg: number;
  origineBotanica: string;
  quote: Record<Canale, number>;
}

const BASE = '/api/edizioni/edizioni';

export const edizioni = {
  elenco: () => api<Edizione[]>('GET', BASE),
  trova: (id: string) => api<Edizione>('GET', `${BASE}/${encodeURIComponent(id)}`),
  crea: (edizione: NuovaEdizione) => api<Edizione>('POST', BASE, edizione),
  ricalcola: (id: string, botanicaDisponibileKg: number) =>
    api<Edizione>('POST', `${BASE}/${encodeURIComponent(id)}/ricalcolo`, { botanicaDisponibileKg }),
};
