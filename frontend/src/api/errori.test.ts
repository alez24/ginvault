import { describe, expect, it } from 'vitest';

import { campiNonValidi, nomeCampo, rifiutoIntegrazione, rimedio } from './errori';
import { ErroreApi } from './http';

describe('campi di un 400', () => {
  it('legge la proprietà "campi" del Problem Detail e scarta ciò che non è un campo', () => {
    const errore = new ErroreApi(400, 'Richiesta non valida', 'Alcuni campi non sono validi', {
      title: 'Richiesta non valida',
      campi: [{ campo: 'nome', messaggio: 'il nome è obbligatorio' }, { campo: 3 }, null],
    });
    expect(campiNonValidi(errore)).toEqual([{ campo: 'nome', messaggio: 'il nome è obbligatorio' }]);
    expect(campiNonValidi(new ErroreApi(409, 'Quota esaurita'))).toEqual([]);
  });

  it('dà ai campi un nome leggibile', () => {
    expect(nomeCampo('botanicaDisponibileKg')).toBe('Kg di botanica disponibili');
    expect(nomeCampo('dosi[1].grammiPerBottiglia')).toBe('Dose 2, grammi per bottiglia');
    expect(nomeCampo('dosi[0].botanica')).toBe('Dose 1, botanica');
    expect(nomeCampo('dosi[0]')).toBe('Dose 1');
    expect(nomeCampo('quote[ONLINE]')).toBe('Quota vendita online');
    expect(nomeCampo('quote[NEGOZIO]')).toBe('Quota NEGOZIO');
    expect(nomeCampo('altro')).toBe('altro');
  });
});

describe('cosa suggerire', () => {
  it('riprovare se il servizio è fermo o lento', () => {
    expect(rimedio(new ErroreApi(503, 'Ricette non disponibile'))).toBe('riprova');
    expect(rimedio(new ErroreApi(0, 'Servizio non raggiungibile'))).toBe('riprova');
    expect(rimedio(new ErroreApi(504, 'Servizio troppo lento'))).toBe('riprova');
  });

  it('ricaricare dopo una modifica concorrente, rientrare se la sessione è scaduta', () => {
    expect(rimedio(new ErroreApi(409, 'Modifica concorrente'))).toBe('ricarica');
    expect(rimedio(new ErroreApi(401, 'Accesso richiesto'))).toBe('accedi');
  });

  it('niente per le regole di dominio: basta il messaggio del servizio', () => {
    expect(rimedio(new ErroreApi(409, 'Tiratura non riducibile'))).toBeUndefined();
    expect(rimedio(new ErroreApi(422, 'Formulazione non utilizzabile'))).toBeUndefined();
  });
});

describe('rifiuto dell’integrazione di un lotto', () => {
  it('l’origine diversa, con le due origini', () => {
    const errore = new ErroreApi(409, 'Integrazione rifiutata', 'origine diversa: l’edizione deve restare fedele alla sua origine', {
      title: 'Integrazione rifiutata',
      motivo: 'ORIGINE_DIVERSA',
      origineRichiesta: 'Alta Val Varaita (Alpi Cozie)',
      origineLotto: 'Campi GinVault (Saluzzo, 400 m)',
    });
    expect(rifiutoIntegrazione(errore)).toEqual({
      motivo: 'ORIGINE_DIVERSA',
      origineRichiesta: 'Alta Val Varaita (Alpi Cozie)',
      origineLotto: 'Campi GinVault (Saluzzo, 400 m)',
    });
  });

  it('gli altri motivi, compreso il rifiuto del magazzino', () => {
    const errore = new ErroreApi(409, 'Riserva rifiutata dal magazzino', 'kg non disponibili', { motivo: 'RISERVA_RIFIUTATA' });
    expect(rifiutoIntegrazione(errore)).toEqual({ motivo: 'RISERVA_RIFIUTATA', origineRichiesta: undefined, origineLotto: undefined });
  });

  it('non è un rifiuto: il magazzino lento, una modifica concorrente, un motivo sconosciuto', () => {
    expect(rifiutoIntegrazione(new ErroreApi(503, 'Magazzino non disponibile'))).toBeUndefined();
    expect(rifiutoIntegrazione(new ErroreApi(409, 'Modifica concorrente'))).toBeUndefined();
    expect(rifiutoIntegrazione(new ErroreApi(409, 'Integrazione rifiutata', undefined, { motivo: 'NUOVO' }))).toBeUndefined();
  });

  it('i campi di magazzino e produzione hanno un nome', () => {
    expect(nomeCampo('kgArrivati')).toBe('Kg arrivati');
    expect(nomeCampo('esitoQualita')).toBe('Controllo qualità');
    expect(nomeCampo('kg')).toBe('Kg da integrare');
  });
});
