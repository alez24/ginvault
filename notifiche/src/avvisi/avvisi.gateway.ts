import { BeforeApplicationShutdown, Inject, Logger } from '@nestjs/common';
import { type OnGatewayConnection, WebSocketGateway } from '@nestjs/websockets';
import type { IncomingMessage } from 'node:http';
import { WebSocket } from 'ws';
import type { UtenteAutenticato } from '../auth/utente-autenticato';
import { estraiBearer, VerificaJwtService } from '../auth/verifica-jwt.service';
import { CONFIGURAZIONE, type Configurazione } from '../configurazione';
import { NotificheRepository } from '../notifiche/notifiche.repository';
import { invia, RegistroConnessioni } from './registro-connessioni';

/** Codici di chiusura (4000-4999: liberi per le applicazioni). */
export const CHIUSURA_NON_AUTENTICATO = 4401;
export const CHIUSURA_TOKEN_SCADUTO = 4001;
/** Il setTimeout di Node accetta al più 2^31 - 1 ms (circa 24 giorni). */
const ATTESA_MASSIMA = 2 ** 31 - 1;

/**
 * Gli avvisi in diretta: ws://notifiche:8086/avvisi, dal browser
 * /api/notifiche/avvisi (il gateway toglie /api/notifiche, come per REST).
 *
 * - Autenticazione all'handshake. Un WebSocket del browser non può mettere
 *   header: arriva con il cookie di sessione, e il gateway aggiunge
 *   "Authorization: Bearer <JWT>" come per ogni richiesta. Il JWT si verifica
 *   con VerificaJwtService, come nel guard HTTP. Mancante o non valido: chiusura
 *   4401.
 * - Il JWT del gateway dura 5 minuti: a quella scadenza il socket si chiude con
 *   4001 e il front-end si ricollega con un JWT nuovo. Così un logout vale anche
 *   per i WebSocket già aperti, al più 5 minuti dopo.
 * - Il server parla, il client ascolta: i messaggi del client si ignorano
 *   (maxPayload 1 KiB). Ogni 25 secondi un {"tipo":"ping"}: nginx chiude una
 *   connessione che resta muta per 60 secondi (proxy_read_timeout).
 */
@WebSocketGateway({ path: '/avvisi', maxPayload: 1024 })
export class AvvisiGateway implements OnGatewayConnection, BeforeApplicationShutdown {
  private readonly log = new Logger(AvvisiGateway.name);

  constructor(
    @Inject(CONFIGURAZIONE) private readonly configurazione: Configurazione,
    private readonly verificaJwt: VerificaJwtService,
    private readonly registro: RegistroConnessioni,
    private readonly notifiche: NotificheRepository,
  ) {}

  async handleConnection(socket: WebSocket, richiesta: IncomingMessage): Promise<void> {
    let utente: UtenteAutenticato;
    try {
      const token = estraiBearer(richiesta.headers.authorization);
      if (!token) {
        throw new Error('JWT mancante');
      }
      utente = await this.verificaJwt.verifica(token);
    } catch (errore) {
      this.log.debug(`WebSocket rifiutato: ${String(errore)}`);
      socket.close(CHIUSURA_NON_AUTENTICATO, 'JWT mancante o non valido');
      return;
    }
    if (socket.readyState !== WebSocket.OPEN) {
      return; // il client se n'è andato mentre si verificava il JWT
    }

    const { sub } = utente;
    this.registro.aggiungi(sub, socket);
    const fraQuanto = Math.min(Math.max(utente.scadeIl.getTime() - Date.now(), 0), ATTESA_MASSIMA);
    const scadenza = setTimeout(
      () => socket.close(CHIUSURA_TOKEN_SCADUTO, 'JWT scaduto: ricollegati'),
      fraQuanto,
    );
    const keepalive = setInterval(
      () => invia(socket, { tipo: 'ping' }),
      this.configurazione.avvisi.keepaliveMs,
    );
    socket.on('close', () => {
      clearTimeout(scadenza);
      clearInterval(keepalive);
      this.registro.togli(sub, socket);
    });

    try {
      invia(socket, { tipo: 'connesso', nonLette: await this.notifiche.nonLette(sub) });
    } catch (errore) {
      this.log.warn(`Notifiche non lette di ${sub} non disponibili: ${String(errore)}`);
      socket.close(1011, 'Errore interno: riprova');
    }
  }

  /** All'arresto: 1001 ("going away") a tutti, che si ricollegano a un'altra replica. */
  beforeApplicationShutdown(): void {
    for (const socket of this.registro.tutte()) {
      socket.close(1001, 'Notifiche in arresto');
    }
  }
}
