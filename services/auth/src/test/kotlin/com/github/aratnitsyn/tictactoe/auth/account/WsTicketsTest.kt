package com.github.aratnitsyn.tictactoe.auth.account

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.security.LoginResponseCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator

@EnabledIfEnvironmentVariable(named = "REDIS_PASSWORD", matches = ".+")
class WsTicketsTest {
    private val database =
        com.github.aratnitsyn.tictactoe.auth.persistence
            .AuthTestDatabase()
    private val source = database.source

    @org.junit.jupiter.api.AfterEach
    fun closeDatabase() = database.close()

    private val hasher = PasswordHasher()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sessions = SessionService(source, hasher, LoginResponseCipher("test", { key }))

    @Test
    fun `concurrent consume succeeds once and exact deadline fails without sleeping`() {
        val config =
            RedisStandaloneConfiguration("127.0.0.1", 6379).apply {
                password = RedisPassword.of(System.getenv("REDIS_PASSWORD"))
            }
        val factory = LettuceConnectionFactory(config).apply { afterPropertiesSet() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val redis = StringRedisTemplate(factory)
            val credentials = Credentials("${UUID.randomUUID()}@example.com", "Abc1!x", ClientType.NATIVE)
            RegistrationService(source, hasher, jacksonObjectMapper()).register(UUID.randomUUID(), credentials)
            val session = sessions.login(UUID.randomUUID(), credentials)
            val now = Instant.now()
            val tickets = WsTickets(redis, sessions, Clock.fixed(now, ZoneOffset.UTC))
            val commandId = UUID.randomUUID()
            val issued = tickets.issue(session.token, commandId)
            val replay = tickets.issue(session.token, commandId)
            assertEquals(issued.ticket, replay.ticket)
            assertEquals(issued.expiresAt, replay.expiresAt)
            val results = (1..4).map { executor.submit(Callable { tickets.consume(issued.ticket) }) }.map { it.get() }
            assertEquals(1, results.count { it != null })
            val expiring = tickets.issue(session.token)
            val atDeadline = WsTickets(redis, sessions, Clock.fixed(expiring.expiresAt, ZoneOffset.UTC))
            assertNull(atDeadline.consume(expiring.ticket))
            assertNull(tickets.consume(expiring.ticket))
            val closingTicket = tickets.issue(session.token)
            LogoutService(source, { false }).begin(session.token, UUID.randomUUID())
            assertNull(tickets.consume(closingTicket.ticket))
        } finally {
            executor.shutdownNow()
            factory.destroy()
        }
    }
}
