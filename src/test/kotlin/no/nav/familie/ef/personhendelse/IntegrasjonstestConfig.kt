package no.nav.familie.ef.personhendelse

import io.mockk.every
import io.mockk.mockk
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile

@Profile("integrasjonstest")
@Configuration
class IntegrasjonstestConfig {

    @Bean
    @Primary
    fun oAuth2AccessTokenServiceMock(): OAuth2AccessTokenService {
        val tokenMockService = mockk<OAuth2AccessTokenService>()
        every { tokenMockService.getAccessToken(any()) }
            .returns(OAuth2AccessTokenResponse("Mock-token-response", 60, 60, emptyMap()))
        return tokenMockService
    }
}
