package com.github.aratnitsyn.tictactoe.user.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class CommandStoreTest {
    private val mapper = ObjectMapper()
    private val database = UserTestDatabase()
    private val source = database.source

    @org.junit.jupiter.api.AfterEach
    fun closeDatabase() = database.close()

    private val store = CommandStore(source)
    private val userId = UUID.randomUUID()
    private val commandId = UUID.randomUUID()

    @BeforeEach
    fun prepare() {
        source.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    "CREATE TABLE IF NOT EXISTS command_store_probe " +
                        "(id uuid PRIMARY KEY, amount integer NOT NULL)",
                )
            }
        }
    }

    @Test
    fun `concurrent retry commits one effect and same response`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (1..8).map {
                    executor.submit(
                        Callable {
                            start.await()
                            store.execute(userId, "MOVE", commandId, mapper.readTree("{\"cell\":1}")) { connection ->
                                connection.prepareStatement("INSERT INTO command_store_probe VALUES (?, 1)").use {
                                    it.setObject(1, commandId)
                                    it.executeUpdate()
                                }
                                CommandResponse(200, mapper.readTree("{\"accepted\":true}"))
                            }
                        },
                    )
                }
            start.countDown()
            assertEquals(1, futures.map { it.get(10, TimeUnit.SECONDS) }.toSet().size)
            assertEquals(1, effects())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `changed payload conflicts without another effect`() {
        store.execute(userId, "MOVE", commandId, mapper.readTree("{\"cell\":1}")) {
            CommandResponse(200, mapper.readTree("{}"))
        }
        assertThrows(IdempotencyConflict::class.java) {
            store.execute(userId, "MOVE", commandId, mapper.readTree("{\"cell\":2}")) {
                error("Must not execute")
            }
        }
    }

    @Test
    fun `failed effect rolls back and may be retried`() {
        assertThrows(IllegalStateException::class.java) {
            store.execute(userId, "MOVE", commandId, mapper.readTree("{}")) { connection ->
                connection.prepareStatement("INSERT INTO command_store_probe VALUES (?, 1)").use {
                    it.setObject(1, commandId)
                    it.executeUpdate()
                }
                error("Injected crash before commit")
            }
        }
        assertEquals(0, effects())
        val result =
            store.execute(userId, "MOVE", commandId, mapper.readTree("{}")) {
                CommandResponse(200, mapper.readTree("{\"retried\":true}"))
            }
        assertEquals(true, result.body["retried"].asBoolean())
    }

    @Test
    fun `object property order does not change command identity`() {
        store.execute(userId, "MOVE", commandId, mapper.readTree("{\"cell\":1,\"version\":0}")) {
            CommandResponse(200, mapper.readTree("{}"))
        }
        store.execute(userId, "MOVE", commandId, mapper.readTree("{\"version\":0,\"cell\":1}")) {
            error("Must not execute twice")
        }
    }

    private fun effects(): Int =
        source.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM command_store_probe WHERE id = ?").use {
                it.setObject(1, commandId)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
}
