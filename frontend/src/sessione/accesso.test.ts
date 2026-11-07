import { afterEach, describe, expect, it, vi } from 'vitest';

import { indirizzoDiAccesso, prendiPaginaDopoAccesso, providerDi, ricordaPaginaDopoAccesso } from './accesso';

function sessionStorageFinto() {
  const valori = new Map<string, string>();
  vi.stubGlobal('sessionStorage', {
    getItem: (chiave: string) => valori.get(chiave) ?? null,
    setItem: (chiave: string, valore: string) => valori.set(chiave, valore),
    removeItem: (chiave: string) => valori.delete(chiave),
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('login', () => {
  it('parte dal gateway, che poi passa al provider', () => {
    expect(indirizzoDiAccesso('google')).toBe('/oauth2/authorization/google');
    expect(indirizzoDiAccesso('github')).toBe('/oauth2/authorization/github');
  });

  it('il provider si legge dal sub', () => {
    expect(providerDi('github:42')).toBe('GitHub');
    expect(providerDi('google:1098')).toBe('Google');
  });
});

describe('pagina da riaprire dopo il login', () => {
  it('si usa una volta sola', () => {
    sessionStorageFinto();
    ricordaPaginaDopoAccesso('/distilleria/formulazioni');

    expect(prendiPaginaDopoAccesso()).toBe('/distilleria/formulazioni');
    expect(prendiPaginaDopoAccesso()).toBeNull();
  });

  it('solo se è una pagina di GinVault', () => {
    sessionStorageFinto();
    for (const esterna of ['https://altro-sito.example', '//altro-sito.example']) {
      ricordaPaginaDopoAccesso(esterna);
      expect(prendiPaginaDopoAccesso()).toBeNull();
    }
  });

  it('senza sessionStorage (navigazione privata, cookie bloccati) si resta sulla home', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw new Error('bloccato');
      },
      setItem: () => {
        throw new Error('bloccato');
      },
      removeItem: () => {
        throw new Error('bloccato');
      },
    });

    expect(() => ricordaPaginaDopoAccesso('/profilo')).not.toThrow();
    expect(prendiPaginaDopoAccesso()).toBeNull();
  });
});
