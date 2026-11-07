import { Link } from 'react-router';

import { TestataPagina } from '../componenti/TestataPagina';

export function NonTrovata() {
  return (
    <section className="pagina">
      <title>Pagina non trovata · GinVault</title>
      <TestataPagina titolo="Pagina non trovata" attacco="L’indirizzo non corrisponde a nessuna pagina di GinVault." />
      <div className="contenitore pagina__corpo">
        <p>
          <Link to="/">Torna alla distilleria</Link>
        </p>
      </div>
    </section>
  );
}
