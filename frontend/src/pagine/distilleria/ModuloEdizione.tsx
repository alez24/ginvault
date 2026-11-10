import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';

import { CANALI, edizioni, NOMI_CANALI, type Canale } from '../../api/edizioni';
import { ErroreApi } from '../../api/http';
import { grammiDiStagione, provaScelta, type Formulazione } from '../../api/ricette';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { PAGINE } from '../../navigazione';
import { bottiglie, numero } from '../../utilita/numeri';
import {
  bottigliePossibili,
  controllaKg,
  controllaQuota,
  quoteSuggerite,
  TIRATURA_MASSIMA,
  tiraturaPrevista,
} from '../../utilita/regole';

type TestiQuote = Record<Canale, string>;

/** Come Edizioni: obbligatoria, al massimo 100 caratteri. */
const ORIGINE_MASSIMA = 100;

const controllaOrigine = (origine: string): string | undefined =>
  !origine.trim()
    ? 'Scrivi da dove deve venire la botanica: un lotto di un’altra origine non entrerà in questa edizione.'
    : origine.trim().length > ORIGINE_MASSIMA
      ? `Al massimo ${ORIGINE_MASSIMA} caratteri.`
      : undefined;

const testi = (quote: Record<Canale, number>): TestiQuote => ({
  SOCI: String(quote.SOCI),
  BAR: String(quote.BAR),
  ONLINE: String(quote.ONLINE),
});

/**
 * POST /api/edizioni/edizioni da una formulazione SCELTA: kg di botanica di
 * stagione disponibili, la sua origine e quote per canale. Edizioni chiede a Ricette la
 * versione scelta, calcola la tiratura e segna la formulazione come USATA.
 */
