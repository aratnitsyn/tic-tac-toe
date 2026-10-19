package com.github.aratnitsyn.tictactoe.game.logout

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationEvent
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationOperation
import com.github.aratnitsyn.tictactoe.game.participation.Participation
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationEvent
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationState
import com.github.aratnitsyn.tictactoe.game.persistence.CommandResponse
import com.github.aratnitsyn.tictactoe.game.persistence.CommandStore
import com.github.aratnitsyn.tictactoe.game.play.PlayChange
import com.github.aratnitsyn.tictactoe.game.play.PlayEngine
import com.github.aratnitsyn.tictactoe.game.play.PlayRule
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationReturn
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationStore
import java.sql.Connection
import java.util.UUID

@Suppress("MagicNumber") // HTTP status codes.
class LogoutService(
    private val store: PreparationStore,
    private val preparation: PreparationService,
    private val play: PlayEngine,
    private val returns: PreparationReturn,
    private val events: PreparationEvent,
) {
    private val db = store.db
    private val commands = CommandStore(db.source, db.mapper)

    fun execute(
        id: UUID,
        payload: JsonNode,
    ): JsonNode {
        val fields = setOf("operationId", "userId", "sessionId")
        if (!payload.isObject || payload.fieldNames().asSequence().toSet() != fields) invalid()
        val values =
            fields.associateWith { key ->
                if (!payload[key].isTextual) invalid()
                runCatching { UUID.fromString(payload[key].asText()) }.getOrElse { invalid() }
            }
        if (values["operationId"] != id) throw CoordinationFailure("IDEMPOTENCY_CONFLICT")
        val user = requireNotNull(values["userId"])
        return commands
            .execute(user, "LOGOUT", id, payload) { connection ->
                // Discover without taking a single-player lock ahead of sorted match locks.
                val candidate = resource(connection, user)
                if (candidate != null) {
                    match(connection, user, candidate, id)
                }
                val current = db.participants(connection, listOf(user)).single()
                if (current.operationId != null || current.state == ParticipationState.IN_MATCH) unavailable()
                free(connection, current, id, payload)
                val version = db.participants(connection, listOf(user)).single().version
                CommandResponse(
                    200,
                    db.mapper
                        .createObjectNode()
                        .put("operationId", id.toString())
                        .put("status", "APPLIED")
                        .put("version", version),
                )
            }.body
    }

    private fun resource(
        connection: Connection,
        user: UUID,
    ): UUID? =
        connection.prepareStatement("SELECT resource_id FROM participation WHERE user_id=? AND state='IN_MATCH'").use {
            it.setObject(1, user)
            it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    private fun match(
        connection: Connection,
        user: UUID,
        id: UUID,
        cause: UUID,
    ) {
        var match = store.lock(connection, id)
        val current = match.participation.single { it.userId == user }
        // A new match won the discovery race: retry from a fresh transaction and lock set.
        if (current.state == ParticipationState.IN_MATCH && current.resourceId != id) unavailable()
        if (current.state != ParticipationState.IN_MATCH) return
        val now = preparation.advance(connection, match, cause)
        match = store.lock(connection, id)
        if (match.state == "RUNNING") {
            val before = requireNotNull(play.store.load(connection, id))
            play.commit(
                connection,
                match,
                cause,
                PlayChange(before, PlayRule.surrender(before, user, now), now, "GameFinished"),
            )
        } else if (match.state in listOf("WAITING_READY", "COUNTDOWN")) {
            db.update(connection, "UPDATE match_member SET connected=false WHERE match_id=? AND user_id=?", id, user)
            returns.cancel(connection, store.lock(connection, id), cause, true)
            val cancelled = store.lock(connection, id)
            events.emit(
                connection,
                cancelled,
                cause,
                CoordinationEvent(
                    "MatchCancelled",
                    "MATCH",
                    id,
                    cancelled.version,
                    cancelled.members.map { it.user },
                    store.snapshot(cancelled),
                ),
            )
        }
    }

    private fun free(
        connection: Connection,
        before: Participation,
        id: UUID,
        payload: JsonNode,
    ) {
        if (before.state == ParticipationState.LOBBY) {
            db.lock(connection, "room/${before.resourceId}")
            db.update(
                connection,
                "DELETE FROM room_slot WHERE room_id=? AND user_id=?",
                before.resourceId,
                before.userId,
            )
            db.update(connection, "UPDATE room_registration SET active=false WHERE room_id=?", before.resourceId)
        }
        // Even MENU advances: commands carrying a pre-logout expectedVersion must lose.
        val after = before.copy(state = ParticipationState.MENU, resourceId = null, version = before.version + 1)
        db.update(
            connection,
            "UPDATE participation SET state='MENU',resource_id=NULL,version=? " +
                "WHERE user_id=?",
            after.version,
            after.userId,
        )
        val op = CoordinationOperation(id, "LOGOUT", payload, id, id, "APPLIED", null, null)
        ParticipationEvent(db).emit(
            connection,
            op,
            CoordinationEvent(
                "ParticipationChanged",
                "PARTICIPATION",
                after.userId,
                after.version,
                listOf(after.userId),
                db.participationJson(after),
            ),
        )
    }

    private fun unavailable(): Nothing = throw CoordinationFailure("SERVICE_UNAVAILABLE", 503)

    private fun invalid(): Nothing = throw CoordinationFailure("VALIDATION_ERROR", 400)
}
