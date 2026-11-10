package it.ginvault.produzione.applicazione;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import it.ginvault.produzione.applicazione.Viste.EsitoIntegrazioneVista;
import it.ginvault.produzione.dominio.ConsegnaArrivata;
import it.ginvault.produzione.dominio.EsitoIntegrazione;
import it.ginvault.produzione.dominio.LottoProposto;
import it.ginvault.produzione.dominio.PianoDiProduzione;
import it.ginvault.produzione.dominio.PianoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * La compensazione di ServizioIntegrazioni nei due casi che con un database
 * vero non si riescono a provocare a comando: il repository e il magazzino
 * sono finti (Mockito), le transazioni non fanno niente.
 *
 *  - due richieste insieme sullo stesso lotto: il salvataggio di questa
 *    fallisce sul vincolo UNIQUE perché l'altra ha già integrato il lotto.
 *    La riserva è dell'altra: NON si annulla, e Marco riceve GIA_INTEGRATO;
 *  - il database è giù: il piano non si salva e nemmeno si rilegge. Si
 *    annulla la riserva lo stesso, e Marco riceve l'errore del database.
 *
 * Il caso normale (il piano non si salva, la riserva si annulla) è in
 * IntegrazioneLottiTest, con PostgreSQL e gRPC veri.
 */
class ServizioIntegrazioniTest {

    private static final UUID PIANO = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID LOTTO = UUID.fromString("a0000000-0000-4000-8000-000000000009");
    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final Instant ADESSO = Instant.parse("2026-08-06T08:30:00Z");
    private static final LottoProposto LOTTO_PROPOSTO = new LottoProposto(LOTTO, "lavanda", ORIGINE,
            new BigDecimal("3.000"), new BigDecimal("3.000"), LocalDate.of(2027, 7, 31), "Fornitore di prova");

    private final PianoRepository piani = mock(PianoRepository.class);
    private final Giacenze giacenze = mock(Giacenze.class);
    private final ServizioIntegrazioni servizio = new ServizioIntegrazioni(piani, giacenze,
            Clock.fixed(ADESSO, ZoneOffset.UTC), mock(PlatformTransactionManager.class));

    /** Quante volte il servizio ha letto il piano: le prime quattro sono il caso d'uso normale. */
    private final AtomicInteger letture = new AtomicInteger();

    @BeforeEach
    void ilMagazzinoHaIlLottoELoRiserva() {
        when(giacenze.disponibilita("lavanda")).thenReturn(List.of(LOTTO_PROPOSTO));
        when(giacenze.riservaLotto(LOTTO, PIANO, new BigDecimal("1.200"))).thenReturn(new Giacenze.Riserva(LOTTO,
                PIANO, ORIGINE, new BigDecimal("1.200"), new BigDecimal("1.800"), false));
    }

    @Test
    void seUnAltraRichiestaHaGiaIntegratoIlLottoLaRiservaNonSiAnnulla() {
        PianoDiProduzione salvatoDallAltra = dopoLaConsegnaDiElena();
        salvatoDallAltra.integraLotto(LOTTO_PROPOSTO, new BigDecimal("1.2"), ADESSO);
        // letture 1-3: controlli e regola; 4: il salvataggio; 5: la rilettura dopo l'errore
        when(piani.findById(PIANO)).thenAnswer(chiamata ->
                Optional.of(letture.incrementAndGet() == 5 ? salvatoDallAltra : dopoLaConsegnaDiElena()));
        when(piani.save(any())).thenThrow(new DataIntegrityViolationException("uk_piano_integrazione_lotto"));

        EsitoIntegrazioneVista esito = servizio.integra(PIANO, LOTTO, new BigDecimal("1.2"));

        assertThat(esito.esito()).isEqualTo(EsitoIntegrazione.GIA_INTEGRATO);
        assertThat(esito.piano().kgDisponibili()).isEqualByComparingTo("12");
        verify(giacenze, never()).annullaRiserva(any(), any());
    }

    @Test
    void conIlDatabaseGiuLaRiservaSiAnnullaAncheSenzaRileggereIlPiano() {
        DataAccessResourceFailureException databaseGiu = new DataAccessResourceFailureException("database giù");
        when(piani.findById(PIANO)).thenAnswer(chiamata -> {
            if (letture.incrementAndGet() == 5) {
                throw databaseGiu;
            }
            return Optional.of(dopoLaConsegnaDiElena());
        });
        when(piani.save(any())).thenThrow(databaseGiu);
        when(giacenze.annullaRiserva(LOTTO, PIANO)).thenReturn(new Giacenze.Annullamento(new BigDecimal("1.200"),
                false));

        assertThatThrownBy(() -> servizio.integra(PIANO, LOTTO, new BigDecimal("1.2"))).isSameAs(databaseGiu);

        verify(giacenze).annullaRiserva(LOTTO, PIANO);
    }

    @Test
    void seAncheLAnnullamentoFallisceMarcoRiceveLErroreDiPartenza() {
        DataAccessResourceFailureException databaseGiu = new DataAccessResourceFailureException("database giù");
        when(piani.findById(PIANO)).thenAnswer(chiamata -> Optional.of(dopoLaConsegnaDiElena()));
        when(piani.save(any())).thenThrow(databaseGiu);
        MagazzinoNonDisponibile magazzinoGiu = new MagazzinoNonDisponibile("Il magazzino non è raggiungibile", null);
        when(giacenze.annullaRiserva(LOTTO, PIANO)).thenThrow(magazzinoGiu);

        assertThatThrownBy(() -> servizio.integra(PIANO, LOTTO, new BigDecimal("1.2")))
                .isSameAs(databaseGiu)
                .satisfies(errore -> assertThat(errore.getSuppressed()).containsExactly(magazzinoGiu));
    }

    /** L'edizione d'estate dopo la consegna di Elena: 10,8 kg su 12, mancano 1,2 kg. Ogni volta un oggetto nuovo. */
    private static PianoDiProduzione dopoLaConsegnaDiElena() {
        PianoDiProduzione piano = PianoDiProduzione.nuovo(UUID.randomUUID(), UUID.randomUUID(), 2, "lavanda",
                new BigDecimal("20"), ORIGINE, new BigDecimal("12"), 600, ADESSO);
        piano.registraConsegna(new ConsegnaArrivata(UUID.randomUUID(), UUID.randomUUID(), "lavanda", ORIGINE,
                new BigDecimal("12"), new BigDecimal("10.8"), "CONFORME", LocalDate.of(2026, 8, 6)), ADESSO);
        return piano;
    }
}