export function ModuloEdizione({ formulazione }: { formulazione: Formulazione }) {
  const naviga = useNavigate();
  const prova = provaScelta(formulazione);
  const grammi = grammiDiStagione(formulazione, prova) ?? 0;
  const stagione = formulazione.botanicaDiStagione;

  const [kg, setKg] = useState('');
  const [origine, setOrigine] = useState('');
  const [quote, setQuote] = useState<TestiQuote>({ SOCI: '', BAR: '', ONLINE: '' });
  /** Finché l'utente non tocca le quote, seguono la tiratura con la divisione di partenza. */
  const [quoteToccate, setQuoteToccate] = useState(false);
  const [mostraErrori, setMostraErrori] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);

  const kgLetti = controllaKg(kg);
  const tiratura = typeof kgLetti === 'number' ? tiraturaPrevista(kgLetti, grammi) : 0;
  const possibili = typeof kgLetti === 'number' ? bottigliePossibili(kgLetti, grammi) : 0;
  const quoteMostrate = quoteToccate ? quote : tiratura > 0 ? testi(quoteSuggerite(tiratura)) : quote;
  const quoteLette = CANALI.map((canale) => controllaQuota(quoteMostrate[canale]));
  const sommaQuote = quoteLette.every((q) => typeof q === 'number')
    ? (quoteLette as number[]).reduce((a, b) => a + b, 0)
    : undefined;

  const cambiaQuota = (canale: Canale, valore: string) => {
    setQuote({ ...quoteMostrate, [canale]: valore });
    setQuoteToccate(true);
  };
  const dividi = () => {
    setQuote(testi(quoteSuggerite(tiratura)));
    setQuoteToccate(true);
  };

  const erroreKg =
    typeof kgLetti === 'string'
      ? kgLetti
      : tiratura === 0
        ? `Con ${numero(kgLetti)} kg non si riempie nemmeno una bottiglia: ne servono ${numero(grammi)} g ciascuna.`
        : undefined;
  const erroreSomma =
    sommaQuote !== undefined && tiratura > 0 && sommaQuote !== tiratura
      ? sommaQuote < tiratura
        ? `Mancano ${bottiglie(tiratura - sommaQuote)}: la somma delle quote deve fare ${numero(tiratura)}.`
        : `${bottiglie(sommaQuote - tiratura)} di troppo: la somma delle quote deve fare ${numero(tiratura)}.`
      : undefined;

  const erroreOrigine = controllaOrigine(origine);

  const crea = async (evento?: FormEvent) => {
    evento?.preventDefault();
    setMostraErrori(true);
    if (!prova || typeof kgLetti !== 'number' || erroreKg || erroreOrigine || sommaQuote === undefined || erroreSomma) {
      return;
    }
    setInvio(true);
    setErrore(null);
    try {
      const [soci, bar, online] = quoteLette as number[];
      const edizione = await edizioni.crea({
        formulazione: { id: formulazione.id, versione: prova.versione },
        botanicaDisponibileKg: kgLetti,
        origineBotanica: origine.trim().replace(/\s+/g, ' '),
        quote: { SOCI: soci, BAR: bar, ONLINE: online },
      });
      naviga(`${PAGINE.edizioni.percorso}/${edizione.id}`, {
        state: {
          avviso: `Edizione creata: ${bottiglie(edizione.tiratura)}. La formulazione «${formulazione.nome}» ora è usata e non si riusa.`,
        },
      });
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
      setInvio(false);
    }
  };

  if (!prova || grammi <= 0) {
    // Non dovrebbe succedere: Ricette rifiuta prove senza la botanica di stagione
    return <p className="nota">La versione scelta non ha la dose di {stagione}: non si può calcolare la tiratura.</p>;
  }

  return (
    <form className="modulo scheda" onSubmit={(evento) => void crea(evento)} noValidate>
      <h2>Crea l’edizione</h2>
      <p>
        Dalla versione {prova.versione}, con {numero(grammi)} g di {stagione} per bottiglia. La tiratura dipende dai kg
        di {stagione} disponibili, fino a {TIRATURA_MASSIMA} bottiglie.
      </p>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="kg">
          Kg di {stagione} disponibili
        </label>
        <span className="campo__con-unita">
          <input
            id="kg"
            className="campo__input campo__input--corto"
            value={kg}
            onChange={(evento) => setKg(evento.target.value)}
            inputMode="decimal"
            placeholder="12"
            autoComplete="off"
            aria-invalid={mostraErrori && erroreKg ? true : undefined}
            aria-describedby="kg-anteprima"
          />
          <span className="campo__unita">kg</span>
        </span>
        <p className="anteprima" id="kg-anteprima" aria-live="polite">
          {tiratura > 0 ? (
            <>
              Tiratura: <strong>{bottiglie(tiratura)}</strong>
              {possibili > TIRATURA_MASSIMA && ` (ne verrebbero ${numero(possibili)}, ma il massimo è ${TIRATURA_MASSIMA})`}
            </>
          ) : mostraErrori && erroreKg ? (
            <span className="campo__errore">{erroreKg}</span>
          ) : kg.trim() && erroreKg ? (
            erroreKg
          ) : (
            'Scrivi i kg per vedere quante bottiglie si fanno.'
          )}
        </p>
      </div>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="origine">
          Origine della {stagione}
        </label>
        <input
          id="origine"
          className="campo__input"
          value={origine}
          onChange={(evento) => setOrigine(evento.target.value)}
          placeholder="Alta Val Varaita (Alpi Cozie)"
          maxLength={ORIGINE_MASSIMA}
          autoComplete="off"
          aria-invalid={mostraErrori && erroreOrigine ? true : undefined}
          aria-describedby="origine-nota"
        />
        {mostraErrori && erroreOrigine ? (
          <p className="campo__errore" id="origine-nota">
            {erroreOrigine}
          </p>
        ) : (
          <p className="anteprima" id="origine-nota">
            L’edizione resta fedele a questa origine: la produzione userà solo lotti che vengono da qui.
          </p>
        )}
      </div>

      <fieldset className="quote-modulo">
        <legend>Quote per canale</legend>
        <div className="quote-modulo__campi">
          {CANALI.map((canale, i) => (
            <div className="campo" key={canale}>
              <label className="campo__etichetta" htmlFor={`quota-${canale}`}>
                {NOMI_CANALI[canale]}
              </label>
              <input
                id={`quota-${canale}`}
                className="campo__input"
                value={quoteMostrate[canale]}
                onChange={(evento) => cambiaQuota(canale, evento.target.value)}
                inputMode="numeric"
                autoComplete="off"
                aria-invalid={mostraErrori && typeof quoteLette[i] === 'string' ? true : undefined}
              />
              {mostraErrori && typeof quoteLette[i] === 'string' && <p className="campo__errore">{quoteLette[i]}</p>}
            </div>
          ))}
        </div>
        <p className="anteprima" aria-live="polite">
          {sommaQuote !== undefined && (
            <>
              Totale <strong>{numero(sommaQuote)}</strong>
              {tiratura > 0 && ` su ${bottiglie(tiratura)}`}
              {tiratura > 0 && sommaQuote === tiratura && ' ✓'}
            </>
          )}
        </p>
        {erroreSomma && (mostraErrori || quoteToccate) && <p className="campo__errore">{erroreSomma}</p>}
        <button type="button" className="pulsante-testo" onClick={dividi} disabled={tiratura === 0}>
          Metà ai soci, un quarto ai bar, il resto online
        </button>
      </fieldset>

      {errore && <ErroreOperazione errore={errore} onRiprova={() => void crea()} onChiudi={() => setErrore(null)} />}

      <div className="modulo__azioni">
        <button type="submit" className="pulsante" disabled={invio}>
          {invio ? 'Creo l’edizione…' : 'Crea l’edizione'}
        </button>
      </div>
    </form>
  );
}
