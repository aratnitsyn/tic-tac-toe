package com.github.aratnitsyn.tictactoe.auth.account

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.security.LoginResponseCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class AuthPersistenceTest {
    private val database =
        com.github.aratnitsyn.tictactoe.auth.persistence
            .AuthTestDatabase()
    private val source = database.source

    @org.junit.jupiter.api.AfterEach
    fun closeDatabase() = database.close()

    private val mapper = jacksonObjectMapper()
    private val hasher = PasswordHasher()
    private val registration = RegistrationService(source, hasher, mapper)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = LoginResponseCipher("test-key", { key })
    private var now = Instant.parse("2026-10-17T12:00:00Z")
    private val sessions = SessionService(source, hasher, cipher) { now }

    private fun credentials() = Credential("${UUID.randomUUID()}@example.com", "Abc1!x", ClientType.NATIVE)

    @Test
    fun `registration retry returns same identity and one secret-free outbox event`() {
        val command = UUID.randomUUID()
        val credentials = credentials()
        val response = registration.register(command, credentials)
        assertEquals(response, registration.register(command, credentials))
        assertFalse(response.toString().contains(credentials.password))
        source.connection.use { connection ->
            connection.prepareStatement("SELECT envelope FROM outbox WHERE aggregate_id=?").use {
                it.setObject(1, UUID.fromString(response["data"]["userId"].asText()))
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val event = mapper.readTree(rows.getString(1))
                    assertEquals("ACCOUNT", event["aggregateType"].asText())
                    assertEquals("AccountRegistered", event["type"].asText())
                    assertFalse(event.toString().contains(credentials.password))
                    assertFalse(rows.next())
                }
            }
        }
        assertThrows(AuthFailure::class.java) { registration.register(UUID.randomUUID(), credentials) }
        val changed = Credential(credentials.email, "Abc2!x", ClientType.NATIVE)
        val conflict = assertThrows(AuthFailure::class.java) { registration.register(command, changed) }
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code)
    }

    @Test
    fun `concurrent registrations with folded email create one account`() {
        val credentials = credentials()
        val results =
            concurrent { index ->
                val email = if (index % 2 == 0) credentials.email.uppercase(Locale.ROOT) else credentials.email
                val variant = Credential(email, credentials.password, credentials.clientType)
                runCatching { registration.register(UUID.randomUUID(), variant) }
            }
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach {
            assertEquals("VALIDATION_ERROR", (it.exceptionOrNull() as AuthFailure).code)
        }
    }

    @Test
    fun `concurrent login gives exactly one active session`() {
        val credentials = credentials()
        registration.register(UUID.randomUUID(), credentials)
        val results = concurrent { runCatching { sessions.login(UUID.randomUUID(), credentials) } }
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach {
            assertEquals("SESSION_ALREADY_ACTIVE", (it.exceptionOrNull() as AuthFailure).code)
        }
        val identity = results.first { it.isSuccess }.getOrThrow()
        assertNotNull(sessions.introspect(identity.token))
    }

    @Test
    fun `lost login reply returns same token but cannot revive expired session`() {
        val credentials = credentials()
        registration.register(UUID.randomUUID(), credentials)
        val command = UUID.randomUUID()
        val first = sessions.login(command, credentials)
        assertEquals(first.token, sessions.login(command, credentials).token)
        assertNull(sessions.introspect("wrong-token"))
        now = first.identity.expiresAt
        assertNull(sessions.introspect(first.token))
        assertThrows(AuthFailure::class.java) { sessions.login(command, credentials) }
        assertNotEquals(first.token, sessions.login(UUID.randomUUID(), credentials).token)
    }

    @Test
    fun `authorized touch extends expiry with exact boundary rejection`() {
        val credentials = credentials()
        registration.register(UUID.randomUUID(), credentials)
        val result = sessions.login(UUID.randomUUID(), credentials)
        now = now.plusSeconds(86399)
        val refreshed = sessions.introspect(result.token)!!
        assertEquals(now.plusSeconds(86400), refreshed.expiresAt)
        now = refreshed.expiresAt
        assertNull(sessions.introspect(result.token))
    }

    @Test
    fun `login replay expires independently of an active touched session and erases ciphertext`() {
        val credentials = credentials()
        registration.register(UUID.randomUUID(), credentials)
        val command = UUID.randomUUID()
        val login = sessions.login(command, credentials)
        now = now.plusSeconds(43200)
        assertNotNull(sessions.introspect(login.token))
        now = now.plusSeconds(43200)
        assertThrows(AuthFailure::class.java) { sessions.login(command, credentials) }
        source.connection.use { connection ->
            eraseExpired(connection, now)
            connection.prepareStatement("SELECT ciphertext,nonce,key_id FROM login_responses WHERE user_id=?").use {
                it.setObject(1, login.identity.userId)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertNull(rows.getBytes("ciphertext"))
                    assertNull(rows.getBytes("nonce"))
                    assertNull(rows.getString("key_id"))
                }
            }
        }
        assertNotNull(sessions.introspect(login.token))
    }

    private fun <T> concurrent(effect: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        return try {
            val futures =
                (1..4).map { index ->
                    executor.submit(
                        Callable {
                            start.await()
                            effect(index)
                        },
                    )
                }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
