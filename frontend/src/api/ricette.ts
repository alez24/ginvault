/* Ricette: formulazioni e prove d'assaggio (dal gateway: /api/ricette/formulazioni). */
import { api } from './http';

export type StatoFormulazione = 'IN_PROVA' | 'SCELTA' | 'USATA';
export type EsitoProva = 'IN_ASSAGGIO' | 'SCELTA' | 'ARCHIVIATA';

export interface Dose {
  botanica: string;
  grammiPerBottiglia: number;
}

export interface Prova {
  versione: number;
  esito: EsitoProva;
  registrataIl: string;
  dosi: Dose[];
}

/** FormulazioneVista di Ricette. */
export interface Formulazione {
  id: string;
  nome: string;
  botanicaDiStagione: string;
  stato: StatoFormulazione;
  versioneScelta: number | null;
  edizioneId: string | null;
  prove: Prova[];
}

export interface NuovaFormulazione {
  nome: string;
  botanicaDiStagione: string;
  dosi: Dose[];
}

const BASE = '/api/ricette/formulazioni';

export const ricette = {
  elenco: (stato?: StatoFormulazione) =>
    api<Formulazione[]>('GET', stato ? `${BASE}?stato=${encodeURIComponent(stato)}` : BASE),
  trova: (id: string) => api<Formulazione>('GET', `${BASE}/${encodeURIComponent(id)}`),
  crea: (formulazione: NuovaFormulazione) => api<Formulazione>('POST', BASE, formulazione),
  aggiungiProva: (id: string, dosi: Dose[]) =>
    api<Formulazione>('POST', `${BASE}/${encodeURIComponent(id)}/versioni`, { dosi }),
  scegli: (id: string, versione: number) =>
    api<Formulazione>('POST', `${BASE}/${encodeURIComponent(id)}/scelta`, { versione }),
};

/** La prova scelta dal panel, se c'è. */
export function provaScelta(formulazione: Formulazione): Prova | undefined {
  return formulazione.prove.find((prova) => prova.versione === formulazione.versioneScelta);
}

/** Grammi per bottiglia della botanica di stagione in una prova (es. 20 g di lavanda). */
export function grammiDiStagione(formulazione: Formulazione, prova: Prova | undefined): number | undefined {
  const stagione = formulazione.botanicaDiStagione.trim().toLowerCase();
  return prova?.dosi.find((dose) => dose.botanica.trim().toLowerCase() === stagione)?.grammiPerBottiglia;
}
