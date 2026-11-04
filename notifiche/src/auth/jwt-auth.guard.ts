import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { CHIAVE_PUBBLICO } from './decoratori';
import { RichiestaAutenticata } from './utente-autenticato';
import { estraiBearer, VerificaJwtService } from './verifica-jwt.service';

/**
 * Guard GLOBALE (registrato in AppModule): ogni endpoint richiede un JWT valido,
 * tranne quelli marcati @Pubblico(). Token assente o non valido -> 401.
 * È l'equivalente di oauth2ResourceServer().jwt() nei servizi Spring.
 */
@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly verificaJwt: VerificaJwtService,
  ) {}

  async canActivate(contesto: ExecutionContext): Promise<boolean> {
    const pubblico = this.reflector.getAllAndOverride<boolean>(CHIAVE_PUBBLICO, [
      contesto.getHandler(),
      contesto.getClass(),
    ]);
    if (pubblico) {
      return true;
    }
    const richiesta = contesto.switchToHttp().getRequest<RichiestaAutenticata>();
    const token = estraiBearer(richiesta.headers.authorization);
    if (!token) {
      throw new UnauthorizedException('JWT mancante');
    }
    try {
      richiesta.utente = await this.verificaJwt.verifica(token);
      return true;
    } catch {
      throw new UnauthorizedException('JWT non valido');
    }
  }
}
