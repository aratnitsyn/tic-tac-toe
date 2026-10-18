package com.github.aratnitsyn.tictactoe.game.participation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.game.persistence.GameTestDatabase
import com.github.aratnitsyn.tictactoe.game.persistence.OutboxEvent
import com.github.aratnitsyn.tictactoe.game.persistence.OutboxWorker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class ParticipationTest {
    private val database = GameTestDatabase()
    private val mapper = jacksonObjectMapper()
    private val db = CoordinationDatabase(database.source, mapper)
    private val operations = OperationStore(db)
    private val events = ParticipationEvent(db)
    private val transitions = ParticipationTransition(db, events)
    private val assignments = PairAssignment(db, transitions, events)
    private val service = fresh()

    private fun fresh(afterEffect: () -> Unit = {}) =
        ParticipationService(
            db,
            operations,
            transitions,
            assignments,
            afterEffect,
        )

    @AfterEach
    fun close() = database.close()

    private fun execute(
        kind: String,
        request: Any,
        correlation: UUID = UUID.randomUUID(),
    ): JsonNode = service.execute(kind, mapper.valueToTree(request), correlation)

    private fun queue(user: UUID = UUID.randomUUID()): Pair<UUID, UUID> {
        val ticket = UUID.randomUUID()
        execute("CLAIM", ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.QUEUED, ticket))
        return user to ticket
    }

    private fun pair(
        a: Pair<UUID, UUID>,
        b: Pair<UUID, UUID>,
    ) = AssignPair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        listOf(PairPlayer(a.first, 1, a.second, "1"), PairPlayer(b.first, 1, b.second, "2")),
    )

    private fun state(user: UUID): JsonNode = service.snapshot(user)["data"]

    @Test
    fun `CAS queue entry cancel replay and changed payload preserve authoritative snapshot`() {
        val user = UUID.randomUUID()
        assertEquals("MENU", state(user)["state"].asText())
        assertEquals(0, state(user)["version"].asInt())
        val request = ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.QUEUED, UUID.randomUUID())
        val first = execute("CLAIM", request)
        val stale = ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.MENU)
        assertEquals("VERSION_CONFLICT", assertThrows(CoordinationFailure::class.java) { execute("CLAIM", stale) }.code)
        assertEquals("REJECTED", service.result(stale.operationId)["status"].asText())
        assertEquals("VERSION_CONFLICT", service.result(stale.operationId)["errorCode"].asText())
        execute("CLAIM", ClaimParticipation(UUID.randomUUID(), user, 1, ParticipationState.MENU))
        assertEquals(first, execute("CLAIM", request))
        assertEquals("MENU", state(user)["state"].asText())
        assertEquals(2, state(user)["version"].asInt())
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            assertThrows(CoordinationFailure::class.java) {
                execute("CLAIM", request.copy(resourceId = UUID.randomUUID()))
            }.code,
        )
    }

    @Test
    fun `simultaneous room registration and queue admission have one winner`() {
        val user = UUID.randomUUID()
        val results =
            race(
                listOf(
                    { execute("REGISTER_ROOM", RegisterRoom(UUID.randomUUID(), UUID.randomUUID(), user, 0)) },
                    {
                        execute(
                            "CLAIM",
                            ClaimParticipation(
                                UUID.randomUUID(),
                                user,
                                0,
                                ParticipationState.QUEUED,
                                UUID.randomUUID(),
                            ),
                        )
                    },
                ),
            )
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, state(user)["version"].asInt())
        assertTrue(state(user)["state"].asText() in listOf("QUEUED", "LOBBY"))
        assertEquals(0, count("SELECT count(*) FROM participation WHERE operation_id IS NOT NULL"))
    }

    @Test
    fun `one concurrent guest creates one lobby match with both members and durable ready deadline`() {
        val host = UUID.randomUUID()
        val room = UUID.randomUUID()
        execute("REGISTER_ROOM", RegisterRoom(UUID.randomUUID(), room, host, 0))
        val guests = List(3) { UUID.randomUUID() }
        val earliest = db.transaction(db::now).plusSeconds(10)
        val results =
            race(
                guests.map { user ->
                    {
                        execute(
                            "CLAIM",
                            ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.LOBBY, room),
                        )
                    }
                },
            )
        val latest = db.transaction(db::now).plusSeconds(10)
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, count("SELECT count(*) FROM game_match WHERE state='WAITING_READY' AND source='LOBBY'"))
        assertEquals(2, count("SELECT count(*) FROM match_member WHERE active"))
        assertEquals(0, count("SELECT count(*) FROM room_slot"))
        assertEquals("IN_MATCH", state(host)["state"].asText())
        assertEquals(1, guests.count { state(it)["state"].asText() == "IN_MATCH" })
        db.source.connection.use { connection ->
            connection.createStatement().use {
                it.executeQuery("SELECT * FROM game_match").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(room, rows.getObject("source_ref", UUID::class.java))
                    val deadline = rows.getTimestamp("ready_deadline").toInstant()
                    assertFalse(deadline.isBefore(earliest))
                    assertFalse(deadline.isAfter(latest))
                }
            }
        }
        assertThrows(CoordinationFailure::class.java) {
            execute("CLAIM", ClaimParticipation(UUID.randomUUID(), host, 1, ParticipationState.MENU, room))
        }
        assertEquals("IN_MATCH", state(host)["state"].asText())
    }

    @Test
    fun `room leave closes registration and missing or closed rooms do not claim a guest`() {
        val owner = UUID.randomUUID()
        val room = UUID.randomUUID()
        execute("REGISTER_ROOM", RegisterRoom(UUID.randomUUID(), room, owner, 0))
        execute("CLAIM", ClaimParticipation(UUID.randomUUID(), owner, 1, ParticipationState.MENU, room))
        for (resource in listOf(room, UUID.randomUUID())) {
            val guest = UUID.randomUUID()
            assertEquals(
                "LOBBY_UNAVAILABLE",
                assertThrows(CoordinationFailure::class.java) {
                    execute(
                        "CLAIM",
                        ClaimParticipation(
                            UUID.randomUUID(),
                            guest,
                            0,
                            ParticipationState.LOBBY,
                            resource,
                        ),
                    )
                }.code,
            )
            assertEquals("MENU", state(guest)["state"].asText())
        }
        assertEquals(0, count("SELECT count(*) FROM room_slot"))
    }

    @Test
    fun `queue pair replay preserves match and cancel cannot release assigned players`() {
        val a = queue()
        val b = queue()
        val request = pair(a, b)
        val first = execute("ASSIGN_PAIR", request)
        assertEquals(first, execute("ASSIGN_PAIR", request))
        assertEquals(request.matchId.toString(), first["resourceId"].asText())
        assertEquals(
            "PARTICIPATION_CONFLICT",
            assertThrows(CoordinationFailure::class.java) {
                execute("CLAIM", ClaimParticipation(UUID.randomUUID(), a.first, 1, ParticipationState.MENU, a.second))
            }.code,
        )
        assertEquals("IN_MATCH", state(a.first)["state"].asText())
        assertEquals("IN_MATCH", state(b.first)["state"].asText())
        assertEquals(1, count("SELECT count(*) FROM game_match"))
        assertEquals(
            2,
            count(
                "SELECT count(*) FROM match_member WHERE ticket_id IS NOT NULL " +
                    "AND original_sequence IN (1,2)",
            ),
        )
    }

    @Test
    fun `cancel versus pair assignment produces either menu or one match and never releases a member`() {
        val a = queue()
        val b = queue()
        val results =
            race(
                listOf(
                    { execute("ASSIGN_PAIR", pair(a, b)) },
                    {
                        execute(
                            "CLAIM",
                            ClaimParticipation(
                                UUID.randomUUID(),
                                a.first,
                                1,
                                ParticipationState.MENU,
                                a.second,
                            ),
                        )
                    },
                ),
            )
        assertEquals(1, results.count { it.isSuccess })
        val inMatch = state(a.first)["state"].asText() == "IN_MATCH"
        assertEquals(if (inMatch) "IN_MATCH" else "QUEUED", state(b.first)["state"].asText())
        assertEquals(if (inMatch) 1 else 0, count("SELECT count(*) FROM game_match"))
        assertEquals(0, count("SELECT count(*) FROM participation WHERE operation_id IS NOT NULL"))
    }

    @Test
    fun `two pairs sharing a player create one match even when user order is reversed`() {
        val a = queue()
        val b = queue()
        val c = queue()
        val results = race(listOf({ execute("ASSIGN_PAIR", pair(a, b)) }, { execute("ASSIGN_PAIR", pair(c, a)) }))
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, count("SELECT count(*) FROM game_match"))
        assertEquals(2, count("SELECT count(*) FROM match_member WHERE active"))
    }

    @Test
    fun `crash before reservation commit leaves no operation or locked participation`() {
        val user = UUID.randomUUID()
        val request = ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.QUEUED, UUID.randomUUID())
        val failedStore = OperationStore(db) { error("Crash before reservation commit") }
        val failed = ParticipationService(db, failedStore, transitions, assignments)
        assertThrows(IllegalStateException::class.java) {
            failed.begin("CLAIM", mapper.valueToTree(request), UUID.randomUUID())
        }
        assertEquals(
            "NOT_FOUND",
            assertThrows(CoordinationFailure::class.java) {
                service.result(request.operationId)
            }.code,
        )
        assertEquals("MENU", state(user)["state"].asText())
        assertEquals(0, count("SELECT count(*) FROM participation WHERE operation_id IS NOT NULL"))
        assertEquals("APPLIED", execute("CLAIM", request)["status"].asText())
    }

    @Test
    fun `pending reservation survives restart and rollback after effects before commit`() {
        val a = queue()
        val b = queue()
        val request = pair(a, b)
        val id = service.begin("ASSIGN_PAIR", mapper.valueToTree(request), UUID.randomUUID())
        assertEquals("PENDING", fresh().result(id)["status"].asText())
        assertEquals("QUEUED", state(a.first)["state"].asText())
        assertThrows(CoordinationFailure::class.java) {
            execute("CLAIM", ClaimParticipation(UUID.randomUUID(), a.first, 1, ParticipationState.MENU))
        }
        val before = count("SELECT count(*) FROM outbox")
        assertThrows(IllegalStateException::class.java) { fresh { error("Crash before commit") }.apply(id) }
        assertEquals("PENDING", service.result(id)["status"].asText())
        assertEquals(0, count("SELECT count(*) FROM game_match"))
        assertEquals(before, count("SELECT count(*) FROM outbox"))
        assertEquals(2, count("SELECT count(*) FROM participation WHERE operation_id IS NOT NULL"))
        fresh().apply(id)
        val committed = service.result(id)
        assertEquals("APPLIED", committed["status"].asText())
        // A lost HTTP response is recovered from the durable operation, never by compensating participation.
        assertEquals(committed, fresh().execute("ASSIGN_PAIR", mapper.valueToTree(request), UUID.randomUUID()))
        assertEquals(before + 3, count("SELECT count(*) FROM outbox"))
    }

    @Test
    fun `correlation links durable operation match and published events`() {
        val a = queue()
        val b = queue()
        val request = pair(a, b)
        val correlation = UUID.randomUUID()
        execute("ASSIGN_PAIR", request, correlation)
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM coordination_operation WHERE " +
                    "correlation_id='$correlation' AND status='APPLIED'",
            ),
        )
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM game_match WHERE " +
                    "correlation_id='$correlation' AND source_ref='${request.operationId}'",
            ),
        )
        val published = mutableListOf<OutboxEvent>()
        val worker =
            OutboxWorker(db.source, {
                published.add(it)
                Unit
            }, "trace-test")
        while (worker.publishOne()) { /* Drain this isolated schema only. */ }
        val linked = published.filter { it.envelope["correlationId"].asText() == correlation.toString() }
        assertEquals(3, linked.size)
        assertTrue(linked.all { it.envelope["causationId"].asText() == request.operationId.toString() })
        assertEquals(1, linked.count { it.type == "MatchReadyRequested" })
    }

    @Test
    fun `database rejects duplicate room positions users and active match membership`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val room = UUID.randomUUID()
        execute("REGISTER_ROOM", RegisterRoom(UUID.randomUUID(), room, owner, 0))
        execute("REGISTER_ROOM", RegisterRoom(UUID.randomUUID(), UUID.randomUUID(), other, 0))
        assertEquals(
            "23505",
            assertThrows(SQLException::class.java) {
                db.source.connection.use { db.update(it, "INSERT INTO room_slot VALUES (?,1,?)", room, other) }
            }.sqlState,
        )
        assertEquals(
            "23505",
            assertThrows(SQLException::class.java) {
                db.source.connection.use { db.update(it, "INSERT INTO room_slot VALUES (?,2,?)", room, other) }
            }.sqlState,
        )
        val a = queue()
        val b = queue()
        val c = queue()
        val d = queue()
        val first = pair(a, b)
        val second = pair(c, d)
        execute("ASSIGN_PAIR", first)
        execute("ASSIGN_PAIR", second)
        db.source.connection.use { connection ->
            db.update(connection, "DELETE FROM match_member WHERE match_id=? AND slot=1", second.matchId)
            val error =
                assertThrows(SQLException::class.java) {
                    db.update(
                        connection,
                        "INSERT INTO match_member(match_id,user_id,slot) VALUES (?,?,1)",
                        second.matchId,
                        a.first,
                    )
                }
            assertEquals("23505", error.sqlState)
            assertTrue(error.message!!.contains("match_member_active_user"))
        }
    }

    private fun count(sql: String): Int =
        db.source.connection.use { connection ->
            connection.createStatement().use {
                it.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun race(actions: List<() -> JsonNode>): List<Result<JsonNode>> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)
        try {
            val futures =
                actions.map { action ->
                    executor.submit(
                        Callable {
                            start.await()
                            runCatching(action)
                        },
                    )
                }
            start.countDown()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }
            results.filter { it.isFailure }.forEach { assertNotNull(it.exceptionOrNull() as? CoordinationFailure) }
            return results
        } finally {
            executor.shutdownNow()
        }
    }
}
