/** I ruoli di GinVault (docs/piano.md, sezione Security). */
export type Ruolo = 'SOCIO' | 'CLIENTE' | 'DISTILLATORE' | 'RESP_ACQUISTI' | 'ADMIN';

/** Risposta di GET /auth/me: l'utente come lo vede il gateway (record UtenteGinVault). */
export interface Utente {
  /** "provider:id", es. github:1234567 */
  sub: string;
  nome: string | null;
  /** Può mancare: GitHub con email privata */
  email: string | null;
  ruoli: string[];
}
