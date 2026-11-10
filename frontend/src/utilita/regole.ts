/*
 * Le regole di Ricette ed Edizioni rifatte qui per le ANTEPRIME e per
 * segnalare gli errori mentre si scrive. Decidono sempre i servizi: se queste
 * regole e il dominio divergono, vince la risposta del servizio (e l'errore
 * che arriva viene mostrato com'è).
 *
 *   Dose, Formulazione.verificaDosi      -> controllaDosi
 *   Tiratura.da                          -> tiraturaPrevista
 *   Edizione.ricalcola / riduci / aumenta -> ricalcoloPrevisto
 *   Richieste.NuovaEdizione / Ricalcolo  -> controllaKg, controllaQuota
 *   OrdineDiAcquisto.nuovo / registraConsegna (magazzino) -> controllaOrdine, controllaConsegna, consegnaPrevista
 *   PianoDiProduzione.integra, Origini.stessa (produzione) -> integrazionePrevista, stessaOrigine
 */
import { CANALI, type Canale, type Edizione } from '../api/edizioni';
import type { EsitoQualita, Fornitore, NuovaConsegna, NuovoOrdine, Ordine } from '../api/magazzino';
import type { LottoDelPiano, Piano } from '../api/produzione';
import type { Dose } from '../api/ricette';
import { cifre, giorniTra, giornoValido, leggiNumero, numero } from './numeri';

/** Tiratura massima di un'edizione (Tiratura.MASSIMA). */
export const TIRATURA_MASSIMA = 600;

/** Come Dose.normalizzaBotanica: "Lavanda " e "lavanda" sono la stessa botanica. */
export function normalizzaBotanica(botanica: string): string {
  return botanica.trim().toLowerCase();
}

// ------------------------------------------------------------------ dosi

/** Una riga del modulo delle dosi, così come la scrive l'utente. */
export interface RigaDose {
  chiave: number;
  botanica: string;
  grammi: string;
}

export interface ErroriRiga {
  botanica?: string;
  grammi?: string;
}

export interface ControlloDosi {
  /** Le dosi da mandare a Ricette, oppure null se c'è qualcosa da correggere. */
  dosi: Dose[] | null;
  /** Errori delle singole righe, per chiave. */
  righe: Record<number, ErroriRiga>;
  /** Errori dell'elenco intero (nessuna dose, manca la botanica di stagione). */
  generali: string[];
}

/** Le righe lasciate del tutto vuote non contano: si possono aggiungere righe in più senza errori. */
export function rigaVuota(riga: RigaDose): boolean {
  return riga.botanica.trim() === '' && riga.grammi.trim() === '';
}

export function controllaDosi(righe: RigaDose[], botanicaDiStagione: string): ControlloDosi {
  const errori: Record<number, ErroriRiga> = {};
  const generali: string[] = [];
  const dosi: Dose[] = [];
  const viste = new Set<string>();

  for (const riga of righe.filter((r) => !rigaVuota(r))) {
    const erroriRiga: ErroriRiga = {};
    const botanica = riga.botanica.trim();
    const nome = normalizzaBotanica(botanica);

    if (botanica === '') {
      erroriRiga.botanica = 'Scrivi la botanica.';
    } else if (botanica.length > 50) {
      erroriRiga.botanica = 'Al massimo 50 caratteri.';
    } else if (viste.has(nome)) {
      erroriRiga.botanica = `${botanica} compare già sopra: ogni botanica una volta sola.`;
    }
    viste.add(nome);

    const grammi = controllaGrammi(riga.grammi);
    if (typeof grammi === 'string') {
      erroriRiga.grammi = grammi;
    }

    if (erroriRiga.botanica || erroriRiga.grammi) {
      errori[riga.chiave] = erroriRiga;
    } else if (typeof grammi === 'number') {
      dosi.push({ botanica, grammiPerBottiglia: grammi });
    }
  }

  if (viste.size === 0) {
    generali.push('Serve almeno una dose.');
  } else {
    const stagione = normalizzaBotanica(botanicaDiStagione);
    if (stagione !== '' && !viste.has(stagione)) {
      generali.push(`Manca la botanica di stagione (${stagione}): serve a calcolare la tiratura.`);
    }
  }

  const valide = Object.keys(errori).length === 0 && generali.length === 0;
  return { dosi: valide ? dosi : null, righe: errori, generali };
}

