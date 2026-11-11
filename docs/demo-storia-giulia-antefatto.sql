-- =============================================================================
-- Antefatto della demo di Giulia (docs/demo-storia-giulia.md, passo 3):
-- prima di lei 56 soci hanno già confermato 111 bottiglie dell'edizione, 55
-- prenotazioni da 2 e una da 1, con i numeri da 1 a 111. Così le bottiglie di
-- Giulia sono la 112 e la 113, come nella storia.
--
-- Nel browser c'è un solo account, e un socio ne prenota al massimo 2: per
-- questo l'antefatto si scrive direttamente nel database di Edizioni. Tiene
-- insieme le stesse cose che tiene Edizione.conferma:
--   - prenotazione: CONFERMATA, canale SOCI, 45 € a bottiglia;
--   - bottiglia_numerata: un numero per bottiglia, a partire da quelli già
--     dati (bottiglie_numerate);
--   - quota SOCI: prenotate + 111;
--   - edizione: bottiglie_numerate + 111 e una revisione nuova (il lock
--     ottimistico di JPA).
-- Gli eventi PrenotazioneConfermata di questi soci non esistono: Notifiche non
-- li conosce, e l'avviso della tiratura arriva solo a Giulia.
--
-- Uso (PowerShell, cartella del progetto), con l'id dell'edizione appena creata:
--   Get-Content docs\demo-storia-giulia-antefatto.sql | kubectl exec -i -n ginvault deploy/edizioni-db -- psql -U edizioni -d edizioni -v ON_ERROR_STOP=1 -v ed=<id>
-- =============================================================================

BEGIN;

-- L'id passato con -v ed=... , in un'impostazione della transazione: le
-- variabili di psql non entrano nel blocco DO qui sotto
SELECT set_config('antefatto.ed', :'ed', true) AS edizione;

-- Si parte solo se l'edizione è in prelazione e la quota dei soci ha posto
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM edizione WHERE id = current_setting('antefatto.ed')::uuid
                 AND prelazione_fino_al > now()) THEN
    RAISE EXCEPTION 'Edizione % non trovata o non in prelazione', current_setting('antefatto.ed');
  END IF;
  IF (SELECT bottiglie - prenotate FROM quota WHERE edizione_id = current_setting('antefatto.ed')::uuid
      AND canale = 'SOCI') < 111 THEN
    RAISE EXCEPTION 'Nella quota dei soci non ci sono 111 bottiglie libere';
  END IF;
END $$;

CREATE TEMP TABLE antefatto ON COMMIT DROP AS
SELECT gen_random_uuid() AS id,
       'antefatto:socio-' || i AS acquirente,
       b AS bottiglie,
       (SELECT bottiglie_numerate FROM edizione WHERE id = current_setting('antefatto.ed')::uuid)
         + sum(b) OVER (ORDER BY i) - b + 1 AS primo
FROM (SELECT i, CASE WHEN i <= 55 THEN 2 ELSE 1 END AS b FROM generate_series(1, 56) AS i) AS soci;

INSERT INTO prenotazione (id, acquirente, bottiglie, canale, edizione_id, prenotata_il, scade_il,
                          prezzo_per_bottiglia, stato, metodo_pagamento, pagamento_id,
                          pagamento_richiesto_il, riferimento_pagamento, confermata_il, revisione)
SELECT id, acquirente, bottiglie, 'SOCI', current_setting('antefatto.ed')::uuid,
       now() - interval '1 hour', now() - interval '45 minutes',
       45, 'CONFERMATA', 'pm_card_visa', gen_random_uuid(),
       now() - interval '59 minutes', 'pi_antefatto_' || replace(acquirente, 'antefatto:', ''),
       now() - interval '59 minutes', 0
FROM antefatto;

INSERT INTO bottiglia_numerata (prenotazione_id, edizione_id, numero)
SELECT a.id, current_setting('antefatto.ed')::uuid, n
FROM antefatto AS a, generate_series(a.primo, a.primo + a.bottiglie - 1) AS n;

UPDATE quota SET prenotate = prenotate + 111
WHERE edizione_id = current_setting('antefatto.ed')::uuid AND canale = 'SOCI';

UPDATE edizione SET bottiglie_numerate = bottiglie_numerate + 111, revisione = coalesce(revisione, 0) + 1
WHERE id = current_setting('antefatto.ed')::uuid;

SELECT count(*) AS prenotazioni, sum(bottiglie) AS bottiglie,
       (SELECT min(numero) FROM bottiglia_numerata WHERE edizione_id = current_setting('antefatto.ed')::uuid) AS primo,
       (SELECT max(numero) FROM bottiglia_numerata WHERE edizione_id = current_setting('antefatto.ed')::uuid) AS ultimo
FROM antefatto;

COMMIT;
