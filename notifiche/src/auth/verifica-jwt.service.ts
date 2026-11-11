import { Inject, Injectable } from '@nestjs/common';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { CONFIGURAZIONE, type Configurazione } from '../configurazione';
import type { UtenteAutenticato } from './utente-autenticato';

/**
 * Verifica il JWT emesso dal gateway, con le stesse regole dei servizi Spring:
 *  - firma RS256 controllata con le chiavi PUBBLICHE del gateway (JWKS);
 *  - issuer = "ginvault-gateway";
 *  - token scaduto o senza "exp"/"sub" -> rifiutato.
 *
 * È separato dal guard HTTP perché serve anche ai WebSocket: l'handshake di
 * una connessione WebSocket arriva dal gateway con lo stesso header
 * "Authorization: Bearer ...", e AvvisiGateway lo verifica con questo servizio.
 */
@Injectable()
export class VerificaJwtService {
  // createRemoteJWKSet scarica la JWKS alla prima verifica, la tiene in cache e
  // la riscarica se arriva un token con un "kid" sconosciuto (gateway riavviato).
  private readonly chiaviGateway: ReturnType<typeof createRemoteJWKSet>;
  private readonly issuer: string;

  constructor(@Inject(CONFIGURAZIONE) configurazione: Configurazione) {
    this.chiaviGateway = createRemoteJWKSet(new URL(configurazione.jwt.jwksUri));
    this.issuer = configurazione.jwt.issuer;
  }

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
      scadeIl: new Date((payload.exp as number) * 1000),
    };
  }
}

/** Estrae il token da un header "Authorization: Bearer <token>" (HTTP o handshake WebSocket). */
export function estraiBearer(header: string | undefined): string | undefined {
  const [schema, token] = header?.split(' ') ?? [];
  return schema?.toLowerCase() === 'bearer' && token ? token : undefined;
}
