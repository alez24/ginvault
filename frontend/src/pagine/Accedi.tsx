import { Navigate, useLocation } from 'react-router';

import { PAESAGGIO } from '../assets/foto/foto';
import { PulsantiAccesso } from '../componenti/PulsantiAccesso';
import { useSessione } from '../sessione/Sessione';

/** Pagina di accesso: ci arriva chi apre una pagina protetta senza aver fatto l'accesso. */
export function Accedi() {
  const { utente } = useSessione();
  const posizione = useLocation();
  const da = (posizione.state as { da?: string } | null)?.da;

  if (utente) {
    return <Navigate to={da ?? '/'} replace />;
  }

  return (
    <section className="accesso" aria-labelledby="accesso-titolo">
      <title>Accedi · GinVault</title>
      <picture className="accesso__foto">
        <source media="(min-width: 48rem)" srcSet={PAESAGGIO.larga.srcSet} sizes="100vw" />
        <img src={PAESAGGIO.alta.src} srcSet={PAESAGGIO.alta.srcSet} sizes="100vw" alt="" />
      </picture>
      <div className="contenitore accesso__griglia">
        <div className="scheda accesso__scheda">
          <h1 id="accesso-titolo">Accedi a GinVault</h1>
          <p className="attacco">
            {da
              ? 'Per aprire questa pagina serve l’accesso. Dopo il login ti riportiamo qui.'
              : 'Entra con il tuo account Google o GitHub: i tuoi ruoli (socio, distillatore…) arrivano con l’account.'}
          </p>
          <PulsantiAccesso dopoAccesso={da} />
          <p className="nota">
            GinVault non vede la tua password: l’accesso lo verifica Google o GitHub, e la sessione la tiene il nostro
            gateway.
          </p>
        </div>
      </div>
    </section>
  );
}
