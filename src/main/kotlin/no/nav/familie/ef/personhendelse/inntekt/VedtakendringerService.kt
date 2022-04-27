package no.nav.familie.ef.personhendelse.inntekt

import no.nav.familie.ef.personhendelse.client.ArbeidsfordelingClient
import no.nav.familie.ef.personhendelse.client.OppgaveClient
import no.nav.familie.ef.personhendelse.client.SakClient
import no.nav.familie.ef.personhendelse.client.fristFerdigstillelse
import no.nav.familie.ef.personhendelse.handler.PersonhendelseService
import no.nav.familie.ef.personhendelse.inntekt.vedtak.EfVedtakRepository
import no.nav.familie.kontrakter.felles.Behandlingstema
import no.nav.familie.kontrakter.felles.Tema
import no.nav.familie.kontrakter.felles.ef.StønadType
import no.nav.familie.kontrakter.felles.oppgave.IdentGruppe
import no.nav.familie.kontrakter.felles.oppgave.OppgaveIdentV2
import no.nav.familie.kontrakter.felles.oppgave.Oppgavetype
import no.nav.familie.kontrakter.felles.oppgave.OpprettOppgaveRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.YearMonth

@Service
class VedtakendringerService(
    val efVedtakRepository: EfVedtakRepository,
    val inntektClient: InntektClient,
    val oppgaveClient: OppgaveClient,
    val sakClient: SakClient,
    val arbeidsfordelingClient: ArbeidsfordelingClient,
    val inntektsendringerService: InntektsendringerService,
    val personhendelseService: PersonhendelseService
) {

    val logger: Logger = LoggerFactory.getLogger(this::class.java)
    val secureLogger: Logger = LoggerFactory.getLogger("secureLogger")

    @Async
    fun beregnNyeVedtakOgLagOppgave(skalOppretteOppgave: Boolean = false) {
        val personerMedVedtakList = efVedtakRepository.hentPersonerMedVedtakIkkeBehandlet().map { it.personIdent }

        personerMedVedtakList.chunked(500)
            .map { sakClient.hentForventetInntektForIdenter(it) }
            .flatMap { it.entries }
            .forEach { (ident, inntekt) ->
                val response = hentInntektshistorikk(ident)
                if (response != null) {
                    opprettOppgaveHvisNyttVedtakEllerEndretInntekt(ident, response, inntekt, skalOppretteOppgave)
                }
            }
    }

    private fun opprettOppgaveHvisNyttVedtakEllerEndretInntekt(
        ident: String,
        response: InntektshistorikkResponse,
        inntekt: Int?,
        skalOppretteOppgave: Boolean
    ) {
        val harNyeVedtak = harNyeVedtak(response)
        val harEndretInntekt = inntektsendringerService.harEndretInntekt(response, ident, inntekt)

        if (harNyeVedtak || harEndretInntekt) {
            if (skalOppretteOppgave) {
                opprettOppgave(harNyeVedtak, harEndretInntekt, ident)
            } else {
                secureLogger.info("Ville opprettet oppgave for $ident harNyeVedtak: $harNyeVedtak harEndretInntekt: $harEndretInntekt")
            }
        }
        if (skalOppretteOppgave) efVedtakRepository.oppdaterAarMaanedProsessert(ident)
    }

    fun harNyeVedtak(inntektshistorikkResponse: InntektshistorikkResponse): Boolean {
        // hent alle registrerte vedtak som var på personen sist beregning
        val nyesteRegistrerteInntekt =
            inntektshistorikkResponse.inntektForMåned(YearMonth.now().minusMonths(1).toString())
        val nestNyesteRegistrerteInntekt =
            inntektshistorikkResponse.inntektForMåned(YearMonth.now().minusMonths(2).toString())

        val antallOffentligeYtelserForNyeste = antallOffentligeYtelser(nyesteRegistrerteInntekt)
        val antallOffentligeYtelserForNestNyeste = antallOffentligeYtelser(nestNyesteRegistrerteInntekt)

        return antallOffentligeYtelserForNyeste > antallOffentligeYtelserForNestNyeste
    }

    private fun antallOffentligeYtelser(nyesteRegistrerteInntekt: List<InntektVersjon>?): Int {
        val offentligYtelseInntekt = nyesteRegistrerteInntekt?.filter {
            it.arbeidsInntektInformasjon.inntektListe?.any {
                it.inntektType == InntektType.YTELSE_FRA_OFFENTLIGE &&
                    it.beskrivelse != "overgangsstoenadTilEnsligMorEllerFarSomBegynteAaLoepe1April2014EllerSenere"
            }
                ?: false
        }

        val nyesteVersjon = offentligYtelseInntekt?.maxOfOrNull { it.versjon }

        val inntektListe =
            offentligYtelseInntekt?.firstOrNull { it.versjon == nyesteVersjon }?.arbeidsInntektInformasjon?.inntektListe
        return inntektListe?.filter {
            it.inntektType == InntektType.YTELSE_FRA_OFFENTLIGE &&
                it.beskrivelse != "overgangsstoenadTilEnsligMorEllerFarSomBegynteAaLoepe1April2014EllerSenere" &&
                it.tilleggsinformasjon?.tilleggsinformasjonDetaljer?.detaljerType != "ETTERBETALINGSPERIODE"
        }?.groupBy { it.beskrivelse }?.size ?: 0
    }

    private fun hentInntektshistorikk(fnr: String): InntektshistorikkResponse? {
        try {
            return inntektClient.hentInntektshistorikk(
                fnr,
                YearMonth.now().minusYears(1),
                null
            )
        } catch (e: Exception) {
            secureLogger.warn("Feil ved kall mot inntektskomponenten ved kall mot person $fnr. Message: ${e.message} Cause: ${e.cause}")
        }
        return null
    }

    private fun opprettOppgave(
        harNyeVedtak: Boolean,
        harEndretInntekt: Boolean,
        personident: String,
        stønadType: StønadType = StønadType.OVERGANGSSTØNAD
    ) {
        val oppgavetekst = lagOppgavetekst(harNyeVedtak, harEndretInntekt)
        secureLogger.info("$personident - $oppgavetekst")
        val enhetsnummer =
            arbeidsfordelingClient.hentArbeidsfordelingEnhetId(personident)
        val oppgaveId = oppgaveClient.opprettOppgave(
            OpprettOppgaveRequest(
                ident = OppgaveIdentV2(
                    ident = personident,
                    gruppe = IdentGruppe.FOLKEREGISTERIDENT
                ),
                saksId = null,
                tema = Tema.ENF,
                oppgavetype = Oppgavetype.VurderKonsekvensForYtelse,
                fristFerdigstillelse = fristFerdigstillelse(),
                beskrivelse = oppgavetekst,
                enhetsnummer = enhetsnummer,
                behandlingstema = stønadType.tilBehandlingstemaValue(),
                tilordnetRessurs = null,
                behandlesAvApplikasjon = "familie-ef-sak"
            )
        )
        secureLogger.info("Opprettet oppgave for person $personident med id: $oppgaveId")
        try {
            personhendelseService.leggOppgaveIMappe(oppgaveId)
        } catch (e: Exception) {
            logger.error("Feil under knytning av mappe til oppgave - se securelogs for stacktrace")
            secureLogger.error("Feil under knytning av mappe til oppgave", e)
        }
    }

    private fun lagOppgavetekst(harNyeVedtak: Boolean, harEndretInntekt: Boolean): String {
        if (harNyeVedtak && harEndretInntekt) {
            return "Person kan ha nye vedtak og endret inntekt."
        } else if (harNyeVedtak) {
            return "Person kan ha nye vedtak."
        } else {
            return "Person kan ha endret inntekt"
        }
    }
}

fun StønadType.tilBehandlingstemaValue(): String {
    return when (this) {
        StønadType.OVERGANGSSTØNAD -> Behandlingstema.Overgangsstønad.value
        StønadType.BARNETILSYN -> Behandlingstema.Barnetilsyn.value
        StønadType.SKOLEPENGER -> Behandlingstema.Skolepenger.value
    }
}
