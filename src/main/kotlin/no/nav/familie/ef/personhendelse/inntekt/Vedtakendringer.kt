package no.nav.familie.ef.personhendelse.inntekt

import no.nav.familie.ef.personhendelse.client.OppgaveClient
import no.nav.familie.ef.personhendelse.client.defaultOpprettOppgaveRequest
import no.nav.familie.ef.personhendelse.inntekt.vedtak.EfVedtakRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.YearMonth

@Component
class Vedtakendringer(
    val vedtakRepository: EfVedtakRepository,
    val inntektClient: InntektClient,
    val oppgaveClient: OppgaveClient
) {

    val secureLogger: Logger = LoggerFactory.getLogger("secureLogger")

    fun beregnNyeVedtakOgLagOppgave() {
        val personerMedVedtakList = vedtakRepository.hentAllePersonerMedVedtak()

        secureLogger.info("Antall personer med aktive vedtak: ${personerMedVedtakList.size}")

        for (ensligForsørgerVedtakhendelse in personerMedVedtakList) {
            val response = inntektClient.hentInntektshistorikk(
                ensligForsørgerVedtakhendelse.personIdent,
                YearMonth.now().minusYears(1),
                null
            )
            if (harNyeVedtak(response)) {
                secureLogger.info("Person med behandlingId ${ensligForsørgerVedtakhendelse.behandlingId} kan ha nye vedtak. Oppretter oppgave.")
                val oppgaveId = oppgaveClient.opprettOppgave(
                    defaultOpprettOppgaveRequest(
                        ensligForsørgerVedtakhendelse.personIdent,
                        "Sjekk om bruker har fått nytt vedtak"
                    )
                )
                secureLogger.info("Oppgave opprettet med id: $oppgaveId")
            }
        }
    }

    fun harNyeVedtak(inntektshistorikkResponse: InntektshistorikkResponse): Boolean {
        // hent alle registrerte vedtak som var på personen sist beregning
        val nyesteRegistrerteInntekt = inntektshistorikkResponse.inntektForMåned(YearMonth.now().minusMonths(1).toString())
        val nestNyesteRegistrerteInntekt = inntektshistorikkResponse.inntektForMåned(YearMonth.now().minusMonths(2).toString())

        val antallOffentligeYtelserForNyeste = antallOffentligeYtelser(nyesteRegistrerteInntekt)
        val antallOffentligeYtelserForNestNyeste = antallOffentligeYtelser(nestNyesteRegistrerteInntekt)

        return antallOffentligeYtelserForNyeste > antallOffentligeYtelserForNestNyeste
    }

    private fun antallOffentligeYtelser(nyesteRegistrerteInntekt: List<InntektVersjon>?): Int {
        val nyesteVersjon = nyesteRegistrerteInntekt?.maxOf { it.versjon }

        val inntektListe =
            nyesteRegistrerteInntekt?.firstOrNull { it.versjon == nyesteVersjon }?.arbeidsInntektInformasjon?.inntektListe
        return inntektListe?.filter { it.inntektType == InntektType.YTELSE_FRA_OFFENTLIGE }?.size ?: 0
    }
}