/** I grammi per bottiglia: maggiori di zero, al massimo 5 cifre intere e 2 decimali. */
function controllaGrammi(testo: string): number | string {
  if (testo.trim() === '') {
    return 'Scrivi i grammi per bottiglia.';
  }
  if (testo.trim().startsWith('-')) {
    return 'Devono essere più di zero.';
  }
  const valore = leggiNumero(testo);
  if (valore === null) {
    return 'Scrivi un numero, per esempio 12,5.';
  }
  if (valore <= 0) {
    return 'Devono essere più di zero.';
  }
  const { intere, decimali } = cifre(testo);
  if (decimali > 2) {
    return 'Al massimo due decimali.';
  }
  if (intere > 5) {
    return 'Al massimo 5 cifre prima della virgola.';
  }
  return valore;
}

// ------------------------------------------------------------------ edizione

/** I kg di botanica: maggiori di zero, al grammo (3 decimali), al massimo 5 cifre intere. */
export function controllaKg(testo: string, seVuoto = 'Scrivi i kg disponibili.'): number | string {
  if (testo.trim() === '') {
    return seVuoto;
  }
  if (testo.trim().startsWith('-')) {
    return 'Devono essere più di zero.';
  }
  const valore = leggiNumero(testo);
  if (valore === null) {
    return 'Scrivi un numero, per esempio 10,8.';
  }
  if (valore <= 0) {
    return 'Devono essere più di zero.';
  }
  const { intere, decimali } = cifre(testo);
  if (decimali > 3) {
    return 'Al massimo tre decimali: i kg si indicano al grammo.';
  }
  if (intere > 5) {
    return 'Al massimo 5 cifre prima della virgola.';
  }
  return valore;
}

/** Una quota: un numero intero di bottiglie, anche zero. */
export function controllaQuota(testo: string): number | string {
  const pulito = testo.trim();
  if (pulito === '') {
    return 'Scrivi quante bottiglie (anche 0).';
  }
  if (!/^\d+$/.test(pulito)) {
    return 'Un numero intero di bottiglie, zero o più.';
  }
  return Number(pulito);
}

/**
 * Quante bottiglie si riempiono: kg × 1000 / grammi per bottiglia, per difetto.
 * I conti si fanno in centigrammi interi per non sbagliare con la virgola
 * mobile (1,005 × 1000 = 1004,999… in JavaScript).
 */
export function bottigliePossibili(kg: number, grammiPerBottiglia: number): number {
  if (!(kg > 0) || !(grammiPerBottiglia > 0)) {
    return 0;
  }
  const centigrammiDisponibili = Math.round(kg * 100_000);
  const centigrammiPerBottiglia = Math.round(grammiPerBottiglia * 100);
  return Math.floor(centigrammiDisponibili / centigrammiPerBottiglia);
}

/** Tiratura.da: le bottiglie possibili, al massimo 600. 0 = non si riempie nemmeno una bottiglia. */
export function tiraturaPrevista(kg: number, grammiPerBottiglia: number): number {
  return Math.min(TIRATURA_MASSIMA, bottigliePossibili(kg, grammiPerBottiglia));
}

/** Una divisione di partenza: metà ai soci, un quarto ai bar, il resto online (600 -> 300/150/150). */
export function quoteSuggerite(tiratura: number): Record<Canale, number> {
  const soci = Math.floor(tiratura / 2);
  const bar = Math.floor(tiratura / 4);
  return { SOCI: soci, BAR: bar, ONLINE: tiratura - soci - bar };
}

