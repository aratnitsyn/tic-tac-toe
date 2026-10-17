package com.github.aratnitsyn.tictactoe.auth.account

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.http.AuthController
import com.github.aratnitsyn.tictactoe.auth.account.security.LoginResponseCipher
import com.github.aratnitsyn.tictactoe.auth.security.AuthErrors
import com.github.aratnitsyn.tictactoe.auth.security.ServiceAuthorization
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID
import javax.crypto.KeyGenerator

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class AuthHttpTest {
    private val mapper = jacksonObjectMapper()
    private val database =
        com.github.aratnitsyn.tictactoe.auth.persistence
            .AuthTestDatabase()
    private val source = database.source

    @org.junit.jupiter.api.AfterEach
    fun closeDatabase() = database.close()

    private val hasher = PasswordHasher()
    private val serviceToken = randomToken()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val controller =
        AuthController(
            RegistrationService(source, hasher, mapper),
            SessionService(source, hasher, LoginResponseCipher("test", { key })),
            mapper,
        )
    private val client =
        WebTestClient
            .bindToController(controller)
            .controllerAdvice(AuthErrors(mapper))
            .webFilter<WebTestClient.ControllerSpec>(ServiceAuthorization(serviceToken))
            .configureClient()
            .responseTimeout(Duration.ofSeconds(10))
            .build()

    @Test
    fun `registration and login return contract fields and never log credentials`() {
        val password = "Aa1!" + randomToken().filter { it.isLetterOrDigit() }.take(8)
        val email = "${UUID.randomUUID()}@example.com"
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(captured)
        try {
            client
                .post()
                .uri("/api/v1/auth/register")
                .header("X-Service-Token", serviceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Credentials(email, password, ClientType.NATIVE))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody()
                .jsonPath("$.data.userId")
                .exists()
                .jsonPath("$.data.emailVerified")
                .isEqualTo(false)
                .jsonPath("$.data.password")
                .doesNotExist()
                .jsonPath("$.data.passwordHash")
                .doesNotExist()
            client
                .post()
                .uri("/api/v1/auth/login")
                .header("X-Service-Token", serviceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Credentials(email, password, ClientType.NATIVE))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.data.token")
                .exists()
                .jsonPath("$.data.state")
                .isEqualTo("ACTIVE")
            assertFalse(captured.list.any { it.formattedMessage.contains(password) })
        } finally {
            root.detachAppender(captured)
            captured.stop()
        }
    }

    @Test
    fun `web login issues secure HttpOnly cookie and excludes token from JSON`() {
        val credentials = Credentials("${UUID.randomUUID()}@example.com", "Abc1!x", ClientType.WEB)
        RegistrationService(source, hasher, mapper).register(UUID.randomUUID(), credentials)
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("X-Service-Token", serviceToken)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .bodyValue(credentials)
            .exchange()
            .expectStatus()
            .isOk
            .expectCookie()
            .httpOnly("SESSION", true)
            .expectCookie()
            .secure("SESSION", true)
            .expectCookie()
            .sameSite("SESSION", "Strict")
            .expectBody()
            .jsonPath("$.data.token")
            .doesNotExist()
    }

    @Test
    fun `direct internal call without service credential is rejected`() {
        client
            .post()
            .uri("/internal/v1/sessions/introspect")
            .bodyValue(mapOf("token" to "invalid"))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }
}
