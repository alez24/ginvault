/*
 * Login e logout. Li fa il gateway: il front-end porta il browser sulle sue
 * pagine (login) o gli manda una POST (logout), e poi rilegge /auth/me.
 */
import { ErroreApi, headerCsrf } from '../api/http';

export const PROVIDER = [
  { id: 'google', nome: 'Google' },
  { id: 'github', nome: 'GitHub' },
] as const;

export type IdProvider = (typeof PROVIDER)[number]['id'];

/**
 * Inizio del login OAuth2 (Authorization Code): è una NAVIGAZIONE, non una fetch.
 * Gateway -> Google/GitHub -> gateway -> home del front-end (ginvault.frontend.url).
 */
export function indirizzoDiAccesso(provider: IdProvider): string {
  return `/oauth2/authorization/${provider}`;
}

/** Nome leggibile del provider, ricavato dal "sub" (es. github:42 -> GitHub). */
export function providerDi(sub: string): string {
  const id = sub.split(':')[0];
  return PROVIDER.find((provider) => provider.id === id)?.nome ?? id;
}

/*
 * Dopo il login il gateway riporta sempre alla home. La pagina che l'utente
 * voleva aprire la ricordiamo noi, per la durata della scheda del browser.
 * sessionStorage può non esserci (navigazione privata, cookie bloccati):
 * in quel caso si torna semplicemente alla home.
 */
const CHIAVE_RITORNO = 'ginvault.dopoAccesso';

export function ricordaPaginaDopoAccesso(percorso: string): void {
  try {
    sessionStorage.setItem(CHIAVE_RITORNO, percorso);
  } catch {
    // nessun problema: dopo il login si resta sulla home
  }
}

/** La pagina da riaprire dopo il login (una volta sola), solo se interna a GinVault. */
export function prendiPaginaDopoAccesso(): string | null {
  try {
    const percorso = sessionStorage.getItem(CHIAVE_RITORNO);
    sessionStorage.removeItem(CHIAVE_RITORNO);
    return percorso && percorso.startsWith('/') && !percorso.startsWith('//') ? percorso : null;
  } catch {
    return null;
  }
}

/**
 * Logout: POST /logout con l'header CSRF (senza, il gateway risponde 403).
 * Il gateway risponde con un redirect alla home: non lo seguiamo
 * (redirect: 'manual'), basta sapere che il logout è andato a buon fine.
 */
export async function esci(): Promise<void> {
  let risposta: Response;
  try {
    risposta = await fetch('/logout', { method: 'POST', headers: await headerCsrf(), redirect: 'manual' });
  } catch {
    throw new ErroreApi(0, 'Uscita non riuscita', 'GinVault non risponde. Riprova tra poco.');
  }
  if (risposta.type !== 'opaqueredirect' && !risposta.ok) {
    throw new ErroreApi(risposta.status, 'Uscita non riuscita', 'Ricarica la pagina e riprova.');
  }
}