export function somma(quote: Record<Canale, number>): number {
  return CANALI.reduce((totale, canale) => totale + quote[canale], 0);
}

// ------------------------------------------------------------------ ricalcolo

export type Ricalcolo =
  /** Si può fare: la nuova tiratura e le nuove quote. */
  | { esito: 'possibile'; tiratura: number; quote: Record<Canale, number> }
  /** Con quei kg non si riempie nemmeno una bottiglia (Edizioni risponde 422). */
  | { esito: 'nessuna-bottiglia' }
  /** Bisognerebbe toccare SOCI o scendere sotto le prenotate (Edizioni risponde 409). */
  | { esito: 'non-riducibile'; tiratura: number; daTogliere: number; libere: number };

/** Prima ONLINE, poi BAR; SOCI non si riduce mai. */
const ORDINE_DI_RIDUZIONE: Canale[] = ['ONLINE', 'BAR'];

/**
 * Edizione.ricalcola: se la tiratura scende si toglie da ONLINE e poi da BAR,
 * mai sotto le bottiglie già prenotate; se sale, le bottiglie in più vanno a ONLINE.
 */
export function ricalcoloPrevisto(edizione: Edizione, kg: number): Ricalcolo {
  const nuova = tiraturaPrevista(kg, edizione.grammiPerBottiglia);
  if (nuova === 0) {
    return { esito: 'nessuna-bottiglia' };
  }
  const quote = { ...edizione.quote };

  if (nuova < edizione.tiratura) {
    const daTogliere = edizione.tiratura - nuova;
    let restano = daTogliere;
    for (const canale of ORDINE_DI_RIDUZIONE) {
      const tolte = Math.min(restano, quote[canale] - edizione.prenotate[canale]);
      quote[canale] -= tolte;
      restano -= tolte;
    }
    if (restano > 0) {
      return { esito: 'non-riducibile', tiratura: nuova, daTogliere, libere: daTogliere - restano };
    }
  } else if (nuova > edizione.tiratura) {
    quote.ONLINE += nuova - edizione.tiratura;
  }
  return { esito: 'possibile', tiratura: nuova, quote };
}

// ------------------------------------------------------------------ acquisti (magazzino)

/** In grammi interi: 12 - 10,8 fa 1,2 e non 1,1999999999999993. */
function grammi(kg: number): number {
  return Math.round(kg * 1000);
}

/** Come Origini.stessa di Produzione: contano le parole, non maiuscole e spazi. */
export function stessaOrigine(una: string, altra: string): boolean {
  const chiave = (testo: string) => testo.trim().replace(/\s+/g, ' ').toLowerCase();
  return chiave(una) === chiave(altra);
}

/** Il modulo del nuovo ordine, così come lo scrive Elena. */
export interface TestiOrdine {
  pianoId: string;
  fornitoreId: string;
  kg: string;
  consegnaAttesa: string;
}

export type ErroriOrdine = Partial<Record<keyof TestiOrdine, string>>;

/**
 * Il nuovo ordine da mandare al magazzino, oppure gli errori da correggere.
 * L'edizione si sceglie dal suo piano di produzione: da lì vengono anche la
 * botanica e l'origine richiesta.
 */
export function controllaOrdine(
  testi: TestiOrdine,
  piani: Piano[],
  fornitori: Fornitore[],
): { ordine: NuovoOrdine | null; errori: ErroriOrdine } {
  const errori: ErroriOrdine = {};
  const piano = piani.find((p) => p.id === testi.pianoId);
  if (!piano) {
    errori.pianoId = 'Scegli l’edizione per cui ordini.';
  }
  const fornitore = fornitori.find((f) => f.id === testi.fornitoreId);
  if (!fornitore) {
    errori.fornitoreId = 'Scegli il fornitore.';
  }
  const kg = controllaKg(testi.kg, 'Scrivi quanti kg ordinare.');
  if (typeof kg === 'string') {
    errori.kg = kg;
  }
  if (testi.consegnaAttesa.trim() === '') {
    errori.consegnaAttesa = 'Scrivi quando deve arrivare.';
  } else if (!giornoValido(testi.consegnaAttesa)) {
    errori.consegnaAttesa = 'Non è una data valida.';
  }
  if (Object.keys(errori).length > 0 || !piano || !fornitore || typeof kg !== 'number') {
    return { ordine: null, errori };
  }
  return {
    ordine: {
      fornitoreId: fornitore.id,
      botanica: piano.botanica,
      kgOrdinati: kg,
      consegnaAttesa: testi.consegnaAttesa,
      edizioneId: piano.edizioneId,
    },
    errori,
  };
}

