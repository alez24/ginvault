/* Numeri e date all'italiana: leggere quello che scrive l'utente e mostrare quello che arriva dalle API. */

/**
 * Da testo a numero, accettando la virgola decimale ("10,8" -> 10.8).
 * null se è vuoto o non è un numero (niente "1e3", "12abc", "1.000,5").
 */
export function leggiNumero(testo: string): number | null {
  const pulito = testo.trim().replace(',', '.');
  if (!/^\d+(\.\d+)?$/.test(pulito)) {
    return null;
  }
  return Number(pulito);
}

/** Quante cifre ha il numero scritto prima e dopo la virgola (per i limiti di Bean Validation). */
export function cifre(testo: string): { intere: number; decimali: number } {
  const [intere = '', decimali = ''] = testo.trim().replace(',', '.').split('.');
  return { intere: intere.replace(/^0+(?=\d)/, '').length, decimali: decimali.replace(/0+$/, '').length };
}

/** Da numero a testo per un campo del modulo: 10.8 -> "10,8". */
export function perCampo(valore: number): string {
  return String(valore).replace('.', ',');
}

const FORMATO_NUMERO = new Intl.NumberFormat('it-IT', { maximumFractionDigits: 3 });
const FORMATO_DATA = new Intl.DateTimeFormat('it-IT', { day: 'numeric', month: 'long', year: 'numeric' });

export function numero(valore: number): string {
  return FORMATO_NUMERO.format(valore);
}

/** "+60", "−60", "0": per le differenze prima/dopo. */
export function differenza(valore: number): string {
  if (valore === 0) {
    return '0';
  }
  return valore > 0 ? `+${numero(valore)}` : `−${numero(-valore)}`;
}

export function data(iso: string): string {
  return FORMATO_DATA.format(new Date(iso));
}

/** La data con l'articolo: "il 5 novembre 2026", ma "l’8 ottobre" e "l’11 marzo". */
export function ilGiorno(iso: string): string {
  const testo = data(iso);
  return /^(8|11) /.test(testo) ? `l’${testo}` : `il ${testo}`;
}

/** La stagione di ogni mese, da gennaio. */
const STAGIONI = [
  'd’inverno', 'd’inverno',
  'di primavera', 'di primavera', 'di primavera',
  'd’estate', 'd’estate', 'd’estate',
  'd’autunno', 'd’autunno', 'd’autunno',
  'd’inverno',
];

/**
 * Il nome dell'edizione sull'etichetta, dalla stagione della data:
 * "Edizione d’estate 2026". Stagioni meteorologiche (giugno–agosto è estate),
 * secondo il calendario di Roma.
 */
export function edizioneDellaStagione(iso: string): string {
  const parti = new Intl.DateTimeFormat('it-IT', { month: 'numeric', year: 'numeric', timeZone: 'Europe/Rome' })
    .formatToParts(new Date(iso));
  const mese = Number(parti.find((p) => p.type === 'month')?.value);
  const anno = parti.find((p) => p.type === 'year')?.value;
  return `Edizione ${STAGIONI[mese - 1]} ${anno}`;
}

/** "lavanda" -> "Lavanda". */
export function maiuscola(testo: string): string {
  return testo ? testo.charAt(0).toLocaleUpperCase('it-IT') + testo.slice(1) : testo;
}

/** "1 bottiglia", "540 bottiglie". */
export function bottiglie(n: number): string {
  return n === 1 ? '1 bottiglia' : `${numero(n)} bottiglie`;
}
