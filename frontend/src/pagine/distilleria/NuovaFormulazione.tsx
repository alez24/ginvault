import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';

import { ErroreApi } from '../../api/http';
import { ricette } from '../../api/ricette';
import { EditorDosi, nuovaRiga } from '../../componenti/EditorDosi';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { TestataPagina } from '../../componenti/TestataPagina';
import { PAGINE } from '../../navigazione';
import { controllaDosi, type RigaDose } from '../../utilita/regole';

interface Errori {
  nome?: string;
  botanicaDiStagione?: string;
}

/** POST /api/ricette/formulazioni: la formulazione con la sua prima prova (versione 1). */
export function NuovaFormulazione() {
  const naviga = useNavigate();
  const [nome, setNome] = useState('');
  const [botanicaDiStagione, setBotanicaDiStagione] = useState('');
  const [righe, setRighe] = useState<RigaDose[]>(() => [nuovaRiga(), nuovaRiga(), nuovaRiga()]);
  const [mostraErrori, setMostraErrori] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);

  const errori: Errori = {
    nome: !nome.trim() ? 'Dai un nome alla formulazione.' : nome.trim().length > 100 ? 'Al massimo 100 caratteri.' : undefined,
    botanicaDiStagione: !botanicaDiStagione.trim()
      ? 'Scrivi la botanica di stagione, per esempio lavanda.'
      : botanicaDiStagione.trim().length > 50
        ? 'Al massimo 50 caratteri.'
        : undefined,
  };
  const controllo = controllaDosi(righe, botanicaDiStagione);

  const salva = async (evento?: FormEvent) => {
    evento?.preventDefault();
    setMostraErrori(true);
    if (errori.nome || errori.botanicaDiStagione || !controllo.dosi) {
      return;
    }
    setInvio(true);
    setErrore(null);
    try {
      const creata = await ricette.crea({
        nome: nome.trim(),
        botanicaDiStagione: botanicaDiStagione.trim(),
        dosi: controllo.dosi,
      });
      naviga(`${PAGINE.formulazioni.percorso}/${creata.id}`, {
        state: { avviso: 'Formulazione registrata: la versione 1 è in assaggio.' },
      });
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
      setInvio(false);
    }
  };

  return (
    <section className="pagina">
      <title>Nuova formulazione · GinVault</title>
      <TestataPagina
        ritorno={{ a: PAGINE.formulazioni.percorso, testo: 'Tutte le formulazioni' }}
        titolo="Nuova formulazione"
        attacco="Nome, botanica di stagione e le dosi della prima prova. Le versioni successive le aggiungi dopo l’assaggio."
      />
      <div className="contenitore pagina__corpo">
      <form className="modulo scheda scheda--stretta" onSubmit={(evento) => void salva(evento)} noValidate>
        <div className="campo">
          <label className="campo__etichetta" htmlFor="nome">
            Nome
          </label>
          <input
            id="nome"
            className="campo__input"
            value={nome}
            onChange={(evento) => setNome(evento.target.value)}
            placeholder="Estate in Provenza"
            aria-invalid={mostraErrori && errori.nome ? true : undefined}
            aria-describedby={mostraErrori && errori.nome ? 'nome-errore' : undefined}
            maxLength={120}
            autoComplete="off"
          />
          {mostraErrori && errori.nome && (
            <p className="campo__errore" id="nome-errore">
              {errori.nome}
            </p>
          )}
        </div>

        <div className="campo">
          <label className="campo__etichetta" htmlFor="stagione">
            Botanica di stagione
          </label>
          <input
            id="stagione"
            className="campo__input"
            value={botanicaDiStagione}
            onChange={(evento) => setBotanicaDiStagione(evento.target.value)}
            placeholder="lavanda"
            aria-invalid={mostraErrori && errori.botanicaDiStagione ? true : undefined}
            aria-describedby={`stagione-aiuto${mostraErrori && errori.botanicaDiStagione ? ' stagione-errore' : ''}`}
            maxLength={60}
            autoComplete="off"
          />
          <p className="campo__aiuto" id="stagione-aiuto">
            Quella che arriva con il raccolto e decide quante bottiglie si fanno.
          </p>
          {mostraErrori && errori.botanicaDiStagione && (
            <p className="campo__errore" id="stagione-errore">
              {errori.botanicaDiStagione}
            </p>
          )}
        </div>

        <EditorDosi
          righe={righe}
          onCambia={setRighe}
          botanicaDiStagione={botanicaDiStagione}
          controllo={mostraErrori ? controllo : null}
        />

        {errore && <ErroreOperazione errore={errore} onRiprova={() => void salva()} onChiudi={() => setErrore(null)} />}

        <div className="modulo__azioni">
          <button type="submit" className="pulsante" disabled={invio}>
            {invio ? 'Registro…' : 'Registra la formulazione'}
          </button>
          <Link to={PAGINE.formulazioni.percorso}>Annulla</Link>
        </div>
      </form>
      </div>
    </section>
  );
}
