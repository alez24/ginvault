import { createParamDecorator, ExecutionContext, SetMetadata } from '@nestjs/common';
import { RichiestaAutenticata } from './utente-autenticato';

export const CHIAVE_PUBBLICO = 'ginvault:pubblico';
export const CHIAVE_RUOLI = 'ginvault:ruoli';

/** Endpoint accessibile senza JWT (es. /health). Equivale a permitAll() di Spring. */
export const Pubblico = () => SetMetadata(CHIAVE_PUBBLICO, true);

/** Ruoli ammessi (basta uno). Equivale a @PreAuthorize("hasAnyRole(...)") di Spring. */
export const Ruoli = (...ruoli: string[]) => SetMetadata(CHIAVE_RUOLI, ruoli);

/** Inietta nel metodo del controller l'utente letto dal JWT. */
export const UtenteCorrente = createParamDecorator(
  (_: unknown, contesto: ExecutionContext) =>
    contesto.switchToHttp().getRequest<RichiestaAutenticata>().utente,
);
