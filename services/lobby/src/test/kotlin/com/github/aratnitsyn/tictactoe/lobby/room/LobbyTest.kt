package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.lobby.persistence.Inbox
import com.github.aratnitsyn.tictactoe.lobby.persistence.LobbyTestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class LobbyTest {
    private val database = LobbyTestDatabase()
    private val mapper = jacksonObjectMapper()
    private val db = LobbyDatabase(database.source, mapper)
    private val calls = AtomicInteger()
    private val game =
        object : GameClient {
            override fun resolve(operation: RoomOperation): JsonNode {
                calls.incrementAndGet()
                return mapper
                    .createObjectNode()
                    .put("status", "APPLIED")
                    .put("version", 1)
                    .put("resourceId", operation.roomId.toString())
            }

            override fun participation(userId: UUID): JsonNode = mapper.createObjectNode()
        }
    private val store = RoomStore(db, InvitationCode()::next)
    private val service = LobbyService(db, store, game)

    @AfterEach fun close() = database.close()

    private fun command(
        kind: String = "CREATE",
        user: UUID = UUID.randomUUID(),
        room: UUID? = null,
        payload: JsonNode = mapper.createObjectNode().put("expectedVersion", 0),
    ) = LobbyCommand(user, UUID.randomUUID(), kind, payload, UUID.randomUUID(), room)

    private fun assertReplay(
        expected: LobbyResponse,
        actual: LobbyResponse,
    ) {
        assertEquals(expected.status, actual.status)
        // Compare the public JSON values: PostgreSQL reparses small integral nodes as Int rather than Long.
        assertEquals(mapper.readTree(expected.body.toString()), mapper.readTree(actual.body.toString()))
    }

    @Test fun `code collisions retry without publishing an unregistered room`() {
        val codes = ArrayDeque(listOf("AAAAAAAAAA", "AAAAAAAAAA", "BBBBBBBBBB"))
        val colliding = RoomStore(db) { codes.removeFirst() }
        val a = colliding.begin(command())
        val b = colliding.begin(command())
        assertNotEquals(a, b)
        val join =
            command(
                "JOIN",
                payload = mapper.createObjectNode().put("expectedVersion", 0).put("code", "AAAAAAAAAA"),
            )
        assertEquals("NOT_FOUND", assertThrows(LobbyFailure::class.java) { service.execute(join) }.code)
        assertEquals(0, calls.get())
        assertEquals("AAAAAAAAAA", LobbyService(db, colliding, game).resume(a).body["data"]["code"].asText())
        assertEquals("BBBBBBBBBB", LobbyService(db, colliding, game).resume(b).body["data"]["code"].asText())
    }

    @Test fun `lost Game response keeps inaccessible pending room and recovery reuses its operation`() {
        val request = command()
        val broken = LobbyService(db, store, game) { throw IllegalStateException("crash") }
        assertThrows(IllegalStateException::class.java) { broken.execute(request) }
        val pending = service.pending().single()
        val room = db.transaction { store.read(it, pending).roomId }
        assertEquals(503, assertThrows(LobbyFailure::class.java) { service.snapshot(request.userId, room) }.status)
        val recovered = service.resume(pending)
        assertReplay(recovered, service.execute(request))
        assertTrue(service.pending().isEmpty())
        assertEquals(2, calls.get()) // Recovery resolves the same remote operation; replay never calls Game again.
        db.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM room").use { row ->
                    row.next()
                    assertEquals(1, row.getInt(1))
                }
                statement.executeQuery("SELECT count(*) FROM outbox").use { row ->
                    row.next()
                    assertEquals(1, row.getInt(1))
                }
            }
        }
    }

    @Test fun `closed invitation rejects join and replay remains historical`() {
        val request = command()
        val created = service.execute(request)
        val room = UUID.fromString(created.body["data"]["lobbyId"].asText())
        service.execute(command("LEAVE", request.userId, room))
        assertEquals("CLOSED", service.snapshot(request.userId, room)["data"]["status"].asText())
        val join =
            command(
                "JOIN",
                payload =
                    mapper
                        .createObjectNode()
                        .put("expectedVersion", 0)
                        .put("code", created.body["data"]["code"].asText()),
            )
        assertEquals(404, assertThrows(LobbyFailure::class.java) { service.execute(join) }.status)
        assertReplay(created, service.execute(request))
        assertEquals(404, assertThrows(LobbyFailure::class.java) { service.snapshot(UUID.randomUUID(), room) }.status)
    }

    @Test fun `changed payload and changed leave resource conflict before remote effect`() {
        val request = command()
        val created = service.execute(request)
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            assertThrows(LobbyFailure::class.java) {
                service.execute(request.copy(payload = mapper.createObjectNode().put("expectedVersion", 1)))
            }.code,
        )
        val room = UUID.fromString(created.body["data"]["lobbyId"].asText())
        val leave = command("LEAVE", request.userId, room)
        service.execute(leave)
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            assertThrows(LobbyFailure::class.java) {
                service.execute(leave.copy(roomId = UUID.randomUUID()))
            }.code,
        )
        assertEquals(2, calls.get())
    }

    @Test fun `rate limit counts invalid attempts across instances and resets at database boundary`() {
        val first = CodeRateLimit(db, 2, 3)
        val second = CodeRateLimit(db, 2, 3)
        first.check("a", "ip1")
        second.check("a", "ip1")
        assertEquals(429, assertThrows(LobbyFailure::class.java) { first.check("a", "ip2") }.status)
        second.check("b", "ip1")
        assertEquals(429, assertThrows(LobbyFailure::class.java) { first.check("c", "ip1") }.status)
        db.transaction { db.update(it, "UPDATE code_attempt SET window_start=clock_timestamp()-interval '60 seconds'") }
        first.check("a", "ip1")
    }

    @Test fun `validation refuses missing string negative and extra fields before storing saga`() {
        listOf(
            "{}",
            "{\"expectedVersion\":\"0\"}",
            "{\"expectedVersion\":-1}",
            "{\"expectedVersion\":0,\"userId\":\"spoof\"}",
        ).forEach {
            val failure =
                assertThrows(LobbyFailure::class.java) {
                    service.execute(command(payload = mapper.readTree(it)))
                }
            assertEquals(400, failure.status)
        }
        val numericCode = mapper.readTree("{\"expectedVersion\":0,\"code\":2222222222}")
        assertEquals(
            400,
            assertThrows(LobbyFailure::class.java) {
                service.execute(command("JOIN", payload = numericCode))
            }.status,
        )
        assertTrue(service.pending().isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun `return consumer rotates once deduplicates event and fences prior match`() {
        val owner = UUID.randomUUID()
        val created = service.execute(command(user = owner))
        val room = UUID.fromString(created.body["data"]["lobbyId"].asText())
        val oldCode = created.body["data"]["code"].asText()
        val match = UUID.randomUUID()
        db.transaction { db.update(it, "UPDATE room SET status='PREPARING',match_id=? WHERE room_id=?", match, room) }
        val returning =
            object : GameClient {
                override fun resolve(operation: RoomOperation): JsonNode = error("not used")

                override fun participation(userId: UUID): JsonNode =
                    mapper.createObjectNode().apply {
                        set<JsonNode>(
                            "data",
                            mapper.createObjectNode().put("state", "LOBBY").put("resourceId", room.toString()),
                        )
                    }
            }
        val handler = LobbyReturnHandler(db, store, returning)
        val event =
            mapper.createObjectNode().apply {
                put("type", "MatchCancelled")
                put("aggregateVersion", 1)
                put("correlationId", UUID.randomUUID().toString())
                put("eventId", UUID.randomUUID().toString())
                set<JsonNode>(
                    "payload",
                    mapper.createObjectNode().apply {
                        put("source", "LOBBY")
                        put("sourceRef", room.toString())
                        put("matchId", match.toString())
                        putArray("participants").addObject().put("userId", owner.toString())
                    },
                )
            }
        val inbox = Inbox(database.source)
        val eventId = UUID.fromString(event["eventId"].asText())
        assertTrue(inbox.process("return-test", eventId) { handler.apply(it, mapper.writeValueAsBytes(event)) })
        val restored = service.snapshot(owner, room)
        assertNotEquals(oldCode, restored["data"]["code"].asText())
        assertEquals("WAITING", restored["data"]["status"].asText())
        assertFalse(inbox.process("return-test", eventId) { error("duplicate applied") })
        event.put("eventId", UUID.randomUUID().toString())
        db.transaction { handler.apply(it, mapper.writeValueAsBytes(event)) }
        assertEquals(restored["data"], service.snapshot(owner, room)["data"])
    }
}
