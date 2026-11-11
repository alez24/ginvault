import {
  Controller,
  Get,
  HttpCode,
  NotFoundException,
  Param,
  ParseUUIDPipe,
  Post,
} from '@nestjs/common';
import { UtenteCorrente } from '../auth/decoratori';
import type { UtenteAutenticato } from '../auth/utente-autenticato';
import { NotificheRepository } from './notifiche.repository';

/** Quante notifiche restituisce GET /notifiche, dalla più recente. */
const ULTIME = 50;

/**
 * Le notifiche di chi chiede (il "sub" del JWT), dal browser
 * /api/notifiche/notifiche. Ogni utente vede solo le sue: basta essere
 * autenticati, nessun ruolo.
 */
@Controller('notifiche')
export class NotificheController {
  constructor(private readonly notifiche: NotificheRepository) {}

  /** GET /notifiche → le ultime 50 e quante non sono ancora lette (anche più vecchie). */
  @Get()
  async elenco(@UtenteCorrente() utente: UtenteAutenticato) {
    const [nonLette, notifiche] = await Promise.all([
      this.notifiche.nonLette(utente.sub),
      this.notifiche.elenco(utente.sub, ULTIME),
    ]);
    return { nonLette, notifiche };
  }

  /** POST /notifiche/lette → tutte lette (204). */
  @Post('lette')
  @HttpCode(204)
  async tutteLette(@UtenteCorrente() utente: UtenteAutenticato): Promise<void> {
    await this.notifiche.segnaTutteLette(utente.sub, new Date());
  }

  /** POST /notifiche/{id}/letta → 204; 404 se non c'è o è di un altro. */
  @Post(':id/letta')
  @HttpCode(204)
  async letta(
    @Param('id', new ParseUUIDPipe()) id: string,
    @UtenteCorrente() utente: UtenteAutenticato,
  ): Promise<void> {
    if (!(await this.notifiche.segnaLetta(id, utente.sub, new Date()))) {
      throw new NotFoundException('Notifica non trovata');
    }
  }
}
