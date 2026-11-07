import { useEffect, useState } from 'react';
import { Link } from 'react-router';

import { CANALI, edizioni, NOMI_CANALI, type Edizione } from '../../api/edizioni';
import { ricette } from '../../api/ricette';
import { Mensola } from '../../componenti/Bottiglie';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Rametto } from '../../componenti/illustrazioni/Illustrazioni';
import { Indicatori, TestataPagina } from '../../componenti/TestataPagina';
import { PAGINE } from '../../navigazione';
import { ilGiorno, maiuscola, numero } from '../../utilita/numeri';
import { useCaricamento } from '../../utilita/useCaricamento';

/** "Edizione con lavanda" se il nome della formulazione non c'è (Ricette fermo). Senza articolo: il pino mugo, la lavanda. */
export function nomeEdizione(nomeFormulazione: string | undefined, botanica: string): string {
  return nomeFormulazione ?? `Edizione con ${botanica}`;
}

/**
 * GET /api/edizioni/edizioni, dalla più recente. I nomi vengono dalle
 * formulazioni USATE di Ricette: se Ricette non risponde l'elenco si vede lo stesso.
 */
export function ElencoEdizioni() {
  const { risultato, ricarica } = useCaricamento(() => edizioni.elenco(), 'edizioni');
  const nomi = useNomiDelleFormulazioni();

  return (
    <section className="pagina">
      <title>Edizioni · GinVault</title>
      <TestataPagina
        titolo="Edizioni"
        attacco="Un’edizione nasce da una formulazione scelta dal panel: la botanica di stagione disponibile decide la tiratura, che si divide tra soci, bar e vendita online."
      />
      {risultato.stato === 'pronto' && risultato.dati.length > 0 && <NumeriDelleEdizioni elenco={risultato.dati} />}

      <div className="contenitore pagina__corpo">
      {risultato.stato === 'caricamento' && <p className="attesa">Carico le edizioni…</p>}
      {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
      {risultato.stato === 'pronto' && risultato.dati.length === 0 && (
        <div className="vuoto">
          <p>Nessuna edizione ancora. Si crea dalla pagina di una formulazione scelta dal panel.</p>
          <Link to={`${PAGINE.formulazioni.percorso}?stato=SCELTA`}>Vedi le formulazioni scelte dal panel</Link>
        </div>
      )}
      {risultato.stato === 'pronto' && risultato.dati.length > 0 && (
        <ul className="carte">
          {risultato.dati.map((edizione) => (
            <li key={edizione.id} className="carta carta--edizione">
              <Rametto botanica={edizione.botanicaDiStagione} className="carta__rametto" />
              <h2 className="carta__titolo">
                <Link to={`${PAGINE.edizioni.percorso}/${edizione.id}`} className="carta__collegamento">
                  {nomeEdizione(nomi?.get(edizione.formulazione.id), edizione.botanicaDiStagione)}
                </Link>
              </h2>
              <p className="carta__botanica">{maiuscola(edizione.botanicaDiStagione)} di stagione</p>
              <p className="carta__cifra">
                {numero(edizione.tiratura)} <span>bottiglie</span>
              </p>
              <Mensola quote={edizione.quote} />
              <p className="carta__dati">
                {CANALI.map((canale) => `${NOMI_CANALI[canale]} ${numero(edizione.quote[canale])}`).join(', ')}.
                Creata {ilGiorno(edizione.creataIl)} dalla versione {edizione.formulazione.versione}.
              </p>
            </li>
          ))}
        </ul>
      )}
      {risultato.stato === 'pronto' && risultato.dati.length > 0 && nomi === null && (
        <p className="nota">Ricette non risponde: al posto dei nomi delle formulazioni c’è la botanica.</p>
      )}
      </div>
    </section>
  );
}

/** Quante edizioni, quante bottiglie in tutto e quante già prenotate. */
function NumeriDelleEdizioni({ elenco }: { elenco: Edizione[] }) {
  const tiratura = elenco.reduce((somma, edizione) => somma + edizione.tiratura, 0);
  const prenotate = elenco.reduce(
    (somma, edizione) => somma + CANALI.reduce((parziale, canale) => parziale + edizione.prenotate[canale], 0),
    0,
  );
  return (
    <Indicatori
      etichetta="Le edizioni in numeri"
      voci={[
        { chiave: 'tiratura', etichetta: 'bottiglie in tiratura', valore: numero(tiratura), principale: true },
        { chiave: 'edizioni', etichetta: elenco.length === 1 ? 'edizione' : 'edizioni', valore: numero(elenco.length) },
        { chiave: 'prenotate', etichetta: 'già prenotate', valore: numero(prenotate) },
      ]}
    />
  );
}

/** id formulazione -> nome, dalle formulazioni USATE. null se Ricette non risponde. */
function useNomiDelleFormulazioni(): Map<string, string> | null {
  const [nomi, setNomi] = useState<Map<string, string> | null>(new Map());
  useEffect(() => {
    let attivo = true;
    ricette.elenco('USATA').then(
      (formulazioni) => {
        if (attivo) setNomi(new Map(formulazioni.map((f) => [f.id, f.nome])));
      },
      () => {
        if (attivo) setNomi(null);
      },
    );
    return () => {
      attivo = false;
    };
  }, []);
  return nomi;
}
