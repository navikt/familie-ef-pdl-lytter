package no.nav.familie.ef.personhendelse.inntekt.vedtak

import no.nav.familie.ef.personhendelse.IntegrasjonSpringRunnerTest
import no.nav.familie.kontrakter.felles.ef.EnsligForsørgerVedtakhendelse
import no.nav.familie.kontrakter.felles.ef.StønadType
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.YearMonth

class EfVedtakRepositoryTest : IntegrasjonSpringRunnerTest() {

    @Autowired
    lateinit var efVedtakRepository: EfVedtakRepository

    @Test
    fun `lagre og hent EnsligForsørgerVedtakshendelse`() {

        val efVedtakshendelse = EnsligForsørgerVedtakhendelse(1L, "personIdent1", StønadType.OVERGANGSSTØNAD)
        efVedtakRepository.lagreEfVedtakshendelse(efVedtakshendelse)

        val vedtakshendelse = efVedtakRepository.hentAllePersonerMedVedtak().first()
        Assertions.assertThat(vedtakshendelse).isNotNull
        Assertions.assertThat(1L).isEqualTo(vedtakshendelse.behandlingId)
        Assertions.assertThat(StønadType.OVERGANGSSTØNAD).isEqualTo(vedtakshendelse.stønadType)
        Assertions.assertThat(YearMonth.now()).isEqualTo(vedtakshendelse.aarMaanedProsessert)
        Assertions.assertThat(1).isEqualTo(vedtakshendelse.versjon)
    }

    @Test
    fun `lagre og hent ikke behandlede ensligForsørgerVedtakshendelse`() {

        val efVedtakshendelse = EnsligForsørgerVedtakhendelse(2L, "personIdent2", StønadType.OVERGANGSSTØNAD)
        efVedtakRepository.lagreEfVedtakshendelse(efVedtakshendelse)

        val vedtakshendelse = efVedtakRepository.hentPersonerMedVedtakIkkeBehandlet()
        Assertions.assertThat(vedtakshendelse).isNotNull
        Assertions.assertThat(vedtakshendelse.size).isEqualTo(0)
    }

    @Test
    fun `lagre og oppdater ensligForsørgerVedtakshendelse`() {

        val efVedtakshendelse = EnsligForsørgerVedtakhendelse(3L, "personIdent3", StønadType.OVERGANGSSTØNAD)
        efVedtakRepository.lagreEfVedtakshendelse(efVedtakshendelse)
        efVedtakRepository.oppdaterAarMaanedProsessert("personIdent3", YearMonth.of(2021, 12))

        val vedtakshendelseList = efVedtakRepository.hentPersonerMedVedtakIkkeBehandlet()
        Assertions.assertThat(vedtakshendelseList).isNotNull
        Assertions.assertThat(vedtakshendelseList.size).isEqualTo(1)
        Assertions.assertThat(vedtakshendelseList.first().aarMaanedProsessert).isEqualTo(YearMonth.of(2021, 12))
    }
}
