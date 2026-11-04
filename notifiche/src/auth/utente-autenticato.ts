import type { Request } from 'express';

/** L'utente come lo descrive il JWT del gateway (claim sub, name, roles). */
export interface UtenteAutenticato {
  sub: string;
  nome?: string;
  ruoli: string[];
}

/** Richiesta HTTP dopo il passaggio di JwtAuthGuard. */
export type RichiestaAutenticata = Request & { utente?: UtenteAutenticato };
