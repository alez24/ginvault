/*
 * Area soci (storia di Giulia): prenotazioni e pagamento, da Edizioni (dal
 * gateway /api/edizioni). Il pagamento è una SAGA: la POST risponde 202 e
 * l'esito arriva dopo, da Pagamenti con Stripe in modalità test (docs/eventi.md).
 * Gli avvisi arrivano da Notifiche (src/api/notifiche.ts).
 */
import { type Canale, type Edizione } from './edizioni';
import { api } from './http';
import { edizioneDellaStagione } from '../utilita/numeri';

/** Gli stati di una prenotazione (StatoPrenotazione di Edizioni). */
export type StatoPrenotazione = 'IN_ATTESA_PAGAMENTO' | 'CONFERMATA' | 'ANNULLATA';

export const NOMI_STATI_PRENOTAZIONE: Record<StatoPrenotazione, string> = {
  IN_ATTESA_PAGAMENTO: 'Da pagare',
  CONFERMATA: 'Confermata',
  ANNULLATA: 'Annullata',
};

export type CausaAnnullamento = 'PAGAMENTO_RIFIUTATO' | 'SCADUTA';

/** Dove e quando si ritirano le bottiglie: un'esperienza del club. */
export interface Ritiro {
  /** In minuscolo, si legge dopo «Ritiro alla»: "masterclass d’agosto". */
  occasione: string;
  tema: string;
  descrizione: string;
  /** Inizio, ISO 8601 con il fuso. */
  inizio: string;
  /** Fine, ISO 8601 con il fuso. */
  fine: string;
  luogo: string;
  daPortare: string;
}

/** PrenotazioneVista di Edizioni. */
export interface Prenotazione {
  id: string;
  edizione: {
    id: string;
    botanicaDiStagione: string;
    origineBotanica: string | null;
    tiratura: number;
    creataIl: string;
  };
  canale: Canale;
  bottiglie: number;
  prezzoPerBottiglia: number;
  totale: number;
  stato: StatoPrenotazione;
  prenotataIl: string;
  /** Entro quando pagare: dopo, la prenotazione si annulla da sola. */
  scadeIl: string;
  /** null finché non si chiede di pagare; riferimento (pi_...) e confermataIl con il pagamento riuscito. */
  pagamento: { metodo: string; richiestoIl: string; riferimento: string | null; confermataIl: string | null } | null;
  /** I numeri delle bottiglie, assegnati alla conferma (vuoto prima). */
  numeriBottiglia: number[];
  annullamento: { causa: CausaAnnullamento; spiegazione: string | null; annullataIl: string } | null;
  /** Un pagamento arrivato a prenotazione annullata: si restituisce. */
  rimborso: { richiestoIl: string; eseguitoIl: string | null; riferimento: string | null } | null;
  ritiro: Ritiro;
}

/** RegoleDiVenditaVista di Edizioni. */
export interface RegoleDiVendita {
  giorniDiPrelazione: number;
  bottigliePerSocio: number;
  bottigliePerCliente: number;
  prezzoSoci: number;
  prezzoOnline: number;
  minutiPerPagare: number;
  /** Le carte di prova di Stripe che si possono scegliere. */
  metodiDiPagamento: string[];
}

const BASE = '/api/edizioni';
const id = (valore: string) => encodeURIComponent(valore);

export const soci = {
  regole: () => api<RegoleDiVendita>('GET', `${BASE}/regole-di-vendita`),
  /** Le mie prenotazioni, dalla più recente. */
  prenotazioni: () => api<Prenotazione[]>('GET', `${BASE}/prenotazioni`),
  prenotazione: (prenotazioneId: string) => api<Prenotazione>('GET', `${BASE}/prenotazioni/${id(prenotazioneId)}`),
  prenota: (edizioneId: string, bottiglie: number) =>
    api<Prenotazione>('POST', `${BASE}/edizioni/${id(edizioneId)}/prenotazioni`, { bottiglie }),
  /** 202: il pagamento è partito, l'esito si legge rileggendo la prenotazione. */
  paga: (prenotazioneId: string, metodo: string) =>
    api<Prenotazione>('POST', `${BASE}/prenotazioni/${id(prenotazioneId)}/pagamento`, { metodo }),
};

/** Il percorso della pagina di un'edizione nell'area soci. */
export const percorsoEdizioneSoci = (edizioneId: string) => `/soci/edizioni/${encodeURIComponent(edizioneId)}`;

