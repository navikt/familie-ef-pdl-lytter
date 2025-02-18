package no.nav.familie.ef.personhendelse.inntekt

import no.nav.familie.ef.personhendelse.client.ArbeidsfordelingClient
import no.nav.familie.ef.personhendelse.client.ForventetInntektForPerson
import no.nav.familie.ef.personhendelse.client.OppgaveClient
import no.nav.familie.ef.personhendelse.client.SakClient
import no.nav.familie.ef.personhendelse.client.fristFerdigstillelse
import no.nav.familie.kontrakter.felles.Tema
import no.nav.familie.kontrakter.felles.oppgave.IdentGruppe
import no.nav.familie.kontrakter.felles.oppgave.OppgaveIdentV2
import no.nav.familie.kontrakter.felles.oppgave.Oppgavetype
import no.nav.familie.kontrakter.felles.oppgave.OpprettOppgaveRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Service
class InntektsendringerService(
    val oppgaveClient: OppgaveClient,
    val sakClient: SakClient,
    val arbeidsfordelingClient: ArbeidsfordelingClient,
    val inntektsendringerRepository: InntektsendringerRepository,
    val inntektClient: InntektClient,
) {
    private val grunnbeløp = 124_028
    private val halvtGrunnbeløpMånedlig = (grunnbeløp / 2) / 12
    private val maxInntekt = Math.floor((grunnbeløp * 5.5) / 1000L) * 1000L // Ingen utbetaling av OS ved inntekt på over 5.5 rundet ned til nærmeste 1000

    val logger: Logger = LoggerFactory.getLogger(this::class.java)
    val secureLogger: Logger = LoggerFactory.getLogger("secureLogger")

    @Async
    fun beregnInntektsendringerAsync() {
        beregnInntektsendringerOgLagreIDb()
    }

    fun loggAutomatiskeRevurderinger() {
        val inntektsendringer = inntektsendringerRepository.hentBrukereMedInntektsendringOver10Prosent()
        val automatiskRevurderingKandidater = inntektsendringer.filter { !it.harNyeVedtak && it.harStabilInntekt() }
        sakClient.revurderAutomatisk(automatiskRevurderingKandidater.map { it.personIdent })
    }

    fun beregnInntektsendringerOgLagreIDb() {
        logger.info("Starter beregning av inntektsendringer")
        val personerMedAktivStønad = sakClient.hentPersonerMedAktivStønadIkkeManueltRevurdertSisteMåneder(0)
        inntektsendringerRepository.clearInntektsendringer()
        logger.info("Antall personer med aktiv stønad: ${personerMedAktivStønad.size}")
        var counter = 0
        personerMedAktivStønad.chunked(500).forEach {
            sakClient.hentForventetInntektForIdenter(it).forEach { forventetInntektForPerson ->
                val response = hentInntekt(forventetInntektForPerson.personIdent)
                if (response != null &&
                    forventetInntektForPerson.forventetInntektForrigeMåned != null &&
                    forventetInntektForPerson.forventetInntektToMånederTilbake != null
                ) {
                    lagreInntektsendringForPerson(forventetInntektForPerson, response)
                }
                counter++
                if (counter % 500 == 0) {
                    logger.info("Antall personer sjekket: $counter (av ${personerMedAktivStønad.size}")
                }
            }
        }

        logger.info("Vedtak- og inntektsendringer ferdig")
    }

    private fun lagreInntektsendringForPerson(
        forventetInntektForPerson: ForventetInntektForPerson,
        response: HentInntektListeResponse,
    ) {
        val nyeVedtak = VedtakendringerUtil.nyeVedtak(response)
        val endretInntekt = beregnEndretInntekt(response, forventetInntektForPerson)
        inntektsendringerRepository.lagreVedtakOgInntektsendringForPersonIdent(
            forventetInntektForPerson.personIdent,
            nyeVedtak?.isNotEmpty() ?: false,
            nyeVedtak?.joinToString(),
            endretInntekt,
            VedtakendringerUtil.offentligeYtelserForNyesteMåned(response)?.joinToString(),
        )
    }

    fun opprettOppgaverForInntektsendringer(skalOppretteOppgave: Boolean): Int {
        val inntektsendringer = inntektsendringerRepository.hentInntektsendringerSomSkalHaOppgave()
        if (skalOppretteOppgave) {
            inntektsendringer.forEach {
                opprettOppgaveForInntektsendring(it, lagOppgavetekstForInntektsendring(it))
            }
        } else {
            logger.info("Ville opprettet inntektsendring-oppgave for ${inntektsendringer.size} personer")
        }
        return inntektsendringer.size
    }

    fun opprettOppgaverForNyeVedtakUføretrygd() {
        val nyeUføretrygdVedtak = inntektsendringerRepository.hentInntektsendringerForUføretrygd()
        nyeUføretrygdVedtak.forEach {
            opprettOppgaveForInntektsendring(it, lagOppgavetekstVedNyYtelseUføretrygd())
        }
    }

    private fun hentInntekt(fnr: String): HentInntektListeResponse? {
        try {
            return inntektClient.hentInntekt(
                fnr,
                YearMonth.now().minusMonths(5),
                YearMonth.now(),
            )
        } catch (e: Exception) {
            secureLogger.warn("Feil ved kall mot inntektskomponenten ved kall mot person $fnr. Message: ${e.message} Cause: ${e.cause}")
        }
        return null
    }

    private fun opprettOppgaveForInntektsendring(
        inntektOgVedtakEndring: InntektOgVedtakEndring,
        beskrivelse: String,
    ) {
        // val oppgavetekst = lagOppgavetekst(harNyeVedtak, inntektsendringToMånederInntekt >= 10 && inntektsendringForrigeMåned >= 10)
        val oppgaveId =
            oppgaveClient.opprettOppgave(
                OpprettOppgaveRequest(
                    ident =
                        OppgaveIdentV2(
                            ident = inntektOgVedtakEndring.personIdent,
                            gruppe = IdentGruppe.FOLKEREGISTERIDENT,
                        ),
                    saksId = null,
                    tema = Tema.ENF,
                    oppgavetype = Oppgavetype.VurderInntekt,
                    fristFerdigstillelse = fristFerdigstillelse(),
                    beskrivelse = beskrivelse,
                    enhetsnummer = arbeidsfordelingClient.hentArbeidsfordelingEnhetId(inntektOgVedtakEndring.personIdent),
                    behandlingstema = null, // Gjelder-feltet i Gosys
                    tilordnetRessurs = null,
                    behandlesAvApplikasjon = null,
                ),
            )
        secureLogger.info("Opprettet oppgave for person ${inntektOgVedtakEndring.personIdent} med id: $oppgaveId")
        oppgaveClient.leggOppgaveIMappe(oppgaveId, "63") // Inntektskontroll
    }

    fun lagOppgavetekstForInntektsendring(inntektOgVedtakEndring: InntektOgVedtakEndring): String {
        val totalFeilutbetaling =
            inntektOgVedtakEndring.inntektsendringTreMånederTilbake.feilutbetaling +
                inntektOgVedtakEndring.inntektsendringToMånederTilbake.feilutbetaling +
                inntektOgVedtakEndring.inntektsendringForrigeMåned.feilutbetaling

        val årMånedProsessert = YearMonth.from(inntektOgVedtakEndring.prosessertTid)

        val periodeTekst =
            "FOM ${årMånedProsessert.minusMonths(3).norskFormat()} - TOM ${årMånedProsessert.minusMonths(1).norskFormat()}"
        val oppgavetekst =
            "Uttrekksperiode: $periodeTekst \n" +
                "Beregnet feilutbetaling i uttrekksperioden: ${totalFeilutbetaling.tusenskille()} kroner "
        return oppgavetekst
    }

    fun lagOppgavetekstVedNyYtelseUføretrygd(): String = "Bruker har fått utbetalt uføretrygd. Vurder samordning."

    private fun YearMonth.norskFormat() = this.format(DateTimeFormatter.ofPattern("MM.yyyy"))

    private fun Int.tusenskille() = String.format("%,d", this).replace(",", " ")

    fun beregnEndretInntekt(
        inntektResponse: HentInntektListeResponse,
        forventetInntektForPerson: ForventetInntektForPerson,
    ): Inntektsendring {
        // hent alle registrerte vedtak som var på personen sist beregning
        val nyesteRegistrerteInntekt =
            inntektResponse.arbeidsinntektMåned?.filter { it.årMåned == YearMonth.now().minusMonths(1) }
        val nestNyesteRegistrerteInntekt =
            inntektResponse.arbeidsinntektMåned?.filter { it.årMåned == YearMonth.now().minusMonths(2) }
        val inntektTreMånederTilbake =
            inntektResponse.arbeidsinntektMåned?.filter { it.årMåned == YearMonth.now().minusMonths(3) }
        val inntektFireMånederTilbake =
            inntektResponse.arbeidsinntektMåned?.filter { it.årMåned == YearMonth.now().minusMonths(4) }

        val inntektsendringFireMånederTilbake =
            beregnInntektsendring(
                inntektFireMånederTilbake,
                forventetInntektForPerson.personIdent,
                forventetInntektForPerson.forventetInntektTreMånederTilbake,
            )

        val inntektsendringTreMånederTilbake =
            beregnInntektsendring(
                inntektTreMånederTilbake,
                forventetInntektForPerson.personIdent,
                forventetInntektForPerson.forventetInntektTreMånederTilbake,
            )
        val inntektsendringToMånederTilbake =
            beregnInntektsendring(
                nestNyesteRegistrerteInntekt,
                forventetInntektForPerson.personIdent,
                forventetInntektForPerson.forventetInntektToMånederTilbake,
            )
        val inntektsendringForrigeMåned =
            beregnInntektsendring(
                nyesteRegistrerteInntekt,
                forventetInntektForPerson.personIdent,
                forventetInntektForPerson.forventetInntektForrigeMåned,
            )

        return Inntektsendring(
            fireMånederTilbake = inntektsendringFireMånederTilbake,
            treMånederTilbake = inntektsendringTreMånederTilbake,
            toMånederTilbake = inntektsendringToMånederTilbake,
            forrigeMåned = inntektsendringForrigeMåned,
        )
    }

    private fun beregnInntektsendring(
        nyesteRegistrerteInntekt: List<ArbeidsinntektMåned>?,
        ident: String,
        forventetInntekt: Int?,
    ): BeregningResultat {
        if (forventetInntekt == null ||
            nyesteRegistrerteInntekt.isNullOrEmpty() ||
            nyesteRegistrerteInntekt
                .firstOrNull()
                ?.arbeidsInntektInformasjon
                ?.inntektListe
                .isNullOrEmpty()
        ) {
            secureLogger.warn("Ingen gjeldende inntekt funnet på person $ident har personen løpende stønad?")
            return BeregningResultat(0, 0, 0)
        }

        if (forventetInntekt > maxInntekt) return BeregningResultat(0, 0, 0) // Ignorer alle med over 652000 i årsinntekt, da de har 0 i utbetaling.
        val månedligForventetInntekt = (forventetInntekt / 12)

        val inntektListe = nyesteRegistrerteInntekt.firstOrNull()?.arbeidsInntektInformasjon?.inntektListe ?: emptyList()
        val samletInntekt =
            inntektListe
                .filterNot {
                    ignorerteYtelserOgUtbetalinger.contains(it.beskrivelse)
                }.sumOf { it.beløp }

        if (samletInntekt < halvtGrunnbeløpMånedlig) return BeregningResultat(0, 0, 0)

        secureLogger.info(
            "Samlet inntekt: $samletInntekt - månedlig forventet inntekt: $månedligForventetInntekt  (årlig: $forventetInntekt) for person $ident",
        )
        val inntektsendringProsent = (((samletInntekt - månedligForventetInntekt) / månedligForventetInntekt.toDouble()) * 100).toInt()
        val endretInntektBeløp = samletInntekt - månedligForventetInntekt
        val feilutbetaling = beregnFeilutbetalingForMåned(månedligForventetInntekt, samletInntekt)
        if (månedligForventetInntekt == 0) return BeregningResultat(endretInntektBeløp, 100, feilutbetaling) // Prioriterer personer registrert med uredusert stønad, men har samlet inntekt over 1/2 G
        return BeregningResultat(endretInntektBeløp, inntektsendringProsent, feilutbetaling)
    }

    // Ignorterte ytelser: Alle uføre går under annet regelverk (samordning) og skal derfor ignoreres.
    val ignorerteYtelserOgUtbetalinger =
        listOf(
            "overgangsstoenadTilEnsligMorEllerFarSomBegynteAaLoepe1April2014EllerSenere",
            "ufoeretrygd",
            "ufoereytelseEtteroppgjoer",
            "ufoerepensjonFraAndreEnnFolketrygden",
        )
}

data class Inntektsendring(
    val fireMånederTilbake: BeregningResultat,
    val treMånederTilbake: BeregningResultat,
    val toMånederTilbake: BeregningResultat,
    val forrigeMåned: BeregningResultat,
) {
    fun harEndretInntekt() = treMånederTilbake.prosent >= 10 && toMånederTilbake.prosent >= 10 && forrigeMåned.prosent >= 10
}

data class BeregningResultat(
    val beløp: Int,
    val prosent: Int,
    val feilutbetaling: Int,
)
