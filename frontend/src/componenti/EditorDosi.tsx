import { useId } from 'react';

import type { Dose } from '../api/ricette';
import { perCampo } from '../utilita/numeri';
import { normalizzaBotanica, type ControlloDosi, type RigaDose } from '../utilita/regole';

let prossimaChiave = 1;

export function nuovaRiga(botanica = '', grammi = ''): RigaDose {
  return { chiave: prossimaChiave++, botanica, grammi };
}

/** Le righe del modulo a partire dalle dosi di una prova (per partire dall'ultima versione). */
export function righeDa(dosi: Dose[]): RigaDose[] {
  return dosi.map((dose) => nuovaRiga(dose.botanica, perCampo(dose.grammiPerBottiglia)));
}

interface Props {
  righe: RigaDose[];
  onCambia: (righe: RigaDose[]) => void;
  botanicaDiStagione: string;
  /** null finché l'utente non prova a salvare: gli errori si mostrano solo dopo. */
  controllo: ControlloDosi | null;
}

/** Le dosi per bottiglia di una prova: una riga per botanica, la botanica di stagione evidenziata. */
export function EditorDosi({ righe, onCambia, botanicaDiStagione, controllo }: Props) {
  const id = useId();
  const stagione = normalizzaBotanica(botanicaDiStagione);

  const cambia = (chiave: number, campo: 'botanica' | 'grammi', valore: string) =>
    onCambia(righe.map((riga) => (riga.chiave === chiave ? { ...riga, [campo]: valore } : riga)));
  const togli = (chiave: number) => onCambia(righe.filter((riga) => riga.chiave !== chiave));

  return (
    <fieldset className="dosi">
      <legend>Dosi per bottiglia</legend>
      <p className="campo__aiuto" id={`${id}-aiuto`}>
        Grammi di ogni botanica per una bottiglia. Ci deve essere la botanica di stagione
        {stagione ? (
          <>
            {' '}
            (<strong>{stagione}</strong>)
          </>
        ) : null}
        : da lei dipende la tiratura.
      </p>

      <div className="dosi__intestazione" aria-hidden="true">
        <span>Botanica</span>
        <span>Grammi</span>
      </div>

      <ol className="dosi__righe">
        {righe.map((riga, indice) => {
          const errori = controllo?.righe[riga.chiave];
          const diStagione = stagione !== '' && normalizzaBotanica(riga.botanica) === stagione;
          const idBotanica = `${id}-${riga.chiave}-b`;
          const idGrammi = `${id}-${riga.chiave}-g`;
          return (
            <li key={riga.chiave} className={`dose${diStagione ? ' dose--stagione' : ''}`}>
              <div className="dose__campi">
                <input
                  id={idBotanica}
                  className="campo__input"
                  aria-label={`Botanica ${indice + 1}`}
                  aria-invalid={errori?.botanica ? true : undefined}
                  aria-describedby={errori?.botanica ? `${idBotanica}-errore` : undefined}
                  value={riga.botanica}
                  onChange={(evento) => cambia(riga.chiave, 'botanica', evento.target.value)}
                  placeholder={indice === 0 ? 'ginepro' : undefined}
                  autoComplete="off"
                  maxLength={60}
                />
                <span className="dose__grammi">
                  <input
                    id={idGrammi}
                    className="campo__input"
                    aria-label={`Grammi per bottiglia ${indice + 1}`}
                    aria-invalid={errori?.grammi ? true : undefined}
                    aria-describedby={errori?.grammi ? `${idGrammi}-errore` : undefined}
                    value={riga.grammi}
                    onChange={(evento) => cambia(riga.chiave, 'grammi', evento.target.value)}
                    inputMode="decimal"
                    placeholder={indice === 0 ? '24' : undefined}
                    autoComplete="off"
                  />
                  <span className="dose__unita">g</span>
                </span>
                <button
                  type="button"
                  className="dose__togli"
                  onClick={() => togli(riga.chiave)}
                  aria-label={`Togli ${riga.botanica.trim() || `la riga ${indice + 1}`}`}
                  disabled={righe.length === 1}
                >
                  ×
                </button>
              </div>
              {diStagione && <span className="dose__stagione">botanica di stagione</span>}
              {errori?.botanica && (
                <p className="campo__errore" id={`${idBotanica}-errore`}>
                  {errori.botanica}
                </p>
              )}
              {errori?.grammi && (
                <p className="campo__errore" id={`${idGrammi}-errore`}>
                  {errori.grammi}
                </p>
              )}
            </li>
          );
        })}
      </ol>

      <button type="button" className="pulsante-testo" onClick={() => onCambia([...righe, nuovaRiga()])}>
        Aggiungi una botanica
      </button>

      {controllo && controllo.generali.length > 0 && (
        <ul className="campo__errore dosi__errori">
          {controllo.generali.map((errore) => (
            <li key={errore}>{errore}</li>
          ))}
        </ul>
      )}
    </fieldset>
  );
}
