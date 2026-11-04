import { Controller, Get } from '@nestjs/common';
import { Ruoli, UtenteCorrente } from '../auth/decoratori';
import type { UtenteAutenticato } from '../auth/utente-autenticato';

/**
 * Endpoint DI PROVA della sicurezza (verranno sostituiti dalla logica di notifica).
 * Dal gateway: /api/notifiche/me e /api/notifiche/prova
 */
@Controller()
export class ProvaController {
  /** Qualsiasi utente autenticato: mostra cosa il servizio legge dal JWT. */
  @Get('me')
  me(@UtenteCorrente() utente: UtenteAutenticato) {
    return {
      servizio: 'notifiche',
      sub: utente.sub,
      nome: utente.nome ?? null,
      ruoli: [...utente.ruoli].sort(),
    };
  }

  /** Solo ADMIN. */
  @Get('prova')
  @Ruoli('ADMIN')
  prova() {
    return { servizio: 'notifiche', esito: 'accesso consentito al ruolo ADMIN' };
  }
}
