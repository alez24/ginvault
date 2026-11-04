import { Injectable } from '@nestjs/common';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { UtenteAutenticato } from './utente-autenticato';

/**
 * Verifica il JWT emesso dal gateway, con le stesse regole dei servizi Spring:
 *  - firma RS256 controllata con le chiavi PUBBLICHE del gateway (JWKS);
 *  - issuer = "ginvault-gateway";
 *  - token scaduto o senza "exp"/"sub" -> rifiutato.
 *
 * È separato dal guard HTTP perché servirà anche ai WebSocket: l'handshake di
 * una connessione WebSocket arriva dal gateway con lo stesso header
 * "Authorization: Bearer ...", e il gateway WebSocket di notifiche lo
 * verificherà con questo stesso servizio.
 */
@Injectable()
export class VerificaJwtService {
  // createRemoteJWKSet scarica la JWKS alla prima verifica, la tiene in cache e
  // la riscarica se arriva un token con un "kid" sconosciuto (gateway riavviato).
  private readonly chiaviGateway = createRemoteJWKSet(
    new URL(process.env.JWKS_URI ?? 'http://localhost:8080/.well-known/jwks.json'),
  );
  private readonly issuer = process.env.JWT_ISSUER ?? 'ginvault-gateway';

  async verifica(token: string): Promise<UtenteAutenticato> {
    const { payload } = await jwtVerify(token, this.chiaviGateway, {
      issuer: this.issuer,
      algorithms: ['RS256'],
      requiredClaims: ['sub', 'exp'],
      clockTolerance: 60, // secondi, come la tolleranza predefinita di Spring
    });
    const ruoli = Array.isArray(payload.roles)
      ? payload.roles.filter((ruolo): ruolo is string => typeof ruolo === 'string')
      : [];
    return {
      sub: payload.sub as string,
      nome: typeof payload.name === 'string' ? payload.name : undefined,
      ruoli,
    };
  }
}

/** Estrae il token da un header "Authorization: Bearer <token>" (HTTP o handshake WebSocket). */
export function estraiBearer(header: string | undefined): string | undefined {
  const [schema, token] = header?.split(' ') ?? [];
  return schema?.toLowerCase() === 'bearer' && token ? token : undefined;
}
