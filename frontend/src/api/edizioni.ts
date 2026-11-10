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
