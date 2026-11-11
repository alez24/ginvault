/*
 * Notifiche: gli avvisi del club (dal gateway /api/notifiche).
 *
 * - REST: l'elenco (le ultime 50 e quante non sono lette), letta, tutte lette.
 * - WebSocket /api/notifiche/avvisi: le notifiche nuove in diretta. Il browser
 *   lo apre con il cookie di sessione e il gateway aggiunge il JWT, come per le
 *   API. Il protocollo è in docs/eventi.md, "Gli avvisi in diretta".
 *
 * Qui ci sono le chiamate e le regole, come funzioni pure (si provano senza
 * browser); il WebSocket vero lo apre AvvisiInDiretta (src/sessione/Avvisi.tsx).
 */
import { api } from './http';

export type TipoNotifica =
  | 'PRENOTAZIONE_CONFERMATA'
  | 'PRENOTAZIONE_ANNULLATA'
  | 'TIRATURA_RIDOTTA'
  | 'TIRATURA_AUMENTATA'
  | 'RIMBORSO_ESEGUITO';

/** NotificaVista di Notifiche. */
export interface Notifica {
  id: string;
  tipo: TipoNotifica;
  titolo: string;
  testo: string;
  edizioneId: string | null;
  prenotazioneId: string | null;
  /** Numeri di bottiglia, tirature, causa... a seconda del tipo. */
  dati: Record<string, unknown>;
  /** Quando è successo il fatto (l'occorsoIl dell'evento). */
  creataIl: string;
  lettaIl: string | null;
}

export interface ElencoNotifiche {
  nonLette: number;
  notifiche: Notifica[];
}

const BASE = '/api/notifiche/notifiche';

export const notifiche = {
  elenco: () => api<ElencoNotifiche>('GET', BASE),
  letta: (id: string) => api<void>('POST', `${BASE}/${encodeURIComponent(id)}/letta`),
  tutteLette: () => api<void>('POST', `${BASE}/lette`),
};

// ---- il WebSocket ---------------------------------------------------------------

export type MessaggioAvvisi =
  | { tipo: 'connesso'; nonLette: number }
  | { tipo: 'notifica'; notifica: Notifica }
  | { tipo: 'ping' };

/** Codici di chiusura di Notifiche (AvvisiGateway). */
export const CHIUSURA_NON_AUTENTICATO = 4401;
export const CHIUSURA_TOKEN_SCADUTO = 4001;

/** ws://localhost/api/notifiche/avvisi, o wss:// se la pagina è in https. */
export function indirizzoAvvisi(posizione: Pick<Location, 'protocol' | 'host'> = window.location): string {
  const protocollo = posizione.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocollo}//${posizione.host}/api/notifiche/avvisi`;
}

/** Un messaggio del server, o null se non si capisce (lettore tollerante: si ignora). */
export function leggiMessaggio(testo: string): MessaggioAvvisi | null {
  let dati: unknown;
  try {
    dati = JSON.parse(testo);
  } catch {
    return null;
  }
  if (typeof dati !== 'object' || dati === null) {
    return null;
  }
  const messaggio = dati as { tipo?: unknown; nonLette?: unknown; notifica?: unknown };
  if (messaggio.tipo === 'connesso' && typeof messaggio.nonLette === 'number') {
    return { tipo: 'connesso', nonLette: messaggio.nonLette };
  }
  if (messaggio.tipo === 'notifica' && typeof messaggio.notifica === 'object' && messaggio.notifica !== null) {
    const notifica = messaggio.notifica as Partial<Notifica>;
    if (typeof notifica.id === 'string' && typeof notifica.titolo === 'string' && typeof notifica.testo === 'string') {
      return { tipo: 'notifica', notifica: notifica as Notifica };
    }
    return null;
  }
  if (messaggio.tipo === 'ping') {
    return { tipo: 'ping' };
  }
  return null;
}

/**
 * Dopo quanti millisecondi riaprire il WebSocket chiuso con questo codice;
 * null = non riaprirlo.
 * - 4401: il JWT non c'è o non vale (sessione finita): si smette;
 * - 4001: il JWT è scaduto (dura 5 minuti): subito, il gateway ne dà uno nuovo;
 * - altro (rete, Notifiche riavviato, 1001): 1, 2, 5, 10 secondi, poi ogni 30.
 */
export function attesaRicollegamento(codice: number, tentativo: number): number | null {
  if (codice === CHIUSURA_NON_AUTENTICATO) {
    return null;
  }
  if (codice === CHIUSURA_TOKEN_SCADUTO) {
    return 0;
  }
  const attese = [1_000, 2_000, 5_000, 10_000, 30_000];
  return attese[Math.min(tentativo, attese.length - 1)];
}

// ---- lo stato degli avvisi nel browser --------------------------------------------

export interface StatoAvvisi {
  /** Dalla più recente. */
  notifiche: Notifica[];
  nonLette: number;
}

export const AVVISI_VUOTI: StatoAvvisi = { notifiche: [], nonLette: 0 };

function ordinate(elenco: Notifica[]): Notifica[] {
  return [...elenco].sort((a, b) => b.creataIl.localeCompare(a.creataIl) || a.id.localeCompare(b.id));
}

/**
 * L'elenco appena riletto con GET /notifiche (dopo ogni "connesso"). Vince
 * l'elenco del server; le notifiche arrivate in diretta che non ci sono (sono
 * nate dopo la lettura) restano, e contano tra le non lette.
 */
export function conElenco(stato: StatoAvvisi, elenco: ElencoNotifiche): StatoAvvisi {
  const nelServer = new Set(elenco.notifiche.map((n) => n.id));
  const soloQui = stato.notifiche.filter((n) => !nelServer.has(n.id));
  return {
    notifiche: ordinate([...elenco.notifiche, ...soloQui]),
    nonLette: elenco.nonLette + soloQui.filter((n) => !n.lettaIl).length,
  };
}

/** Una notifica arrivata in diretta; se c'è già (stesso id) non cambia niente. */
export function conNotifica(stato: StatoAvvisi, notifica: Notifica): StatoAvvisi {
  if (stato.notifiche.some((n) => n.id === notifica.id)) {
    return stato;
  }
  return {
    notifiche: ordinate([notifica, ...stato.notifiche]),
    nonLette: stato.nonLette + (notifica.lettaIl ? 0 : 1),
  };
}

export function conLetta(stato: StatoAvvisi, id: string, adesso: string): StatoAvvisi {
  const notifica = stato.notifiche.find((n) => n.id === id);
  if (!notifica || notifica.lettaIl) {
    return stato;
  }
  return {
    notifiche: stato.notifiche.map((n) => (n.id === id ? { ...n, lettaIl: adesso } : n)),
    nonLette: Math.max(0, stato.nonLette - 1),
  };
}

export function conTutteLette(stato: StatoAvvisi, adesso: string): StatoAvvisi {
  return { notifiche: stato.notifiche.map((n) => (n.lettaIl ? n : { ...n, lettaIl: adesso })), nonLette: 0 };
}

/** Che aspetto dare all'avviso: la tiratura ridotta e gli annullamenti si notano di più. */
export function tonoAvviso(tipo: TipoNotifica): 'informazione' | 'attenzione' {
  return tipo === 'TIRATURA_RIDOTTA' || tipo === 'PRENOTAZIONE_ANNULLATA' ? 'attenzione' : 'informazione';
}
