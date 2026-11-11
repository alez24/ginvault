import { Injectable } from '@nestjs/common';
import { WebSocket } from 'ws';

/** I messaggi che Notifiche manda sul WebSocket (docs/eventi.md, "Gli avvisi in diretta"). */
export type MessaggioAvvisi =
  | { tipo: 'connesso'; nonLette: number }
  | { tipo: 'notifica'; notifica: unknown }
  | { tipo: 'ping' };

/**
 * I WebSocket aperti su QUESTA replica, per utente (il "sub" del JWT). Un
 * utente può averne più di uno: due schede del browser, il telefono.
 */
@Injectable()
export class RegistroConnessioni {
  private readonly perUtente = new Map<string, Set<WebSocket>>();

  aggiungi(sub: string, socket: WebSocket): void {
    const sockets = this.perUtente.get(sub) ?? new Set<WebSocket>();
    sockets.add(socket);
    this.perUtente.set(sub, sockets);
  }

  togli(sub: string, socket: WebSocket): void {
    const sockets = this.perUtente.get(sub);
    sockets?.delete(socket);
    if (sockets?.size === 0) {
      this.perUtente.delete(sub);
    }
  }

  collegato(sub: string): boolean {
    return this.perUtente.has(sub);
  }

  /** Manda il messaggio a tutti i WebSocket dell'utente; restituisce a quanti. */
  invia(sub: string, messaggio: MessaggioAvvisi): number {
    let inviati = 0;
    for (const socket of this.perUtente.get(sub) ?? []) {
      if (invia(socket, messaggio)) {
        inviati++;
      }
    }
    return inviati;
  }

  tutte(): WebSocket[] {
    return [...this.perUtente.values()].flatMap((sockets) => [...sockets]);
  }
}

export function invia(socket: WebSocket, messaggio: MessaggioAvvisi): boolean {
  if (socket.readyState !== WebSocket.OPEN) {
    return false;
  }
  socket.send(JSON.stringify(messaggio));
  return true;
}
