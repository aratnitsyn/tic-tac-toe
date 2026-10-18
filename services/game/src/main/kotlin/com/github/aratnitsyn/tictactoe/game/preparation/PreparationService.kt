package com.github.aratnitsyn.tictactoe.game.preparation

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationEvent
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.persistence.CommandResponse
import com.github.aratnitsyn.tictactoe.game.persistence.CommandStore
import com.github.aratnitsyn.tictactoe.game.play.PlayDeadlineStore
import com.github.aratnitsyn.tictactoe.game.play.PlayEngine
import com.github.aratnitsyn.tictactoe.game.play.PlayStore
import org.springframework.http.HttpStatus
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// Command, snapshot and deadline entry points share one transactional state machine.
@Suppress("TooManyFunctions")
class PreparationService(
    private val store: PreparationStore,
    private val commands: CommandStore,
    private val events: PreparationEvent,
    private val returns: PreparationReturn,
    private val clock: (Connection) -> Instant = store.db::now,
    private val play: PlayEngine = PlayEngine(store.db, PlayStore(store.db), events, PlayDeadlineStore(store.db)),
) {
    private val db = store.db

    fun snapshot(
        user: UUID,
        id: UUID,
    ): JsonNode =
        db.transaction { connection ->
            val match = store.lock(connection, id)
            authorize(match, user)
            advance(connection, match, id)
            response(connection, store.lock(connection, id))
        }

    fun ready(
        user: UUID,
        id: UUID,
        command: UUID,
        payload: JsonNode,
    ): CommandResponse {
        val expected = PreparationInput.ready(payload)
        return commands.execute(user, "READY/$id", command, payload) { connection ->
            var match = store.lock(connection, id)
            authorize(match, user)
            advance(connection, match, command)
            match = store.lock(connection, id)
            when {
                match.state != "WAITING_READY" -> error(connection, command, "INVALID_STATE")
                match.version != expected -> error(connection, command, "VERSION_CONFLICT")
                else -> {
                    if (!match.members.single { it.user == user }.ready) {
                        db.update(
                            connection,
                            "UPDATE match_member SET ready=true " +
                                "WHERE match_id=? AND user_id=?",
                            id,
                            user,
                        )
                        db.update(connection, "UPDATE game_match SET version=version+1 WHERE match_id=?", id)
                        match = store.lock(connection, id)
                        if (match.members.all { it.ready }) {
                            db.update(
                                connection,
                                "UPDATE game_match SET state='COUNTDOWN',start_deadline=? WHERE match_id=?",
                                Timestamp.from(clock(connection).plusSeconds(COUNTDOWN_SECONDS)),
                                id,
                            )
                        }
                        publish(
                            connection,
                            store.lock(connection, id),
                            command,
                            if (match.members.all {
                                    it.ready
                                }
                            ) {
                                "MatchCountdownStarted"
                            } else {
                                "MatchReadyRequested"
                            },
                        )
                    }
                    CommandResponse(HttpStatus.OK.value(), response(connection, store.lock(connection, id)))
                }
            }
        }
    }

    fun observe(
        user: UUID,
        id: UUID,
        command: UUID,
        payload: JsonNode,
    ): CommandResponse {
        val generation = PreparationInput.connection(payload, user, id, command)
        return commands.execute(user, "OBSERVE/$id", command, payload) { connection ->
            var match = store.lock(connection, id)
            authorize(match, user)
            val checkedAt = advance(connection, match, command)
            match = store.lock(connection, id)
            val member = match.members.single { it.user == user }
            val connected = payload["connected"].asBoolean()
            val next = generation
            val applies = next > member.generation || (next == member.generation && member.connected && !connected)
            if (applies && match.state != "FINISHED") {
                db.update(
                    connection,
                    "UPDATE match_member SET connected=?,connection_generation=? WHERE match_id=? AND user_id=?",
                    connected,
                    next,
                    id,
                    user,
                )
                val current = store.lock(connection, id)
                if (current.state == "RUNNING") {
                    play.connection(connection, current, user, command, checkedAt)
                } else if (!connected) {
                    disconnected(connection, current, user, command)
                }
            }
            val result =
                db.mapper
                    .createObjectNode()
                    .put("operationId", command.toString())
                    .put("status", "APPLIED")
                    .put("resourceId", id.toString())
                    .put("version", store.lock(connection, id).version)
            CommandResponse(HttpStatus.OK.value(), result)
        }
    }

    private fun disconnected(
        connection: Connection,
        match: PreparedMatch,
        user: UUID,
        command: UUID,
    ) {
        val cancelled =
            if (match.state in listOf("WAITING_READY", "COUNTDOWN")) {
                returns.cancel(connection, match, command, true)
                true
            } else if (match.state == "CANCELLED") {
                returns.revoke(connection, match, match.members.single { it.user == user }, command)
            } else {
                false
            }
        if (cancelled) publish(connection, store.lock(connection, match.id), command, "MatchCancelled")
    }

    fun tick(id: UUID) = db.transaction { connection -> advance(connection, store.lock(connection, id), id) }

    fun due(): List<UUID> =
        db.source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT match_id FROM game_match WHERE " +
                            "(state='WAITING_READY' AND ready_deadline<=clock_timestamp()) OR " +
                            "(state='COUNTDOWN' AND start_deadline<=clock_timestamp()) ORDER BY " +
                            "COALESCE(start_deadline,ready_deadline) LIMIT 100",
                    ).use { rows ->
                        buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }
                    }
            }
        }

    fun advance(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
    ): Instant {
        val now = clock(connection)
        if (match.state == "RUNNING") {
            play.advance(connection, match, cause, now)
        } else if (match.state == "WAITING_READY" && !now.isBefore(match.readyDeadline)) {
            returns.cancel(connection, match, cause, false)
            publish(connection, store.lock(connection, match.id), cause, "MatchCancelled")
        } else if (match.state == "COUNTDOWN" && !now.isBefore(requireNotNull(match.startDeadline))) {
            if (match.members.any { !it.connected }) {
                returns.cancel(connection, match, cause, true)
                publish(connection, store.lock(connection, match.id), cause, "MatchCancelled")
            } else {
                start(connection, match, cause, now)
            }
        }
        return now
    }

    private fun start(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        now: Instant,
    ) {
        play.start(connection, match, cause, now)
    }

    private fun authorize(
        match: PreparedMatch,
        user: UUID,
    ) {
        if (match.members.none { it.user == user }) throw CoordinationFailure("FORBIDDEN", HttpStatus.FORBIDDEN.value())
    }

    private fun publish(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        type: String,
    ) = events.emit(
        connection,
        match,
        cause,
        CoordinationEvent(
            type,
            "MATCH",
            match.id,
            match.version,
            match.members.map { it.user },
            store.snapshot(match),
        ),
    )

    private fun response(
        connection: Connection,
        match: PreparedMatch,
    ): JsonNode =
        db.mapper.createObjectNode().apply {
            put("serverTime", clock(connection).toString())
            put("version", match.version)
            set<JsonNode>("data", store.snapshot(match))
        }

    private fun error(
        connection: Connection,
        command: UUID,
        code: String,
    ) = CommandResponse(
        HttpStatus.CONFLICT.value(),
        db.mapper
            .createObjectNode()
            .put("code", code)
            .put("message", code)
            .put("serverTime", clock(connection).toString())
            .put("correlationId", command.toString()),
    )
}

private const val COUNTDOWN_SECONDS = 5L
