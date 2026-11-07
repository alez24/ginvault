import { Link, useSearchParams } from 'react-router';

import { ricette, type Formulazione, type StatoFormulazione } from '../../api/ricette';
import { EtichettaStato } from '../../componenti/Distilleria';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Rametto } from '../../componenti/illustrazioni/Illustrazioni';
import { TestataPagina } from '../../componenti/TestataPagina';
import { PAGINE } from '../../navigazione';
import { ilGiorno, maiuscola } from '../../utilita/numeri';
import { useCaricamento } from '../../utilita/useCaricamento';

const FILTRI: { stato: StatoFormulazione | undefined; etichetta: string }[] = [
  { stato: undefined, etichetta: 'Tutte' },
  { stato: 'IN_PROVA', etichetta: 'In prova' },
  { stato: 'SCELTA', etichetta: 'Scelte dal panel' },
  { stato: 'USATA', etichetta: 'Usate' },
];

function statoDa(valore: string | null): StatoFormulazione | undefined {
  return FILTRI.find((filtro) => filtro.stato === valore)?.stato;
}

/** GET /api/ricette/formulazioni[?stato=], dalla prova più recente. */
export function ElencoFormulazioni() {
  const [parametri] = useSearchParams();
  const stato = statoDa(parametri.get('stato'));
  const { risultato, ricarica } = useCaricamento(() => ricette.elenco(stato), stato ?? 'tutte');

  return (
    <section className="pagina">
      <title>Formulazioni · GinVault</title>
      <TestataPagina
        titolo="Formulazioni"
        attacco="Ogni formulazione è una ricetta con le sue prove d’assaggio. Il panel ne sceglie una versione, e da quella nasce l’edizione."
        azioni={
          <Link to={`${PAGINE.formulazioni.percorso}/nuova`} className="pulsante">
            Nuova formulazione
          </Link>
        }
        piede={
          <nav className="filtri" aria-label="Filtra per stato">
            {FILTRI.map((filtro) => (
              <Link
                key={filtro.etichetta}
                to={filtro.stato ? `?stato=${filtro.stato}` : PAGINE.formulazioni.percorso}
                className="filtri__voce"
                aria-current={filtro.stato === stato ? 'page' : undefined}
              >
                {filtro.etichetta}
              </Link>
            ))}
          </nav>
        }
      />

      <div className="contenitore pagina__corpo">
        {risultato.stato === 'caricamento' && <p className="attesa">Carico le formulazioni…</p>}
        {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
        {risultato.stato === 'pronto' && <Elenco formulazioni={risultato.dati} stato={stato} />}
      </div>
    </section>
  );
}

function Elenco({ formulazioni, stato }: { formulazioni: Formulazione[]; stato: StatoFormulazione | undefined }) {
  if (formulazioni.length === 0) {
    return (
      <div className="vuoto">
        <p>
          {stato
            ? 'Nessuna formulazione in questo stato.'
            : 'Non c’è ancora nessuna formulazione. Comincia dalla ricetta della prossima stagione.'}
        </p>
        {!stato && <Link to={`${PAGINE.formulazioni.percorso}/nuova`}>Registra la prima formulazione</Link>}
      </div>
    );
  }
  return (
    <ul className="carte">
      {formulazioni.map((formulazione) => (
        <li key={formulazione.id} className="carta">
          <Rametto botanica={formulazione.botanicaDiStagione} className="carta__rametto" />
          <EtichettaStato stato={formulazione.stato} />
          <h2 className="carta__titolo">
            <Link to={`${PAGINE.formulazioni.percorso}/${formulazione.id}`} className="carta__collegamento">
              {formulazione.nome}
            </Link>
          </h2>
          <p className="carta__botanica">{maiuscola(formulazione.botanicaDiStagione)} di stagione</p>
          <p className="carta__dati">{riassunto(formulazione)}</p>
          <p className="carta__nota">{cosaSuccede(formulazione)}</p>
        </li>
      ))}
    </ul>
  );
}

function riassunto(formulazione: Formulazione): string {
  const prove = formulazione.prove.length;
  const ultima = formulazione.prove.at(-1);
  if (!ultima) {
    return 'Nessuna prova.';
  }
  return prove === 1
    ? `Una prova, registrata ${ilGiorno(ultima.registrataIl)}.`
    : `${prove} prove, l’ultima registrata ${ilGiorno(ultima.registrataIl)}.`;
}

function cosaSuccede(formulazione: Formulazione): string {
  switch (formulazione.stato) {
    case 'IN_PROVA':
      return 'Il panel sta ancora assaggiando.';
    case 'SCELTA':
      return `Il panel ha scelto la versione ${formulazione.versioneScelta}: pronta per l’edizione.`;
    case 'USATA':
      return `Versione ${formulazione.versioneScelta} usata per un’edizione.`;
  }
}
