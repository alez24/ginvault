import { PAESAGGIO } from '../assets/foto/foto';
import { Divisore } from '../componenti/Montagne';

/**
 * Area soci (storia di Giulia): come la home, il paesaggio in cima e le montagne
 * che scendono sulla pagina. I contenuti arrivano nel passo 3.
 */
export function AreaSoci() {
  return (
    <section className="pagina pagina--soci" aria-labelledby="soci-titolo">
      <title>Area soci · GinVault</title>
      <header className="paesaggio">
        <picture className="paesaggio__foto">
          <source media="(min-width: 48rem)" srcSet={PAESAGGIO.larga.srcSet} sizes="100vw" />
          <img src={PAESAGGIO.alta.src} srcSet={PAESAGGIO.alta.srcSet} sizes="100vw" alt="" />
        </picture>
        <div className="contenitore paesaggio__testo">
          <h1 id="soci-titolo">Area soci</h1>
          <p className="attacco">
            L’edizione d’estate, le tue prenotazioni e la storia delle tue bottiglie, dalla formulazione al controllo
            qualità.
          </p>
        </div>
        <Divisore da="trasparente" a="neve" variante={2} />
      </header>
      <div className="contenitore pagina__corpo testo-corrente">
        <p className="in-arrivo">
          Questa sezione è in preparazione: qui potrai prenotare le bottiglie delle nuove edizioni nei sette giorni
          riservati ai soci.
        </p>
      </div>
    </section>
  );
}
