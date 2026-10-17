package com.github.aratnitsyn.tictactoe.user.profile

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.user.persistence.Inbox
import com.github.aratnitsyn.tictactoe.user.persistence.UserTestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class AccountRegisteredTest {
    private val database = UserTestDatabase()
    private val mapper = ObjectMapper()
    private val handler = AccountRegisteredHandler(mapper)
    private val inbox = Inbox(database.source)

    @AfterEach
    fun close() = database.close()

    @Test
    fun `duplicate event and different events for same identity create one minimal profile`() {
        val userId = UUID.randomUUID()
        val event =
            mapper
                .createObjectNode()
                .put("type", "AccountRegistered")
                .put("schemaVersion", 1)
                .put("aggregateType", "ACCOUNT")
                .put("aggregateId", userId.toString())
                .put("occurredAt", "2026-10-17T12:00:00Z")
        event.putObject("payload").put("userId", userId.toString())
        val eventId = UUID.randomUUID()
        val bytes = event.toString().toByteArray()
        assertTrue(inbox.process("user-profiles-v1", eventId) { handler.apply(it, bytes) })
        assertFalse(inbox.process("user-profiles-v1", eventId) { error("Duplicate callback must not run") })
        assertTrue(inbox.process("user-profiles-v1", UUID.randomUUID()) { handler.apply(it, bytes) })
        database.source.connection.use { connection ->
            connection.createStatement().use {
                it.executeQuery("SELECT user_id FROM profiles").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(userId, rows.getObject(1, UUID::class.java))
                    assertFalse(rows.next())
                }
            }
        }
    }
}
