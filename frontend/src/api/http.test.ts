import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { api, ErroreApi, tokenCsrf } from './http';

/** Una fetch finta che registra le richieste e risponde con le risposte date, in ordine. */
function fetchFinta(...risposte: Response[]) {
  const finta = vi.fn(async (_percorso: string, _opzioni?: RequestInit) => {
    const risposta = risposte.shift();
    if (!risposta) {
      throw new Error('nessuna risposta preparata');
    }
    return risposta;
  });
  vi.stubGlobal('fetch', finta);
  return finta;
}

function json(stato: number, corpo: unknown, tipo = 'application/json') {
  return new Response(JSON.stringify(corpo), { status: stato, headers: { 'Content-Type': tipo } });
}

function headerDi(chiamata: unknown[]): Record<string, string> {
  return ((chiamata[1] as RequestInit | undefined)?.headers ?? {}) as Record<string, string>;
}

beforeEach(() => {
  vi.stubGlobal('document', { cookie: 'SESSION=non-leggibile-davvero; XSRF-TOKEN=abc-123' });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('tokenCsrf', () => {
  it('legge il cookie XSRF-TOKEN', () => {
    expect(tokenCsrf('a=1; XSRF-TOKEN=abc-123; b=2')).toBe('abc-123');
  });

  it('non lo confonde con cookie dal nome simile e restituisce undefined se manca', () => {
    expect(tokenCsrf('MY-XSRF-TOKEN=falso')).toBeUndefined();
    expect(tokenCsrf('')).toBeUndefined();
  });
});

describe('api', () => {
  it('in GET non manda il token CSRF', async () => {
    const finta = fetchFinta(json(200, { ok: true }));

    await expect(api('GET', '/auth/me')).resolves.toEqual({ ok: true });

    expect(headerDi(finta.mock.calls[0])).not.toHaveProperty('X-XSRF-TOKEN');
  });

  it('in POST rimanda nell’header il valore del cookie XSRF-TOKEN, con il corpo in JSON', async () => {
    const finta = fetchFinta(json(201, { id: 'f1' }));

    await api('POST', '/api/ricette/formulazioni', { nome: 'Gin alla lavanda' });

    const [percorso, opzioni] = finta.mock.calls[0];
    expect(percorso).toBe('/api/ricette/formulazioni');
    expect(opzioni?.method).toBe('POST');
    expect(opzioni?.body).toBe('{"nome":"Gin alla lavanda"}');
    expect(headerDi(finta.mock.calls[0])).toMatchObject({
      'X-XSRF-TOKEN': 'abc-123',
      'Content-Type': 'application/json',
    });
  });

  it('se il cookie manca, prima lo fa arrivare con una GET', async () => {
    const documento = { cookie: '' };
    vi.stubGlobal('document', documento);
    const finta = vi.fn(async (percorso: string) => {
      if (percorso === '/auth/me') {
        documento.cookie = 'XSRF-TOKEN=nuovo'; // come farebbe il browser con Set-Cookie
        return json(401, {});
      }
      return json(200, {});
    });
    vi.stubGlobal('fetch', finta);

    await api('POST', '/logout-di-prova', {});

    expect(finta.mock.calls.map((chiamata) => chiamata[0])).toEqual(['/auth/me', '/logout-di-prova']);
    expect(headerDi(finta.mock.calls[1])).toMatchObject({ 'X-XSRF-TOKEN': 'nuovo' });
  });

  it('un Problem Detail del servizio diventa un ErroreApi con i suoi testi', async () => {
    fetchFinta(
      json(
        409,
        { title: 'Formulazione già usata', status: 409, detail: 'È già usata da un’altra edizione.' },
        'application/problem+json',
      ),
    );

    const errore = await api('POST', '/api/edizioni/edizioni', {}).catch((e: unknown) => e);

    expect(errore).toBeInstanceOf(ErroreApi);
    expect(errore).toMatchObject({
      stato: 409,
      titolo: 'Formulazione già usata',
      dettaglio: 'È già usata da un’altra edizione.',
    });
  });

  it('senza Problem Detail usa testi in italiano (es. 403 del CSRF, 502 del proxy)', async () => {
    fetchFinta(
      new Response('An expected CSRF token cannot be found', { status: 403 }),
      new Response('', { status: 502 }),
    );

    await expect(api('POST', '/api/x', {})).rejects.toMatchObject({ stato: 403, titolo: 'Operazione non consentita' });
    await expect(api('GET', '/api/x')).rejects.toMatchObject({ stato: 502, titolo: 'Servizio non raggiungibile' });
  });

  it('se il gateway non risponde l’errore ha stato 0', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    );

    await expect(api('GET', '/auth/me')).rejects.toMatchObject({ stato: 0, titolo: 'Servizio non raggiungibile' });
  });
});
