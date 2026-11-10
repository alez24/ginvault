/* Come presentare gli errori delle API: i campi del 400 e cosa può fare l'utente. */
import { NOMI_CANALI, type Canale } from './edizioni';
import type { ErroreApi } from './http';
import type { MotivoRifiuto } from './produzione';

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
  origineBotanica: 'Origine della botanica',
  quote: 'Quote',
  // magazzino
  fornitoreId: 'Fornitore',
  botanica: 'Botanica',
  kgOrdinati: 'Kg ordinati',
  consegnaAttesa: 'Consegna attesa',
  edizioneId: 'Edizione',
  kgArrivati: 'Kg arrivati',
  consegnataIl: 'Giorno della consegna',
  scadenza: 'Scadenza del lotto',
  esitoQualita: 'Controllo qualità',
  nota: 'Nota',
  // produzione
  lottoId: 'Lotto',
  kg: 'Kg da integrare',
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

const MOTIVI: MotivoRifiuto[] = [
  'LOTTO_NON_DISPONIBILE',
  'ALTRA_BOTANICA',
  'ORIGINE_DIVERSA',
  'LOTTO_GIA_INTEGRATO',
  'LOTTO_DELLA_CONSEGNA',
  'KG_NON_VALIDI',
  'PIANO_COMPLETO',
  'KG_ECCESSIVI',
  'RISERVA_RIFIUTATA',
];

/** Il 409 di POST /piani/{id}/integrazioni: il motivo e, per l'origine diversa, le due origini. */
export interface RifiutoIntegrazione {
  motivo: MotivoRifiuto;
  origineRichiesta?: string;
  origineLotto?: string;
}

/** undefined se l'errore non è un rifiuto con motivo (per esempio 503: il magazzino non risponde). */
export function rifiutoIntegrazione(errore: ErroreApi): RifiutoIntegrazione | undefined {
  const motivo = errore.problema?.motivo;
  if (errore.stato !== 409 || typeof motivo !== 'string' || !MOTIVI.includes(motivo as MotivoRifiuto)) {
    return undefined;
  }
  const testo = (valore: unknown) => (typeof valore === 'string' && valore.trim() !== '' ? valore : undefined);
  return {
    motivo: motivo as MotivoRifiuto,
    origineRichiesta: testo(errore.problema?.origineRichiesta),
    origineLotto: testo(errore.problema?.origineLotto),
  };
}
