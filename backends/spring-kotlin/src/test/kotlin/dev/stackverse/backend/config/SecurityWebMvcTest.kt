package dev.stackverse.backend.config

import dev.stackverse.backend.account.UserAccount
import dev.stackverse.backend.account.UserAccountService
import dev.stackverse.backend.account.UserAccountStatus
import dev.stackverse.backend.common.ApiExceptionHandler
import dev.stackverse.backend.message.LanguageResolver
import dev.stackverse.backend.message.MessageController
import dev.stackverse.backend.message.MessageLocalizer
import dev.stackverse.backend.message.MessageRepository
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.web.MeController
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(controllers = [MeController::class, MessageController::class])
@Import(SecurityConfig::class, ApiExceptionHandler::class)
@ExtendWith(OutputCaptureExtension::class)
class SecurityWebMvcTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var messageRepository: MessageRepository

    @MockitoBean
    lateinit var messageService: MessageService

    @MockitoBean
    lateinit var languageResolver: LanguageResolver

    @MockitoBean
    lateinit var accountService: UserAccountService

    @MockitoBean
    lateinit var localizer: MessageLocalizer

    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `bearer token derives identity and application roles from decoded claims`() {
        val tokenValue = "valid-admin-token"
        `when`(jwtDecoder.decode(tokenValue)).thenReturn(
            jwt(tokenValue, "alice", listOf("default-roles-stackverse", "moderator", "admin")),
        )
        `when`(accountService.recordSeen("alice")).thenReturn(activeAccount("alice"))

        mockMvc.perform(
            get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer $tokenValue"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.name").value("Alice User"))
            .andExpect(jsonPath("$.email").value("alice@stackverse.local"))
            .andExpect(jsonPath("$.roles", containsInAnyOrder("moderator", "admin")))
            .andExpect { result -> check(result.response.getHeader(HttpHeaders.SET_COOKIE) == null) }

        verify(accountService).recordSeen("alice")
    }

    @Test
    fun `roleless bearer token gets a forbidden problem before an admin mutation`() {
        val tokenValue = "valid-regular-token"
        `when`(jwtDecoder.decode(tokenValue)).thenReturn(jwt(tokenValue, "regular", emptyList()))
        `when`(accountService.recordSeen("regular")).thenReturn(activeAccount("regular"))

        mockMvc.perform(
            post("/api/v1/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $tokenValue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"ui.action.save","language":"en","text":"Save"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Forbidden"))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.detail").value("You do not have the role required for this operation."))

        verifyNoInteractions(messageService)
    }

    @Test
    fun `invalid bearer token returns a sanitized unauthorized problem`(output: CapturedOutput) {
        val tokenValue = "sensitive-invalid-token-marker"
        `when`(jwtDecoder.decode(tokenValue)).thenThrow(
            JwtValidationException(
                "signature rejected",
                listOf(OAuth2Error("invalid_token", "signature rejected", null)),
            ),
        )

        mockMvc.perform(
            get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer $tokenValue"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value("Missing or invalid bearer token."))

        check(output.all.contains("jwt_validation_failed")) { "expected the stable JWT failure event in captured logs" }
        check(output.all.contains("invalid_token")) { "expected the stable OAuth error code in captured logs" }
        check(!output.all.contains(tokenValue)) { "raw bearer tokens must never be logged" }
        verifyNoInteractions(accountService)
    }

    private fun jwt(tokenValue: String, username: String, roles: List<String>): Jwt =
        Jwt.withTokenValue(tokenValue)
            .header("alg", "none")
            .subject("subject-of-$username")
            .claim("preferred_username", username)
            .claim("realm_access", mapOf("roles" to roles))
            .claim("name", "${username.replaceFirstChar { it.uppercase() }} User")
            .claim("email", "$username@stackverse.local")
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
            .build()

    private fun activeAccount(username: String) = UserAccount(
        username = username,
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        status = UserAccountStatus.ACTIVE,
        blockedReason = null,
    )
}
