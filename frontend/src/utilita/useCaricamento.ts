import { useCallback, useEffect, useState } from 'react';

import { ErroreApi } from '../api/http';

export type Caricamento<T> =
  | { stato: 'caricamento' }
  | { stato: 'pronto'; dati: T }
  | { stato: 'errore'; errore: ErroreApi };

/**
 * Carica dei dati quando la pagina compare e quando cambia la chiave (es. l'id
 * nell'indirizzo). Una risposta arrivata dopo un cambio di pagina viene ignorata.
 *
 * - ricarica(): rifà la richiesta (pulsante "Riprova", dati cambiati da altri);
 * - imposta(dati): sostituisce i dati senza ricaricare, con la risposta di una POST.
 */
export function useCaricamento<T>(carica: () => Promise<T>, chiave: string) {
  const [risultato, setRisultato] = useState<Caricamento<T>>({ stato: 'caricamento' });
  const [giro, setGiro] = useState(0);

  useEffect(() => {
    let attivo = true;
    setRisultato({ stato: 'caricamento' });
    carica().then(
      (dati) => {
        if (attivo) setRisultato({ stato: 'pronto', dati });
      },
      (errore: unknown) => {
        if (attivo) {
          setRisultato({
            stato: 'errore',
            errore: errore instanceof ErroreApi ? errore : new ErroreApi(0, 'Errore imprevisto', String(errore)),
          });
        }
      },
    );
    return () => {
      attivo = false;
    };
    // "carica" cambia a ogni render: conta solo la chiave
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chiave, giro]);

  const ricarica = useCallback(() => setGiro((n) => n + 1), []);
  const imposta = useCallback((dati: T) => setRisultato({ stato: 'pronto', dati }), []);

  return { risultato, ricarica, imposta };
}
