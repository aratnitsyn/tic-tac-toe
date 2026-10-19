package com.github.aratnitsyn.tictactoe.game.play

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.aratnitsyn.tictactoe.game.participation.AssignPair
import com.github.aratnitsyn.tictactoe.game.participation.ClaimParticipation
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.OperationStore
import com.github.aratnitsyn.tictactoe.game.participation.PairAssignment
import com.github.aratnitsyn.tictactoe.game.participation.PairPlayer
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationEvent
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationService
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationState
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationTransition
import com.github.aratnitsyn.tictactoe.game.persistence.CommandStore
import com.github.aratnitsyn.tictactoe.game.persistence.GameTestDatabase
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationReturn
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationStore
import java.sql.Connection
import java.time.Instant
import java.util.UUID

class PlayTestFixture(
    start: Boolean = true,
) : AutoCloseable {
    private val database = GameTestDatabase()
    val mapper = jacksonObjectMapper()
    val db = CoordinationDatabase(database.source, mapper)
    private val participantEvent = ParticipationEvent(db)
    private val transitions = ParticipationTransition(db, participantEvent)
    val participation =
        ParticipationService(
            db,
            OperationStore(db),
            transitions,
            PairAssignment(db, transitions, participantEvent),
        )
    val matches = PreparationStore(db)
    val event = PreparationEvent(db)
    val engine = PlayEngine(db, PlayStore(db), event, PlayDeadlineStore(db))
    var now: Instant = Instant.now()
    val preparation =
        PreparationService(
            matches,
            CommandStore(db.source, mapper),
            event,
            PreparationReturn(db, event),
            { now },
            engine,
        )
    val service = PlayService(preparation, matches, engine)
    val a = UUID.randomUUID()
    val b = UUID.randomUUID()
    val id = UUID.randomUUID()

    init {
        val players =
            listOf(a, b).mapIndexed { index, user ->
                val ticket = UUID.randomUUID()
                execute("CLAIM", ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.QUEUED, ticket))
                PairPlayer(user, 1, ticket, (index + 1).toString())
            }
        execute("ASSIGN_PAIR", AssignPair(UUID.randomUUID(), id, players))
        now = db.transaction { matches.lock(it, id).readyDeadline.minusSeconds(10) }
        if (start) {
            preparation.ready(a, id, UUID.randomUUID(), body(0))
            preparation.ready(b, id, UUID.randomUUID(), body(1))
            now = now.plusSeconds(5)
            preparation.tick(id)
        }
    }

    fun game(): PlayState = db.transaction { requireNotNull(engine.store.load(it, id)) }

    fun state(user: UUID): JsonNode = participation.snapshot(user)["data"]

    fun body(
        version: Long = game().version,
        cell: Int? = null,
    ): JsonNode =
        mapper.createObjectNode().apply {
            put("expectedVersion", version)
            cell?.let { put("cell", it) }
        }

    fun move(
        cell: Int,
        user: UUID = game().turnPlayer(),
        version: Long = game().version,
        key: UUID = UUID.randomUUID(),
    ) = service.command(user, id, key, "MOVE", body(version, cell))

    fun surrender(
        user: UUID = game().symbol.x,
        version: Long = game().version,
    ) = service.command(user, id, UUID.randomUUID(), "SURRENDER", body(version))

    fun observe(
        user: UUID,
        connected: Boolean,
        generation: Long? = null,
    ): JsonNode {
        val current =
            db.transaction {
                matches
                    .lock(it, id)
                    .members
                    .single { m -> m.user == user }
                    .generation
            }
        val next = generation ?: if (connected) current + 1 else current
        val command = UUID.randomUUID()
        val payload =
            mapper
                .createObjectNode()
                .put("operationId", command.toString())
                .put("userId", user.toString())
                .put("matchId", id.toString())
                .put("generation", next)
                .put("connected", connected)
        return preparation.observe(user, id, command, payload).body
    }

    fun task(): PlayDeadline =
        db.transaction { connection ->
            connection
                .prepareStatement(
                    "SELECT * FROM game_deadline WHERE match_id=? " +
                        "ORDER BY due_at,kind LIMIT 1",
                ).use {
                    it.setObject(1, id)
                    it.executeQuery().use { row ->
                        check(row.next())
                        PlayDeadline(
                            id,
                            row.getString("kind"),
                            row.getLong("generation"),
                            row.getLong("expected_version"),
                            row.getTimestamp("due_at").toInstant(),
                        )
                    }
                }
        }

    fun count(sql: String): Int =
        db.source.connection.use { c ->
            c.createStatement().use {
                it.executeQuery(sql).use { row ->
                    row.next()
                    row.getInt(1)
                }
            }
        }

    fun effect(effect: (Connection) -> Unit) = db.transaction(effect)

    private fun execute(
        kind: String,
        body: Any,
    ) = participation.execute(kind, mapper.valueToTree(body), UUID.randomUUID())

    override fun close() = database.close()
}
