package com.github.aratnitsyn.tictactoe.game.preparation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.game.participation.AssignPair
import com.github.aratnitsyn.tictactoe.game.participation.ClaimParticipation
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.participation.OperationStore
import com.github.aratnitsyn.tictactoe.game.participation.PairAssignment
import com.github.aratnitsyn.tictactoe.game.participation.PairPlayer
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationEvent
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationService
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationState
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationTransition
import com.github.aratnitsyn.tictactoe.game.participation.RegisterRoom
import com.github.aratnitsyn.tictactoe.game.persistence.CommandStore
import com.github.aratnitsyn.tictactoe.game.persistence.GameTestDatabase
import com.github.aratnitsyn.tictactoe.game.persistence.IdempotencyConflict
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class PreparationTest {
    private val database = GameTestDatabase()
    private val mapper = jacksonObjectMapper()
    private val db = CoordinationDatabase(database.source, mapper)
    private val event = ParticipationEvent(db)
    private val transition = ParticipationTransition(db, event)
    private val coordination =
        ParticipationService(
            db,
            OperationStore(db),
            transition,
            PairAssignment(db, transition, event),
        )
    private val store = PreparationStore(db)
    private val prepEvent = PreparationEvent(db)
    private var now = Instant.parse("2030-01-01T00:00:00Z")

    private fun fresh() =
        PreparationService(
            store,
            CommandStore(db.source, mapper),
            prepEvent,
            PreparationReturn(db, prepEvent),
            { now },
        )

    private val service = fresh()
    private var a = UUID.randomUUID()
    private var b = UUID.randomUUID()
    private lateinit var match: UUID

    @AfterEach fun close() = database.close()

    private fun execute(
        kind: String,
        value: Any,
    ) = coordination.execute(kind, mapper.valueToTree(value), UUID.randomUUID())

    private fun pair(source: String = "QUEUE") {
        if (source == "QUEUE") {
            val tickets =
                listOf(a, b).map { user ->
                    val ticket = UUID.randomUUID()
                    execute("CLAIM", ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.QUEUED, ticket))
                    PairPlayer(user, 1, ticket, if (user == a) "1" else "2")
                }
            match = UUID.randomUUID()
            execute("ASSIGN_PAIR", AssignPair(UUID.randomUUID(), match, tickets))
        } else {
            val room = UUID.randomUUID()
            execute("REGISTER_ROOM", RegisterRoom(UUID.randomUUID(), room, a, 0))
            match =
                UUID.fromString(
                    execute(
                        "CLAIM",
                        ClaimParticipation(
                            UUID.randomUUID(),
                            b,
                            0,
                            ParticipationState.LOBBY,
                            room,
                        ),
                    )["resourceId"].asText(),
                )
        }
        now = db.transaction { store.lock(it, match).readyDeadline.minusSeconds(10) }
    }

    private fun version() = db.transaction { store.lock(it, match).version }

    private fun data() = service.snapshot(a, match)["data"]

    private fun state(user: UUID) = coordination.snapshot(user)["data"]["state"].asText()

    private fun accept(
        user: UUID,
        command: UUID = UUID.randomUUID(),
        expected: Long = version(),
    ) = service.ready(user, match, command, mapper.createObjectNode().put("expectedVersion", expected))

    private fun disconnect(
        user: UUID,
        generation: Long = 0,
        connected: Boolean = false,
    ): Int {
        val id = UUID.randomUUID()
        val payload =
            mapper
                .createObjectNode()
                .put("operationId", id.toString())
                .put("matchId", match.toString())
                .put("userId", user.toString())
                .put("generation", generation)
                .put("connected", connected)
        return service.observe(user, match, id, payload).status
    }

    private fun count(sql: String): Int =
        db.source.connection.use { c ->
            c.createStatement().use {
                it.executeQuery(sql).use { row ->
                    row.next()
                    row.getInt(1)
                }
            }
        }

    @Test fun `ready replay is historical and cannot confirm another player or extend the deadline`() {
        pair()
        val deadline = data()["readyDeadline"]
        val command = UUID.randomUUID()
        val first = accept(a, command, 0)
        now = now.plusSeconds(8)
        val replay = accept(a, command, 0)
        assertEquals(first.status, replay.status)
        assertEquals(mapper.readTree(first.body.toString()), mapper.readTree(replay.body.toString()))
        assertEquals(200, accept(a).status)
        assertEquals(deadline, data()["readyDeadline"])
        assertEquals(1, data()["participants"].count { it["ready"].asBoolean() })
        assertThrows(IdempotencyConflict::class.java) { accept(a, command, 1) }
        assertEquals(409, accept(b, expected = 0).status)
        assertEquals(200, accept(b).status)
        assertEquals("COUNTDOWN", data()["state"].asText())
    }

    @Test fun `exclusive deadline is evaluated after locking before version and survives a fresh worker`() {
        pair()
        accept(a)
        now = now.plusSeconds(10)
        assertEquals(409, accept(b).status)
        assertEquals("CANCELLED", data()["state"].asText())
        assertEquals("QUEUED", state(a))
        assertEquals("MENU", state(b))
        fresh().tick(match)
        assertEquals(0, count("SELECT count(*) FROM game"))
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE event_type='ReturnToQueue'"))
    }

    @Test fun `countdown starts one persisted game with X and independent recovery budgets across repeated workers`() {
        pair()
        accept(a)
        accept(b)
        val start = Instant.parse(data()["startDeadline"].asText())
        now = start.minusNanos(1)
        service.tick(match)
        assertEquals("COUNTDOWN", data()["state"].asText())
        now = start
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll(List(8) { Callable { fresh().tick(match) } }).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("RUNNING", data()["state"].asText())
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM game WHERE state='X_TURN' AND jsonb_array_length(board)=9 " +
                    "AND x_player_id<>o_player_id AND recovery_x_ms=30000 AND recovery_o_ms=30000",
            ),
        )
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE event_type='GameStarted'"))
        db.source.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT turn_deadline FROM game").use {
                    it.next()
                    assertEquals(now.plusSeconds(5), it.getTimestamp(1).toInstant())
                }
            }
        }
    }

    @Test fun `readiness timeout returns only accepting players for queue and lobby`() {
        // Each source gets a separate database fixture via its own test instance below.
        pair()
        now = now.plusSeconds(10)
        service.tick(match)
        assertEquals("MENU", state(a))
        assertEquals("MENU", state(b))
    }

    @Test fun `lobby timeout restores accepting guest as host with authoritative slot`() {
        pair("LOBBY")
        accept(b)
        now = now.plusSeconds(10)
        service.tick(match)
        assertEquals("MENU", state(a))
        assertEquals("LOBBY", state(b))
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM room_registration r JOIN room_slot s USING(room_id) " +
                    "WHERE r.active AND s.slot=1 AND r.owner_id=s.user_id",
            ),
        )
    }

    @Test fun `lobby timeout without acceptance closes both participants`() {
        pair("LOBBY")
        now = now.plusSeconds(10)
        service.tick(match)
        assertEquals("MENU", state(a))
        assertEquals("MENU", state(b))
        assertEquals(0, count("SELECT count(*) FROM room_registration WHERE active"))
    }

    @Test fun `readiness disconnect returns connected unready peer and second close removes that return`() {
        pair()
        disconnect(a)
        assertEquals("MENU", state(a))
        assertEquals("QUEUED", state(b))
        disconnect(b)
        assertEquals("MENU", state(b))
        assertEquals("CANCELLED", data()["state"].asText())
        service.tick(match)
        assertEquals(0, count("SELECT count(*) FROM game"))
    }

    @Test fun `countdown double close restores neither player and emits no game or loss`() {
        pair("LOBBY")
        accept(a)
        accept(b)
        now = now.plusSeconds(2)
        disconnect(a)
        assertEquals("LOBBY", state(b))
        disconnect(b)
        assertEquals("MENU", state(a))
        assertEquals("MENU", state(b))
        assertEquals(0, count("SELECT count(*) FROM room_registration WHERE active"))
        assertEquals(0, count("SELECT count(*) FROM game"))
    }

    @Test fun `old generation close is fenced and cancelled match cannot revive`() {
        pair()
        disconnect(a, 2, true)
        disconnect(a, 1)
        assertEquals("WAITING_READY", data()["state"].asText())
        disconnect(a, 2)
        disconnect(a, 3, true)
        assertEquals("CANCELLED", data()["state"].asText())
        assertEquals(409, accept(a).status)
    }

    @Test fun `old match second close never changes a newly assigned match`() {
        pair()
        disconnect(a)
        val old = match
        val third = UUID.randomUUID()
        val ticket = UUID.randomUUID()
        execute("CLAIM", ClaimParticipation(UUID.randomUUID(), third, 0, ParticipationState.QUEUED, ticket))
        val bState = coordination.snapshot(b)["data"]
        val new = UUID.randomUUID()
        execute(
            "ASSIGN_PAIR",
            AssignPair(
                UUID.randomUUID(),
                new,
                listOf(
                    PairPlayer(b, bState["version"].asLong(), UUID.fromString(bState["resourceId"].asText()), "2"),
                    PairPlayer(third, 1, ticket, "3"),
                ),
            ),
        )
        match = old
        disconnect(b)
        assertEquals(new.toString(), coordination.snapshot(b)["data"]["resourceId"].asText())
        assertEquals("IN_MATCH", state(b))
    }

    @Test fun `foreign snapshot and confirmation cannot access another match`() {
        pair()
        val foreign = UUID.randomUUID()
        assertEquals(403, assertThrows(CoordinationFailure::class.java) { service.snapshot(foreign, match) }.status)
        assertEquals(403, assertThrows(CoordinationFailure::class.java) { accept(foreign) }.status)
        assertEquals(0L, version())
    }

    @Test fun `model traces cover preparation transitions for both sources including terminal rejection`() {
        val triples =
            listOf(
                listOf("A", "B", "T"),
                listOf("A", "DA", "DB"),
                listOf("B", "DA", "T"),
                listOf("A", "B", "DA"),
            )
        val traces = triples.flatMap(::permutation)
        for (source in listOf("QUEUE", "LOBBY")) {
            traces.forEach { trace -> runTrace(source, trace) }
        }
    }

    private fun runTrace(
        source: String,
        trace: List<String>,
    ) {
        a = UUID.randomUUID()
        b = UUID.randomUUID()
        pair(source)
        val model = PreparationModel()
        trace.forEach { action ->
            model.step(action)
            when (action) {
                "A" -> accept(a)
                "B" -> accept(b)
                "DA" -> disconnect(a)
                "DB" -> disconnect(b)
                else -> {
                    now = now.plusSeconds(10)
                    fresh().tick(match)
                }
            }
            assertEquals(model.phase, data()["state"].asText(), "$source $trace $action")
            assertEquals(
                if (model.phase == "RUNNING") 1 else 0,
                count("SELECT count(*) FROM game WHERE match_id='$match'"),
            )
        }
    }

    @Test fun `failed game start transaction rolls back state symbols and outbox before retry`() {
        pair()
        accept(a)
        accept(b)
        now = now.plusSeconds(5)
        db.transaction { connection ->
            connection.createStatement().use {
                it.execute(
                    "CREATE FUNCTION fail_start() RETURNS trigger LANGUAGE plpgsql AS \$\$ " +
                        "BEGIN IF NEW.event_type='GameStarted' THEN " +
                        "RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END \$\$",
                )
                it.execute(
                    "CREATE TRIGGER fail_start BEFORE INSERT ON outbox " +
                        "FOR EACH ROW EXECUTE FUNCTION fail_start()",
                )
            }
        }
        assertThrows(java.sql.SQLException::class.java) { service.tick(match) }
        assertEquals(0, count("SELECT count(*) FROM game"))
        assertEquals("COUNTDOWN", db.transaction { store.lock(it, match).state })
        db.transaction { connection ->
            connection.createStatement().use { it.execute("DROP TRIGGER fail_start ON outbox") }
        }
        fresh().tick(match)
        fresh().tick(match)
        assertEquals("RUNNING", data()["state"].asText())
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE event_type='GameStarted'"))
    }

    @Test fun `all ready masks route each participant for queue and lobby`() {
        for (source in listOf("QUEUE", "LOBBY")) {
            for (mask in 0..3) {
                a = UUID.randomUUID()
                b = UUID.randomUUID()
                pair(source)
                if (mask and 1 != 0) accept(a)
                if (mask and 2 != 0) accept(b)
                now = now.plusSeconds(10)
                fresh().tick(match)
                val returned = if (source == "QUEUE") "QUEUED" else "LOBBY"
                val expectedA =
                    if (mask == 3) {
                        "IN_MATCH"
                    } else if (mask and 1 != 0) {
                        returned
                    } else {
                        "MENU"
                    }
                val expectedB =
                    if (mask == 3) {
                        "IN_MATCH"
                    } else if (mask and 2 != 0) {
                        returned
                    } else {
                        "MENU"
                    }
                assertEquals(expectedA, state(a), "$source mask=$mask A")
                assertEquals(expectedB, state(b), "$source mask=$mask B")
            }
        }
    }

    @Test fun `concurrent countdown worker and close obey the same exclusive time boundary`() {
        for (atDeadline in listOf(false, true)) {
            a = UUID.randomUUID()
            b = UUID.randomUUID()
            pair()
            accept(a)
            accept(b)
            now = Instant.parse(data()["startDeadline"].asText())
            if (!atDeadline) now = now.minusNanos(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                pool
                    .invokeAll(
                        listOf(
                            Callable {
                                service.tick(match)
                                true
                            },
                            Callable {
                                disconnect(a)
                                true
                            },
                        ),
                    ).forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            assertEquals(if (atDeadline) "RUNNING" else "CANCELLED", data()["state"].asText())
            assertEquals(if (atDeadline) 1 else 0, count("SELECT count(*) FROM game WHERE match_id='$match'"))
        }
    }

    @Test fun `finished aggregate never restarts or accepts preparation commands`() {
        pair()
        accept(a)
        accept(b)
        now = now.plusSeconds(5)
        service.tick(match)
        db.transaction {
            db.update(it, "UPDATE game_match SET state='FINISHED',version=version+1 WHERE match_id=?", match)
        }
        val before = data()
        disconnect(a)
        fresh().tick(match)
        assertEquals(409, accept(b).status)
        assertEquals(before, data())
        assertEquals(1, count("SELECT count(*) FROM game"))
    }
}

// Independent abstract automaton: no database, transport, versions or production transition helpers.
private class PreparationModel {
    var phase = "WAITING_READY"
    private val accepted = mutableSetOf<String>()

    fun step(action: String) {
        if (phase in listOf("CANCELLED", "RUNNING")) return
        when (action) {
            "T" -> phase = if (phase == "COUNTDOWN") "RUNNING" else "CANCELLED"
            "DA", "DB" -> phase = "CANCELLED"
            else ->
                if (phase == "WAITING_READY") {
                    accepted.add(action)
                    if (accepted.size == 2) phase = "COUNTDOWN"
                }
        }
    }
}

private fun permutation(values: List<String>): List<List<String>> {
    if (values.isEmpty()) return listOf(emptyList())
    return values.flatMap { first -> permutation(values.filter { it != first }).map { listOf(first) + it } }
}
