/**
 * Lo schema del database di Notifiche, creato all'avvio (CREATE ... IF NOT
 * EXISTS): niente Flyway o Liquibase per quattro tabelle. Lo crea
 * DatabaseService con un advisory lock, così due repliche che partono insieme
 * non si scontrano sullo stesso CREATE TABLE.
 *
 * - evento_elaborato: gli eventId già elaborati (consumer idempotente);
 * - edizione e prenotazione_confermata: la PROIEZIONE degli eventi di
 *   Edizioni che serve per scrivere gli avvisi (il nome della botanica, chi ha
 *   quali numeri di bottiglia). Notifiche non chiama Edizioni;
 * - notifica: gli avvisi, uno per destinatario (il "sub" del JWT).
 */
export const SCHEMA = `
CREATE TABLE IF NOT EXISTS evento_elaborato (
  event_id      uuid PRIMARY KEY,
  tipo          varchar(64)  NOT NULL,
  topic         varchar(255) NOT NULL,
  partizione    integer      NOT NULL,
  posizione     bigint       NOT NULL,
  avvisi        integer      NOT NULL DEFAULT 0,
  elaborato_il  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS edizione (
  edizione_id           uuid PRIMARY KEY,
  botanica_di_stagione  varchar(64) NOT NULL,
  creata_il             timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS prenotazione_confermata (
  prenotazione_id   uuid PRIMARY KEY,
  edizione_id       uuid         NOT NULL,
  acquirente        varchar(255) NOT NULL,
  canale            varchar(16)  NOT NULL,
  bottiglie         integer      NOT NULL,
  numeri_bottiglia  integer[]    NOT NULL,
  confermata_il     timestamptz  NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_prenotazione_confermata_edizione
  ON prenotazione_confermata (edizione_id);

CREATE TABLE IF NOT EXISTS notifica (
  id               uuid PRIMARY KEY,
  destinatario     varchar(255) NOT NULL,
  tipo             varchar(32)  NOT NULL,
  titolo           varchar(200) NOT NULL,
  testo            text         NOT NULL,
  edizione_id      uuid,
  prenotazione_id  uuid,
  dati             jsonb        NOT NULL DEFAULT '{}'::jsonb,
  evento_id        uuid         NOT NULL,
  creata_il        timestamptz  NOT NULL,
  letta_il         timestamptz,
  CONSTRAINT uk_notifica_evento_destinatario UNIQUE (evento_id, destinatario)
);
CREATE INDEX IF NOT EXISTS ix_notifica_destinatario
  ON notifica (destinatario, creata_il DESC);
CREATE INDEX IF NOT EXISTS ix_notifica_non_lette
  ON notifica (destinatario) WHERE letta_il IS NULL;
`;

/** La chiave dell'advisory lock che mette in fila la creazione dello schema. */
export const BLOCCO_SCHEMA = 8086;

/** Il canale di LISTEN/NOTIFY per le notifiche appena salvate (vedi AscoltoNotifiche). */
export const CANALE_NOTIFICHE = 'notifiche_nuove';
