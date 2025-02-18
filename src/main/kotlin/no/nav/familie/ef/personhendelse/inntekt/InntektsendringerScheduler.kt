package no.nav.familie.ef.personhendelse.inntekt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class InntektsendringerScheduler(
    val inntektsendringerService: InntektsendringerService,
) {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Scheduled(initialDelay = 60 * 1000L, fixedDelay = 365 * 24 * 60 * 60 * 1000L) // Kjører ved oppstart av app
    fun inntektskontroll() {
        logger.info("Cron scheduler starter inntektskontroll")
        inntektsendringerService.beregnInntektsendringerOgLagreIDb()
        // Send med alle som har 10% eller mer i inntektsendring 3 mnd på rad
        inntektsendringerService.opprettOppgaverForInntektsendringer(true)
        inntektsendringerService.opprettOppgaverForNyeVedtakUføretrygd()
    }
}
