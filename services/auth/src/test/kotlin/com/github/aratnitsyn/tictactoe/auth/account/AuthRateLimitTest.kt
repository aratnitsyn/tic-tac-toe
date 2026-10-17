package com.github.aratnitsyn.tictactoe.auth.account

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.http.AuthController
import com.github.aratnitsyn.tictactoe.auth.account.security.LoginResponseCipher
import com.github.aratnitsyn.tictactoe.auth.security.AuthErrorHandler
import com.github.aratnitsyn.tictactoe.auth.security.AuthRateLimit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID
import javax.crypto.KeyGenerator

@EnabledIfEnvironmentVariable(named = "REDIS_PASSWORD", matches = ".+")
class AuthRateLimitTest {
    private val database =
        com.github.aratnitsyn.tictactoe.auth.persistence
            .AuthTestDatabase()

    @AfterEach
    fun close() = database.close()

    @Test
    fun `account limits are shared across source addresses before password hashing`() {
        val configuration =
            RedisStandaloneConfiguration("127.0.0.1", 6379).apply {
                password = RedisPassword.of(System.getenv("REDIS_PASSWORD"))
            }
        val factory = LettuceConnectionFactory(configuration).apply { afterPropertiesSet() }
        try {
            val mapper = jacksonObjectMapper()
            val hasher = spy(PasswordHasher())
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val controller =
                AuthController(
                    RegistrationService(database.source, hasher, mapper),
                    SessionService(database.source, hasher, LoginResponseCipher("test", { key })),
                    mapper,
                )
            val client =
                WebTestClient
                    .bindToController(controller)
                    .controllerAdvice(AuthErrorHandler(mapper))
                    .webFilter<WebTestClient.ControllerSpec>(AuthRateLimit(StringRedisTemplate(factory), mapper))
                    .build()
            val email = "${UUID.randomUUID()}@example.com"
            RegistrationService(database.source, hasher, mapper)
                .register(UUID.randomUUID(), Credential(email, "Abc2!x", ClientType.NATIVE))
            clearInvocations(hasher)
            repeat(10) {
                client
                    .post()
                    .uri("/api/v1/auth/login")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-Client-IP", UUID.randomUUID().toString())
                    .bodyValue(Credential(email, "Abc1!x", ClientType.NATIVE))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized
            }
            verify(hasher, times(10)).matches(anyString(), anyString())
            client
                .post()
                .uri("/api/v1/auth/register")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Client-IP", UUID.randomUUID().toString())
                .bodyValue(Credential(email, "Abc1!x", ClientType.NATIVE))
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("RATE_LIMITED")
            verify(hasher, times(10)).matches(anyString(), anyString())
            verify(hasher, never()).hash(anyString())
        } finally {
            factory.destroy()
        }
    }
}
