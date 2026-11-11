/*
 * Le pagine di GinVault e chi può aprirle. È l'unico punto in cui si decide:
 * lo usano sia il menu (Intestazione) sia le route protette (Protetta).
 *
 * Attenzione: questo nasconde solo ciò che l'utente non può usare. La sicurezza
 * vera è nei servizi (@PreAuthorize sui ruoli del JWT): una chiamata fatta "a
 * mano" senza il ruolo giusto riceve comunque 403.
 */
import type { Ruolo, Utente } from './sessione/tipi';

export interface Pagina {
  percorso: string;
  etichetta: string;
  /**
   * undefined = pubblica; [] = qualsiasi utente che ha fatto l'accesso;
   * altrimenti basta UNO dei ruoli elencati.
   */
  ruoli?: Ruolo[];
}

export const PAGINE = {
  home: { percorso: '/', etichetta: 'La distilleria' },
  formulazioni: { percorso: '/distilleria/formulazioni', etichetta: 'Formulazioni', ruoli: ['DISTILLATORE'] },
  edizioni: { percorso: '/distilleria/edizioni', etichetta: 'Edizioni', ruoli: ['DISTILLATORE'] },
  piani: { percorso: '/distilleria/piani', etichetta: 'Produzione', ruoli: ['DISTILLATORE'] },
  acquisti: { percorso: '/acquisti', etichetta: 'Acquisti', ruoli: ['RESP_ACQUISTI'] },
  soci: { percorso: '/soci', etichetta: 'Area soci', ruoli: ['SOCIO'] },
  profilo: { percorso: '/profilo', etichetta: 'Il mio profilo', ruoli: [] },
} satisfies Record<string, Pagina>;

/** Ordine delle voci nel menu. */
const MENU: Pagina[] = [
  PAGINE.home,
  PAGINE.formulazioni,
  PAGINE.edizioni,
  PAGINE.piani,
  PAGINE.acquisti,
  PAGINE.soci,
  PAGINE.profilo,
];

export function accessibile(utente: Utente | null, pagina: Pagina): boolean {
  if (pagina.ruoli === undefined) {
    return true;
  }
  if (utente === null) {
    return false;
  }
  return pagina.ruoli.length === 0 || pagina.ruoli.some((ruolo) => utente.ruoli.includes(ruolo));
}

/** Le voci del menu per questo utente (null = non ha fatto l'accesso). */
export function vociDelMenu(utente: Utente | null): Pagina[] {
  return MENU.filter((pagina) => accessibile(utente, pagina));
}

/** Come presentare i ruoli: nome e cosa permettono (docs/piano.md, sezione Security). */
export const RUOLI: Record<Ruolo, { nome: string; descrizione: string }> = {
  SOCIO: {
    nome: 'Socio del club',
    descrizione: 'Prenota durante la prelazione (al massimo 2 bottiglie) e segue la tracciabilità delle sue bottiglie.',
  },
  CLIENTE: {
    nome: 'Cliente',
    descrizione: 'Prenota la quota online dopo la prelazione (al massimo 6 bottiglie).',
  },
  DISTILLATORE: {
    nome: 'Distillatore',
    descrizione: 'Registra formulazioni e prove d’assaggio, annuncia le edizioni e ne ricalcola la tiratura.',
  },
  RESP_ACQUISTI: {
    nome: 'Responsabile acquisti',
    descrizione: 'Ordina le botaniche ai fornitori, registra le consegne e segue lotti e fornitori.',
  },
  ADMIN: {
    nome: 'Amministratore',
    descrizione: 'Gestisce utenti e ruoli.',
  },
};

export function nomeDelRuolo(ruolo: string): string {
  return ruolo in RUOLI ? RUOLI[ruolo as Ruolo].nome : ruolo;
}

/** Elenco leggibile dei ruoli richiesti da una pagina: "Distillatore" o "Socio del club o Cliente". */
export function ruoliRichiesti(pagina: Pagina): string {
  return (pagina.ruoli ?? []).map(nomeDelRuolo).join(' o ');
}
