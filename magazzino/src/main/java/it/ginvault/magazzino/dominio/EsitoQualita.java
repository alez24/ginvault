package it.ginvault.magazzino.dominio;

/**
 * Il controllo qualità fatto all'arrivo della botanica (umidità, profumo,
 * impurità). Un lotto NON_CONFORME resta in magazzino, tracciato, ma non si
 * usa per produrre.
 */
public enum EsitoQualita {
    CONFORME,
    NON_CONFORME
}
