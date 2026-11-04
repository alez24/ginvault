import { CanActivate, ExecutionContext, ForbiddenException, Injectable } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { CHIAVE_RUOLI } from './decoratori';
import { RichiestaAutenticata } from './utente-autenticato';

/**
 * Guard GLOBALE eseguito dopo JwtAuthGuard: se l'endpoint ha @Ruoli(...),
 * l'utente deve avere almeno uno di quei ruoli, altrimenti 403.
 * È l'equivalente di @PreAuthorize("hasRole(...)") nei servizi Spring.
 */
@Injectable()
export class RuoliGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(contesto: ExecutionContext): boolean {
    const richiesti = this.reflector.getAllAndOverride<string[] | undefined>(CHIAVE_RUOLI, [
      contesto.getHandler(),
      contesto.getClass(),
    ]);
    if (!richiesti || richiesti.length === 0) {
      return true;
    }
    const utente = contesto.switchToHttp().getRequest<RichiestaAutenticata>().utente;
    if (utente && richiesti.some((ruolo) => utente.ruoli.includes(ruolo))) {
      return true;
    }
    throw new ForbiddenException('Ruolo insufficiente');
  }
}
