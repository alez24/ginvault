/*
 * Chi è l'utente, letto da GET /auth/me all'avvio e dopo login/logout.
 * Il front-end non conserva nulla: la sessione vera è nel gateway.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { api, ErroreApi } from '../api/http';
import { esci as logout } from './accesso';
import type { Utente } from './tipi';

export type StatoSessione =
  | { tipo: 'caricamento' }
  | { tipo: 'anonimo' }
  | { tipo: 'autenticato'; utente: Utente }
  /** Il gateway non risponde: non sappiamo se l'utente ha fatto l'accesso. */
  | { tipo: 'irraggiungibile'; errore: ErroreApi };

interface ValoreSessione {
  stato: StatoSessione;
  /** Comodo per i componenti: l'utente se ha fatto l'accesso, altrimenti null. */
  utente: Utente | null;
  ricarica: () => Promise<void>;
  esci: () => Promise<void>;
}

const ContestoSessione = createContext<ValoreSessione | null>(null);

export function SessioneProvider({ children }: { children: ReactNode }) {
  const [stato, setStato] = useState<StatoSessione>({ tipo: 'caricamento' });

  const ricarica = useCallback(async () => {
    try {
      const utente = await api<Utente>('GET', '/auth/me');
      setStato({ tipo: 'autenticato', utente });
    } catch (errore) {
      if (errore instanceof ErroreApi && errore.stato === 401) {
        setStato({ tipo: 'anonimo' });
      } else {
        setStato({
          tipo: 'irraggiungibile',
          errore: errore instanceof ErroreApi ? errore : new ErroreApi(0, 'Servizio non raggiungibile'),
        });
      }
    }
  }, []);

  const esci = useCallback(async () => {
    await logout();
    setStato({ tipo: 'anonimo' });
  }, []);

  useEffect(() => {
    void ricarica();
  }, [ricarica]);

  const valore = useMemo<ValoreSessione>(
    () => ({ stato, utente: stato.tipo === 'autenticato' ? stato.utente : null, ricarica, esci }),
    [stato, ricarica, esci],
  );

  return <ContestoSessione.Provider value={valore}>{children}</ContestoSessione.Provider>;
}

export function useSessione(): ValoreSessione {
  const valore = useContext(ContestoSessione);
  if (valore === null) {
    throw new Error('useSessione va usato dentro <SessioneProvider>');
  }
  return valore;
}