/** Il modulo della consegna. */
export interface TestiConsegna {
  kg: string;
  consegnataIl: string;
  scadenza: string;
  esito: EsitoQualita | '';
  nota: string;
}

export type ErroriConsegna = Partial<Record<keyof TestiConsegna, string>>;

export const NOTA_MASSIMA = 500;

/**
 * Le regole di OrdineDiAcquisto.registraConsegna: kg maggiori di zero e non più
 * di quelli ordinati, consegna non nel futuro, scadenza dopo la consegna,
 * l'esito del controllo qualità. Una consegna con meno kg va bene: è ridotta.
 */
export function controllaConsegna(
  testi: TestiConsegna,
  ordine: Ordine,
  oggi: string,
): { consegna: NuovaConsegna | null; errori: ErroriConsegna } {
  const errori: ErroriConsegna = {};
  const kg = controllaKg(testi.kg, 'Scrivi i kg arrivati.');
  if (typeof kg === 'string') {
    errori.kg = kg;
  } else if (grammi(kg) > grammi(ordine.kgOrdinati)) {
    errori.kg = `Ne erano stati ordinati ${numero(ordine.kgOrdinati)} kg: quelli in più vanno registrati con un altro ordine.`;
  }

  const consegnaValida = giornoValido(testi.consegnataIl);
  if (testi.consegnataIl.trim() === '') {
    errori.consegnataIl = 'Scrivi quando è arrivata.';
  } else if (!consegnaValida) {
    errori.consegnataIl = 'Non è una data valida.';
  } else if (giorniTra(oggi, testi.consegnataIl) > 0) {
    errori.consegnataIl = 'È nel futuro: la consegna si registra quando la botanica è arrivata.';
  }

  if (testi.scadenza.trim() === '') {
    errori.scadenza = 'Scrivi la scadenza del lotto.';
  } else if (!giornoValido(testi.scadenza)) {
    errori.scadenza = 'Non è una data valida.';
  } else if (consegnaValida && giorniTra(testi.consegnataIl, testi.scadenza) <= 0) {
    errori.scadenza = 'La scadenza deve venire dopo il giorno della consegna.';
  }

  if (testi.esito === '') {
    errori.esito = 'Scegli l’esito del controllo qualità.';
  }
  const nota = testi.nota.trim().replace(/\s+/g, ' ');
  if (nota.length > NOTA_MASSIMA) {
    errori.nota = `Al massimo ${NOTA_MASSIMA} caratteri (ora sono ${numero(nota.length)}).`;
  }

  if (Object.keys(errori).length > 0 || typeof kg !== 'number' || testi.esito === '') {
    return { consegna: null, errori };
  }
  return {
    consegna: {
      kgArrivati: kg,
      consegnataIl: testi.consegnataIl,
      scadenza: testi.scadenza,
      esitoQualita: testi.esito,
      ...(nota ? { nota } : {}),
    },
    errori,
  };
}

export interface ConsegnaPrevista {
  ridotta: boolean;
  kgMancanti: number;
  inRitardo: boolean;
  giorniDiRitardo: number;
}

