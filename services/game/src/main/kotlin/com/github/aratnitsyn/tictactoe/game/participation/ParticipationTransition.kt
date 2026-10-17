package com.github.aratnitsyn.tictactoe.game.participation

import java.sql.Connection
import java.util.UUID

class ParticipationTransition(
    private val db: CoordinationDatabase,
    private val events: ParticipationEvent,
) {
    fun expect(
        value: Participation,
        state: ParticipationState,
        version: Long,
    ) {
        if (value.operationId == null || value.state != state) throw CoordinationFailure("PARTICIPATION_CONFLICT")
        if (value.version != version) throw CoordinationFailure("VERSION_CONFLICT")
    }

    fun change(
        connection: Connection,
        op: CoordinationOperation,
        before: Participation,
        target: ParticipationState,
        resourceId: UUID?,
    ): Participation {
        val after = before.copy(state = target, resourceId = resourceId, version = before.version + 1)
        db
            .update(
                connection,
                "UPDATE participation SET state=?,resource_id=?,version=? WHERE user_id=? AND operation_id=?",
                target.name,
                resourceId,
                after.version,
                before.userId,
                op.id,
            ).also { check(it == 1) }
        events.emit(
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
        return after
    }

    fun leave(
        connection: Connection,
        op: CoordinationOperation,
        value: Participation,
        expected: Long,
    ): Participation {
        if (value.state !in listOf(ParticipationState.QUEUED, ParticipationState.LOBBY)) {
            throw CoordinationFailure("PARTICIPATION_CONFLICT")
        }
        expect(value, value.state, expected)
        if (value.state == ParticipationState.LOBBY) {
            db.lock(connection, "room/${value.resourceId}")
            db.update(connection, "DELETE FROM room_slot WHERE room_id=? AND user_id=?", value.resourceId, value.userId)
            db.update(connection, "UPDATE room_registration SET active=false WHERE room_id=?", value.resourceId)
        }
        return change(connection, op, value, ParticipationState.MENU, null)
    }
}
