package com.github.aratnitsyn.tictactoe.auth.account

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.auth.security.LoginResponseCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import javax.crypto.KeyGenerator

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class LogoutTest {
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
    fun `closing denies access and login until participation confirms ordinary menu logout`() {
        val credentials = Credentials("${UUID.randomUUID()}@example.com", "Abc1!x", ClientType.NATIVE)
        RegistrationService(source, hasher, jacksonObjectMapper()).register(UUID.randomUUID(), credentials)
        val loginCommand = UUID.randomUUID()
        val session = sessions.login(loginCommand, credentials)
        var gameAvailable = false
        val logout = LogoutService(source, { gameAvailable })
        val command = UUID.randomUUID()
        val operation = logout.begin(session.token, command)
        assertEquals(operation.operationId, logout.begin(session.token, command).operationId)
        assertNull(sessions.introspect(session.token))
        assertNull(sessions.touchIdentity(session.identity.userId, session.identity.sessionId))
        assertFalse(logout.reconcile(operation))
        assertEquals(
            "SESSION_ALREADY_ACTIVE",
            assertThrows(AuthFailure::class.java) { sessions.login(UUID.randomUUID(), credentials) }.code,
        )
        gameAvailable = true
        assertTrue(logout.reconcile(operation))
        assertNull(sessions.introspect(session.token))
        assertThrows(AuthFailure::class.java) { sessions.login(loginCommand, credentials) }
        val next = sessions.login(UUID.randomUUID(), credentials)
        assertNotEquals(session.token, next.token)
        // A repeated completion cannot close the new session.
        assertTrue(logout.reconcile(operation))
        assertEquals(next.identity.sessionId, sessions.introspect(next.token)?.sessionId)
    }
}
