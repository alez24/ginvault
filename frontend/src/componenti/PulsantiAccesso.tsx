import { indirizzoDiAccesso, PROVIDER, ricordaPaginaDopoAccesso } from '../sessione/accesso';

interface Props {
  /** Pagina da riaprire dopo il login (es. quella protetta che l'utente voleva vedere). */
  dopoAccesso?: string;
}

/**
 * "Accedi con Google" / "Accedi con GitHub". Sono link veri e non pulsanti
 * JavaScript: il login OAuth2 è una navigazione del browser verso il gateway,
 * che poi passa al provider.
 */
export function PulsantiAccesso({ dopoAccesso }: Props) {
  return (
    <div className="pulsanti-accesso">
      {PROVIDER.map((provider) => (
        <a
          key={provider.id}
          className="pulsante"
          href={indirizzoDiAccesso(provider.id)}
          onClick={() => {
            if (dopoAccesso) {
              ricordaPaginaDopoAccesso(dopoAccesso);
            }
          }}
        >
          Accedi con {provider.nome}
        </a>
      ))}
    </div>
  );
}