/** "Edizione d’estate 2026": il nome sull'etichetta, dalla stagione dell'annuncio. */
export function nomeEdizione(edizione: { creataIl: string }): string {
  return edizioneDellaStagione(edizione.creataIl);
}

/** La lavanda è il colore della sola edizione d'estate. */
export function estiva(nome: string): boolean {
  return /estate/i.test(nome);
}

/** "n. 112 e 113", "n. 112", "n. 110, 111 e 112". */
export function numeriBottiglie(numeri: number[]): string {
  if (numeri.length === 0) {
    return '';
  }
  const ultimi = numeri.slice(-1);
  const primi = numeri.slice(0, -1);
  return `n. ${primi.length ? `${primi.join(', ')} e ` : ''}${ultimi[0]}`;
}

/** Le carte di prova di Stripe, con le parole per sceglierle. */
const CARTE: Record<string, { nome: string; aiuto: string }> = {
  pm_card_visa: {
    nome: 'Visa di prova',
    aiuto: 'Il pagamento va a buon fine: la prenotazione si conferma e le bottiglie prendono il loro numero.',
  },
  pm_card_chargeDeclined: {
    nome: 'Carta rifiutata',
    aiuto: 'La banca rifiuta l’addebito: la prenotazione si annulla e le bottiglie tornano disponibili.',
  },
};

export function carta(metodo: string): { nome: string; aiuto: string } {
  return CARTE[metodo] ?? { nome: metodo, aiuto: 'Carta di prova di Stripe.' };
}

/**
 * A che punto è una prenotazione, per decidere cosa mostrare:
 * - DA_PAGARE: si sceglie la carta;
 * - IN_PAGAMENTO: il pagamento è partito, l'esito arriva tra poco;
 * - TEMPO_FINITO: in attesa ma oltre la scadenza (Edizioni la annulla entro 30 secondi);
 * - CONFERMATA, ANNULLATA: com'è finita.
 */
export type Fase = 'DA_PAGARE' | 'IN_PAGAMENTO' | 'TEMPO_FINITO' | 'CONFERMATA' | 'ANNULLATA';

export function fase(prenotazione: Prenotazione, adesso: Date = new Date()): Fase {
  if (prenotazione.stato !== 'IN_ATTESA_PAGAMENTO') {
    return prenotazione.stato;
  }
  if (prenotazione.pagamento) {
    return 'IN_PAGAMENTO';
  }
  return new Date(prenotazione.scadeIl) <= adesso ? 'TEMPO_FINITO' : 'DA_PAGARE';
}

/** Quante bottiglie può ancora prenotare chi guarda, e da quale quota (la stessa regola di Edizione.prenota). */
export interface Possibilita {
  canale: Canale;
  prezzo: number;
  /** Il massimo per persona nel canale (2 in prelazione, 6 online). */
  massimo: number;
  /** Le sue prenotazioni non annullate nello stesso canale. */
  giaPrenotate: number;
  /** Le bottiglie libere nella quota. */
  libere: number;
  /** Quante ne può prenotare adesso: 0 = niente modulo, e motivo dice perché. */
  ancora: number;
  motivo: 'ESAURITA' | 'LIMITE' | null;
}

export function possibilita(
  edizione: Pick<Edizione, 'id' | 'stato' | 'quote' | 'prenotate'>,
  regole: RegoleDiVendita,
  mie: Prenotazione[],
): Possibilita {
  const prelazione = edizione.stato === 'IN_PRELAZIONE';
  const canale: Canale = prelazione ? 'SOCI' : 'ONLINE';
  const massimo = prelazione ? regole.bottigliePerSocio : regole.bottigliePerCliente;
  const giaPrenotate = mie
    .filter((p) => p.edizione.id === edizione.id && p.canale === canale && p.stato !== 'ANNULLATA')
    .reduce((somma, p) => somma + p.bottiglie, 0);
  const libere = Math.max(0, edizione.quote[canale] - edizione.prenotate[canale]);
  const ancora = Math.max(0, Math.min(massimo - giaPrenotate, libere));
  let motivo: Possibilita['motivo'] = null;
  if (ancora === 0) {
    motivo = giaPrenotate >= massimo ? 'LIMITE' : 'ESAURITA';
  }
  return { canale, prezzo: prelazione ? regole.prezzoSoci : regole.prezzoOnline, massimo, giaPrenotate, libere, ancora, motivo };
}
