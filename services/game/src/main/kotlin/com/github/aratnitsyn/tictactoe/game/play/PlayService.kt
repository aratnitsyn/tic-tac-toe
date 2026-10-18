package com.github.aratnitsyn.tictactoe.game.play

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.persistence.CommandResponse
import com.github.aratnitsyn.tictactoe.game.persistence.CommandStore
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationInput
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationStore
import org.springframework.http.HttpStatus
import java.sql.Connection
import java.time.Instant
import java.util.UUID

class PlayService(
    private val preparation: PreparationService,
    private val matches: PreparationStore,
    private val engine: PlayEngine,
) {
    private val db = matches.db
    private val commands = CommandStore(db.source, db.mapper)

    fun snapshot(
        user: UUID,
        id: UUID,
    ): JsonNode {
        val view =
            db.transaction { connection ->
                val match = matches.lock(connection, id)
                if (match.members.none { it.user == user }) {
                    throw CoordinationFailure("FORBIDDEN", HttpStatus.FORBIDDEN.value())
                }
                val now = preparation.advance(connection, match, id)
                engine.store.load(connection, id)?.let { response(it, now) }
            }
        // A not-yet-started game may have just cancelled its preparation. Keep that commit.
        return view ?: throw CoordinationFailure("INVALID_STATE")
    }

    fun command(
        user: UUID,
        id: UUID,
        command: UUID,
        kind: String,
        payload: JsonNode,
    ): CommandResponse {
        val version = PlayInput.validate(payload, kind)
        return commands.execute(user, "$kind/$id", command, payload) { connection ->
            var match = matches.lock(connection, id)
            if (match.members.none { it.user == user }) {
                throw CoordinationFailure("FORBIDDEN", HttpStatus.FORBIDDEN.value())
            }
            val now = preparation.advance(connection, match, command)
            match = matches.lock(connection, id)
            val game = engine.store.load(connection, id)
            if (game == null) return@execute error(command, "INVALID_STATE", now)
            if (game.phase == PlayPhase.FINISHED) return@execute error(command, "INVALID_STATE", now)
            if (game.version != version) return@execute error(command, "VERSION_CONFLICT", now)
            val after =
                try {
                    if (kind == "MOVE") {
                        PlayRule.move(game, user, payload["cell"].asInt(), now)
                    } else {
                        PlayRule.surrender(game, user, now)
                    }
                } catch (failure: PlayFailure) {
                    return@execute error(command, failure.code, now)
                }
            engine.commit(connection, match, command, PlayChange(game, after, now, "GameStateChanged"))
            CommandResponse(HttpStatus.OK.value(), response(after, now))
        }
    }

    fun expire(task: PlayDeadline) =
        db.transaction { connection ->
            val match = matches.lock(connection, task.matchId)
            if (engine.deadlines.current(connection, task)) preparation.advance(connection, match, task.matchId)
        }

    private fun response(
        game: PlayState,
        now: Instant,
    ): JsonNode =
        db.mapper.createObjectNode().apply {
            put("serverTime", now.toString())
            put("version", game.version)
            set<JsonNode>("data", engine.store.snapshot(game, now))
        }

    private fun error(
        command: UUID,
        code: String,
        now: Instant,
    ) = CommandResponse(
        HttpStatus.CONFLICT.value(),
        db.mapper
            .createObjectNode()
            .put("code", code)
            .put("message", code)
            .put("serverTime", now.toString())
            .put("correlationId", command.toString()),
    )
}

object PlayInput {
    fun validate(
        payload: JsonNode,
        kind: String,
    ): Long {
        if (kind == "SURRENDER") return PreparationInput.ready(payload)
        if (kind != "MOVE" || !payload.isObject || payload.size() != MOVE_FIELD_COUNT) invalid()
        val cell = payload.path("cell")
        if (!cell.isIntegralNumber || !cell.canConvertToInt() || cell.asInt() !in 0 until BOARD_SIZE) invalid()
        val version = payload.path("expectedVersion")
        return PreparationInput.ready(
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .set<JsonNode>("expectedVersion", version),
        )
    }

    private fun invalid(): Nothing = throw CoordinationFailure("VALIDATION_ERROR", HttpStatus.BAD_REQUEST.value())
}

private const val MOVE_FIELD_COUNT = 2
private const val BOARD_SIZE = 9
