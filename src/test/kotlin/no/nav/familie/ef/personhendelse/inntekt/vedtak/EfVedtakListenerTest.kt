package no.nav.familie.ef.personhendelse.inntekt.vedtak

import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.verify
import no.nav.familie.ef.personhendelse.inntekt.Vedtakendringer
import no.nav.familie.kontrakter.felles.ef.EnsligForsørgerVedtakhendelse
import no.nav.familie.kontrakter.felles.ef.StønadType
import no.nav.familie.kontrakter.felles.objectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment

class EfVedtakListenerTest {

    @MockK
    lateinit var efVedtakRepository: EfVedtakRepository

    @MockK
    lateinit var vedtakendringer: Vedtakendringer

    @MockK(relaxed = true)
    lateinit var ack: Acknowledgment

    private lateinit var efVedtakListener: EfVedtakListener

    @BeforeEach
    internal fun setUp() {
        MockKAnnotations.init(this)
        efVedtakListener = EfVedtakListener(efVedtakRepository, vedtakendringer)
        clearAllMocks()
    }

    @Test
    fun `send inn gyldig consumer record, forvent lagring av efvedtakhendelse til db`() {

        every {
            efVedtakRepository.lagreEfVedtakshendelse(any())
        } just Runs
        every {
            vedtakendringer.beregnNyeVedtakOgLagOppgave()
        } just Runs
        val efVedtakshendelse = EnsligForsørgerVedtakhendelse(1L, "personIdent", StønadType.OVERGANGSSTØNAD)
        val hendelse = ConsumerRecord("topic", 1, 1, "key", objectMapper.writeValueAsString(efVedtakshendelse))
        efVedtakListener.listen(hendelse)
        verify(exactly = 1) {
            efVedtakRepository.lagreEfVedtakshendelse(efVedtakshendelse)
        }
    }
}
