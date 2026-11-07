/*
 * Chiamate al gateway (pattern BFF, Backend For Frontend).
 *
 * Il front-end NON gestisce token: il browser manda da solo il cookie di
 * sessione SESSION (HttpOnly, JavaScript non lo vede) e il gateway aggiunge il
 * JWT verso i servizi. L'unica cosa che fa il front-end è la protezione CSRF:
 * nelle richieste che modificano (POST, PUT, DELETE) rimanda nell'header
 * X-XSRF-TOKEN il valore del cookie XSRF-TOKEN. Un sito malevolo può far partire
 * una POST con i cookie dell'utente, ma non può leggere il cookie per copiarlo.
 *
 * È la funzione api() di docs/demo-storia-marco.md, con i tipi e gli errori.
 */

export type Metodo = 'GET' | 'POST' | 'PUT' | 'DELETE';

/** Corpo di errore dei servizi Spring (Problem Details, RFC 9457). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  [campo: string]: unknown;
}

/** Errore di una chiamata: stato HTTP (0 = gateway non raggiungibile) e testi da mostrare. */
export class ErroreApi extends Error {
  readonly stato: number;
  readonly titolo: string;
  readonly dettaglio: string | undefined;
  readonly problema: ProblemDetail | undefined;

  constructor(stato: number, titolo: string, dettaglio?: string, problema?: ProblemDetail) {
    super(dettaglio ? `${titolo}: ${dettaglio}` : titolo);
    this.name = 'ErroreApi';
    this.stato = stato;
    this.titolo = titolo;
    this.dettaglio = dettaglio;
    this.problema = problema;
  }
}

const COOKIE_CSRF = 'XSRF-TOKEN';
const HEADER_CSRF = 'X-XSRF-TOKEN';

/** Valore del cookie XSRF-TOKEN, se il gateway l'ha già mandato. */
export function tokenCsrf(cookie: string = document.cookie): string | undefined {
  for (const parte of cookie.split(';')) {
    const [nome, ...valore] = parte.trim().split('=');
    if (nome === COOKIE_CSRF) {
      return decodeURIComponent(valore.join('='));
    }
  }
  return undefined;
}

/**
 * Header CSRF per una richiesta che modifica. Se il cookie non c'è ancora,
 * una GET qualsiasi delle API lo fa arrivare (il gateway lo scrive a ogni risposta).
 */
export async function headerCsrf(): Promise<Record<string, string>> {
  if (!tokenCsrf()) {
    await fetch('/auth/me', { headers: { Accept: 'application/json' } }).catch(() => undefined);
  }
  return { [HEADER_CSRF]: tokenCsrf() ?? '' };
}

/**
 * Chiama il gateway e restituisce il corpo JSON della risposta.
 * Le risposte 4xx/5xx diventano un ErroreApi con titolo e dettaglio in italiano
 * (quelli dei servizi quando ci sono, altrimenti testi generici).
 */
export async function api<T = unknown>(metodo: Metodo, percorso: string, corpo?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (corpo !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (metodo !== 'GET') {
    Object.assign(headers, await headerCsrf());
  }

  let risposta: Response;
  try {
    risposta = await fetch(percorso, {
      method: metodo,
      headers,
      body: corpo === undefined ? undefined : JSON.stringify(corpo),
    });
  } catch {
    throw new ErroreApi(0, TITOLI[0], DETTAGLI[0]);
  }

  const dati = await leggiCorpo(risposta);
  if (!risposta.ok) {
    throw erroreDa(risposta.status, dati);
  }
  return dati as T;
}

async function leggiCorpo(risposta: Response): Promise<unknown> {
  if (risposta.status === 204) {
    return undefined;
  }
  const testo = await risposta.text();
  if (!testo) {
    return undefined;
  }
  if ((risposta.headers.get('Content-Type') ?? '').includes('json')) {
    try {
      return JSON.parse(testo);
    } catch {
      return testo;
    }
  }
  return testo;
}

function erroreDa(stato: number, dati: unknown): ErroreApi {
  const problema = typeof dati === 'object' && dati !== null ? (dati as ProblemDetail) : undefined;
  const titolo = problema?.title ?? TITOLI[stato] ?? (stato >= 500 ? TITOLI[500] : `Errore ${stato}`);
  const dettaglio = problema?.detail ?? DETTAGLI[stato] ?? (stato >= 500 ? DETTAGLI[500] : undefined);
  return new ErroreApi(stato, titolo, dettaglio, problema);
}

/** Testi usati quando la risposta non porta un Problem Detail (es. 403 del CSRF, 502 del proxy). */
const TITOLI: Record<number, string> = {
  0: 'Servizio non raggiungibile',
  401: 'Accesso richiesto',
  403: 'Operazione non consentita',
  404: 'Non trovato',
  500: 'Errore del servizio',
  502: 'Servizio non raggiungibile',
  503: 'Servizio non disponibile',
  504: 'Servizio troppo lento',
};

const DETTAGLI: Record<number, string> = {
  0: 'GinVault non risponde. Controlla la connessione e riprova tra poco.',
  401: 'La sessione è scaduta oppure non hai ancora fatto l’accesso.',
  403: 'Il tuo account non ha il ruolo necessario, oppure la pagina è aperta da troppo tempo: ricaricala e riprova.',
  500: 'Qualcosa non ha funzionato dalla nostra parte. Riprova tra poco.',
  502: 'GinVault non risponde. Riprova tra poco.',
  503: 'Il servizio è momentaneamente fermo. Riprova tra poco.',
  504: 'Il servizio non ha risposto in tempo. Riprova tra poco.',
};
