import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';

import { ErroreApi } from '../../api/http';
import { magazzino, type Fornitore } from '../../api/magazzino';
import { edizioneDelPiano, produzione, type Piano } from '../../api/produzione';
import { PERCORSI_ACQUISTI } from '../../componenti/Acquisti';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { TestataPagina } from '../../componenti/TestataPagina';
import { ilGiorno, kg, maiuscola, numero, perCampo } from '../../utilita/numeri';
import { controllaOrdine, stessaOrigine, type TestiOrdine } from '../../utilita/regole';
import { useCaricamento } from '../../utilita/useCaricamento';

/**
 * POST /api/magazzino/ordini. L'edizione si sceglie dal piano di produzione
 * (GET /api/produzione/piani: le edizioni le legge solo il distillatore), che
 * dice anche la botanica, l'origine richiesta e quanti kg mancano.
 */
export function NuovoOrdine() {
  const { risultato, ricarica } = useCaricamento(
    () => Promise.all([produzione.piani(), magazzino.fornitori()]),
    'nuovo-ordine',
  );

  return (
    <section className="pagina">
      <title>Nuovo ordine · GinVault</title>
      <TestataPagina
        ritorno={{ a: PERCORSI_ACQUISTI.ordini, testo: 'Tutti gli ordini' }}
        titolo="Nuovo ordine"
        attacco="Per quale edizione, a quale fornitore, quanti kg e per quando. La botanica è quella dell’edizione."
      />
      <div className="contenitore pagina__corpo">
        {risultato.stato === 'caricamento' && <p className="attesa">Carico edizioni e fornitori…</p>}
        {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
        {risultato.stato === 'pronto' && risultato.dati[0].length === 0 && (
          <div className="vuoto">
            <p>Non c’è ancora nessuna edizione da rifornire: il piano di produzione nasce quando il distillatore crea l’edizione.</p>
          </div>
        )}
        {risultato.stato === 'pronto' && risultato.dati[0].length > 0 && (
          <ModuloOrdine piani={risultato.dati[0]} fornitori={risultato.dati[1]} />
        )}
      </div>
    </section>
  );
}

function ModuloOrdine({ piani, fornitori }: { piani: Piano[]; fornitori: Fornitore[] }) {
  const naviga = useNavigate();
  const [testi, setTesti] = useState<TestiOrdine>({
    pianoId: piani.length === 1 ? piani[0].id : '',
    fornitoreId: '',
    kg: '',
    consegnaAttesa: '',
  });
  const [mostraErrori, setMostraErrori] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);

  const { ordine, errori } = controllaOrdine(testi, piani, fornitori);
  const piano = piani.find((p) => p.id === testi.pianoId);
  const fornitore = fornitori.find((f) => f.id === testi.fornitoreId);
  const cambia = (campo: keyof TestiOrdine) => (valore: string) => setTesti((t) => ({ ...t, [campo]: valore }));
  const sbagliato = (campo: keyof TestiOrdine) => (mostraErrori && errori[campo] ? true : undefined);

  const ordina = async (evento?: FormEvent) => {
    evento?.preventDefault();
    setMostraErrori(true);
    if (!ordine || !fornitore) {
      return;
    }
    setInvio(true);
    setErrore(null);
    try {
      const creato = await magazzino.ordina(ordine);
      naviga(PERCORSI_ACQUISTI.ordine(creato.id), {
        state: {
          avviso: `Ordine registrato: ${kg(creato.kgOrdinati)} di ${creato.botanica} da ${fornitore.nome}, attesi ${ilGiorno(creato.consegnaAttesa)}.`,
        },
      });
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
      setInvio(false);
    }
  };

  return (
    <form className="modulo scheda scheda--stretta" onSubmit={(evento) => void ordina(evento)} noValidate>
      <div className="campo">
        <label className="campo__etichetta" htmlFor="piano">
          Per quale edizione
        </label>
        <select
          id="piano"
          className="campo__input"
          value={testi.pianoId}
          onChange={(evento) => cambia('pianoId')(evento.target.value)}
          aria-invalid={sbagliato('pianoId')}
          aria-describedby="piano-nota"
        >
          <option value="">Scegli l’edizione…</option>
          {piani.map((p) => (
            <option key={p.id} value={p.id}>
              {edizioneDelPiano(p)}: {p.botanica} da {p.origineRichiesta}
            </option>
          ))}
        </select>
        <p className={mostraErrori && errori.pianoId ? 'campo__errore' : 'anteprima'} id="piano-nota">
          {mostraErrori && errori.pianoId
            ? errori.pianoId
            : piano
              ? `Il piano chiede ${kg(piano.kgPrevisti)} di ${piano.botanica} da «${piano.origineRichiesta}»: ${
                  piano.kgMancanti > 0 ? `ne mancano ${kg(piano.kgMancanti)}.` : 'ci sono tutti.'
                }`
              : 'La botanica e l’origine le decide l’edizione.'}
        </p>
      </div>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="fornitore">
          Fornitore
        </label>
        <select
          id="fornitore"
          className="campo__input"
          value={testi.fornitoreId}
          onChange={(evento) => cambia('fornitoreId')(evento.target.value)}
          aria-invalid={sbagliato('fornitoreId')}
          aria-describedby="fornitore-nota"
        >
          <option value="">Scegli il fornitore…</option>
          {fornitori.map((f) => (
            <option key={f.id} value={f.id}>
              {f.nome}
            </option>
          ))}
        </select>
        <div id="fornitore-nota" aria-live="polite">
          {mostraErrori && errori.fornitoreId ? (
            <p className="campo__errore">{errori.fornitoreId}</p>
          ) : (
            fornitore && <NotaFornitore fornitore={fornitore} piano={piano} />
          )}
        </div>
      </div>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="kg-ordinati">
          Kg {piano ? `di ${piano.botanica} ` : ''}da ordinare
        </label>
        <span className="campo__con-unita">
          <input
            id="kg-ordinati"
            className="campo__input campo__input--corto"
            value={testi.kg}
            onChange={(evento) => cambia('kg')(evento.target.value)}
            inputMode="decimal"
            placeholder={piano ? perCampo(piano.kgMancanti > 0 ? piano.kgMancanti : piano.kgPrevisti) : '12'}
            autoComplete="off"
            aria-invalid={sbagliato('kg')}
            aria-describedby={mostraErrori && errori.kg ? 'kg-errore' : undefined}
          />
          <span className="campo__unita">kg</span>
        </span>
        {mostraErrori && errori.kg && (
          <p className="campo__errore" id="kg-errore">
            {errori.kg}
          </p>
        )}
        {piano && piano.kgMancanti > 0 && testi.kg.trim() === '' && (
          <button type="button" className="pulsante-testo pulsante-testo--sinistra" onClick={() => cambia('kg')(perCampo(piano.kgMancanti))}>
            Ordina quanto manca: {kg(piano.kgMancanti)}
          </button>
        )}
      </div>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="consegna-attesa">
          Consegna attesa
        </label>
        <input
          id="consegna-attesa"
          type="date"
          className="campo__input campo__input--data"
          value={testi.consegnaAttesa}
          onChange={(evento) => cambia('consegnaAttesa')(evento.target.value)}
          aria-invalid={sbagliato('consegnaAttesa')}
          aria-describedby={mostraErrori && errori.consegnaAttesa ? 'consegna-attesa-errore' : undefined}
        />
        {mostraErrori && errori.consegnaAttesa && (
          <p className="campo__errore" id="consegna-attesa-errore">
            {errori.consegnaAttesa}
          </p>
        )}
      </div>

      {errore && <ErroreOperazione errore={errore} onRiprova={() => void ordina()} onChiudi={() => setErrore(null)} />}

      <div className="modulo__azioni">
        <button type="submit" className="pulsante" disabled={invio}>
          {invio ? 'Registro l’ordine…' : 'Registra l’ordine'}
        </button>
      </div>
    </form>
  );
}

/** Da dove viene il fornitore (va bene per l'edizione?) e com'è andato finora. */
function NotaFornitore({ fornitore, piano }: { fornitore: Fornitore; piano: Piano | undefined }) {
  const { consegne, ridotte, inRitardo } = fornitore.riepilogo;
  const storia =
    consegne === 0
      ? 'Nessuna consegna ancora.'
      : `${consegne === 1 ? '1 consegna' : `${numero(consegne)} consegne`}: ${
          ridotte === 1 ? '1 ridotta' : `${numero(ridotte)} ridotte`
        }, ${numero(inRitardo)} in ritardo.`;
  if (piano && !stessaOrigine(fornitore.origine, piano.origineRichiesta)) {
    return (
      <p className="campo__avviso">
        {maiuscola(fornitore.nome)} viene da «{fornitore.origine}», ma l’edizione chiede {piano.botanica} da «
        {piano.origineRichiesta}»: i suoi kg non conteranno per la produzione. {storia}
      </p>
    );
  }
  return (
    <p className="anteprima">
      Da {fornitore.origine}
      {piano ? ', come chiede l’edizione' : ''}. {storia}
    </p>
  );
}
