import { Link } from 'react-router';

import { edizioneDelPiano, produzione, type Piano } from '../../api/produzione';
import { MisuraKg, type ParteDellaMisura } from '../../componenti/Acquisti';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Rametto } from '../../componenti/illustrazioni/Illustrazioni';
import { TestataPagina } from '../../componenti/TestataPagina';
import { PAGINE } from '../../navigazione';
import { bottiglie, kg } from '../../utilita/numeri';
import { kgDelPiano } from '../../utilita/regole';
import { useCaricamento } from '../../utilita/useCaricamento';

/** La misura dei kg di un piano: dalle consegne, dai lotti integrati, ancora attesi; il resto manca. */
export function partiDelPiano(piano: Piano): ParteDellaMisura[] {
  const { consegnati, integrati, attesi } = kgDelPiano(piano);
  const parti: ParteDellaMisura[] = [
    { chiave: 'consegne', kg: consegnati, tipo: 'pieno', etichetta: 'dalle consegne' },
    { chiave: 'integrati', kg: integrati, tipo: 'integrato', etichetta: 'dai lotti integrati' },
    { chiave: 'attesi', kg: attesi, tipo: 'atteso', etichetta: 'ancora attesi' },
  ];
  return parti.filter((parte) => parte.kg > 0);
}

/** GET /api/produzione/piani: un piano per edizione, dal più recente. */
export function ElencoPiani() {
  const { risultato, ricarica } = useCaricamento(() => produzione.piani(), 'piani');
  return (
    <section className="pagina">
      <title>Produzione · GinVault</title>
      <TestataPagina
        titolo="Produzione"
        attacco="Ogni edizione ha il suo piano: quanta botanica serve, quanta ne arriva davvero e quante bottiglie si possono fare. Cambia da solo con le consegne."
      />
      <div className="contenitore pagina__corpo">
        {risultato.stato === 'caricamento' && <p className="attesa">Carico i piani…</p>}
        {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
        {risultato.stato === 'pronto' && risultato.dati.length === 0 && (
          <div className="vuoto">
            <p>Nessun piano ancora: nasce da solo quando crei un’edizione.</p>
            <Link to={PAGINE.edizioni.percorso}>Vai alle edizioni</Link>
          </div>
        )}
        {risultato.stato === 'pronto' && risultato.dati.length > 0 && (
          <ul className="carte">
            {risultato.dati.map((piano) => (
              <li key={piano.id} className="carta">
                <Rametto botanica={piano.botanica} className="carta__rametto" />
                <h2 className="carta__titolo">
                  <Link to={`${PAGINE.piani.percorso}/${piano.id}`} className="carta__collegamento">
                    {edizioneDelPiano(piano)}
                  </Link>
                </h2>
                <p className="carta__botanica">
                  {piano.botanica} da {piano.origineRichiesta}
                </p>
                <MisuraKg totale={piano.kgPrevisti} parti={partiDelPiano(piano)} mancanti="mancanti" />
                <p className="carta__dati">
                  {piano.kgMancanti > 0
                    ? `Mancano ${kg(piano.kgMancanti)}: si fanno ${bottiglie(piano.bottiglieProducibili)} invece di ${piano.tiraturaPrevista}.`
                    : `Ci sono tutti i ${kg(piano.kgPrevisti)}: ${bottiglie(piano.bottiglieProducibili)}.`}
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
