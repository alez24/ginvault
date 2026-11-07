/* Come presentare gli errori delle API: i campi del 400 e cosa può fare l'utente. */
import { NOMI_CANALI, type Canale } from './edizioni';
import type { ErroreApi } from './http';

export interface CampoNonValido {
  campo: string;
  messaggio: string;
}

/** I campi sbagliati di un 400 "Richiesta non valida" (proprietà "campi" del Problem Detail). */
export function campiNonValidi(errore: ErroreApi): CampoNonValido[] {
  const campi = errore.problema?.campi;
  if (!Array.isArray(campi)) {
    return [];
  }
  return campi.filter(
    (campo): campo is CampoNonValido =>
      typeof campo === 'object' &&
      campo !== null &&
      typeof (campo as CampoNonValido).campo === 'string' &&
      typeof (campo as CampoNonValido).messaggio === 'string',
  );
}

const NOMI: Record<string, string> = {
  nome: 'Nome',
  botanicaDiStagione: 'Botanica di stagione',
  dosi: 'Dosi',
  versione: 'Versione',
  formulazione: 'Formulazione',
  'formulazione.id': 'Formulazione',
  'formulazione.versione': 'Versione della formulazione',
  botanicaDisponibileKg: 'Kg di botanica disponibili',
  quote: 'Quote',
};

/** Il nome di un campo come lo legge una persona: "dosi[1].grammiPerBottiglia" -> "Dose 2, grammi per bottiglia". */
export function nomeCampo(campo: string): string {
  if (campo in NOMI) {
    return NOMI[campo];
  }
  const dose = /^dosi\[(\d+)\](?:\.(\w+))?$/.exec(campo);
  if (dose) {
    const numero = Number(dose[1]) + 1;
    const parte = dose[2] === 'botanica' ? ', botanica' : dose[2] === 'grammiPerBottiglia' ? ', grammi per bottiglia' : '';
    return `Dose ${numero}${parte}`;
  }
  const quota = /^quote\[(\w+)\]$/.exec(campo);
  if (quota) {
    const canale = quota[1] as Canale;
    return `Quota ${canale in NOMI_CANALI ? NOMI_CANALI[canale].toLowerCase() : canale}`;
  }
  return campo;
}

/** Cosa suggerire oltre al messaggio del servizio. */
export type Rimedio = 'riprova' | 'ricarica' | 'accedi' | undefined;

export function rimedio(errore: ErroreApi): Rimedio {
  if (errore.stato === 401) {
    return 'accedi';
  }
  if (errore.stato === 0 || errore.stato === 502 || errore.stato === 503 || errore.stato === 504) {
    return 'riprova';
  }
  if (errore.stato === 409 && errore.titolo === 'Modifica concorrente') {
    return 'ricarica';
  }
  return undefined;
}
