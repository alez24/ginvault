import { campiNonValidi, nomeCampo, rimedio } from '../api/errori';
import type { ErroreApi } from '../api/http';
import { useSessione } from '../sessione/Sessione';
import { Avviso } from './Avviso';

interface Props {
  errore: ErroreApi;
  /** Ripete l'operazione (servizio fermo o lento). */
  onRiprova?: () => void;
  /** Ricarica i dati della pagina (modifica concorrente). */
  onRicarica?: () => void;
  onChiudi?: () => void;
}

/**
 * Un'operazione rifiutata, con le parole del servizio: il titolo e il
 * dettaglio del Problem Detail (es. 409 "Tiratura non riducibile"), i campi
 * sbagliati di un 400 e, se serve, cosa fare (riprovare, ricaricare, rientrare).
 */
export function ErroreOperazione({ errore, onRiprova, onRicarica, onChiudi }: Props) {
  const sessione = useSessione();
  const campi = campiNonValidi(errore);
  const cosaFare = rimedio(errore);

  return (
    <Avviso tipo="problema" titolo={errore.titolo} onChiudi={onChiudi}>
      {errore.dettaglio && <p>{errore.dettaglio}</p>}
      {campi.length > 0 && (
        <ul className="avviso__elenco">
          {campi.map((campo) => (
            <li key={`${campo.campo}:${campo.messaggio}`}>
              <strong>{nomeCampo(campo.campo)}</strong>: {campo.messaggio}
            </li>
          ))}
        </ul>
      )}
      {cosaFare === 'riprova' && (
        <p>
          Di solito torna disponibile in pochi secondi.{' '}
          {onRiprova && (
            <button type="button" className="pulsante-testo" onClick={onRiprova}>
              Riprova
            </button>
          )}
        </p>
      )}
      {cosaFare === 'ricarica' && onRicarica && (
        <p>
          Nel frattempo qualcuno ha modificato questi dati.{' '}
          <button type="button" className="pulsante-testo" onClick={onRicarica}>
            Ricarica i dati aggiornati
          </button>
        </p>
      )}
      {cosaFare === 'accedi' && (
        <p>
          <button type="button" className="pulsante-testo" onClick={() => void sessione.ricarica()}>
            Accedi di nuovo
          </button>{' '}
          e poi ripeti l’operazione.
        </p>
      )}
    </Avviso>
  );
}