/** Come la vedrà il magazzino: ridotta se arrivano meno kg, in ritardo se arriva dopo la data attesa. */
export function consegnaPrevista(ordine: Ordine, kgArrivati: number, consegnataIl: string): ConsegnaPrevista {
  const mancanti = Math.max(0, grammi(ordine.kgOrdinati) - grammi(kgArrivati)) / 1000;
  const ritardo = giornoValido(consegnataIl) ? Math.max(0, giorniTra(ordine.consegnaAttesa, consegnataIl)) : 0;
  return { ridotta: mancanti > 0, kgMancanti: mancanti, inRitardo: ritardo > 0, giorniDiRitardo: ritardo };
}

/** L'avviso della consegna ridotta: "Consegna ridotta: 10,8 su 12 kg". */
export function testoConsegnaRidotta(kgArrivati: number, kgOrdinati: number): string {
  return `Consegna ridotta: ${numero(kgArrivati)} su ${numero(kgOrdinati)} kg`;
}

// ------------------------------------------------------------------ piano di produzione

/**
 * Da dove vengono i kg disponibili di un piano: dalle consegne utilizzabili,
 * dai lotti integrati, e quelli ancora attesi (ordini aperti o da fare: la
 * regola di Produzione li conta finché non arriva la consegna). In grammi
 * interi, mai oltre i disponibili.
 */
export function kgDelPiano(piano: Piano): { consegnati: number; integrati: number; attesi: number; mancanti: number } {
  const disponibili = grammi(piano.kgDisponibili);
  const integrati = Math.min(
    disponibili,
    piano.integrazioni.reduce((somma, integrazione) => somma + grammi(integrazione.kg), 0),
  );
  const consegnati = Math.min(
    disponibili - integrati,
    piano.consegne.reduce((somma, consegna) => somma + grammi(consegna.kgUtilizzabili), 0),
  );
  return {
    consegnati: consegnati / 1000,
    integrati: integrati / 1000,
    attesi: (disponibili - integrati - consegnati) / 1000,
    mancanti: Math.max(0, grammi(piano.kgPrevisti) - disponibili) / 1000,
  };
}

// ------------------------------------------------------------------ integrazione di un lotto (produzione)

/** I kg da proporre per un lotto: quelli che mancano al piano, se il lotto li ha liberi. */
export function kgDaProporre(lotto: LottoDelPiano, kgMancanti: number): number {
  return Math.min(grammi(kgMancanti), grammi(lotto.kgDisponibili)) / 1000;
}

export type IntegrazionePrevista =
  /** Il piano lo accetta: i kg disponibili dopo e le bottiglie che si possono fare. */
  | { esito: 'possibile'; kgDopo: number; bottiglie: number }
  /** L'origine del lotto non è quella dell'edizione: Produzione rifiuta (ORIGINE_DIVERSA). */
  | { esito: 'origine-diversa' }
  /** Più kg di quelli che mancano al piano (KG_ECCESSIVI). */
  | { esito: 'troppi'; kgMancanti: number }
  /** Il lotto non ha tanti kg liberi: il magazzino rifiuta la riserva. */
  | { esito: 'non-bastano'; kgLiberi: number };

/** PianoDiProduzione.integra: prima l'origine, poi i kg che mancano, poi i kg liberi del lotto. */
export function integrazionePrevista(piano: Piano, lotto: LottoDelPiano, kg: number): IntegrazionePrevista {
  if (!lotto.stessaOrigine) {
    return { esito: 'origine-diversa' };
  }
  if (grammi(kg) > grammi(piano.kgMancanti)) {
    return { esito: 'troppi', kgMancanti: piano.kgMancanti };
  }
  if (grammi(kg) > grammi(lotto.kgDisponibili)) {
    return { esito: 'non-bastano', kgLiberi: lotto.kgDisponibili };
  }
  const kgDopo = (grammi(piano.kgDisponibili) + grammi(kg)) / 1000;
  return { esito: 'possibile', kgDopo, bottiglie: tiraturaPrevista(kgDopo, piano.grammiPerBottiglia) };
}
