/*
 * Magazzino: ordini di acquisto, consegne, lotti e fornitori (dal gateway:
 * /api/magazzino/...). I tipi sono le Viste di magazzino (Viste.java): i kg
 * arrivano come numeri al grammo, le date senza ora come "2026-08-06".
 */
import { api } from './http';

export type StatoOrdine = 'APERTO' | 'CONSEGNATO';
export type EsitoQualita = 'CONFORME' | 'NON_CONFORME';

export const NOMI_ESITI_QUALITA: Record<EsitoQualita, string> = {
  CONFORME: 'Conforme',
  NON_CONFORME: 'Non conforme',
};

export interface RiferimentoFornitore {
  id: string;
  nome: string;
}

/** La consegna di un ordine. Per quella di Elena: 10,8 kg su 12, ridotta e in ritardo di 6 giorni. */
export interface Consegna {
  consegnataIl: string;
  kgArrivati: number;
  kgMancanti: number;
  ridotta: boolean;
  inRitardo: boolean;
  giorniDiRitardo: number;
  origine: string;
  lottoId: string;
  nota: string | null;
}

export interface Ordine {
  id: string;
  fornitore: RiferimentoFornitore;
  botanica: string;
  kgOrdinati: number;
  consegnaAttesa: string;
  edizioneId: string;
  stato: StatoOrdine;
  consegna: Consegna | null;
  creatoIl: string;
}

export interface NuovoOrdine {
  fornitoreId: string;
  botanica: string;
  kgOrdinati: number;
  consegnaAttesa: string;
  edizioneId: string;
}

export interface NuovaConsegna {
  kgArrivati: number;
  consegnataIl: string;
  scadenza: string;
  esitoQualita: EsitoQualita;
  nota?: string;
}

/** Un lotto di botanica. ordineId è null per i lotti senza ordine (il lotto dei campi). */
export interface Lotto {
  id: string;
  botanica: string;
  origine: string;
  kg: number;
  /** I kg non ancora riservati da un piano di produzione. */
  kgDisponibili: number;
  scadenza: string;
  esitoQualita: EsitoQualita;
  /** Conforme e non scaduto: si può usare per produrre. */
  utilizzabile: boolean;
  fornitore: RiferimentoFornitore;
  ordineId: string | null;
  ricevutoIl: string;
}

export interface EsitoConsegna {
  ordine: Ordine;
  lotto: Lotto;
}

export interface RiepilogoFornitore {
  consegne: number;
  puntuali: number;
  inRitardo: number;
  ridotte: number;
}

export interface VoceStorico {
  ordineId: string;
  botanica: string;
  kgOrdinati: number;
  kgArrivati: number;
  consegnaAttesa: string;
  consegnataIl: string;
  ridotta: boolean;
  inRitardo: boolean;
  giorniDiRitardo: number;
  nota: string | null;
}

/** Un fornitore con il riepilogo e lo storico, dalla consegna più recente. */
export interface Fornitore {
  id: string;
  nome: string;
  origine: string;
  riepilogo: RiepilogoFornitore;
  storico: VoceStorico[];
}

const BASE = '/api/magazzino';
const id = (valore: string) => encodeURIComponent(valore);

export const magazzino = {
  ordini: () => api<Ordine[]>('GET', `${BASE}/ordini`),
  ordine: (ordineId: string) => api<Ordine>('GET', `${BASE}/ordini/${id(ordineId)}`),
  ordina: (ordine: NuovoOrdine) => api<Ordine>('POST', `${BASE}/ordini`, ordine),
  registraConsegna: (ordineId: string, consegna: NuovaConsegna) =>
    api<EsitoConsegna>('POST', `${BASE}/ordini/${id(ordineId)}/consegne`, consegna),
  lotti: (botanica?: string) =>
    api<Lotto[]>('GET', botanica ? `${BASE}/lotti?botanica=${encodeURIComponent(botanica)}` : `${BASE}/lotti`),
  lotto: (lottoId: string) => api<Lotto>('GET', `${BASE}/lotti/${id(lottoId)}`),
  fornitori: () => api<Fornitore[]>('GET', `${BASE}/fornitori`),
  fornitore: (fornitoreId: string) => api<Fornitore>('GET', `${BASE}/fornitori/${id(fornitoreId)}`),
};
