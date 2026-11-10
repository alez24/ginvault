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

/** Le date senza ora del magazzino ("2026-08-06"): si leggono e si scrivono in UTC, così non cambiano giorno col fuso. */
const SOLO_GIORNO = /^\d{4}-\d{2}-\d{2}$/;
const FORMATO_GIORNO_UTC = new Intl.DateTimeFormat('it-IT', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

/** "5 novembre 2026", da un istante ("2026-11-05T10:00:00Z") o da un giorno ("2026-11-05"). */
export function data(iso: string): string {
  return SOLO_GIORNO.test(iso) ? FORMATO_GIORNO_UTC.format(new Date(`${iso}T00:00:00Z`)) : FORMATO_DATA.format(new Date(iso));
}

/** Oggi a Roma, come lo conta il magazzino: "2026-11-10". */
export function oggi(adesso: Date = new Date()): string {
  const parti = new Intl.DateTimeFormat('it-IT', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    timeZone: 'Europe/Rome',
  }).formatToParts(adesso);
  const parte = (tipo: Intl.DateTimeFormatPartTypes) => parti.find((p) => p.type === tipo)?.value ?? '';
  return `${parte('year')}-${parte('month')}-${parte('day')}`;
}

/** Un giorno spostato di qualche giorno: ("2026-08-06", 1) -> "2026-08-07". */
export function giornoDopo(giorno: string, giorni: number): string {
  const d = new Date(`${giorno}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + giorni);
  return d.toISOString().slice(0, 10);
}

/** Giorni da un giorno all'altro: ("2026-07-31", "2026-08-06") -> 6. Negativo se il secondo viene prima. */
export function giorniTra(da: string, a: string): number {
  return Math.round((Date.parse(`${a}T00:00:00Z`) - Date.parse(`${da}T00:00:00Z`)) / 86_400_000);
}

/** È un giorno vero nel formato "AAAA-MM-GG" (niente 31 febbraio). */
export function giornoValido(testo: string): boolean {
  return SOLO_GIORNO.test(testo) && new Date(`${testo}T00:00:00Z`).toISOString().startsWith(testo);
}

/** "10,8 kg". */
export function kg(valore: number): string {
  return `${numero(valore)} kg`;
}

/** "6 giorni", "1 giorno". */
export function giorni(n: number): string {
  return n === 1 ? '1 giorno' : `${numero(n)} giorni`;
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

// Gli appuntamenti del club sono in distilleria: le ore sono quelle di Roma
const FUSO = 'Europe/Rome';
const FORMATO_GIORNO = new Intl.DateTimeFormat('it-IT', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: FUSO,
});
const FORMATO_ORA = new Intl.DateTimeFormat('it-IT', { hour: 'numeric', minute: '2-digit', timeZone: FUSO });

/** "sabato 22 agosto 2026". */
export function giornoConNome(iso: string): string {
  return FORMATO_GIORNO.format(new Date(iso));
}

/** Le parti della data, per mostrarle separate: { settimana: "sabato", giorno: "22", mese: "agosto", anno: "2026" }. */
export function partiDelGiorno(iso: string): { settimana: string; giorno: string; mese: string; anno: string } {
  const parti = FORMATO_GIORNO.formatToParts(new Date(iso));
  const parte = (tipo: Intl.DateTimeFormatPartTypes) => parti.find((p) => p.type === tipo)?.value ?? '';
  return { settimana: parte('weekday'), giorno: parte('day'), mese: parte('month'), anno: parte('year') };
}

/** "18" per le ore piene, "18:30" altrimenti. */
export function ora(iso: string): string {
  const testo = FORMATO_ORA.format(new Date(iso));
  return testo.endsWith(':00') ? testo.slice(0, -3) : testo;
}

/** "dalle 18 alle 20". */
export function orario(inizio: string, fine: string): string {
  return `dalle ${ora(inizio)} alle ${ora(fine)}`;
}

/** "lavanda" -> "Lavanda". */
export function maiuscola(testo: string): string {
  return testo ? testo.charAt(0).toLocaleUpperCase('it-IT') + testo.slice(1) : testo;
}

/** "1 bottiglia", "540 bottiglie". */
export function bottiglie(n: number): string {
  return n === 1 ? '1 bottiglia' : `${numero(n)} bottiglie`;
}
