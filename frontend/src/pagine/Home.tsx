import { m, useReducedMotion, useScroll, useTransform } from 'motion/react';
import { useRef, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';

import { PAESAGGIO, PRATO } from '../assets/foto/foto';
import { Avviso } from '../componenti/Avviso';
import { Bottiglia, Cartellino } from '../componenti/Bottiglia';
import { BOTTIGLIE_PER_DISEGNO, QuoteInBottiglie } from '../componenti/Bottiglie';
import { Lavanda } from '../componenti/illustrazioni/Illustrazioni';
import { CRINALI_APERTURA, Divisore } from '../componenti/Montagne';
import { Appare, Contatore } from '../componenti/Movimento';
import { PulsantiAccesso } from '../componenti/PulsantiAccesso';
import { StrisciaBotaniche } from '../componenti/StrisciaBotaniche';
import { PAGINE, vociDelMenu } from '../navigazione';
import { useSessione } from '../sessione/Sessione';

const DOLCE = [0.22, 1, 0.36, 1] as const;

/** L'edizione d'estate raccontata in home: 10,8 kg di lavanda, 540 bottiglie. */
const ESTATE = { tiratura: 540, quote: { SOCI: 300, BAR: 150, ONLINE: 90 } };

/** Home pubblica: presenta la distilleria e porta al login (o alle proprie aree). */
export function Home() {
  const [parametri, setParametri] = useSearchParams();
  const accessoNegato = parametri.get('accesso') === 'negato';

  return (
    <>
      <title>GinVault, gin di montagna in edizioni stagionali</title>

      <Apertura
        avviso={
          accessoNegato && (
            <Avviso titolo="Accesso annullato" onChiudi={() => setParametri({}, { replace: true })}>
              Non hai autorizzato GinVault su Google o GitHub. Puoi riprovare quando vuoi.
            </Avviso>
          )
        }
      />
      <Cifre />
      <Divisore da="neve" a="avorio" variante={0} />
      <Estate />
      <StrisciaBotaniche />
      <Divisore da="avorio" a="neve" variante={1} />
      <Passi />
      <Divisore da="neve" a="avorio" variante={2} />
      <Club />
    </>
  );
}

/**
 * Il paesaggio a tutto schermo con un velo chiaro; titolo e bottiglia stanno
 * dentro. Scorrendo, foto e crinali lontani scendono più lenti della pagina
 * (parallax) e la bottiglia affonda dietro il crinale più vicino.
 */
function Apertura({ avviso }: { avviso: ReactNode }) {
  const sezione = useRef<HTMLElement>(null);
  const fermo = useReducedMotion();
  const { scrollYProgress } = useScroll({ target: sezione, offset: ['start start', 'end start'] });
  const foto = useTransform(scrollYProgress, [0, 1], ['0%', '18%']);
  const lontano = useTransform(scrollYProgress, [0, 1], ['0%', '38%']);
  const medio = useTransform(scrollYProgress, [0, 1], ['0%', '18%']);
  const bottiglia = useTransform(scrollYProgress, [0, 1], ['0%', '12%']);
  const entra = (ritardo: number) =>
    fermo
      ? {}
      : {
          initial: { opacity: 0, y: 26 },
          animate: { opacity: 1, y: 0 },
          transition: { duration: 1, ease: DOLCE, delay: ritardo },
        };

  return (
    <section ref={sezione} className="apertura" aria-labelledby="apertura-titolo">
      <m.div className="apertura__foto" style={fermo ? undefined : { y: foto }}>
        <picture>
          <source media="(min-width: 48rem)" srcSet={PAESAGGIO.larga.srcSet} sizes="100vw" />
          <img
            src={PAESAGGIO.alta.src}
            srcSet={PAESAGGIO.alta.srcSet}
            sizes="100vw"
            width={PAESAGGIO.alta.larghezza}
            height={PAESAGGIO.alta.altezza}
            alt=""
            fetchPriority="high"
          />
        </picture>
      </m.div>
      <div className="apertura__velo" />
      <m.div className="apertura__crinale apertura__crinale--lontano" style={fermo ? undefined : { y: lontano }}>
        <svg viewBox="0 0 1440 200" preserveAspectRatio="none" aria-hidden="true" focusable="false">
          <path d={CRINALI_APERTURA.lontano} />
        </svg>
      </m.div>
      <m.div className="apertura__crinale apertura__crinale--medio" style={fermo ? undefined : { y: medio }}>
        <svg viewBox="0 0 1440 200" preserveAspectRatio="none" aria-hidden="true" focusable="false">
          <path d={CRINALI_APERTURA.medio} />
        </svg>
      </m.div>

      <div className="contenitore apertura__griglia">
        <m.div className="apertura__testo" {...entra(0)}>
          {avviso}
          <h1 id="apertura-titolo" className="apertura__titolo">
            Un gin di montagna, un’edizione per stagione
          </h1>
          <p className="apertura__attacco">
            Ogni stagione una botanica del territorio accanto al ginepro, al massimo 600 bottiglie numerate. La
            ricetta la sceglie il nostro panel d’assaggio, e i soci del club prenotano per primi.
          </p>
        </m.div>
        <m.div className="apertura__comandi" {...entra(0.12)}>
          <Azioni />
        </m.div>

        <m.div className="apertura__bottiglia" style={fermo ? undefined : { y: bottiglia }}>
          <m.figure {...entra(0.25)}>
            <Bottiglia subito sizes="(min-width: 48rem) 16rem, 10rem" />
            <Cartellino edizione="Edizione d’estate 2026" sotto="n. 112 di 540" />
          </m.figure>
        </m.div>
      </div>

      <div className="apertura__crinale apertura__crinale--vicino">
        <svg viewBox="0 0 1440 200" preserveAspectRatio="none" aria-hidden="true" focusable="false">
          <path d={CRINALI_APERTURA.vicino} />
        </svg>
      </div>
    </section>
  );
}

/** 600 bottiglie, 7 giorni, 3 canali: contano quando arrivano sullo schermo. */
function Cifre() {
  const cifre = [
    { valore: 600, unita: 'bottiglie', testo: 'al massimo per ogni edizione, ognuna con il suo numero.' },
    { valore: 7, unita: 'giorni', testo: 'di prelazione: i soci del club prenotano prima di tutti.' },
    { valore: 3, unita: 'canali', testo: 'per arrivare al bicchiere: i soci, i bar che ci ospitano e la vendita online.' },
  ];
  return (
    <section className="cifre" aria-labelledby="cifre-titolo">
      <div className="contenitore">
        <h2 id="cifre-titolo" className="nascosto">
          GinVault in tre numeri
        </h2>
        <ul className="cifre__elenco">
          {cifre.map((cifra, i) => (
            <li key={cifra.unita} className="cifra">
              <Appare ritardo={i * 0.12}>
                <p className="cifra__numero">
                  <Contatore valore={cifra.valore} durata={cifra.valore > 100 ? 1.8 : 1.1} />
                  <span className="cifra__unita">{cifra.unita}</span>
                </p>
                <p className="cifra__testo">{cifra.testo}</p>
              </Appare>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

/** L'edizione d'estate: la bottiglia, la lavanda e la tiratura in bottigliette. */
function Estate() {
  return (
    <section className="estate" aria-labelledby="estate-titolo">
      <div className="contenitore estate__griglia">
        <Appare className="estate__figura">
          <figure>
            <span className="estate__alone" aria-hidden="true" />
            <span className="estate__lavanda" aria-hidden="true">
              <Lavanda />
            </span>
            <Bottiglia sizes="(min-width: 62rem) 15rem, 10rem" />
          </figure>
        </Appare>

        <div className="estate__testo">
          <Appare>
            <h2 id="estate-titolo">L’edizione d’estate 2026</h2>
            <p className="attacco">
              Ginepro e lavanda d’alta quota. Il raccolto di quest’estate ha dato 10,8 kg di lavanda: a 20 grammi per
              bottiglia fanno 540 bottiglie numerate, invece delle 600 possibili.
            </p>
          </Appare>
          <Appare ritardo={0.12} className="estate__tiratura">
            <p className="estate__totale">
              <span className="estate__numero">{ESTATE.tiratura}</span> bottiglie in tiratura
            </p>
            <QuoteInBottiglie quote={ESTATE.quote} />
            <p className="nota">Ogni bottiglia disegnata ne vale {BOTTIGLIE_PER_DISEGNO}.</p>
          </Appare>
        </div>
      </div>
    </section>
  );
}

/** Come nasce un'edizione: tre tappe lungo un sentiero che sale. */
function Passi() {
  const passi = [
    {
      titolo: 'Le prove d’assaggio',
      testo:
        'Il mastro distillatore prepara più versioni della ricetta. Il panel le assaggia e ne sceglie una: le altre restano in archivio, intatte.',
    },
    {
      titolo: 'La botanica decide la tiratura',
      testo:
        'Quanta botanica arriva dal raccolto stabilisce quante bottiglie: con 20 g di lavanda per bottiglia, 12 kg ne riempiono 600.',
    },
    {
      titolo: 'Soci, bar, online',
      testo:
        'Le bottiglie si dividono tra i soci del club, i bar e la vendita online. Se il raccolto delude cala prima la quota online; quella dei soci non si tocca.',
    },
  ];
  return (
    <section className="nascita" aria-labelledby="nascita-titolo">
      <div className="contenitore">
        <Appare className="nascita__testa">
          <h2 id="nascita-titolo">Come nasce un’edizione</h2>
          <p className="attacco">Dalla prova d’assaggio alla bottiglia numerata, in tre passi.</p>
        </Appare>
        <div className="sentiero">
          <svg
            className="sentiero__traccia"
            viewBox="0 0 1000 300"
            preserveAspectRatio="none"
            aria-hidden="true"
            focusable="false"
          >
            <path d="M 20 284 C 160 290 230 214 340 200 S 560 170 660 112 S 880 26 990 18" />
          </svg>
          <ol className="passi">
            {passi.map((passo, i) => (
              <li key={passo.titolo} className="passo">
                <Appare ritardo={i * 0.15}>
                  <span className="passo__segno" aria-hidden="true">
                    {['I', 'II', 'III'][i]}
                  </span>
                  <h3>{passo.titolo}</h3>
                  <p>{passo.testo}</p>
                </Appare>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </section>
  );
}

function Club() {
  return (
    <section className="club" aria-labelledby="club-titolo">
      <div className="contenitore club__griglia">
        <Appare className="club__testo">
          <h2 id="club-titolo">Il club dei soci</h2>
          <p className="attacco">
            Quando un’edizione viene annunciata i soci hanno sette giorni per prenotare, prima dei bar e della vendita
            online. Al massimo due bottiglie a testa, e la loro quota non cala mai, nemmeno se il raccolto delude.
          </p>
          <AzioniClub />
        </Appare>
        <Appare className="club__figura" ritardo={0.15}>
          <img
            className="club__foto"
            src={PRATO.src}
            srcSet={PRATO.srcSet}
            sizes="(min-width: 62rem) 30rem, 90vw"
            width={PRATO.larghezza}
            height={PRATO.altezza}
            alt={PRATO.alt}
            loading="lazy"
            decoding="async"
          />
        </Appare>
      </div>
    </section>
  );
}

/** Nell'apertura: login per chi non è entrato, le proprie aree per chi è entrato. */
function Azioni() {
  const { stato, utente } = useSessione();
  const aree = vociDelMenu(utente).filter((pagina) => pagina !== PAGINE.home);

  if (utente) {
    return (
      <div className="apertura__azioni">
        <p className="saluto">Bentornato, {utente.nome ?? 'nella distilleria'}.</p>
        <div className="pulsanti-accesso">
          {aree.map((pagina, indice) => (
            <Link
              key={pagina.percorso}
              to={pagina.percorso}
              className={indice === 0 ? 'pulsante' : 'pulsante pulsante--contorno'}
            >
              {pagina.etichetta}
            </Link>
          ))}
        </div>
      </div>
    );
  }
  if (stato.tipo === 'anonimo' || stato.tipo === 'irraggiungibile') {
    return (
      <div className="apertura__azioni">
        <PulsantiAccesso />
        <p className="apertura__nota">Soci, distillatori e staff entrano con il proprio account Google o GitHub.</p>
      </div>
    );
  }
  return <div className="apertura__azioni" />;
}

function AzioniClub() {
  const { utente } = useSessione();
  if (utente?.ruoli.includes('SOCIO')) {
    return (
      <Link to={PAGINE.soci.percorso} className="pulsante">
        Vai all’area soci
      </Link>
    );
  }
  if (utente) {
    return <p className="club__nota">Il tuo account non è ancora nel club: i soci li aggiunge la distilleria.</p>;
  }
  return (
    <p className="club__nota">
      Sei già socio? <Link to="/accedi">Accedi</Link> con l’account che hai dato alla distilleria.
    </p>
  );
}
