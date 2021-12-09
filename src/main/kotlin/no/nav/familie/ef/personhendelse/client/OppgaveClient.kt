package no.nav.familie.ef.personhendelse.client

import no.nav.familie.http.client.AbstractRestClient
import no.nav.familie.kontrakter.felles.Behandlingstema
import no.nav.familie.kontrakter.felles.Ressurs
import no.nav.familie.kontrakter.felles.Tema
import no.nav.familie.kontrakter.felles.getDataOrThrow
import no.nav.familie.kontrakter.felles.oppgave.FinnMappeRequest
import no.nav.familie.kontrakter.felles.oppgave.FinnMappeResponseDto
import no.nav.familie.kontrakter.felles.oppgave.IdentGruppe
import no.nav.familie.kontrakter.felles.oppgave.Oppgave
import no.nav.familie.kontrakter.felles.oppgave.OppgaveIdentV2
import no.nav.familie.kontrakter.felles.oppgave.OppgaveResponse
import no.nav.familie.kontrakter.felles.oppgave.Oppgavetype
import no.nav.familie.kontrakter.felles.oppgave.OpprettOppgaveRequest
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestOperations
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month

@Component
class OppgaveClient(
    @Value("\${FAMILIE_INTEGRASJONER_API_URL}") private val integrasjonUrl: String,
    @Qualifier("azure") restOperations: RestOperations,
) : AbstractRestClient(restOperations, "familie.integrasjoner") {

    val oppgaveUrl = "$integrasjonUrl/api/oppgave"

    fun opprettOppgave(opprettOppgaveRequest: OpprettOppgaveRequest): Long {

        val opprettOppgaveUri = URI.create("$oppgaveUrl/opprett")
        val response =
            postForEntity<Ressurs<OppgaveResponse>>(
                opprettOppgaveUri,
                opprettOppgaveRequest,
                HttpHeaders().medContentTypeJsonUTF8()
            )
        return response.getDataOrThrow().oppgaveId
    }

    fun finnOppgaveMedId(oppgaveId: Long): Oppgave {
        val response = getForEntity<Ressurs<Oppgave>>(
            URI.create("$oppgaveUrl/$oppgaveId"),
            HttpHeaders().medContentTypeJsonUTF8()
        )
        return response.getDataOrThrow()
    }

    fun finnMapper(finnMappeRequest: FinnMappeRequest): FinnMappeResponseDto {
        val response = getForEntity<Ressurs<FinnMappeResponseDto>>(
            UriComponentsBuilder.fromUri(URI.create("$oppgaveUrl/mappe/sok"))
                .queryParams(finnMappeRequest.toQueryParams())
                .build()
                .toUri()
        )
        return response.getDataOrThrow()
    }

    fun oppdaterOppgave(oppgave: Oppgave): Long {
        val response = patchForEntity<Ressurs<OppgaveResponse>>(
            URI.create("$oppgaveUrl".plus("/${oppgave.id!!}/oppdater")),
            oppgave,
            HttpHeaders().medContentTypeJsonUTF8()
        )
        return response.getDataOrThrow().oppgaveId
    }
}

fun defaultOpprettOppgaveRequest(personIdent: String, beskrivelse: String) =
    OpprettOppgaveRequest(
        ident = OppgaveIdentV2(ident = personIdent, gruppe = IdentGruppe.FOLKEREGISTERIDENT),
        saksId = null,
        tema = Tema.ENF,
        oppgavetype = Oppgavetype.VurderLivshendelse,
        fristFerdigstillelse = fristFerdigstillelse(),
        beskrivelse = beskrivelse,
        enhetsnummer = null,
        behandlingstema = Behandlingstema.Overgangsstønad.value,
        tilordnetRessurs = null,
        behandlesAvApplikasjon = "familie-ef-sak"
    )

fun HttpHeaders.medContentTypeJsonUTF8(): HttpHeaders {
    this.add("Content-Type", "application/json;charset=UTF-8")
    this.acceptCharset = listOf(Charsets.UTF_8)
    return this
}

fun fristFerdigstillelse(daysToAdd: Long = 0): LocalDate {
    var date = LocalDateTime.now().plusDays(daysToAdd)

    if (date.hour >= 14) {
        date = date.plusDays(1)
    }

    when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date = date.plusDays(2)
        DayOfWeek.SUNDAY -> date = date.plusDays(1)
        else -> {
        }
    }

    when {
        date.dayOfMonth == 1 && date.month == Month.JANUARY -> date = date.plusDays(1)
        date.dayOfMonth == 1 && date.month == Month.MAY -> date = date.plusDays(1)
        date.dayOfMonth == 17 && date.month == Month.MAY -> date = date.plusDays(1)
        date.dayOfMonth == 25 && date.month == Month.DECEMBER -> date = date.plusDays(2)
        date.dayOfMonth == 26 && date.month == Month.DECEMBER -> date = date.plusDays(1)
    }

    when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date = date.plusDays(2)
        DayOfWeek.SUNDAY -> date = date.plusDays(1)
        else -> {
        }
    }

    return date.toLocalDate()
}
