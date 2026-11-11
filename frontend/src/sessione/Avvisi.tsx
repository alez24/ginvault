import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

import {
  attesaRicollegamento,
  AVVISI_VUOTI,
  conElenco,
  conLetta,
  conNotifica,
  conTutteLette,
  indirizzoAvvisi,
  leggiMessaggio,
  notifiche,
  type Notifica,
  type StatoAvvisi,
} from '../api/notifiche';

/** collegato = il WebSocket è aperto e gli avvisi arrivano in diretta. */
export type Collegamento = 'in collegamento' | 'collegato' | 'scollegato';

interface ValoreAvvisi extends StatoAvvisi {
  collegamento: Collegamento;
  segnaLetta: (id: string) => void;
  segnaTutteLette: () => void;
  /** Chiamata a ogni notifica in diretta (per rileggere i dati della pagina); restituisce come smettere. */
  alNuovoAvviso: (ascolta: (notifica: Notifica) => void) => () => void;
}

const Contesto = createContext<ValoreAvvisi | null>(null);

/**
 * Gli avvisi del club per le pagine che contiene (l'area soci): li legge con
 * GET /notifiche e tiene aperto il WebSocket di Notifiche.
 *
 * - "connesso" → rilegge l'elenco: dentro ci sono anche le notifiche arrivate
 *   mentre il WebSocket era chiuso;
 * - "notifica" → la aggiunge (una sola volta per id) e avvisa chi ascolta;
 * - chiusura → riapre secondo attesaRicollegamento (subito dopo un 4001, il
 *   JWT scaduto; mai dopo un 4401).
 */
export function AvvisiInDiretta({ children }: { children: ReactNode }) {
  const [stato, setStato] = useState<StatoAvvisi>(AVVISI_VUOTI);
  const [collegamento, setCollegamento] = useState<Collegamento>('in collegamento');
  const ascoltatori = useRef(new Set<(notifica: Notifica) => void>());

  useEffect(() => {
    let socket: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let tentativo = 0;
    let finito = false;

    const rileggi = () =>
      notifiche.elenco().then(
        (elenco) => {
          if (!finito) setStato((attuale) => conElenco(attuale, elenco));
        },
        () => undefined, // l'elenco resta quello di prima; si riprova al prossimo "connesso"
      );

    const apri = () => {
      if (finito) return;
      setCollegamento('in collegamento');
      try {
        socket = new WebSocket(indirizzoAvvisi());
      } catch {
        setCollegamento('scollegato');
        return;
      }
      socket.onmessage = (evento) => {
        const messaggio = leggiMessaggio(String(evento.data));
        if (messaggio?.tipo === 'connesso') {
          tentativo = 0;
          setCollegamento('collegato');
          void rileggi();
        } else if (messaggio?.tipo === 'notifica') {
          setStato((attuale) => conNotifica(attuale, messaggio.notifica));
          ascoltatori.current.forEach((ascolta) => ascolta(messaggio.notifica));
        }
      };
      socket.onclose = (evento) => {
        socket = null;
        if (finito) return;
        setCollegamento('scollegato');
        const attesa = attesaRicollegamento(evento.code, tentativo++);
        if (attesa !== null) {
          timer = setTimeout(apri, attesa);
        }
      };
    };

    void rileggi(); // anche se il WebSocket non si apre, l'elenco c'è
    apri();
    return () => {
      finito = true;
      clearTimeout(timer);
      socket?.close(1000, 'Pagina chiusa');
    };
  }, []);

  const segnaLetta = useCallback((id: string) => {
    setStato((attuale) => conLetta(attuale, id, new Date().toISOString()));
    void notifiche.letta(id).catch(() => undefined);
  }, []);

  const segnaTutteLette = useCallback(() => {
    setStato((attuale) => conTutteLette(attuale, new Date().toISOString()));
    void notifiche.tutteLette().catch(() => undefined);
  }, []);

  const alNuovoAvviso = useCallback((ascolta: (notifica: Notifica) => void) => {
    ascoltatori.current.add(ascolta);
    return () => {
      ascoltatori.current.delete(ascolta);
    };
  }, []);

  const valore = useMemo(
    () => ({ ...stato, collegamento, segnaLetta, segnaTutteLette, alNuovoAvviso }),
    [stato, collegamento, segnaLetta, segnaTutteLette, alNuovoAvviso],
  );
  return <Contesto.Provider value={valore}>{children}</Contesto.Provider>;
}

export function useAvvisi(): ValoreAvvisi {
  const valore = useContext(Contesto);
  if (!valore) {
    throw new Error('useAvvisi va usato dentro <AvvisiInDiretta>');
  }
  return valore;
}
