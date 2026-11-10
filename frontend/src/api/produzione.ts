/*
 * Produzione: i piani di produzione delle edizioni (dal gateway:
 * /api/produzione/piani). Un piano nasce con l'edizione e cambia con le
 * consegne; l'unica azione a mano è di Marco: integrare un lotto del magazzino.
 */
import { edizioneDellaStagione } from '../utilita/numeri';
import { api } from './http';

/** Una consegna applicata al piano: i kg utilizzabili sono zero se il lotto non va bene, con il motivo. */
export interface ConsegnaNelPiano {
  ordineId: string;
  lottoId: string;
  origine: string;
  kgOrdinati: number;
  kgArrivati: number;
  kgUtilizzabili: number;
  motivo: string;
  consegnataIl: string;
}

export interface IntegrazioneNelPiano {
  lottoId: string;
  origine: string;
  kg: number;
  motivo: string;
  integrataIl: string;
}

/** Dopo la consegna di Elena: 12 kg previsti, 10,8 disponibili, 1,2 mancanti, 540 bottiglie producibili. */
export interface Piano {
  id: string;
  edizioneId: string;
  formulazione: { id: string; versione: number };
  botanica: string;
  grammiPerBottiglia: number;
  origineRichiesta: string;
  kgPrevisti: number;
  kgDisponibili: number;
  kgMancanti: number;
  tiraturaPrevista: number;
  bottiglieProducibili: number;
  consegne: ConsegnaNelPiano[];
  integrazioni: IntegrazioneNelPiano[];
  creatoIl: string;
  aggiornatoIl: string;
}

/** Un lotto del magazzino che il piano potrebbe integrare. stessaOrigine dice in anticipo se va bene. */
export interface LottoDelPiano {
  lottoId: string;
  origine: string;
  kg: number;
  kgDisponibili: number;
  scadenza: string;
  fornitore: string;
  stessaOrigine: boolean;
}

export interface LottiDelPiano {
  pianoId: string;
  botanica: string;
  origineRichiesta: string;
  kgMancanti: number;
  lotti: LottoDelPiano[];
}

export type EsitoIntegrazione = 'RESA_RICALCOLATA' | 'RESA_INVARIATA' | 'GIA_INTEGRATO';

export interface RisultatoIntegrazione {
  esito: EsitoIntegrazione;
  piano: Piano;
}

/** I motivi del 409 "Integrazione rifiutata" (IntegrazioneRifiutata.Motivo), più il rifiuto del magazzino. */
export type MotivoRifiuto =
  | 'LOTTO_NON_DISPONIBILE'
  | 'ALTRA_BOTANICA'
  | 'ORIGINE_DIVERSA'
  | 'LOTTO_GIA_INTEGRATO'
  | 'LOTTO_DELLA_CONSEGNA'
  | 'KG_NON_VALIDI'
  | 'PIANO_COMPLETO'
  | 'KG_ECCESSIVI'
  | 'RISERVA_RIFIUTATA';

/**
 * Il nome dell'edizione di un piano, dalla stagione: "Edizione d’estate 2026".
 * Serve dove l'edizione non si può leggere (chi fa gli acquisti non ha accesso a Edizioni).
 */
export function edizioneDelPiano(piano: Pick<Piano, 'creatoIl'>): string {
  return edizioneDellaStagione(piano.creatoIl);
}

const BASE = '/api/produzione/piani';
const id = (valore: string) => encodeURIComponent(valore);

export const produzione = {
  piani: () => api<Piano[]>('GET', BASE),
  /** Il piano di un'edizione: un elenco con un elemento, o vuoto se il piano non è ancora nato. */
  pianiDellEdizione: (edizioneId: string) => api<Piano[]>('GET', `${BASE}?edizione=${id(edizioneId)}`),
  piano: (pianoId: string) => api<Piano>('GET', `${BASE}/${id(pianoId)}`),
  lotti: (pianoId: string) => api<LottiDelPiano>('GET', `${BASE}/${id(pianoId)}/lotti`),
  integra: (pianoId: string, lottoId: string, kg: number) =>
    api<RisultatoIntegrazione>('POST', `${BASE}/${id(pianoId)}/integrazioni`, { lottoId, kg }),
};
